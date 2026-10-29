package app.nestbox.engine.modern

import android.system.Os
import app.nestbox.bridge.SharedFolder
import app.nestbox.bridge.Spoof
import app.nestbox.vm.VmConfig
import java.io.File

/**
 * Nestbox's own system properties for a Modern (Android 13) guest, kept in a marked block at the
 * end of the guest's /product/etc/build.prop. The product partition loads last and Wayondroid's
 * prep step does not rewrite it, so the block survives boots and wins over earlier values.
 */
object GuestProps {
    private const val BEGIN = "# nestbox-begin"
    private const val END = "# nestbox-end"

    /** Android Go mode plus smaller app heaps and lighter app compilation. */
    private val LOW_MEMORY = listOf(
        "ro.config.low_ram=true",
        "dalvik.vm.heapstartsize=4m",
        "dalvik.vm.heapgrowthlimit=128m",
        "dalvik.vm.heapsize=256m",
        "dalvik.vm.heapminfree=512k",
        "dalvik.vm.heapmaxfree=8m",
        "dalvik.vm.dex2oat-threads=2",
        "dalvik.vm.boot-dex2oat-threads=2",
        "dalvik.vm.dex2oat-Xmx=256m",
        "ro.config.max_starting_bg=2",
    )

    /** Run in the guest once it has booted (ActivityManager reads these live). */
    val RUNTIME_COMMANDS = listOf(
        listOf("device_config", "put", "activity_manager", "max_cached_processes", "8"),
        listOf("device_config", "put", "activity_manager", "max_phantom_processes", "2147483647"),
    )

    fun release(sdk: Int) = when (sdk) { 29 -> "10"; 30 -> "11"; 31, 32 -> "12"; else -> "13" }

    /**
     * The machine's overlay, applied before every boot (the first one included) by NestBox's build
     * of the rootless helper (scripts/patch_helper.py):
     * - nestbox.prop: system properties (low memory mode, the ROM's tuning, spoofing)
     * - nestbox.remove: guest paths to delete (bloat apps; su when root is off)
     * - nestbox.files/: files copied into the guest with their modes (su, nb-runas)
     * - nestbox.shared: the phone folder mounted at the guest's /sdcard/Host
     */
    fun writeOverlay(
        guest: File, rom: app.nestbox.vm.Catalog.Rom, lowMemory: Boolean, vm: VmConfig, libDir: String,
        assets: android.content.res.AssetManager,
    ) {
        guest.mkdirs()
        val q = rom.compat == "q"
        val props = (if (lowMemory) LOW_MEMORY else emptyList()) + rom.props + Spoof.bootProps(vm.spoof)
        File(guest, "nestbox.prop").writeText(props.joinToString(NL, postfix = NL))
        val remove = rom.remove + (if (vm.root) emptyList() else listOf(SU)) + (if (q) emptyList() else listOf("/$Q_MARKER"))
        File(guest, "nestbox.remove").writeText(remove.joinToString(NL, postfix = NL))

        val files = File(guest, "nestbox.files")
        files.deleteRecursively()
        // Android 10 GSIs: Waydroid's Android 10 init (container patches: no fatal mmap entropy or
        // SELinux process-label checks), and the marker that turns on tawcroot's SELinux shims.
        val compatMarker = File(guest, "nestbox.compat-q")
        if (q) {
            val init = File(files, "system/bin/init")
            init.parentFile?.mkdirs()
            assets.open("guest/android10/init").use { i -> init.outputStream().use { i.copyTo(it) } }
            Os.chmod(init.path, 0b111_101_101) // 0755
            File(files, Q_MARKER).writeText("1\n")
            compatMarker.writeText("1\n")
        } else compatMarker.delete()
        val nbsu = File(libDir, "libnbsu.so")
        if (nbsu.isFile) {
            // nb-runas lets NestBox's own root commands run as another user (location as shell).
            install(nbsu, File(files, "system/bin/nb-runas"), 0b111_101_101)          // 0755
            // tawcroot honours the setuid bit: su makes its caller root inside the guest only.
            if (vm.root) install(nbsu, File(files, SU.removePrefix("/")), 0b100_111_101_101) // 04755
        }

        val shared = File(guest, "nestbox.shared")
        if (vm.shared) {
            val host = SharedFolder.hostDir()
            if (host.isDirectory || host.mkdirs()) shared.writeText(host.absolutePath + NL) else shared.delete()
        } else shared.delete()
    }

    private const val SU = "/system/xbin/su"
    /** In the guest's root: tawcroot reads it to enable the Android 10 SELinux shims. */
    private const val Q_MARKER = ".nestbox-selinux-compat"
    private const val NL = "\n"

    private fun install(src: File, dst: File, mode: Int) {
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        Os.chmod(dst.path, mode)
    }

    /** Writes or removes the block. Returns false when the rootfs is not extracted yet. */
    fun apply(rootfs: File, lowMemory: Boolean): Boolean {
        if (!File(rootfs, "system/bin/init").exists()) return false
        val prop = resolve(rootfs, "/product/etc/build.prop") ?: return false
        val old = if (prop.isFile) prop.readText() else ""
        val kept = old.replace(Regex("(?s)\\n?$BEGIN.*?$END\\n?"), "\n").trimEnd()
        val block = if (lowMemory) "\n$BEGIN\n" + LOW_MEMORY.joinToString("\n") + "\n$END\n" else "\n"
        val text = kept + block
        if (text != old) {
            prop.parentFile?.mkdirs()
            prop.writeText(text)
        }
        return true
    }

    /** Resolves a guest path inside [rootfs], following guest symlinks (e.g. /product → /system/product). */
    private fun resolve(rootfs: File, guestPath: String, depth: Int = 0): File? {
        if (depth > 8) return null
        var cur = rootfs
        val parts = guestPath.trim('/').split('/')
        for ((i, part) in parts.withIndex()) {
            val next = File(cur, part)
            val target = runCatching { Os.readlink(next.path) }.getOrNull()
            if (target != null) {
                val rest = parts.drop(i + 1).joinToString("/")
                val guestTarget = if (target.startsWith("/")) target
                else "/" + cur.relativeTo(rootfs).path.replace('\\', '/') + "/" + target
                return resolve(rootfs, "$guestTarget/$rest".replace("//", "/"), depth + 1)
            }
            cur = next
        }
        return cur
    }
}
