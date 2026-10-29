package app.nestbox.engine.modern

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import app.nestbox.engine.BootLogs
import app.nestbox.engine.ProcUtil
import app.nestbox.engine.VmEngine
import app.nestbox.engine.VmState
import app.nestbox.engine.rom.RomStore
import app.nestbox.host.HostInfo
import app.nestbox.settings.AppSettings
import app.nestbox.vm.Catalog
import app.nestbox.vm.Health
import app.nestbox.vm.VmConfig
import com.hexadecinull.vineos.`native`.VineRuntime
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Modern mode: Wayondroid's rootless engine (tawcroot + binderd + gfxstream) booting a Waydroid
 * LineageOS 20 image. Runs inside the machine's VmService process; the helper is its child.
 */
class ModernEngine(
    private val context: Context,
    private val settings: AppSettings,
) : VmEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = HostInfo(context)
    private val roms = RomStore(context)
    private val base = File(context.filesDir, "vms")
    private val state = MutableStateFlow<VmState>(VmState.Stopped)
    private val log = ArrayList<String>()
    private var vm: VmConfig? = null
    private var helper: HelperLink? = null
    private var display = 0L
    private var surface: Surface? = null
    private var viewW = 0
    private var viewH = 0
    private var watchdog: Job? = null

    private fun guestDir(id: String) = File(base, "$id/guest")

    private fun line(text: String, pct: Float) {
        synchronized(log) {
            log += text
            while (log.size > 200) log.removeAt(0)
        }
        state.value = VmState.Booting(pct, text, synchronized(log) { log.toList() })
    }

    override suspend fun prepare(vm: VmConfig, onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        val dir = guestDir(vm.id)
        if (File(dir, "system.img").isFile) return@withContext
        val rom = Catalog.rom(vm.romId)
        if (rom.source is Catalog.Source.Port) {
            roms.ensurePort(rom, dir, onProgress)
            return@withContext
        }
        val vrom = roms.ensure(rom, vm.id, vm.importUri) { p, t -> onProgress(p * 0.7f, t) }
        unpackVrom(vrom, dir) { p, t -> onProgress(0.7f + p * 0.3f, t) }
        roms.delete(rom, vm.id) // imported copies are not kept twice
    }

    /** .vrom = zip with manifest.json (sha256 per image), system.img and optional vendor.img. */
    private fun unpackVrom(vrom: File, dir: File, progress: (Float, String) -> Unit) {
        dir.mkdirs()
        ZipFile(vrom).use { zip ->
            val manifestText = zip.getEntry("manifest.json")?.let { e -> zip.getInputStream(e).bufferedReader().use { it.readText() } }
                ?: throw IOException("This ROM file has no manifest.json")
            val sums = runCatching {
                Json.parseToJsonElement(manifestText).jsonObject["sha256"]?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.content }
            }.getOrNull().orEmpty()
            val wanted = listOf("vendor.img", "system.img").mapNotNull { n -> zip.getEntry(n)?.let { n to it } }
            if (wanted.none { it.first == "system.img" }) throw IOException("This ROM file has no system.img")
            val free = dir.usableSpace
            val need = wanted.sumOf { it.second.size }
            if (free < need + 1_000_000_000L) {
                throw IOException("Not enough free storage: needs ${RomStore.mb(need + 1_000_000_000L)}, ${RomStore.mb(free)} free")
            }
            File(dir, "manifest.json").writeText(manifestText)
            var done = 0L
            for ((name, entry) in wanted) {
                val part = File(dir, "$name.part")
                val md = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    part.outputStream().buffered(1 shl 18).use { os ->
                        val buf = ByteArray(1 shl 18)
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            os.write(buf, 0, n)
                            done += n
                            if (done - last > 20_000_000) {
                                last = done
                                progress(done.toFloat() / need, "Unpacking $name · ${RomStore.mb(done)} / ${RomStore.mb(need)}")
                            }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                val expected = sums[name]
                if (expected != null && !sha.equals(expected, true)) {
                    part.delete()
                    throw IOException("$name is damaged (checksum mismatch). Delete the machine and download again.")
                }
                part.renameTo(File(dir, name))
            }
        }
    }

    override suspend fun start(vm: VmConfig, surface: Surface?) {
        this.vm = vm
        if (surface != null) this.surface = surface
        synchronized(log) { log.clear() }
        line("[nestbox] Modern engine · Wayondroid · ${vm.name}", 0f)
        try {
            prepare(vm) { p, t -> line(t, p * 0.4f) }
            line("[nestbox] starting rootless engine", 0.42f)
            val link = HelperLink(scope, listOf(nativeLib("libvine_helper.so"), "--rootless", "--base", base.path))
            helper = link
            scope.launch { link.events.collect(::onEvent) }
            link.connect()
            val p = Catalog.preset(vm.preset)
            val (w, h) = vm.res.split('×', 'x').map { it.trim().toInt() }.let { it[0] to it[1] }
            val dm = context.resources.displayMetrics
            val dpi = (dm.densityDpi * w / dm.widthPixels.coerceAtLeast(1)).coerceIn(160, 640)
            line("[nestbox] boot ${w}x$h @ ${p.fps} · ${if (settings.gpuAcceleration) "GPU (gfxstream)" else "software rendering"}", 0.45f)
            // The rootfs only exists after the first boot extracts it, so this applies from the second boot.
            // NestBox's engine build applies these on every start, from the first boot.
            val rom = Catalog.rom(vm.romId)
            GuestProps.writeOverlay(guestDir(vm.id), rom, settings.lowMemory)
            if (settings.lowMemory) line("[nestbox] low memory mode on", 0.46f)
            val gpu = settings.gpuAcceleration && rom.sdk >= 33
            if (settings.gpuAcceleration && !gpu) line("[nestbox] Android ${GuestProps.release(rom.sdk)} has no ANGLE: software rendering", 0.46f)
            val reply = link.request(
                "start", vm.id, guestDir(vm.id).path,
                "ram_mb=${p.ramGb * 1024}",
                "cpu_cores=${p.cores}",
                "data_size_mb=16384",
                "width=$w", "height=$h", "dpi=$dpi",
                "refresh=${p.fps}",
                "network=${if (settings.guestInternet) "nat" else "none"}",
                "graphics=${if (gpu) "gfxstream" else "cpu"}",
                "qemu32=0",
                timeoutMs = 180_000,
            )
            if (!reply.ok) return fail(reply.payload.ifBlank { "The machine could not be started" })
            openDisplay()
            watchdog = scope.launch { watchBoot(vm) }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onEvent(e: HelperEvent) {
        when (e.name) {
            "state" -> {
                if (e.fields.getOrNull(0) != vm?.id) return
                when (e.fields.getOrNull(1)) {
                    "running" -> {
                        watchdog?.cancel()
                        state.value = VmState.Running
                        openDisplay()
                        scope.launch { tuneRunningGuest() }
                    }
                    "error" -> scope.launch { fail(e.fields.getOrNull(2).orEmpty().ifBlank { "The machine stopped unexpectedly" }) }
                    "stopped" -> if (state.value !is VmState.Failed) state.value = VmState.Stopped
                }
            }
            "helper_exit" -> if (state.value is VmState.Booting || state.value is VmState.Running) {
                scope.launch { fail(e.fields.firstOrNull().orEmpty()) }
            }
        }
    }

    /** Settings Android reads at runtime: keep fewer apps cached so the guest stays small. */
    private suspend fun tuneRunningGuest() {
        val h = helper ?: return
        val id = vm?.id ?: return
        val rom = vm?.let { Catalog.rom(it.romId) }
        val cmds = (if (settings.lowMemory) GuestProps.RUNTIME_COMMANDS else emptyList()) + rom?.afterBoot.orEmpty()
        for (cmd in cmds) {
            runCatching { h.request("exec", id, "8000", *cmd.toTypedArray(), timeoutMs = 10_000) }
        }
    }

    private var paused = false

    /** Battery preset: freeze the guest while its screen is minimized (CPU and battery, not RAM). */
    override fun setVisible(vmId: String, visible: Boolean) {
        val v = vm ?: return
        if (v.preset != "battery" || state.value !is VmState.Running) return
        val h = helper ?: return
        if (!visible && !paused) {
            paused = true
            scope.launch { h.request("pause", vmId) }
        } else if (visible && paused) {
            paused = false
            scope.launch { h.request("resume", vmId) }
        }
    }

    /** While booting: shows the guest's kernel log and stops a crash loop instead of spinning forever. */
    private suspend fun watchBoot(vm: VmConfig) {
        val started = System.currentTimeMillis()
        val kmsg = File(guestDir(vm.id), "rootless/rootfs/dev/kmsg")
        var shown = 0
        while (scope.isActive && state.value is VmState.Booting) {
            delay(2000)
            val lines = BootLogs.tail(kmsg, 400)
            val zygoteStarts = lines.count { "starting service 'zygote'" in it }
            val fresh = lines.takeLast(6).filter { it.isNotBlank() }
            if (fresh.isNotEmpty() && lines.size != shown) {
                shown = lines.size
                val elapsed = (System.currentTimeMillis() - started) / 1000f
                fresh.takeLast(2).forEach { line(it.substringAfter("> ").take(160), (0.5f + elapsed / 600f).coerceAtMost(0.95f)) }
            }
            if (zygoteStarts >= 8) return fail("The guest keeps restarting (zygote crashed $zygoteStarts times)")
            if (System.currentTimeMillis() - started > 15 * 60_000) return fail("The guest did not finish booting in 15 minutes")
        }
    }

    private suspend fun fail(message: String) {
        if (state.value is VmState.Failed) return
        val v = vm
        line("[nestbox] $message", 1f)
        var dir: String? = null
        if (v != null && settings.saveBootLogs) {
            line("[nestbox] saving logs…", 1f)
            dir = collectLogs(v, message)
            line(if (dir != null) "[nestbox] logs saved to $dir" else "[nestbox] could not save logs", 1f)
        }
        state.value = VmState.Failed(message, dir, synchronized(log) { log.toList() })
        runCatching { helper?.request("kill", v?.id.orEmpty(), timeoutMs = 10_000) }
        closeDisplay()
    }

    private suspend fun collectLogs(v: VmConfig, message: String): String? {
        val h = helper
        val guest = guestDir(v.id)
        val texts = LinkedHashMap<String, String>()
        texts["summary.txt"] = buildString {
            appendLine("Nestbox ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
            appendLine("Machine: ${v.name} (${v.id}) · Modern · ${v.romId} · ${v.preset} · ${v.res}")
            appendLine("Phone: ${host.deviceName} · ${host.osVersion} · ${host.socName} · ${host.gpu}")
            appendLine("GPU acceleration: ${settings.gpuAcceleration}")
            appendLine("Failure: $message")
        }
        if (h != null) {
            runCatching { h.request("diag", v.id, timeoutMs = 15_000) }.getOrNull()?.let { texts["diag.txt"] = it.payload }
            // The guest's own logcat explains crashes; it works as long as the guest's logd is up.
            runCatching { h.request("exec", v.id, "15000", "logcat", "-d", "-b", "all", timeoutMs = 20_000) }.getOrNull()
                ?.let { texts["guest-logcat.txt"] = it.payload }
            texts["engine-stderr.txt"] = h.stderrTail()
        }
        texts["nestbox-boot.txt"] = synchronized(log) { log.joinToString("\n") }
        return BootLogs.save(
            context, v.name, texts,
            mapOf(
                "engine-logs" to File(guest, "rootless/logs"),
                "kmsg.txt" to File(guest, "rootless/rootfs/dev/kmsg"),
                "tombstones" to File(guest, "data/tombstones"),
                "anr" to File(guest, "data/anr"),
            ),
        )
    }

    private fun openDisplay() {
        val v = vm ?: return
        val s = surface ?: return
        if (!VineRuntime.isLoaded) return
        val fb = File(guestDir(v.id), "shared/vinefb")
        if (!fb.exists()) return
        if (display == 0L) {
            display = VineRuntime.openDisplay(fb.path)
            if (display == 0L) return
            VineRuntime.setDisplayFrameRate(display, Catalog.preset(v.preset).fps)
        }
        VineRuntime.attachSurface(display, s)
        VineRuntime.startRendering(display)
    }

    private fun closeDisplay() {
        if (display != 0L) {
            runCatching { VineRuntime.stopRendering(display) }
            runCatching { VineRuntime.closeDisplay(display) }
            display = 0L
        }
    }

    override fun attachSurface(vmId: String, surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        viewW = width
        viewH = height
        if (surface == null) {
            if (display != 0L) runCatching { VineRuntime.detachSurface(display) }
        } else openDisplay()
    }

    override fun sendInput(vmId: String, ev: MotionEvent) {
        val h = helper ?: return
        if (viewW <= 0 || viewH <= 0) return
        val masked = ev.actionMasked
        val sb = StringBuilder("touch ").append(masked).append(' ').append(ev.actionIndex).append(' ').append(ev.pointerCount)
        for (i in 0 until ev.pointerCount) {
            sb.append(' ').append(ev.getPointerId(i)).append(' ')
            sb.append(String.format(Locale.ROOT, "%.4f %.4f", (ev.getX(i) / viewW).coerceIn(0f, 1f), (ev.getY(i) / viewH).coerceIn(0f, 1f)))
        }
        h.send("agent_send", vmId, sb.toString())
    }

    override fun sendKey(vmId: String, keycode: Int) {
        val h = helper ?: return
        h.send("agent_send", vmId, "key $keycode 1")
        h.send("agent_send", vmId, "key $keycode 0")
    }

    override suspend fun stop(vmId: String) {
        watchdog?.cancel()
        closeDisplay()
        helper?.let {
            runCatching { it.request("stop", vmId, timeoutMs = 30_000) }
            runCatching { it.request("quit", timeoutMs = 8_000) }
            it.destroy()
        }
        helper = null
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

    override suspend fun installApk(vmId: String, apk: File): Result<Unit> = Result.failure(IOException("Phase 5"))

    override suspend fun reconnect(vm: VmConfig) = state.value is VmState.Running

    private fun nativeLib(name: String) = File(context.applicationInfo.nativeLibraryDir, name).path

    companion object {
        val KEYS = intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)
    }
}
