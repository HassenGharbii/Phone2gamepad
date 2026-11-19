package com.phone2gamepad.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A corrupt DataStore file is replaced by an empty one instead of crashing the app on every launch. */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "phone2gamepad",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Per-PC pairing credential + stable per-install device id. */
interface SecretStore {
    suspend fun deviceId(): String
    suspend fun loadSecret(pcKey: String): ByteArray?
    suspend fun saveSecret(pcKey: String, secret: ByteArray)
    suspend fun deleteSecret(pcKey: String)
}

/**
 * Pairing secrets are encrypted with an AES-256-GCM key that lives in the Android Keystore (non-exportable);
 * only the ciphertext (IV || ct) is written to DataStore.
 */
class KeystoreSecretStore(private val context: Context) : SecretStore {
    private val alias = "p2g_pairing_secret_key"
    private val ds get() = context.dataStore

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun prefKey(pcKey: String) = stringPreferencesKey("secret_" + Base64.encodeToString(pcKey.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE))

    override suspend fun deviceId(): String {
        val k = stringPreferencesKey("device_id")
        ds.data.first()[k]?.let { return it }
        val id = UUID.randomUUID().toString()
        ds.edit { it[k] = id }
        return id
    }

    override suspend fun loadSecret(pcKey: String): ByteArray? {
        val stored = ds.data.first()[prefKey(pcKey)] ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            c.doFinal(raw, 12, raw.size - 12)
        } catch (e: Exception) {
            // Keystore key lost or data corrupt: treat as unpaired so the user can pair again.
            deleteSecret(pcKey)
            null
        }
    }

    override suspend fun saveSecret(pcKey: String, secret: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(secret)
        val blob = Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        ds.edit { it[prefKey(pcKey)] = blob }
    }

    override suspend fun deleteSecret(pcKey: String) {
        ds.edit { it.remove(prefKey(pcKey)) }
    }
}

data class Settings(
    val deadZone: Float = 0.10f,
    val sensitivity: Float = 1.0f,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val opacity: Float = 0.85f,
    val digitalTriggers: Boolean = false,
    val showOverlay: Boolean = true,
    val lastHost: String = "",
    val lastTcpPort: Int = 47821,
    val lastName: String = "",
)

class SettingsRepository(private val context: Context) {
    private val ds get() = context.dataStore
    private val kDead = floatPreferencesKey("dead_zone")
    private val kSens = floatPreferencesKey("sensitivity")
    private val kHaptics = booleanPreferencesKey("haptics")
    private val kKeepOn = booleanPreferencesKey("keep_screen_on")
    private val kOpacity = floatPreferencesKey("opacity")
    private val kDigital = booleanPreferencesKey("digital_triggers")
    private val kOverlay = booleanPreferencesKey("show_overlay")
    private val kHost = stringPreferencesKey("last_host")
    private val kPort = intPreferencesKey("last_tcp_port")
    private val kName = stringPreferencesKey("last_name")

    val settings: Flow<Settings> = ds.data.map { p ->
        val d = Settings()
        Settings(
            deadZone = (p[kDead] ?: d.deadZone).coerceIn(0f, 0.5f),
            sensitivity = (p[kSens] ?: d.sensitivity).coerceIn(0.5f, 2f),
            haptics = p[kHaptics] ?: d.haptics,
            keepScreenOn = p[kKeepOn] ?: d.keepScreenOn,
            opacity = (p[kOpacity] ?: d.opacity).coerceIn(0.2f, 1f),
            digitalTriggers = p[kDigital] ?: d.digitalTriggers,
            showOverlay = p[kOverlay] ?: d.showOverlay,
            lastHost = p[kHost] ?: d.lastHost,
            lastTcpPort = (p[kPort] ?: d.lastTcpPort).coerceIn(1, 65535),
            lastName = p[kName] ?: d.lastName,
        )
    }

    suspend fun update(s: Settings) {
        ds.edit {
            it[kDead] = s.deadZone; it[kSens] = s.sensitivity; it[kHaptics] = s.haptics
            it[kKeepOn] = s.keepScreenOn; it[kOpacity] = s.opacity; it[kDigital] = s.digitalTriggers
            it[kOverlay] = s.showOverlay; it[kHost] = s.lastHost; it[kPort] = s.lastTcpPort; it[kName] = s.lastName
        }
    }
}
