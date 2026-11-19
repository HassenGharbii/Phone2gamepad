package com.phone2gamepad.net

import com.phone2gamepad.data.SecretStore

/**
 * Decides which stored pairing secret belongs to the PC that just answered `hello`.
 *
 * Secrets are keyed by the PC's `serverId` when it sends one (`srv:<id>`), otherwise by its name (older servers).
 * Secrets saved before serverId existed live under the bare PC name; when a PC that now reports a serverId is
 * reached and has no id-keyed secret, the name-keyed one is used and re-keyed after the session proves it is valid.
 * The legacy entry is only removed after a successful session, so a same-named *different* PC cannot cost the
 * user its secret.
 */
class PcSecretResolver(private val store: SecretStore, private val hello: HelloInfo) {
    val key: String = hello.serverId?.let { "srv:$it" } ?: hello.serverName

    /** Name key of a legacy secret that is being adopted (set by [load]); null when none. */
    var adoptedLegacyKey: String? = null
        private set

    /** Stored secret for this PC, or null if the phone has to pair. */
    suspend fun load(): ByteArray? {
        if (!hello.paired) {
            // The PC does not know this phone: whatever we hold under this PC's key is stale.
            store.deleteSecret(key)
            return null
        }
        store.loadSecret(key)?.let { return it }
        if (hello.serverId != null) {
            store.loadSecret(hello.serverName)?.let {
                adoptedLegacyKey = hello.serverName
                return it
            }
        }
        return null
    }

    /** Persists a freshly paired secret. */
    suspend fun saveNew(secret: ByteArray) {
        store.saveSecret(key, secret)
        adoptedLegacyKey = null
    }

    /** Call once a session has been established with [secret]: completes a pending legacy re-key. */
    suspend fun sessionEstablished(secret: ByteArray) {
        val legacy = adoptedLegacyKey ?: return
        store.saveSecret(key, secret)
        store.deleteSecret(legacy)
        adoptedLegacyKey = null
    }

    /** The PC rejected our secret: forget it (the legacy entry is kept when it was only adopted, not yet proven). */
    suspend fun rejected() {
        store.deleteSecret(key)
        adoptedLegacyKey = null
    }
}
