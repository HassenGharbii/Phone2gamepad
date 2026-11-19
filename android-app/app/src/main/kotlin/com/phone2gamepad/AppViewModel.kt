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

enum class Screen { Welcome, Discover, Manual, Connect, Gamepad, Settings, Profiles, Tuning, LayoutEditor, Skins, SkinEditor, About }

private val GAMEPAD_SUBSCREENS = setOf(Screen.LayoutEditor, Screen.Tuning, Screen.Skins, Screen.SkinEditor, Screen.Profiles)

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
    private val history = ArrayDeque<Screen>()

    val discovered = MutableStateFlow<List<DiscoveredPc>>(emptyList())
    var scanning by mutableStateOf(false)
        private set
    private var scanJob: Job? = null
    private var lastTarget: PcTarget? = null

    fun navigate(s: Screen) {
        if (s == screen) return
        history.addLast(screen)
        screen = s
        if (s == Screen.Discover) startScan() else stopScan()
    }

    /** Replace the current screen without growing the back stack. */
    fun replace(s: Screen) {
        screen = s
        stopScan()
    }

    /** Returns false when there is nothing to go back to (the activity should finish). */
    fun back(): Boolean {
        Log.i("P2G", "back() from $screen")
        when (screen) {
            Screen.Gamepad, Screen.Connect -> connection.disconnect()
            else -> {}
        }
        val leaving = screen
        val prev = history.removeLastOrNull() ?: return false
        // The editors/galleries opened from the gamepad return to it (the session stays up); otherwise a stale
        // Gamepad/Connect entry means "back to discovery".
        val fromGamepadSubScreen = prev == Screen.Gamepad && leaving in GAMEPAD_SUBSCREENS
        screen = if (!fromGamepadSubScreen && (prev == Screen.Gamepad || prev == Screen.Connect)) Screen.Discover else prev
        if (screen == Screen.Discover) startScan() else stopScan()
        return true
    }

    fun startScan() {
        scanJob?.cancel()
        discovered.value = emptyList()
        scanning = true
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

    fun connect(target: PcTarget) {
        lastTarget = target
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

    /** Activity left the foreground while playing: release everything and drop the session. */
    fun onBackgrounded() {
        Log.i("P2G", "onBackgrounded screen=$screen")
        connection.input.releaseAll()
        if (screen == Screen.Gamepad) connection.disconnect()
    }

    /** Activity is back; silently re-establish the session if the user was on the gamepad. */
    fun onForegrounded() {
        if (screen == Screen.Gamepad && connection.state.value is ConnState.Idle) {
            lastTarget?.let { connection.connect(it) }
        }
    }
}
