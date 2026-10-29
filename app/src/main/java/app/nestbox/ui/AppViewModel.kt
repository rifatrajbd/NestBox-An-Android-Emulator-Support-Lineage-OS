package app.nestbox.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.nestbox.adb.PairState
import app.nestbox.adb.PairingService
import app.nestbox.adb.ProcessLimit
import app.nestbox.engine.ClassicBusyException
import app.nestbox.engine.MachineFiles
import app.nestbox.engine.NoFreeSlotException
import app.nestbox.service.Heartbeat
import app.nestbox.service.VmService
import app.nestbox.xiaomi.XiaomiIntents
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nestbox.NestboxApp
import app.nestbox.host.HostInfo
import app.nestbox.engine.VmState
import app.nestbox.settings.AppSettings
import app.nestbox.vm.Catalog
import app.nestbox.vm.Snapshot
import app.nestbox.vm.SpoofProfile
import app.nestbox.vm.VmConfig
import app.nestbox.vm.VmStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import app.nestbox.bridge.SharedFolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WizardDraft(
    val engine: String = Catalog.MODERN,
    val rom: String = "a13gapps",
    /** content:// URI and display name of an imported ROM file. */
    val importUri: String? = null,
    val importName: String? = null,
    val name: String,
    val preset: String,
    val res: String = "1080×2400",
    val root: Boolean = true,
    val xposed: Boolean = true,
    val gms: Boolean = false,
    val keepAlive: Boolean = true,
    val shared: Boolean = true,
)

sealed interface UiEvent {
    data object AskNotifications : UiEvent
    /** Storage, to save boot logs and use the shared folder in /sdcard/Nestbox. */
    data object AskStorage : UiEvent
}

/** A phone app; [apks] is the base APK followed by its split APKs. */
data class HostApp(val pkg: String, val name: String, val sizeBytes: Long, val apks: List<File>, val icon: Bitmap?)

data class SharedFile(val icon: String, val name: String, val meta: String)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NestboxApp
    val host = app.host
    private val repo = app.repo
    private val engine = app.engine

    val vms: StateFlow<List<VmConfig>> = repo.vms
    val settings: StateFlow<AppSettings> = app.settings.settings
    val setupFinished: Boolean get() = app.setup.finished

    /** Setup row key → done. Real checks where HyperOS reports them, the user's word otherwise. */
    private val _setupDone = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val setupDone: StateFlow<Map<String, Boolean>> = _setupDone.asStateFlow()
    val pairState = ProcessLimit.state

    /** One-shot requests to the activity, e.g. the notification permission prompt. */
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _states = MutableStateFlow<Map<String, VmState>>(emptyMap())
    val states: StateFlow<Map<String, VmState>> = _states.asStateFlow()
    private val watched = mutableSetOf<String>()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private var toastJob: Job? = null

    private val _hostTemp = MutableStateFlow(host.tempC())
    val hostTemp: StateFlow<Int> = _hostTemp.asStateFlow()

    // Wizard
    private val _draft = MutableStateFlow(newDraft())
    val draft: StateFlow<WizardDraft> = _draft.asStateFlow()
    val wizardStep = MutableStateFlow(0)

    // Spoof editor
    val spoofDraft = MutableStateFlow(SpoofProfile())

    /** Machine whose display should open, set by a notification tap. */
    val openVm = MutableStateFlow<String?>(null)

    // Running screen
    val floating = MutableStateFlow(false)
    val ballOpen = MutableStateFlow(false)

    // Transfer
    val transferTab = MutableStateFlow("apps")
    private val _hostApps = MutableStateFlow<List<HostApp>>(emptyList())
    val hostApps: StateFlow<List<HostApp>> = _hostApps.asStateFlow()
    private val _copied = MutableStateFlow<Set<String>>(emptySet())
    val copied: StateFlow<Set<String>> = _copied.asStateFlow()
    val clipboardSync = MutableStateFlow(true)

    private val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    /** The newest files in the shared folder (/sdcard/Nestbox). */
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()

    init {
        refreshSetup()
        viewModelScope.launch {
            repo.vms.collect { list -> list.forEach { watch(it.id) } }
        }
        viewModelScope.launch {
            while (true) {
                _hostTemp.value = host.tempC()
                delay(3000)
            }
        }
        // Machines that were running when the app last closed: reattach, or learn HyperOS killed them.
        repo.vms.value.filter { it.lastStatus == VmStatus.Running }.forEach { vm ->
            viewModelScope.launch { engine.reconnect(vm) }
        }
        viewModelScope.launch {
            ProcessLimit.state.collect { if (it == PairState.Done) refreshSetup() }
        }
    }

    /** Real memory (PSS) per machine, from the engines' health reports. */
    private val _ram = MutableStateFlow<Map<String, Long>>(emptyMap())
    val ram: StateFlow<Map<String, Long>> = _ram.asStateFlow()

    private fun watch(id: String) {
        if (!watched.add(id)) return
        viewModelScope.launch {
            engine.health(id).collect { h -> _ram.update { it + (id to h.ramBytes) } }
        }
        viewModelScope.launch {
            engine.state(id).collect { st ->
                _states.update { it + (id to st) }
                when (st) {
                    VmState.Running -> repo.update(id) { it.copy(lastStatus = VmStatus.Running, lastSeenMs = null) }
                    is VmState.Killed -> repo.update(id) {
                        it.copy(lastStatus = VmStatus.Killed, lastSeenMs = st.lastSeenMs ?: it.lastSeenMs ?: System.currentTimeMillis())
                    }
                    else -> Unit
                }
            }
        }
    }

    fun status(vm: VmConfig): VmStatus = when (_states.value[vm.id]) {
        is VmState.Booting -> VmStatus.Booting
        VmState.Running -> VmStatus.Running
        is VmState.Killed -> VmStatus.Killed
        else -> if (vm.lastStatus == VmStatus.Killed) VmStatus.Killed else VmStatus.Stopped
    }

    fun health(id: String) = engine.health(id)

    /** Real engines draw into a SurfaceView; the demo engine (emulators) has no guest display. */
    val hasGuestDisplay: Boolean get() = HostInfo.realEngines

    fun attachSurface(id: String, surface: android.view.Surface?, w: Int, h: Int) = engine.attachSurface(id, surface, w, h)
    fun sendInput(id: String, ev: android.view.MotionEvent) = engine.sendInput(id, ev)
    fun sendKey(id: String, keycode: Int) = engine.sendKey(id, keycode)
    fun setVisible(id: String, visible: Boolean) = engine.setVisible(id, visible)

    /** When each machine's last frame was saved; the machine card reloads its picture on change. */
    private val _frames = MutableStateFlow<Map<String, Long>>(emptyMap())
    val frames: StateFlow<Map<String, Long>> = _frames.asStateFlow()

    fun lastFrameFile(id: String) = File(app.filesDir, "vms/$id/last.jpg")

    /** Keeps the guest's screen at minimize time for the machine card (VMOS-style). */
    fun saveLastFrame(id: String, bmp: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val f = lastFrameFile(id)
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                tmp.renameTo(f)
                _frames.update { it + (id to System.currentTimeMillis()) }
            }
            bmp.recycle()
        }
    }

    /** True once: the first time a guest fills the screen, to explain the minimize gesture. */
    fun firstFullScreen(): Boolean {
        val p = app.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE)
        if (p.getBoolean("three_finger", false)) return false
        p.edit().putBoolean("three_finger", true).apply()
        return true
    }

    fun showToast(text: String) {
        toastJob?.cancel()
        _toast.value = text
        toastJob = viewModelScope.launch {
            delay(2200)
            _toast.value = null
        }
    }

    // ---- Setup
    /** Re-reads every check; the setup screen calls this each time it comes back into view. */
    fun refreshSetup() {
        app.setup.refresh()
        val flags = app.setup.done.value
        val c = app.checks
        _setupDone.value = mapOf(
            "autostart" to (c.autostart() ?: (flags["autostart"] == true)),
            "battery" to c.battery(),
            "recents" to (flags["recents"] == true),
            "popup" to c.popup(),
            "processes" to (flags["processes"] == true),
        )
    }

    /** Opens the HyperOS screen for a setup row. Rows the phone cannot report count as done once opened. */
    fun openFix(key: String) {
        when (key) {
            "autostart" -> {
                XiaomiIntents.autostart(app)
                if (app.checks.autostart() == null) app.setup.mark("autostart")
            }
            "battery" -> XiaomiIntents.battery(app)
            "popup" -> XiaomiIntents.popup(app)
        }
    }

    /** The user confirmed a step Nestbox cannot check (Lock in Recents). */
    fun confirmFix(key: String) {
        app.setup.mark(key)
        refreshSetup()
    }

    /** Starts pairing with Wireless debugging and opens its settings screen. */
    fun startPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) _events.tryEmit(UiEvent.AskNotifications)
        if (ProcessLimit.state.value !is PairState.Working) ProcessLimit.reset()
        PairingService.start(app)
        XiaomiIntents.wirelessDebugging(app)
    }

    fun submitPairingCode(code: String) {
        viewModelScope.launch { ProcessLimit.pairAndFix(app, code) }
    }

    fun finishSetup() {
        app.setup.finished = true
    }

    // ---- Settings
    fun updateSettings(change: (AppSettings) -> AppSettings) = app.settings.update(change)

    // ---- Machines
    fun start(id: String) {
        val vm = repo.get(id) ?: return
        floating.value = false
        ballOpen.value = false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) _events.tryEmit(UiEvent.AskNotifications)
        if (app.settings.settings.value.saveBootLogs &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) _events.tryEmit(UiEvent.AskStorage)
        val wasKilled = status(vm) == VmStatus.Killed
        viewModelScope.launch {
            try {
                engine.start(vm, null)
                if (wasKilled) showToast("Resumed from last saved state")
            } catch (_: NoFreeSlotException) {
                showToast("Stop a machine first · ${VmService.SLOTS} can run at once")
            } catch (e: ClassicBusyException) {
                showToast("Stop ${repo.get(e.running)?.name ?: "the other machine"} first · one Classic machine runs at a time")
            }
        }
    }

    fun stop(id: String) {
        viewModelScope.launch {
            engine.stop(id)
            repo.update(id) { it.copy(lastStatus = VmStatus.Stopped) }
        }
    }

    /** The engine's failure for this machine, if its last start failed. */
    fun failure(id: String): VmState.Failed? = _states.value[id] as? VmState.Failed

    /** Stops the machine if needed, then erases it and everything in it. */
    fun deleteMachine(id: String, done: () -> Unit) {
        val vm = repo.get(id) ?: return
        viewModelScope.launch {
            val st = status(vm)
            if (st == VmStatus.Running || st == VmStatus.Booting) engine.stop(id)
            withContext(Dispatchers.IO) {
                repeat(10) { if (Heartbeat.read(app, id)?.fresh() == true && Heartbeat.read(app, id)?.stopped == false) delay(500) }
                MachineFiles.delete(app, id)
            }
            repo.remove(id)
            showToast("${vm.name} deleted")
            done()
        }
    }

    fun toggle(id: String, key: String) {
        val vm = repo.get(id) ?: return
        repo.update(id) {
            when (key) {
                "root" -> it.copy(root = !it.root)
                "xposed" -> it.copy(xposed = !it.xposed)
                "gms" -> it.copy(gms = !it.gms)
                else -> it.copy(keepAlive = !it.keepAlive)
            }
        }
        if (status(vm) == VmStatus.Running && key != "keepAlive") showToast("Applies on next restart")
    }

    fun takeSnapshot(id: String) {
        val vm = repo.get(id) ?: return
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val size = "%.1f GB".format(Locale.US, 1.2 + Catalog.preset(vm.preset).ramGb * 0.3)
        repo.update(id) { it.copy(snapshots = listOf(Snapshot("Snapshot " + (it.snapshots.size + 1), "Today · $time", size)) + it.snapshots) }
        showToast("Snapshot saved")
    }

    fun copyAdb() {
        val cm = app.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("adb", "adb connect 127.0.0.1:5556"))
        showToast("adb connect 127.0.0.1:5556 copied")
    }

    // ---- Wizard
    private fun newDraft() = WizardDraft(name = "Machine " + (repo.vms.value.size + 1), preset = app.settings.settings.value.defaultPreset)

    /** Switches the wizard between Modern and Classic, picking that mode's first system. */
    fun setDraftEngine(engine: String) = _draft.update {
        it.copy(engine = engine, rom = Catalog.romsFor(engine).first().id, importUri = null, importName = null)
    }

    fun setDraftImport(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        _draft.update { it.copy(importUri = uri.toString(), importName = name ?: "chosen file") }
    }

    fun resetWizard() {
        _draft.value = newDraft()
        wizardStep.value = 0
    }

    fun editDraft(change: (WizardDraft) -> WizardDraft) = _draft.update(change)

    /** Creates the machine and starts it. Returns the new id. */
    fun createFromDraft(): String {
        val d = _draft.value
        val vm = VmConfig(
            id = repo.newId(), name = d.name.ifBlank { "Machine" }, engine = d.engine, romId = d.rom,
            importUri = d.importUri, preset = d.preset, res = d.res,
            root = d.root, xposed = d.xposed, gms = d.gms || Catalog.rom(d.rom).gms, keepAlive = d.keepAlive, shared = d.shared,
        )
        repo.add(vm)
        start(vm.id)
        return vm.id
    }

    // ---- Spoof
    fun openSpoof(id: String) {
        spoofDraft.value = repo.get(id)?.spoof ?: SpoofProfile()
    }

    fun applySpoof(id: String) {
        val p = spoofDraft.value
        val place = Catalog.place(p.place)
        val profile = p.copy(lat = place.lat, lng = place.lng)
        repo.update(id) { it.copy(spoof = profile) }
        showToast("Profile applied · reopen apps to refresh")
        // A running machine takes the region and location now; device identity at its next start.
        val vm = repo.get(id) ?: return
        if (status(vm) == VmStatus.Running) viewModelScope.launch { engine.applySpoof(id, profile) }
    }

    /** Reads the shared folder; Classic machines copy their side over first. */
    fun refreshShared(vmId: String) {
        viewModelScope.launch {
            _sharedFiles.value = listShared()
            val vm = repo.get(vmId) ?: return@launch
            if (vm.shared && vm.engine == Catalog.CLASSIC && status(vm) == VmStatus.Running) {
                engine.syncShared(vmId)
                _sharedFiles.value = listShared()
            }
        }
    }

    private suspend fun listShared(): List<SharedFile> = withContext(Dispatchers.IO) {
        val root = SharedFolder.hostDir()
        val day = SimpleDateFormat("MMM d", Locale.US)
        SharedFolder.hostFiles().sortedByDescending { it.mtimeSec }.take(12).map { e ->
            val ext = e.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val icon = when (ext) {
                "png", "jpg", "jpeg", "webp", "gif", "heic" -> "image"
                "zip", "apk", "7z", "rar", "gz", "tar" -> "zip"
                else -> "text"
            }
            val size = File(root, e.path).length().let { b ->
                if (b >= 1_000_000) String.format(Locale.US, "%.1f MB", b / 1e6) else "${(b + 999) / 1000} KB"
            }
            val ms = e.mtimeSec * 1000
            val diffDays = ((System.currentTimeMillis() - ms) / 86_400_000L)
            val whenText = when (diffDays) { 0L -> "today"; 1L -> "yesterday"; else -> day.format(Date(ms)) }
            SharedFile(icon, e.path, "$size · $whenText")
        }
    }

    private fun reason(e: Throwable) = e.message?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

    fun toggleJoystick(id: String) = repo.update(id) { it.copy(spoof = it.spoof.copy(joystick = !it.spoof.joystick)) }

    // ---- Transfer
    fun loadHostApps() {
        if (_hostApps.value.isNotEmpty()) return
        viewModelScope.launch {
            _hostApps.value = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcher, 0).map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != app.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { ai ->
                        val files = listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
                        HostApp(
                            ai.packageName, pm.getApplicationLabel(ai).toString(), files.sumOf { File(it).length() },
                            files.map { File(it) }, runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96) }.getOrNull(),
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }
        }
    }

    fun copyApp(vmId: String, a: HostApp) {
        val key = vmId + a.pkg
        if (key in _copied.value) return
        val vmName = repo.get(vmId)?.name ?: return
        viewModelScope.launch {
            engine.installApk(vmId, a.apks).onSuccess {
                _copied.update { it + key }
                showToast("${a.name} installed in $vmName")
            }.onFailure { showToast("Could not install ${a.name}" + reason(it)) }
        }
    }

    fun installApkFrom(vmId: String, uri: Uri) {
        val vmName = repo.get(vmId)?.name ?: return
        viewModelScope.launch {
            val (file, name) = withContext(Dispatchers.IO) {
                val cr = app.contentResolver
                val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "app.apk"
                val out = File(app.cacheDir, "install.apk")
                cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                out to name.removeSuffix(".apk")
            }
            engine.installApk(vmId, listOf(file))
                .onSuccess { showToast("$name installed in $vmName") }
                .onFailure { showToast("Could not install $name" + reason(it)) }
            withContext(Dispatchers.IO) { file.delete() }
        }
    }
}
