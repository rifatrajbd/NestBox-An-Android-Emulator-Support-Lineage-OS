package app.nestbox.engine.rom

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android sparse image (simg) to raw ext4, streaming. GSIs such as Pixel Experience ship sparse
 * images; Wayondroid's extractor reads raw ext4. "Don't care" chunks become holes in the output.
 */
object SparseImage {
    private const val MAGIC = 0xED26FF3A.toInt()
    private const val RAW = 0xCAC1
    private const val FILL = 0xCAC2
    private const val DONT_CARE = 0xCAC3
    private const val CRC32 = 0xCAC4

    /** True when [head] (at least 4 bytes) starts a sparse image. */
    fun isSparse(head: ByteArray) = head.size >= 4 && le32(head, 0) == MAGIC

    /**
     * Writes the raw image of the sparse stream [input] to [out]. [first] holds bytes already read
     * from the stream's start. [progress] gets bytes written so far and the raw size.
     */
    fun unsparse(first: ByteArray, input: InputStream, out: File, progress: (Long, Long) -> Unit) {
        val stream = DataInputStream(java.io.SequenceInputStream(first.inputStream(), input))
        val hdr = ByteArray(28)
        stream.readFully(hdr)
        if (le32(hdr, 0) != MAGIC) throw IOException("Not a sparse image")
        val fileHdr = le16(hdr, 8)
        val chunkHdr = le16(hdr, 10)
        val blockSize = le32(hdr, 12).toLong()
        val totalBlocks = le32(hdr, 16).toLong() and 0xFFFFFFFFL
        val chunks = le32(hdr, 20)
        skip(stream, (fileHdr - 28).toLong())
        val rawSize = blockSize * totalBlocks
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val buf = ByteArray(1 shl 20)
            val ch = ByteArray(12)
            var pos = 0L
            var lastReport = 0L
            repeat(chunks) {
                stream.readFully(ch)
                val type = le16(ch, 0)
                val blocks = le32(ch, 4).toLong() and 0xFFFFFFFFL
                val total = le32(ch, 8).toLong() and 0xFFFFFFFFL
                skip(stream, (chunkHdr - 12).toLong())
                val bytes = blocks * blockSize
                when (type) {
                    RAW -> {
                        raf.seek(pos)
                        var left = bytes
                        while (left > 0) {
                            val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw EOFException("Sparse image ended early")
                            raf.write(buf, 0, n)
                            left -= n
                        }
                    }
                    FILL -> {
                        val v = ByteArray(4)
                        stream.readFully(v)
                        if (v.any { it != 0.toByte() }) {
                            val pattern = ByteArray(buf.size) { v[it % 4] }
                            raf.seek(pos)
                            var left = bytes
                            while (left > 0) {
                                val n = minOf(pattern.size.toLong(), left).toInt()
                                raf.write(pattern, 0, n)
                                left -= n
                            }
                        } // zero fill stays a hole
                    }
                    DONT_CARE -> Unit
                    CRC32 -> skip(stream, total - chunkHdr)
                    else -> throw IOException("Unknown sparse chunk 0x${type.toString(16)}")
                }
                pos += if (type == CRC32) 0 else bytes
                if (pos - lastReport > 64_000_000) {
                    lastReport = pos
                    progress(pos, rawSize)
                }
            }
            raf.setLength(rawSize)
        }
        progress(rawSize, rawSize)
    }

    private fun skip(s: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) { if (s.read() < 0) throw EOFException(); left-- } else left -= k
        }
    }

    private fun le32(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun le16(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
}
