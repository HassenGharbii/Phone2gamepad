package com.phone2gamepad

import android.app.Application
import android.os.Build
import com.phone2gamepad.data.BackgroundStore
import com.phone2gamepad.data.KeystoreSecretStore
import com.phone2gamepad.data.ProfileRepository
import com.phone2gamepad.data.SettingsRepository
import com.phone2gamepad.data.SkinRepository
import com.phone2gamepad.net.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-scoped singletons (the connection must survive activity recreation). */
class App : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settingsRepo: SettingsRepository
        private set
    lateinit var profileRepo: ProfileRepository
        private set
    lateinit var skinRepo: SkinRepository
        private set
    lateinit var backgroundStore: BackgroundStore
        private set
    lateinit var connection: ConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        profileRepo = ProfileRepository(this)
        skinRepo = SkinRepository(this)
        backgroundStore = BackgroundStore(this)
        val name = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ").ifBlank { "Android phone" }
        connection = ConnectionManager(appScope, KeystoreSecretStore(this), name)
    }
}
