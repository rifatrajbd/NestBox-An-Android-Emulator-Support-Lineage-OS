package app.nestbox.engine.classic

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.view.MotionEvent
import android.view.Surface
import app.nestbox.bridge.GuestAdb
import app.nestbox.bridge.SharedFolder
import app.nestbox.bridge.Spoof
import app.nestbox.engine.BootLogs
import app.nestbox.engine.ProcUtil
import app.nestbox.engine.VmEngine
import app.nestbox.engine.VmState
import app.nestbox.engine.rom.RomStore
import app.nestbox.host.HostInfo
import app.nestbox.settings.AppSettings
import app.nestbox.vm.Catalog
import app.nestbox.vm.Health
import app.nestbox.vm.SpoofProfile
import app.nestbox.vm.VmConfig
import io.twoyi.Renderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

/**
 * Classic mode: Twoyi's engine (Android 8.1), improved for Nestbox:
 * runs in its own VmService process, survives HyperOS through the heartbeat and process-limit fix,
 * keeps one rootfs per machine, saves logs on a failed boot, and cleans up leftover guest processes.
 *
 * Twoyi's binaries use the fixed path /data/data/app.nbox/rootfs (rewritten from io.twoyi), so
 * that path is a link to the running machine's rootfs and one Classic machine runs at a time.
 */
class ClassicEngine(private val context: Context, private val settings: AppSettings) : VmEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = HostInfo(context)
    private val roms = RomStore(context)
    private val dataDir = File("/data/data/" + context.packageName)
    private val activeRootfs = File(dataDir, "rootfs")
    private val bootLog = File(dataDir, "log.txt")
    private val state = MutableStateFlow<VmState>(VmState.Stopped)
    private val log = ArrayList<String>()
    private var vm: VmConfig? = null
    private var surface: Surface? = null
    private var surfaceReady = CompletableDeferred<Surface>()
    private var rendererStarted = false
    private var server: LocalServerSocket? = null
    private var watchdog: Job? = null

    private fun rootfs(id: String) = File(context.filesDir, "vms/$id/rootfs")

    private fun line(text: String, pct: Float) {
        synchronized(log) {
            log += text
            while (log.size > 200) log.removeAt(0)
        }
        state.value = VmState.Booting(pct, text, synchronized(log) { log.toList() })
    }

    override suspend fun prepare(vm: VmConfig, onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        val dest = rootfs(vm.id)
        if (File(dest, TwoyiRootfs.MARKER).isFile) return@withContext
        val rom = Catalog.rom(vm.romId)
        val archive = roms.ensure(rom, vm.id, vm.importUri) { p, t -> onProgress(p * 0.5f, t) }
        // Measure the app's storage: a folder that does not exist yet reports 0 bytes free.
        val free = context.filesDir.usableSpace
        if (free < 1_500_000_000L) {
            throw IOException("Not enough free storage: Classic machines need about 1.5 GB, ${RomStore.mb(free)} free")
        }
        val patched = TwoyiRootfs.extract(archive, dest) { p, t -> onProgress(0.5f + p * 0.5f, t) }
        onProgress(1f, "System ready · $patched files adapted for Nestbox")
        roms.delete(rom, vm.id)
    }

    override suspend fun start(vm: VmConfig, surface: Surface?) {
        this.vm = vm
        if (surface != null) surfaceReady.complete(surface)
        synchronized(log) { log.clear() }
        line("[nestbox] Classic engine · Twoyi improved · ${vm.name}", 0f)
        try {
            prepare(vm) { p, t -> line(t, p * 0.5f) }
            withContext(Dispatchers.IO) { bootFiles(vm) }
            line("[nestbox] loading renderer", 0.55f)
            System.loadLibrary("OpenglRender")
            System.loadLibrary("twoyi")
            startBootListener()
            line("[nestbox] waiting for the display", 0.58f)
            val s = surfaceReady.await()
            val dm = context.resources.displayMetrics
            line("[nestbox] starting Android 8.1", 0.6f)
            Renderer.init(s, loaderPath(), dm.xdpi, dm.ydpi, Catalog.preset(vm.preset).fps.coerceAtMost(60))
            rendererStarted = true
            watchdog = scope.launch { watchBoot(vm) }
        } catch (e: Throwable) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Twoyi's RomManager.initRootfs + ensureBootFiles, per machine. */
    private fun bootFiles(vm: VmConfig) {
        val root = rootfs(vm.id)
        // The fixed rootfs path points at this machine.
        runCatching { Os.remove(activeRootfs.path) }
        if (activeRootfs.exists()) activeRootfs.deleteRecursively()
        Os.symlink(root.path, activeRootfs.path)
        val loader = File(dataDir, "loader64")
        runCatching { Os.remove(loader.path) }
        Os.symlink(loaderPath(), loader.path)
        listOf("dev/input", "dev/socket", "dev/maps").forEach { File(root, it).mkdirs() }
        File(dataDir, "socket").mkdirs()
        // Guest processes left over from a killed run would hold the old sockets.
        ProcUtil.kill(ProcUtil.orphansUnder(root) + ProcUtil.orphansUnder(activeRootfs))
        // Language, timezone and density, like Twoyi.
        val region = Catalog.region(vm.spoof.region)
        val tz = if (region.code == "Off") TimeZone.getDefault().id else region.tz
        val locale = if (region.code == "Off") Locale.getDefault() else Locale.forLanguageTag(region.locale)
        val props = Properties().apply {
            setProperty("persist.sys.language", locale.language)
            setProperty("persist.sys.country", locale.country)
            setProperty("persist.sys.timezone", tz)
            setProperty("ro.sf.lcd_density", context.resources.displayMetrics.densityDpi.toString())
            // Spoofed device and carrier: vendor/default.prop loads before build.prop, so ro.* win.
            Spoof.bootProps(vm.spoof).forEach { kv ->
                val (k, v) = kv.split('=', limit = 2)
                setProperty(k, v)
            }
        }
        // Android 8.1 keeps persistent properties as one file each; these beat earlier values.
        val propDir = File(root, "data/property").apply { mkdirs() }
        File(propDir, "persist.sys.timezone").writeText(tz)
        File(propDir, "persist.sys.locale").writeText(locale.toLanguageTag())
        // Twoyi's su daemon (phh Superuser) runs only while root_access is 1 or 3 (apps and adb).
        File(propDir, "persist.sys.root_access").writeText(if (vm.root) "3" else "0")
        // NestBox's ADB key, so the bridge connects without a prompt in the guest.
        runCatching {
            val keys = File(root, "data/misc/adb").apply { mkdirs() }
            File(keys, "adb_keys").writeText(bridge.publicKeyLine())
        }
        val vendor = File(root, "vendor").let { if (it.exists()) it else File(root, "system/vendor") }
        runCatching { File(vendor, "default.prop").writer().use { props.store(it, null) } }
        bootLog.delete()
    }

    /** The guest reports a finished boot on the abstract socket TWOYI_SOCK, like in Twoyi. */
    private fun startBootListener() {
        if (server != null) return
        val sock = LocalSocket(LocalSocket.SOCKET_SEQPACKET)
        sock.bind(LocalSocketAddress("TWOYI_SOCK", LocalSocketAddress.Namespace.ABSTRACT))
        val srv = LocalServerSocket(sock.fileDescriptor)
        server = srv
        scope.launch {
            while (isActive) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                launch {
                    runCatching {
                        val buf = ByteArray(1024)
                        val input = client.inputStream
                        while (isActive) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            if (String(buf, 0, n, Charsets.US_ASCII).startsWith("BOOT_COMPLETED")) {
                                watchdog?.cancel()
                                state.value = VmState.Running
                                onRunning()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun watchBoot(vm: VmConfig) {
        val started = System.currentTimeMillis()
        var shown = 0
        while (scope.isActive && state.value is VmState.Booting) {
            delay(1500)
            val lines = BootLogs.tail(bootLog, 200)
            if (lines.size != shown) {
                shown = lines.size
                val elapsed = (System.currentTimeMillis() - started) / 1000f
                lines.takeLast(2).filter { it.isNotBlank() }
                    .forEach { line(it.take(160), (0.6f + elapsed / 240f).coerceAtMost(0.95f)) }
            }
            // Guest init shutting down: a critical service crashed (or something asked for a reboot).
            // It cannot come back on its own, so stop now and keep the log that explains it.
            val down = lines.firstOrNull { l -> REBOOT_MARKS.any { it in l } }
            if (down != null) {
                val why = lines.lastOrNull { "critical process" in it || "exited 4 times" in it }
                    ?: lines.lastOrNull { "Reboot start" in it || "shutdown_command" in it || "powerctl" in it }
                    ?: down
                return fail("The guest shut itself down: " + why.substringAfter("init: ").take(140))
            }
            if (System.currentTimeMillis() - started > 6 * 60_000) return fail("The guest did not finish booting in 6 minutes")
        }
    }

    private suspend fun fail(message: String) {
        if (state.value is VmState.Failed) return
        val v = vm
        line("[nestbox] $message", 1f)
        var dir: String? = null
        if (v != null && settings.saveBootLogs) {
            dir = BootLogs.save(
                context, v.name,
                mapOf(
                    "summary.txt" to buildString {
                        appendLine("Machine: ${v.name} (${v.id}) · Classic · ${v.romId} · ${v.preset}")
                        appendLine("Phone: ${host.deviceName} · ${host.osVersion} · ${host.socName} · ${host.gpu}")
                        appendLine("Failure: $message")
                    },
                    "nestbox-boot.txt" to synchronized(log) { log.joinToString("\n") },
                ),
                mapOf(
                    "init-log.txt" to bootLog,
                    "tombstones" to File(rootfs(v.id), "data/tombstones"),
                    "anr" to File(rootfs(v.id), "data/anr"),
                    "last-kmsg.txt" to File(rootfs(v.id), "dev/kmsg"),
                ),
            )
            line(if (dir != null) "[nestbox] logs saved to $dir" else "[nestbox] could not save logs", 1f)
        }
        state.value = VmState.Failed(message, dir, synchronized(log) { log.toList() })
        killGuest()
    }

    private fun killGuest() {
        ProcUtil.kill(ProcUtil.descendants(android.os.Process.myPid()))
    }

    override fun attachSurface(vmId: String, surface: Surface?, width: Int, height: Int) {
        val old = this.surface
        this.surface = surface
        if (surface == null) {
            if (old != null && rendererStarted) runCatching { Renderer.removeWindow(old) }
            return
        }
        if (!surfaceReady.isCompleted) {
            surfaceReady.complete(surface)
            return
        }
        if (rendererStarted) {
            val dm = context.resources.displayMetrics
            runCatching {
                Renderer.init(surface, loaderPath(), dm.xdpi, dm.ydpi, Catalog.preset(vm?.preset ?: "balanced").fps.coerceAtMost(60))
                if (width > 0 && height > 0) Renderer.resetWindow(surface, 0, 0, width, height)
            }
        }
    }

    override fun sendInput(vmId: String, ev: MotionEvent) {
        if (rendererStarted) runCatching { Renderer.handleTouch(ev) }
    }

    /** Battery preset: freeze the guest's processes while its screen is minimized. */
    override fun setVisible(vmId: String, visible: Boolean) {
        if (vm?.preset != "battery" || state.value !is VmState.Running) return
        val sig = if (visible) 18 /* SIGCONT */ else 19 /* SIGSTOP */
        ProcUtil.descendants(android.os.Process.myPid()).forEach { runCatching { android.os.Process.sendSignal(it, sig) } }
    }

    override fun sendKey(vmId: String, keycode: Int) {
        if (rendererStarted) runCatching { Renderer.sendKeycode(keycode) }
    }

    override suspend fun stop(vmId: String) {
        watchdog?.cancel()
        // Bring guest-side changes back to the phone before the machine goes away.
        if (state.value is VmState.Running && vm?.shared == true) {
            kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { syncShared(vmId) } }
        }
        bridge.close()
        runCatching { server?.close() }
        server = null
        killGuest()
        if (state.value !is VmState.Failed) state.value = VmState.Stopped
    }

    override fun state(vmId: String): StateFlow<VmState> = state

    override fun health(vmId: String): Flow<Health> = flow {
        while (true) {
            val ram = ProcUtil.pssBytes(ProcUtil.descendants(android.os.Process.myPid()) + android.os.Process.myPid())
            emit(Health(fps = -1, tempC = host.tempC(), ramBytes = ram, throttled = false))
            delay(3000)
        }
    }

    private val bridge = GuestAdb(context)
    private val syncLock = kotlinx.coroutines.sync.Mutex()

    private fun onRunning() {
        val v = vm ?: return
        scope.launch {
            if (v.spoof.region != "Off") runCatching { bridge.shRoot(Spoof.liveCommands(v.spoof).joinToString("; ")) }
            if (v.shared) runCatching { syncShared(v.id) }
        }
    }

    override suspend fun installApk(vmId: String, apks: List<File>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (state.value !is VmState.Running) throw IOException("Start the machine first")
            if (apks.isEmpty()) throw IOException("No APK was given")
            val out = if (apks.size == 1) {
                bridge.exec("cmd package install -r -t -S ${apks[0].length()}", apks[0])
            } else {
                val created = bridge.exec("cmd package install-create -r -t -S ${apks.sumOf { it.length() }}")
                val session = Regex("""\[(\d+)]""").find(created)?.groupValues?.get(1) ?: throw IOException(pmError(created))
                apks.forEachIndexed { i, f ->
                    val w = bridge.exec("cmd package install-write -S ${f.length()} $session ${if (i == 0) "base" else "split_$i"}.apk -", f)
                    if ("Success" !in w) {
                        runCatching { bridge.exec("cmd package install-abandon $session") }
                        throw IOException(pmError(w))
                    }
                }
                bridge.exec("cmd package install-commit $session")
            }
            if ("Success" !in out) throw IOException(pmError(out))
            "Success"
        }
    }

    private fun pmError(out: String) = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        ?: "The package manager refused the app"

    override suspend fun applySpoof(vmId: String, spoof: SpoofProfile): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            vm = vm?.copy(spoof = spoof)
            if (state.value is VmState.Running) bridge.shRoot(Spoof.liveCommands(spoof).joinToString("; "))
            Unit
        }
    }

    /**
     * Two-way copy between the phone's /sdcard/Nestbox and the guest's /sdcard/Host over adb.
     * New and changed files go both ways (the newer copy wins); deletions are not copied, and a
     * file deleted on one side since the last sync is not brought back.
     */
    override suspend fun syncShared(vmId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (state.value !is VmState.Running) throw IOException("Start the machine first")
            syncLock.lock()
            try {
                val stateFile = File(context.filesDir, "vms/$vmId/shared-sync.txt")
                val known = if (stateFile.isFile) stateFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
                val host = SharedFolder.hostDir().apply { mkdirs() }
                val phone = SharedFolder.hostFiles().associateBy { it.path }
                val guest = HashMap<String, GuestAdb.Dent>()
                suspend fun walk(dir: String, rel: String) {
                    for (d in runCatching { bridge.list(dir) }.getOrDefault(emptyList())) {
                        val r = if (rel.isEmpty()) d.name else "$rel/${d.name}"
                        if (d.isDir) walk("$dir/${d.name}", r) else if (d.isFile) guest[r] = d
                    }
                }
                walk(SharedFolder.GUEST_DIR, "")
                val both = HashSet<String>()
                for (path in phone.keys + guest.keys) {
                    val p = phone[path]
                    val g = guest[path]
                    val remote = SharedFolder.GUEST_DIR + "/" + path
                    val local = File(host, path)
                    when {
                        p != null && g != null -> {
                            if (p.size != g.size || kotlin.math.abs(p.mtimeSec - g.mtimeSec) > 2) {
                                if (p.mtimeSec >= g.mtimeSec) bridge.push(local, remote, p.mtimeSec)
                                else { bridge.pull(remote, local); local.setLastModified(g.mtimeSec * 1000) }
                            }
                            both += path
                        }
                        p != null && path !in known -> { bridge.push(local, remote, p.mtimeSec); both += path }
                        g != null && path !in known -> { bridge.pull(remote, local); local.setLastModified(g.mtimeSec * 1000); both += path }
                    }
                }
                stateFile.writeText(both.joinToString("\n"))
            } finally {
                syncLock.unlock()
            }
        }
    }

    override suspend fun reconnect(vm: VmConfig) = state.value is VmState.Running

    private companion object {
        val REBOOT_MARKS = listOf("Reboot start", "shutdown_command", "sys.powerctl", "umounted /dev/block", "critical process")
    }

    private fun loaderPath() = File(context.applicationInfo.nativeLibraryDir, "libloader.so").path
}
