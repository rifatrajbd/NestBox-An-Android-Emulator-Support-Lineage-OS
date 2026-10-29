package app.nestbox.engine.modern

import android.system.Os
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
