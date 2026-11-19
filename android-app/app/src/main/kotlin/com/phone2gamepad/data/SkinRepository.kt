package com.phone2gamepad.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists the user's custom skins as one JSON document in DataStore (bundled skins live in code).
 * An unreadable or newer-than-supported document is moved to a backup key, never silently destroyed,
 * and the app carries on with the bundled skins only.
 */
class SkinRepository(private val context: Context) {
    private val ds get() = context.dataStore
    private val kSkins = stringPreferencesKey("skins_json")
    private val kBackup = stringPreferencesKey("skins_json_backup")
    private val mutex = Mutex()

    private fun read(p: Preferences): SkinLibrary {
        val text = p[kSkins] ?: return SkinLibrary.empty()
        return when (val r = SkinCodec.decodeLibrary(text)) {
            is SkinCodec.LibraryResult.Ok -> r.library
            else -> SkinLibrary.empty()
        }
    }

    val library: Flow<SkinLibrary> = ds.data.map { read(it) }

    /** True when a damaged/newer skin file was set aside; the UI tells the user once. */
    val backupPresent: Flow<Boolean> = ds.data.map { it[kBackup] != null }

    suspend fun recoverIfCorrupt() = mutex.withLock {
        val text = ds.data.first()[kSkins] ?: return@withLock
        val r = SkinCodec.decodeLibrary(text)
        if (r !is SkinCodec.LibraryResult.Ok) {
            Log.w("P2G", "skin file unreadable ($r); setting aside and using bundled skins only")
            ds.edit { it[kBackup] = text; it.remove(kSkins) }
        }
    }

    suspend fun dismissBackupNotice() = mutex.withLock { ds.edit { it.remove(kBackup) } }

    /** Read-modify-write; [change] returns null to leave things as they are. Returns success. */
    suspend fun update(change: (SkinLibrary) -> SkinLibrary?): Boolean = mutex.withLock {
        var ok = false
        ds.edit { p ->
            val next = change(read(p))
            if (next != null) {
                p[kSkins] = SkinCodec.encodeLibrary(next)
                ok = true
            }
        }
        ok
    }
}
