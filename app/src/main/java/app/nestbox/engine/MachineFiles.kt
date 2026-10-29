package app.nestbox.engine

import android.content.Context
import android.system.Os
import java.io.File

/** Erases a machine's files: config, guest system, data and snapshots. */
object MachineFiles {
    fun dir(context: Context, id: String) = File(context.filesDir, "vms/$id")

    fun delete(context: Context, id: String) {
        val d = dir(context, id)
        // Left by the removed Classic mode: Twoyi's fixed rootfs path may hold this machine's system.
        val fixed = File("/data/data/${context.packageName}/rootfs")
        if (isLink(fixed)) {
            runCatching { if (Os.readlink(fixed.path).startsWith(d.path)) Os.remove(fixed.path) }
        } else if (runCatching { File(fixed, ".nestbox-machine").readText().trim() }.getOrNull() == id) {
            deleteTree(fixed)
        }
        context.getSharedPreferences("slots", Context.MODE_PRIVATE).edit().remove(id).apply()
        File(context.filesDir, "roms").listFiles { f -> f.name.startsWith("import-$id") }?.forEach { it.delete() }
        deleteTree(d)
    }

    /** Like deleteRecursively, but makes read-only guest folders writable first and never follows links. */
    fun deleteTree(root: File) {
        if (!root.exists() && !isLink(root)) return
        val stack = ArrayDeque(listOf(root))
        val dirs = ArrayList<File>()
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (isLink(f) || !f.isDirectory) {
                runCatching { Os.remove(f.path) }
                continue
            }
            runCatching { Os.chmod(f.path, "700".toInt(8)) }
            dirs += f
            f.listFiles()?.forEach { stack += it }
        }
        dirs.asReversed().forEach { runCatching { Os.remove(it.path) } }
    }

    private fun isLink(f: File) = runCatching {
        android.system.OsConstants.S_ISLNK(Os.lstat(f.path).st_mode)
    }.getOrDefault(false)
}
