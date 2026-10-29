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
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloaded system images, shared by every machine that uses them: filesDir/roms/<romId>.
 * Runs in the machine's process, where the foreground service keeps long downloads alive.
 */
class RomStore(private val context: Context) {
    private val dir = File(context.filesDir, "roms").apply { mkdirs() }

    fun fileFor(rom: Catalog.Rom, vmId: String): File = when (rom.source) {
        // Imports belong to one machine.
        Catalog.Source.Import -> File(dir, "import-$vmId" + if (rom.engine == Catalog.MODERN) ".vrom" else ".7z")
        else -> File(dir, rom.id + if (rom.engine == Catalog.MODERN) ".vrom" else ".7z")
    }

    /** Makes sure the ROM file is on disk and returns it. [progress] gets 0..1 and a status line. */
    suspend fun ensure(rom: Catalog.Rom, vmId: String, importUri: String?, progress: (Float, String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val out = fileFor(rom, vmId)
            if (out.isFile && out.length() > 0) return@withContext out
            when (val src = rom.source) {
                is Catalog.Source.File -> download(src, out, progress)
                is Catalog.Source.InZip -> downloadZipEntry(src, out, progress)
                Catalog.Source.Import -> copyImport(importUri ?: throw IOException("No ROM file was chosen"), out, progress)
                is Catalog.Source.Port -> throw IOException("Ported systems are assembled with ensurePort")
            }
            out
        }

    /**
     * Assembles a ported system in [guest]: system.img from the GSI's .img.xz (sparse images are
     * expanded to raw ext4) and vendor.img from Waydroid's vendor zip. Downloads are kept in
     * filesDir/roms for the next machine; the images are written straight into the machine.
     */
    suspend fun ensurePort(rom: Catalog.Rom, guest: File, progress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        val src = rom.source as Catalog.Source.Port
        guest.mkdirs()
        val systemImg = File(guest, "system.img")
        val vendorImg = File(guest, "vendor.img")
        if (!systemImg.isFile) {
            val xz = File(dir, "${rom.id}-system.img.xz")
            if (!xz.isFile) fetch(src.systemXzUrl, src.systemXzSha256, src.systemXzBytes, xz, "Downloading system") { p, t -> progress(p * 0.45f, t) }
            progress(0.45f, "Unpacking system")
            val part = File(guest, "system.img.part")
            org.tukaani.xz.XZInputStream(xz.inputStream().buffered(1 shl 20)).use { input ->
                val head = ByteArray(28)
                var got = 0
                while (got < head.size) {
                    val n = input.read(head, got, head.size - got)
                    if (n < 0) break
                    got += n
                }
                if (SparseImage.isSparse(head)) {
                    SparseImage.unsparse(head, input, part) { done, total ->
                        progress(0.45f + 0.35f * done / total, "Unpacking system · ${mb(done)} / ${mb(total)}")
                    }
                } else {
                    part.outputStream().buffered(1 shl 20).use { os ->
                        os.write(head, 0, got)
                        copy(input, os, got.toLong(), -1, "Unpacking system", progress)
                    }
                }
            }
            if (!part.renameTo(systemImg)) throw IOException("Could not save system.img")
        }
        if (!vendorImg.isFile) {
            val zip = File(dir, "${rom.id}-vendor.zip")
            if (!zip.isFile) fetch(src.vendorZipUrl, src.vendorZipSha256, src.vendorZipBytes, zip, "Downloading vendor") { p, t -> progress(0.8f + p * 0.15f, t) }
            progress(0.95f, "Unpacking vendor")
            java.util.zip.ZipFile(zip).use { z ->
                val e = z.getEntry(src.vendorEntry) ?: throw IOException("${src.vendorEntry} is missing from the vendor download")
                val part = File(guest, "vendor.img.part")
                z.getInputStream(e).use { i -> part.outputStream().use { i.copyTo(it, 1 shl 20) } }
                if (!part.renameTo(vendorImg)) throw IOException("Could not save vendor.img")
            }
        }
        File(guest, "manifest.json").writeText("{\"kind\":\"waydroid\",\"displayName\":\"${rom.name}\",\"apiLevel\":${rom.sdk}}\n")
        progress(1f, "System ready")
    }

    /** Resumable download of [url] to [out]; checks [sha256] when given. */
    private suspend fun fetch(url: String, sha256: String?, bytes: Long, out: File, label: String, progress: (Float, String) -> Unit) {
        val part = File(out.path + ".part")
        val conn = open(url, part.length())
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed) part.delete()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, resumed).use { os -> copy(input, os, part.length(), bytes, label, progress) }
        }
        if (bytes > 0 && part.length() != bytes) throw IOException("The download stopped early. Start the machine again to resume it.")
        if (sha256 != null && !sha256(part).equals(sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("The download is damaged (checksum mismatch). Start the machine again to retry.")
        }
        if (!part.renameTo(out)) throw IOException("Could not save $out")
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

    /** Streams a zip (Twoyi's APK) and keeps only [Catalog.Source.InZip.entry]. */
    private suspend fun downloadZipEntry(src: Catalog.Source.InZip, out: File, progress: (Float, String) -> Unit) {
        val part = File(out.path + ".part")
        val conn = open(src.url, 0)
        var read = 0L
        val counting = object : java.io.FilterInputStream(conn.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) read += it }
        }
        ZipInputStream(counting.buffered(1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: throw IOException("${src.entry} was not found in the download")
                if (e.name != src.entry) continue
                part.outputStream().use { os ->
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        if (read - last > 4_000_000) {
                            last = read
                            coroutineContext.ensureActive()
                            progress((read.toFloat() / src.bytes).coerceAtMost(0.99f), "Downloading system · ${mb(read)} / ${mb(src.bytes)}")
                        }
                    }
                }
                break
            }
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
