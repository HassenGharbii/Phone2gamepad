package com.phone2gamepad.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists the [ProfileBook] as one JSON document in DataStore.
 *
 * - No stored document yet: a "Standard" profile is seeded from the legacy single-set settings keys, so a user who
 *   tuned dead zone / sensitivity / opacity / digital triggers before profiles existed keeps that feel.
 * - Stored document unreadable or from a newer app: it is moved to a backup key (never silently destroyed),
 *   and a default book is used.
 */
class ProfileRepository(private val context: Context) {
    private val ds get() = context.dataStore
    private val kBook = stringPreferencesKey("profiles_json")
    private val kBackup = stringPreferencesKey("profiles_json_backup")
    private val mutex = Mutex()

    // Legacy keys written by SettingsRepository before profiles existed.
    private val kDead = floatPreferencesKey("dead_zone")
    private val kSens = floatPreferencesKey("sensitivity")
    private val kOpacity = floatPreferencesKey("opacity")
    private val kDigital = booleanPreferencesKey("digital_triggers")

    private fun seeded(p: Preferences) = ProfileBook.default(
        deadZone = p[kDead] ?: 0.10f, sensitivity = p[kSens] ?: 1f, opacity = p[kOpacity] ?: 0.85f,
        digitalTriggers = p[kDigital] ?: false,
    )

    private fun read(p: Preferences): ProfileBook {
        val text = p[kBook] ?: return seeded(p)
        return when (val r = ProfileCodec.decodeBook(text)) {
            is ProfileCodec.BookResult.Ok -> r.book
            else -> seeded(p)
        }
    }

    val book: Flow<ProfileBook> = ds.data.map { read(it) }

    /** True when a damaged/newer profile file was set aside; the UI tells the user once. */
    val backupPresent: Flow<Boolean> = ds.data.map { it[kBackup] != null }

    /** Moves an unreadable stored document to the backup key. Call once at startup. */
    suspend fun recoverIfCorrupt() = mutex.withLock {
        val text = ds.data.first()[kBook] ?: return@withLock
        val r = ProfileCodec.decodeBook(text)
        if (r !is ProfileCodec.BookResult.Ok) {
            Log.w("P2G", "profile file unreadable ($r); setting aside and starting from defaults")
            ds.edit { it[kBackup] = text; it.remove(kBook) }
        }
    }

    suspend fun dismissBackupNotice() = mutex.withLock { ds.edit { it.remove(kBackup) } }

    /** Read-modify-write; [change] returns null to leave things as they are (e.g. a limit was hit). Returns success. */
    suspend fun update(change: (ProfileBook) -> ProfileBook?): Boolean = mutex.withLock {
        var ok = false
        ds.edit { p ->
            val next = change(read(p))
            if (next != null) {
                p[kBook] = ProfileCodec.encodeBook(next)
                ok = true
            }
        }
        ok
    }
}
