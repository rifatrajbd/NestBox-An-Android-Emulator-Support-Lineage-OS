package app.nestbox.engine

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves everything needed to diagnose a failed boot to /sdcard/Nestbox/logs/<machine>-<time>/,
 * so no adb is needed. Falls back to Android/data/app.nbox/files/logs without storage permission.
 */
object BootLogs {
    fun save(context: Context, machine: String, texts: Map<String, String>, files: Map<String, File>): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = machine.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + stamp
        val roots = listOfNotNull(
            File(Environment.getExternalStorageDirectory(), "Nestbox/logs"),
            context.getExternalFilesDir("logs"),
        )
        for (root in roots) {
            val dir = File(root, name)
            try {
                if (!dir.mkdirs() && !dir.isDirectory) continue
                texts.forEach { (n, t) -> File(dir, n).writeText(t) }
                files.forEach { (n, f) -> copyAny(f, File(dir, n)) }
                return dir.path
            } catch (e: Exception) {
                Log.w("NestboxLogs", "could not save logs to $dir", e)
            }
        }
        return null
    }

    private fun copyAny(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { copyAny(it, File(dest, it.name)) }
        } else if (src.isFile) {
            // Logs can be huge; the last 2 MB is what matters.
            val len = src.length()
            src.inputStream().use { i ->
                if (len > MAX) i.skip(len - MAX)
                dest.outputStream().use { i.copyTo(it) }
            }
        }
    }

    fun tail(f: File, lines: Int): List<String> = runCatching {
        val len = f.length()
        f.inputStream().use { i ->
            if (len > 64_000) i.skip(len - 64_000)
            i.bufferedReader().readLines().takeLast(lines)
        }
    }.getOrDefault(emptyList())

    private const val MAX = 2L * 1024 * 1024
}
