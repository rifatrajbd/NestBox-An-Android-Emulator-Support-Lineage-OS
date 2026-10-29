package app.nestbox.engine.rom

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals

/**
 * Expands a real GSI (.img.xz, sparse) the way the phone does and compares the result with a
 * known-good raw image hash. Skipped unless NESTBOX_TEST_XZ and NESTBOX_TEST_SHA are set.
 */
class SparseImageTest {
    @Test
    fun expandsGsi() {
        val xz = System.getenv("NESTBOX_TEST_XZ")?.let(::File)
        val want = System.getenv("NESTBOX_TEST_SHA")
        assumeTrue(xz != null && xz.isFile && want != null)
        val out = File.createTempFile("gsi", ".img")
        try {
            org.tukaani.xz.XZInputStream(xz!!.inputStream().buffered(1 shl 20)).use { input ->
                val head = ByteArray(28)
                var got = 0
                while (got < head.size) { val n = input.read(head, got, head.size - got); if (n < 0) break; got += n }
                check(SparseImage.isSparse(head))
                SparseImage.unsparse(head, input, out) { _, _ -> }
            }
            val md = MessageDigest.getInstance("SHA-256")
            out.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            assertEquals(want, md.digest().joinToString("") { "%02x".format(it) })
        } finally {
            out.delete()
        }
    }
}
