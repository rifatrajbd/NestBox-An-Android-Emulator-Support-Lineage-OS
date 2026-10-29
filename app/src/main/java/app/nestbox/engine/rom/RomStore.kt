package app.nestbox.engine.rom

import android.content.Context
import android.net.Uri
import app.nestbox.vm.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloaded system images, shared by every machine that uses them: filesDir/roms/<romId>.
 * Runs in the machine's process, where the foreground service keeps long downloads alive.
 */
class RomStore(private val context: Context) {
    private val dir = File(context.filesDir, "roms").apply { mkdirs() }

    fun fileFor(rom: Catalog.Rom, vmId: String): File = when (rom.source) {
        // Imports belong to one machine.
        Catalog.Source.Import -> File(dir, "import-$vmId.vrom")
        else -> File(dir, rom.id + ".vrom")
    }

    /** Makes sure the ROM file is on disk and returns it. [progress] gets 0..1 and a status line. */
    suspend fun ensure(rom: Catalog.Rom, vmId: String, importUri: String?, progress: (Float, String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val out = fileFor(rom, vmId)
            if (out.isFile && out.length() > 0) return@withContext out
            when (val src = rom.source) {
                is Catalog.Source.File -> {
                    // Copied to the phone by USB: read it where it is instead of downloading
                    findLocal(src.bytes, src.sha256, progress)?.let { return@withContext it }
                    download(src, out, progress)
                }
                Catalog.Source.Import -> copyImport(importUri ?: throw IOException("No ROM file was chosen"), out, progress)
            }
            out
        }

    /**
     * A download the user already copied to the phone (by USB): any file of the exact size in
     * /sdcard/Nestbox/roms or /sdcard/Download whose SHA-256 matches. Read in place, not copied.
     */
    private fun findLocal(bytes: Long, sha256: String, progress: (Float, String) -> Unit): File? {
        @Suppress("DEPRECATION")
        val sd = android.os.Environment.getExternalStorageDirectory()
        val dirs = listOf(File(sd, "Nestbox/roms"), File(sd, "Download"), File(sd, "Nestbox"))
        for (d in dirs) {
            val files = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (!f.isFile || f.length() != bytes) continue
                progress(0f, "Checking ${f.name}")
                if (runCatching { sha256(f) }.getOrNull().equals(sha256, ignoreCase = true)) return f
            }
        }
        return null
    }

    fun delete(rom: Catalog.Rom, vmId: String) {
        if (rom.source == Catalog.Source.Import) fileFor(rom, vmId).delete()
    }

    private suspend fun download(src: Catalog.Source.File, out: File, progress: (Float, String) -> Unit) {
        val part = File(out.path + ".part")
        val conn = open(src.url, part.length())
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed) part.delete()
        val total = src.bytes
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, resumed).use { os ->
                copy(input, os, part.length(), total, "Downloading system", progress)
            }
        }
        progress(1f, "Checking download")
        val sha = sha256(part)
        if (!sha.equals(src.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("The download is damaged (checksum mismatch). Start the machine again to retry.")
        }
        if (!part.renameTo(out)) throw IOException("Could not save the system file")
    }

    private suspend fun copyImport(uri: String, out: File, progress: (Float, String) -> Unit) {
        val part = File(out.path + ".part")
        val cr = context.contentResolver
        val size = runCatching { cr.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize } }.getOrNull() ?: -1L
        (cr.openInputStream(Uri.parse(uri)) ?: throw IOException("The chosen file can no longer be read")).use { input ->
            part.outputStream().use { os -> copy(input, os, 0, size, "Copying ROM file", progress) }
        }
        if (!part.renameTo(out)) throw IOException("Could not save the ROM file")
    }

    private suspend fun copy(input: java.io.InputStream, os: java.io.OutputStream, start: Long, total: Long, label: String, progress: (Float, String) -> Unit) {
        val buf = ByteArray(1 shl 16)
        var done = start
        var last = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            os.write(buf, 0, n)
            done += n
            if (done - last > 4_000_000) {
                last = done
                coroutineContext.ensureActive()
                val f = if (total > 0) (done.toFloat() / total).coerceAtMost(0.99f) else 0f
                progress(f, if (total > 0) "$label · ${mb(done)} / ${mb(total)}" else "$label · ${mb(done)}")
            }
        }
    }

    /** The original host first, then NestBox's GitHub copy (roms release) if there is one. */
    private fun open(url: String, from: Long): HttpURLConnection = try {
        connect(url, from)
    } catch (e: IOException) {
        val mirror = Catalog.mirrorOf(url) ?: throw e
        try {
            connect(mirror, from)
        } catch (_: IOException) {
            throw e
        }
    }

    private fun connect(url: String, from: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        // SourceForge serves an HTML page instead of the file to browser-like clients.
        c.setRequestProperty("User-Agent", "curl/8.5.0")
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        val code = c.responseCode
        if (code !in 200..299) throw IOException("Download failed (HTTP $code)")
        return c
    }

    companion object {
        fun mb(b: Long) = if (b >= 1_000_000_000) String.format(java.util.Locale.US, "%.2f GB", b / 1e9) else "${b / 1_000_000} MB"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
