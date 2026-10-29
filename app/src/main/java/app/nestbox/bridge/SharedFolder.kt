package app.nestbox.bridge

import android.os.Environment
import java.io.File

/**
 * The folder shared between the phone and a machine: /sdcard/Nestbox on the phone, /sdcard/Host in
 * the guest, mounted live into the machine.
 * Boot logs (/sdcard/Nestbox/logs) stay on the phone.
 */
object SharedFolder {
    const val GUEST_DIR = "/sdcard/Host"
    private const val LOGS = "logs"

    @Suppress("DEPRECATION") // targetSdk 28: legacy storage, the real path works
    fun hostDir(): File = File(Environment.getExternalStorageDirectory(), "Nestbox")

    data class Entry(val path: String, val size: Long, val mtimeSec: Long)

    /** Files under the phone folder, relative paths, logs left out. */
    fun hostFiles(): List<Entry> {
        val root = hostDir()
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || !(it.parentFile == root && it.name == LOGS) }
            .filter { it.isFile }
            .map { Entry(it.relativeTo(root).path.replace('\\', '/'), it.length(), it.lastModified() / 1000) }
            .toList()
    }
}
