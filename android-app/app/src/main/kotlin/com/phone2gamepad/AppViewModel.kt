package com.phone2gamepad

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phone2gamepad.data.BackgroundStore
import com.phone2gamepad.data.ControllerType
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.ProfileBook
import com.phone2gamepad.data.Settings
import com.phone2gamepad.data.Skin
import com.phone2gamepad.data.SkinLibrary
import com.phone2gamepad.net.ConnState
import com.phone2gamepad.net.DiscoveredPc
import com.phone2gamepad.net.DiscoveryClient
import com.phone2gamepad.net.PcTarget
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen { Welcome, Discover, Manual, Connect, Home, Gamepad, Customize, Settings, Profiles, Tuning, LayoutEditor, Skins, SkinEditor, About }

/** Screens on which a session to the last PC is kept up (and re-established after the app returns to the foreground). */
private val SESSION_SCREENS = setOf(
    Screen.Home, Screen.Gamepad, Screen.Customize, Screen.LayoutEditor, Screen.Tuning, Screen.Skins, Screen.SkinEditor, Screen.Profiles,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val application get() = getApplication<App>()
    val connection = application.connection
    private val repo = application.settingsRepo
    private val profileRepo = application.profileRepo
    private val skinRepo = application.skinRepo
    private val backgroundStore = application.backgroundStore

    val settings: StateFlow<Settings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Saved layouts/tuning; the active profile drives the gamepad. */
    val profiles: StateFlow<ProfileBook> = profileRepo.book.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileBook.default())
    val profileBackupPresent: StateFlow<Boolean> = profileRepo.backupPresent.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Bundled + custom skins. */
    val skins: StateFlow<SkinLibrary> = skinRepo.library.stateIn(viewModelScope, SharingStarted.Eagerly, SkinLibrary.empty())
    val skinBackupPresent: StateFlow<Boolean> = skinRepo.backupPresent.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** One-shot message for the profile screens (limit reached, import failed...). */
    var notice by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch { profileRepo.recoverIfCorrupt() }
        viewModelScope.launch { skinRepo.recoverIfCorrupt() }
        // Pictures no profile uses any more (and not picked recently) are deleted once per start.
        viewModelScope.launch(Dispatchers.IO) {
            val keep = profileRepo.book.first().profiles.mapNotNull { it.background.imageRef }.toSet()
            backgroundStore.prune(keep)
        }
    }

    var screen by mutableStateOf(Screen.Welcome)
        private set
    /** False until the saved settings were read and the first screen (onboarding or Home) was chosen. */
    var ready by mutableStateOf(false)
        private set
    private val history = ArrayDeque<Screen>()

    val discovered = MutableStateFlow<List<DiscoveredPc>>(emptyList())
    var scanning by mutableStateOf(false)
        private set
    /** Wall-clock start of the current scan (for "nothing found yet" help timing). */
    var scanStartedAt by mutableStateOf(0L)
        private set
    private var scanJob: Job? = null
    private var lastTarget: PcTarget? = null

    /** Set when the user chose to disconnect (or cancelled pairing); Home then does not reconnect on its own. */
    var userDisconnected by mutableStateOf(false)
        private set
    /** Play was tapped while not connected: open the gamepad as soon as the session is up. */
    var playPending by mutableStateOf(false)

    init {
        viewModelScope.launch {
            val s = repo.settings.first()
            if (s.lastHost.isNotBlank()) lastTarget = PcTarget(s.lastName.ifEmpty { s.lastHost }, s.lastHost, s.lastTcpPort)
            screen = com.phone2gamepad.ui.Startup.initialScreen(s.lastHost)
            if (screen == Screen.Discover) startScan()
            ready = true
        }
    }

    fun navigate(s: Screen) {
        if (s == screen) return
        history.addLast(screen)
        screen = s
        if (s == Screen.Discover) startScan() else stopScan()
    }

    /** Replace the current screen without growing the back stack. */
    fun replace(s: Screen) {
        screen = s
        if (s == Screen.Discover) startScan() else stopScan()
    }

    /** Go to Home and forget the screens before it (after pairing, or leaving the onboarding). */
    fun goHome() {
        history.clear()
        screen = Screen.Home
        stopScan()
    }

    /** Returns false when there is nothing to go back to (the activity should finish). */
    fun back(): Boolean {
        Log.i("P2G", "back() from $screen")
        when (screen) {
            Screen.Home -> { connection.disconnect(); return false }
            // Leaving the pad keeps the session so Play is instant; controls are released.
            Screen.Gamepad -> { connection.input.releaseAll(); playPending = false; goHome(); return true }
            // Backing out of connect/pair cancels it, and Home must not immediately start it again.
            Screen.Connect -> { connection.disconnect(); userDisconnected = true; playPending = false }
            else -> {}
        }
        val prev = history.removeLastOrNull()
        if (prev == null) {
            if (screen == Screen.Welcome) return false
            screen = if (lastTarget != null) Screen.Home else Screen.Welcome
            stopScan()
            return true
        }
        // A stale Connect entry (e.g. pairing finished) is skipped.
        screen = if (prev == Screen.Connect) (history.removeLastOrNull() ?: Screen.Home) else prev
        if (screen == Screen.Discover) startScan() else stopScan()
        return true
    }

    val hasPc: Boolean get() = lastTarget != null
    val lastPc: PcTarget? get() = lastTarget

    /** Home auto-connect (see [com.phone2gamepad.ui.Startup.shouldAutoConnect]). */
    fun autoConnectIfNeeded() {
        val t = lastTarget ?: return
        if (com.phone2gamepad.ui.Startup.shouldAutoConnect(screen, connection.state.value, t.host, userDisconnected)) {
            connection.connect(t)
        }
    }

    /** Explicit connect from Home (clears a previous manual disconnect). */
    fun reconnect() {
        val t = lastTarget ?: return
        userDisconnected = false
        connection.connect(t)
    }

    fun disconnectByUser() {
        userDisconnected = true
        playPending = false
        connection.disconnect()
    }

    /** Play: straight to the pad when connected, otherwise connect first and open it once the session is up. */
    fun play() {
        if (connection.state.value is ConnState.Connected) {
            navigate(Screen.Gamepad)
        } else {
            playPending = true
            if (connection.state.value is ConnState.Idle || connection.state.value is ConnState.Failed) reconnect()
        }
    }

    /** "Change PC": drop the session and pick another PC (the stored pairing stays, so going back needs no PIN). */
    fun changePc() {
        disconnectByUser()
        navigate(Screen.Discover)
    }

    /** Debug builds: show the first-run screens without wiping the pairing. */
    fun previewOnboarding() {
        disconnectByUser()
        navigate(Screen.Welcome)
    }

    fun openCustomize() = navigate(Screen.Customize)

    fun startScan() {
        scanJob?.cancel()
        discovered.value = emptyList()
        scanning = true
        scanStartedAt = System.currentTimeMillis()
        scanJob = viewModelScope.launch {
            try {
                DiscoveryClient.scan().collect { discovered.value = it }
            } finally {
                scanning = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanning = false
    }

    /** Connect to a PC picked in discovery / manual entry; shows the connect & pair screen. */
    fun connect(target: PcTarget) {
        lastTarget = target
        userDisconnected = false
        viewModelScope.launch {
            repo.update(settings.value.copy(lastHost = target.host, lastTcpPort = target.tcpPort, lastName = target.name))
        }
        connection.connect(target)
        navigate(Screen.Connect)
    }

    private fun editProfiles(limitMessage: String? = null, change: (ProfileBook) -> ProfileBook?) {
        viewModelScope.launch {
            if (!profileRepo.update(change) && limitMessage != null) notice = limitMessage
        }
    }

    /** Profile being edited by the tuning / layout editor screens (independent of which one is active). */
    var editingId by mutableStateOf<String?>(null)
        private set

    fun openTuning(id: String) { editingId = id; navigate(Screen.Tuning) }
    fun openLayoutEditor(id: String) { editingId = id; navigate(Screen.LayoutEditor) }

    /** Skin gallery for profile [profileId] (the skin chosen there is applied to that profile). */
    fun openSkins(profileId: String) { editingId = profileId; navigate(Screen.Skins) }

    /** Skin being edited by the skin creator (a custom skin id). */
    var editingSkinId by mutableStateOf<String?>(null)
        private set

    fun openSkinEditor(skinId: String) { editingSkinId = skinId; navigate(Screen.SkinEditor) }

    /** Opens the layout editor from the live gamepad: every control is released first so nothing stays pressed. */
    fun openLayoutEditorFromGamepad() {
        connection.input.releaseAll()
        openLayoutEditor(profiles.value.activeId)
    }

    private val limitMsg = "You can keep up to ${ProfileBook.MAX_PROFILES} profiles. Delete one first."
    fun selectProfile(id: String) = editProfiles { it.select(id) }
    fun createProfile(name: String) = editProfiles(limitMsg) { it.create(name) }
    fun renameProfile(id: String, name: String) = editProfiles { it.rename(id, name) }
    fun duplicateProfile(id: String) = editProfiles(limitMsg) { it.duplicate(id) }
    fun deleteProfile(id: String) = editProfiles { it.delete(id) }
    fun resetProfile(id: String) = editProfiles { it.reset(id) }
    fun saveProfile(p: Profile) = editProfiles { it.replace(p) }

    /** Applies [change] to the *stored* version of profile [id] (so concurrent edits of other fields are not overwritten). */
    fun editProfile(id: String, change: (Profile) -> Profile) = editProfiles { b ->
        b.profiles.firstOrNull { it.id == id }?.let { b.replace(change(it)) }
    }

    /** Imports an already-decoded profile; false result is reported through [notice]. */
    fun importProfile(p: Profile) = editProfiles(limitMsg) { it.importProfile(p) }
    fun dismissProfileBackupNotice() { viewModelScope.launch { profileRepo.dismissBackupNotice() } }

    // ---- skins ----
    private val skinLimitMsg = "You can keep up to ${SkinLibrary.MAX_CUSTOM} custom skins. Delete one first."

    fun skinFor(p: Profile): Skin = skins.value.resolve(p.skinId)
    fun setProfileSkin(profileId: String, skinId: String) = editProfile(profileId) { it.copy(skinId = skinId) }

    /** Saves a custom skin the user edited. */
    fun saveSkin(skin: Skin) { viewModelScope.launch { skinRepo.update { it.replace(skin) } } }

    /** Makes a custom copy of any skin; [open] then jumps straight into the creator. */
    fun duplicateSkin(id: String, open: Boolean) {
        viewModelScope.launch {
            var created: Skin? = null
            val ok = skinRepo.update { lib -> lib.duplicate(id)?.also { created = it.second }?.first }
            if (!ok) notice = skinLimitMsg else if (open) created?.let { openSkinEditor(it.id) }
        }
    }

    fun importSkin(skin: Skin) {
        viewModelScope.launch {
            var added: Skin? = null
            val ok = skinRepo.update { lib -> lib.add(skin)?.also { added = it.second }?.first }
            notice = if (ok) "Imported skin \"${added?.name}\"." else skinLimitMsg
        }
    }

    /** Deletes a custom skin; profiles that used it go back to Classic. */
    fun deleteSkin(id: String) {
        viewModelScope.launch {
            skinRepo.update { it.delete(id) }
            profileRepo.update { it.clearSkin(id) }
        }
    }

    // ---- controller type and background ----

    /** Switches the controller type of profile [id]; every control is released first so nothing stays pressed. */
    fun setProfileType(id: String, type: ControllerType) {
        connection.input.releaseAll()
        editProfile(id) { it.copy(controllerType = type) }
    }

    /** Stores the picture at [uri] as a background; [onDone] gets the new file reference or an error message. */
    fun importBackground(uri: Uri, onDone: (ref: String?, error: String?) -> Unit) {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { backgroundStore.importImage(uri) }) {
                is BackgroundStore.Result.Ok -> onDone(r.ref, null)
                is BackgroundStore.Result.Rejected -> onDone(null, r.message)
            }
        }
    }

    /** Loads a stored background picture (off the main thread). */
    suspend fun loadBackground(ref: String): Bitmap? = withContext(Dispatchers.IO) { backgroundStore.load(ref) }

    fun dismissSkinBackupNotice() { viewModelScope.launch { skinRepo.dismissBackupNotice() } }

    fun updateSettings(s: Settings) {
        viewModelScope.launch { repo.update(s) }
    }

    /** Activity left the foreground: release everything and drop the session (the PC must not keep a live pad). */
    fun onBackgrounded() {
        Log.i("P2G", "onBackgrounded screen=$screen")
        connection.input.releaseAll()
        if (screen in SESSION_SCREENS) connection.disconnect()
    }

    /** Activity is back: silently re-establish the session if the user was using it. */
    fun onForegrounded() {
        if (screen in SESSION_SCREENS && !userDisconnected && connection.state.value is ConnState.Idle) {
            lastTarget?.let { connection.connect(it) }
        }
    }
}
