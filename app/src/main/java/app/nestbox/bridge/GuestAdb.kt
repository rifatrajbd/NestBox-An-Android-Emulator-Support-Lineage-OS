package app.nestbox.bridge

import android.content.Context
import android.util.Base64
import app.nestbox.adb.NestboxAdb
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.interfaces.RSAPublicKey

/**
 * The bridge to a Classic (Twoyi) guest: its adbd listens on 127.0.0.1:22122. NestBox's ADB key
 * is written into the guest's adb_keys before boot, so no "Allow USB debugging?" prompt appears.
 * Used for app installs, spoof changes and the shared folder.
 */
class GuestAdb(private val context: Context) {
    private var adb: NestboxAdb? = null

    /** adb_keys line for NestBox's key. */
    fun publicKeyLine(): String {
        val key = NestboxAdb.get(context).publicKey() as RSAPublicKey
        return Base64.encodeToString(encodePubkey(key), Base64.NO_WRAP) + " nestbox@nestbox\n"
    }

    /**
     * adbd's RSA public key format (system/core/libcrypto_utils android_pubkey.c), all little-endian:
     * u32 modulus size in words, u32 n0inv = -1/n mod 2^32, modulus, R^2 mod n (R = 2^bits), u32 e.
     */
    private fun encodePubkey(key: RSAPublicKey): ByteArray {
        val words = 64
        val n = key.modulus
        val bytes = words * 4
        val r32 = java.math.BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong()
        val rr = java.math.BigInteger.ONE.shiftLeft(bytes * 8 * 2).mod(n)
        val buf = java.nio.ByteBuffer.allocate(4 + 4 + bytes + bytes + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        buf.put(littleEndian(n, bytes))
        buf.put(littleEndian(rr, bytes))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }

    private fun littleEndian(v: java.math.BigInteger, size: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until minOf(size, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    /** Connects, waiting up to [waitMs] for the guest's adbd to come up. */
    suspend fun connect(waitMs: Long = 60_000): NestboxAdb {
        adb?.takeIf { it.isConnected }?.let { return it }
        val until = System.currentTimeMillis() + waitMs
        var last: Exception? = null
        while (System.currentTimeMillis() < until) {
            try {
                val a = NestboxAdb.newConnection(context).apply {
                    setApi(27)
                    setThrowOnUnauthorised(true)
                }
                if (a.connect(HOST, PORT)) {
                    adb = a
                    return a
                }
            } catch (e: Exception) {
                last = e
            }
            delay(2_000)
        }
        throw IOException("Could not reach the machine's adb" + (last?.message?.let { ": $it" } ?: ""))
    }

    fun close() {
        runCatching { adb?.close() }
        adb = null
    }

    /** Runs [cmd] through the guest's shell and returns its output. [input] is fed to stdin. */
    suspend fun exec(cmd: String, input: File? = null): String {
        val s = connect().openStream("exec:$cmd")
        return s.use {
            if (input != null) {
                input.inputStream().use { i ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        s.write(buf, 0, n)
                    }
                }
                s.flush()
            }
            readAll(s.openInputStream())
        }
    }

    /** Shell command line, as root when the guest allows it (Twoyi's su), else as the shell user. */
    suspend fun shRoot(cmd: String): String {
        val rooted = exec("su -c " + Spoof.quote(cmd))
        return if ("not found" in rooted || "denied" in rooted) exec("sh -c " + Spoof.quote(cmd)) else rooted
    }

    // ---- sync protocol (adb push / pull / ls)

    data class Dent(val name: String, val mode: Int, val size: Long, val mtimeSec: Long) {
        val isDir get() = mode and 0xF000 == 0x4000
        val isFile get() = mode and 0xF000 == 0x8000
    }

    suspend fun list(dir: String): List<Dent> = sync { s, input ->
        request(s, "LIST", dir.toByteArray())
        val out = ArrayList<Dent>()
        while (true) {
            val h = readFully(input, 20)
            val id = String(h, 0, 4, Charsets.US_ASCII)
            if (id == "DONE") break
            if (id != "DENT") throw IOException("adb sync: unexpected $id")
            val name = String(readFully(input, le32(h, 16)))
            if (name != "." && name != "..") out += Dent(name, le32(h, 4), le32(h, 8).toLong() and 0xFFFFFFFFL, le32(h, 12).toLong() and 0xFFFFFFFFL)
        }
        out
    }

    suspend fun push(local: File, remote: String, mtimeSec: Long) = sync { s, input ->
        request(s, "SEND", "$remote,${0b110_110_000}".toByteArray()) // 0660
        local.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                s.write(header("DATA", n), 0, 8)
                s.write(buf, 0, n)
            }
        }
        s.write(header("DONE", mtimeSec.toInt()), 0, 8)
        s.flush()
        val h = readFully(input, 8)
        val id = String(h, 0, 4, Charsets.US_ASCII)
        if (id != "OKAY") throw IOException("adb push failed: " + String(readFully(input, le32(h, 4))))
    }

    suspend fun pull(remote: String, local: File) = sync { s, input ->
        request(s, "RECV", remote.toByteArray())
        val part = File(local.path + ".part")
        local.parentFile?.mkdirs()
        part.outputStream().use { os ->
            while (true) {
                val h = readFully(input, 8)
                val id = String(h, 0, 4, Charsets.US_ASCII)
                val len = le32(h, 4)
                when (id) {
                    "DATA" -> os.write(readFully(input, len))
                    "DONE" -> break
                    "FAIL" -> throw IOException("adb pull failed: " + String(readFully(input, len)))
                    else -> throw IOException("adb sync: unexpected $id")
                }
            }
        }
        if (!part.renameTo(local)) throw IOException("Could not save $local")
    }

    private suspend fun <T> sync(block: (AdbStream, InputStream) -> T): T {
        val s = connect().openStream("sync:")
        return s.use {
            val r = block(s, s.openInputStream())
            runCatching { request(s, "QUIT", ByteArray(0)) }
            r
        }
    }

    private fun request(s: AdbStream, id: String, data: ByteArray) {
        s.write(header(id, data.size), 0, 8)
        if (data.isNotEmpty()) s.write(data, 0, data.size)
        s.flush()
    }

    private fun header(id: String, n: Int) = id.toByteArray(Charsets.US_ASCII) +
        byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    private fun le32(b: ByteArray, at: Int): Int {
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
        return v
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw IOException("The machine closed the connection")
            off += r
        }
        return b
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: IOException) {
            // "Stream closed": the command finished
        }
        return out.toString("UTF-8")
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 22122
    }
}
