package app.nestbox.adb

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** Progress of pairing with Wireless debugging and lifting the background process limit. */
sealed interface PairState {
    data object Idle : PairState
    /** Waiting for the "Pair device with pairing code" screen to appear on the network. */
    data object Searching : PairState
    data class Ready(val host: String, val port: Int) : PairState
    data class Working(val step: String) : PairState
    data object Done : PairState
    data class Failed(val message: String) : PairState
}

/**
 * Android 12+ kills an app's child processes past 32, and a booted guest runs 60+.
 * The limit is a global setting only the shell user can change, so Nestbox pairs with the
 * phone's own Wireless debugging (like LADB, no PC) and runs the command itself.
 */
object ProcessLimit {
    const val COMMAND =
        "settings put global settings_enable_monitor_phantom_procs false; " +
            "device_config put activity_manager max_phantom_processes 2147483647; " +
            "settings get global settings_enable_monitor_phantom_procs"

    private val _state = MutableStateFlow<PairState>(PairState.Idle)
    val state: StateFlow<PairState> = _state.asStateFlow()
    private var mdns: AdbMdns? = null
    /** Last pairing endpoint seen, so a wrong code can be retried after a failure. */
    private var endpoint: PairState.Ready? = null

    fun startDiscovery(context: Context) {
        if (mdns?.isRunning == true) return
        _state.value = PairState.Searching
        mdns = AdbMdns(context.applicationContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { addr: InetAddress?, port: Int ->
            android.util.Log.i(TAG, "pairing service: $addr:$port")
            if (port > 0) {
                val ready = PairState.Ready(addr?.hostAddress ?: "127.0.0.1", port)
                endpoint = ready
                if (_state.value.let { it is PairState.Searching || it is PairState.Ready || it is PairState.Failed }) {
                    _state.value = ready
                }
            }
        }.also { it.start() }
    }

    fun stopDiscovery() {
        mdns?.stop()
        mdns = null
    }

    fun reset() {
        stopDiscovery()
        endpoint = null
        _state.value = PairState.Idle
    }

    /** Pairs with [code] (6 digits from the Settings dialog), connects and lifts the limit. */
    suspend fun pairAndFix(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        val ready = _state.value as? PairState.Ready ?: endpoint
        if (ready == null) {
            _state.value = PairState.Failed("Open Wireless debugging › Pair device with pairing code first.")
            return@withContext false
        }
        val adb = NestboxAdb.get(context)
        try {
            _state.value = PairState.Working("Pairing")
            val paired = try {
                adb.pair(ready.host, ready.port, code.trim())
            } catch (e: java.net.ConnectException) {
                // Discovery can report a closed dialog's port first; wait briefly for the open one.
                android.util.Log.w(TAG, "pairing port ${ready.port} refused, waiting for a newer one")
                val newer = waitForOtherPort(ready.port) ?: throw e
                adb.pair(newer.host, newer.port, code.trim())
            }
            if (!paired) error("Pairing was refused. Check the code and try again.")
            stopDiscovery()
            endpoint = null

            _state.value = PairState.Working("Connecting")
            if (!adb.isConnected && !adb.connectTls(context, 15_000)) error("Could not connect. Keep Wireless debugging on and try again.")

            _state.value = PairState.Working("Turning off the limit")
            val out = shell(adb, COMMAND)
            when {
                out.contains("denial", true) || out.contains("SecurityException") -> error(
                    "HyperOS blocked the change. Turn on \"USB debugging (Security settings)\" in Developer options, then try again."
                )
                out.trim().lines().lastOrNull()?.trim() != "false" -> error("The setting did not change: ${out.trim().take(120)}")
            }
            context.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("processes", true).apply()
            _state.value = PairState.Done
            true
        } catch (e: Exception) {
            _state.value = PairState.Failed(e.message ?: e.javaClass.simpleName)
            false
        } finally {
            runCatching { adb.disconnect() }
        }
    }

    private suspend fun waitForOtherPort(old: Int): PairState.Ready? {
        repeat(20) {
            endpoint?.takeIf { it.port != old }?.let { return it }
            kotlinx.coroutines.delay(250)
        }
        return null
    }

    private const val TAG = "NestboxPairing"

    /** Runs [command] and returns its output. libadb ends a finished command with "Stream closed", not EOF. */
    private fun shell(adb: NestboxAdb, command: String): String =
        adb.openStream("shell:$command").use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val input = stream.openInputStream()
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } catch (e: java.io.IOException) {
                if (!stream.isClosed && out.size() == 0) throw e
            }
            out.toString(Charsets.UTF_8.name())
        }
}
