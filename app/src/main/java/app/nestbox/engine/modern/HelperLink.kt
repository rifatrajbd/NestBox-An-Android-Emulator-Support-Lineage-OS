package app.nestbox.engine.modern

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class HelperReply(val ok: Boolean, val payload: String)
data class HelperEvent(val name: String, val fields: List<String>)

/**
 * Talks to Wayondroid's rootless engine (libvine_helper.so --rootless) over stdin/stdout:
 * one tab-separated line per message, replies matched by id, "*" lines are events.
 * Protocol from Wayondroid's HelperClient/HelperProtocol (GPL-3.0).
 */
class HelperLink(private val scope: CoroutineScope, private val command: List<String>) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<HelperReply>>()
    private val writes = Channel<String>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<HelperEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<HelperEvent> = _events
    private val stderr = ArrayDeque<String>()
    @Volatile var process: Process? = null
        private set

    /** Starts the helper and waits for its "ready" event. */
    suspend fun connect(timeoutMs: Long = 60_000) {
        if (process?.isAlive == true) return
        val proc = ProcessBuilder(command).start()
        process = proc
        val ready = CompletableDeferred<Unit>()
        scope.launch(Dispatchers.IO) {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val f = line.split('\t')
                        if (f.size >= 2 && f[0] == "*") {
                            val e = HelperEvent(f[1], f.drop(2).map(::unescape))
                            if (e.name == "ready") ready.complete(Unit) else _events.tryEmit(e)
                        } else if (f.size >= 3) {
                            pending.remove(f[0])?.complete(HelperReply(f[1] == "ok", unescape(f[2])))
                        }
                    }
                }
            } catch (_: IOException) {
            }
            val why = failureText(proc)
            ready.completeExceptionally(IOException(why))
            pending.values.forEach { it.complete(HelperReply(false, why)) }
            pending.clear()
            _events.tryEmit(HelperEvent("helper_exit", listOf(why)))
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                proc.errorStream.bufferedReader().useLines { l ->
                    l.forEach { synchronized(stderr) { stderr.addLast(it); while (stderr.size > 40) stderr.removeFirst() } }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            val out = proc.outputStream
            try {
                for (line in writes) {
                    out.write((line + "\n").toByteArray())
                    out.flush()
                }
            } catch (_: IOException) {
            }
        }
        withTimeoutOrNull(timeoutMs) { ready.await() } ?: throw IOException("The Modern engine did not start in time")
    }

    suspend fun request(cmd: String, vararg args: String, timeoutMs: Long = 20_000): HelperReply {
        val id = nextId.getAndIncrement().toString()
        val d = CompletableDeferred<HelperReply>()
        pending[id] = d
        writes.trySend(encode(id, cmd, args.toList()))
        return withTimeoutOrNull(timeoutMs) { d.await() }.also { pending.remove(id) }
            ?: HelperReply(false, "The engine did not answer ($cmd)")
    }

    /** Fire and forget (touch, keys). */
    fun send(cmd: String, vararg args: String) {
        writes.trySend(encode("-", cmd, args.toList()))
    }

    fun stderrTail(): String = synchronized(stderr) { stderr.joinToString("\n") }

    fun destroy() {
        process?.let { runCatching { it.destroy() } }
        process = null
    }

    private fun failureText(p: Process): String {
        val code = runCatching { p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS); if (p.isAlive) null else p.exitValue() }.getOrNull()
        val tail = synchronized(stderr) { stderr.takeLast(3).joinToString(" ").trim() }
        return when {
            tail.isNotEmpty() -> "The Modern engine stopped: $tail"
            code != null -> "The Modern engine stopped (exit code $code)"
            else -> "The Modern engine stopped"
        }
    }

    companion object {
        fun escape(t: String) = buildString {
            for (c in t) when (c) {
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); '\\' -> append("\\\\"); else -> append(c)
            }
        }

        fun unescape(t: String) = buildString {
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (c == '\\' && i + 1 < t.length) {
                    i++
                    append(when (val n = t[i]) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> n })
                } else append(c)
                i++
            }
        }

        fun encode(id: String, cmd: String, args: List<String>) = (listOf(id, cmd) + args.map(::escape)).joinToString("\t")

        fun keyValues(payload: String): Map<String, String> = payload.lineSequence().mapNotNull { l ->
            val eq = l.indexOf('=')
            if (eq > 0) l.substring(0, eq) to l.substring(eq + 1) else null
        }.toMap()
    }
}
