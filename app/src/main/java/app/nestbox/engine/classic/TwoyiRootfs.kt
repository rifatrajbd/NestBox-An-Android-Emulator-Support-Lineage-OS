package app.nestbox.engine.classic

import android.system.Os
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException

/**
 * Unpacks Twoyi's rootfs.7z into a machine's folder. Twoyi's ROM has "/data/data/io.twoyi" built
 * into 9 binaries (init, linker64, libc, adbd, su…); every occurrence is rewritten to
 * "/data/data/app.nbox", which is the same length, so the binaries stay valid.
 */
object TwoyiRootfs {
    private val OLD = "/data/data/io.twoyi".toByteArray()
    private val NEW = "/data/data/app.nbox".toByteArray()
    private const val PREFIX = "rootfs/"
    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000
    private const val S_IFDIR = 0x4000
    private const val UNIX_EXTENSION = 0x8000

    const val MARKER = ".nestbox-extracted"
    private const val PATCH_LIMIT = 16L * 1024 * 1024

    /** Extracts [archive] into [dest] (which becomes the guest's "/"). Returns the number of patched files. */
    fun extract(archive: File, dest: File, progress: (Float, String) -> Unit): Int {
        if (File(dest, MARKER).isFile) return 0
        dest.mkdirs()
        var patched = 0
        val links = ArrayList<Pair<File, String>>()
        val modes = ArrayList<Pair<File, Int>>()
        SevenZFile.builder().setFile(archive).get().use { z ->
            val entries = z.entries.toList()
            val total = entries.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            var last = 0L
            var e = z.nextEntry
            while (e != null) {
                val name = e.name.replace('\\', '/').removePrefix(PREFIX).removePrefix("rootfs")
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(dest.canonicalPath)) throw IOException("Bad path in archive: ${e.name}")
                val attrs = if (e.hasWindowsAttributes) e.windowsAttributes else 0
                val mode = if (attrs and UNIX_EXTENSION != 0) (attrs ushr 16) and 0xFFFF else 0
                when {
                    name.isEmpty() -> Unit
                    e.isDirectory || mode and S_IFMT == S_IFDIR -> {
                        out.mkdirs()
                        if (mode != 0) modes += out to (mode and 0xFFF)
                    }
                    // Sequential reads only (z.read): getInputStream(entry) is random access and
                    // re-decompresses the solid block from its start for every file, which made
                    // the unpack crawl to a stop around 20%.
                    mode and S_IFMT == S_IFLNK -> links += out to String(readEntry(z, e.size))
                    else -> {
                        out.parentFile?.mkdirs()
                        if (e.size > PATCH_LIMIT) {
                            // Big APKs never hold the path; stream them to keep memory low.
                            out.outputStream().use { os ->
                                val buf = ByteArray(1 shl 16)
                                var left = e.size
                                while (left > 0) {
                                    val n = z.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                    if (n < 0) throw IOException("Archive ended early in ${e.name}")
                                    os.write(buf, 0, n)
                                    left -= n
                                }
                            }
                        } else {
                            val bytes = readEntry(z, e.size)
                            val fixed = replaceAll(bytes)
                            if (fixed !== bytes) patched++
                            out.writeBytes(fixed)
                        }
                        if (mode != 0) modes += out to (mode and 0xFFF)
                    }
                }
                done += e.size
                if (done - last > 8_000_000) {
                    last = done
                    progress(done.toFloat() / total, "Unpacking system · ${done / 1_000_000} / ${total / 1_000_000} MB")
                }
                e = z.nextEntry
            }
        }
        for ((f, target) in links) {
            runCatching { f.delete() }
            f.parentFile?.mkdirs()
            Os.symlink(target, f.path)
        }
        // Directories last, so read-only ones do not block writing their contents.
        for ((f, m) in modes.sortedByDescending { it.first.path.length }) runCatching { Os.chmod(f.path, m) }
        File(dest, MARKER).writeText("patched=$patched\n")
        return patched
    }

    private fun readEntry(z: SevenZFile, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        var off = 0
        while (off < out.size) {
            val n = z.read(out, off, out.size - off)
            if (n < 0) throw IOException("Archive ended early")
            off += n
        }
        return out
    }

    /** Returns [data] itself when it holds no Twoyi path, else a patched copy. */
    private fun replaceAll(data: ByteArray): ByteArray {
        var i = indexOf(data, 0)
        if (i < 0) return data
        val copy = data.copyOf()
        while (i >= 0) {
            NEW.copyInto(copy, i)
            i = indexOf(data, i + OLD.size)
        }
        return copy
    }

    private fun indexOf(data: ByteArray, from: Int): Int {
        val first = OLD[0]
        var i = from
        val end = data.size - OLD.size
        outer@ while (i <= end) {
            if (data[i] != first) { i++; continue }
            for (j in 1 until OLD.size) if (data[i + j] != OLD[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}
