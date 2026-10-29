package app.nestbox.service

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * filesDir/vms/<id>/heartbeat.json, written by the machine's service every [INTERVAL_MS].
 * A stale heartbeat whose state is not "stopped" means the system killed the machine.
 */
@Serializable
data class Heartbeat(val state: String, val timeMs: Long, val slot: Int, val pid: Int) {
    val stopped get() = state == STOPPED
    fun fresh(now: Long = System.currentTimeMillis()) = now - timeMs < STALE_MS

    companion object {
        const val RUNNING = "running"
        const val BOOTING = "booting"
        const val STOPPED = "stopped"
        const val INTERVAL_MS = 5_000L
        /** Three missed beats. */
        const val STALE_MS = 15_000L

        private val json = Json { ignoreUnknownKeys = true }

        fun file(context: Context, vmId: String) = File(File(context.filesDir, "vms/$vmId"), "heartbeat.json")

        fun read(context: Context, vmId: String): Heartbeat? = runCatching {
            json.decodeFromString(serializer(), file(context, vmId).readText())
        }.getOrNull()

        fun write(context: Context, vmId: String, hb: Heartbeat) {
            val f = file(context, vmId)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "heartbeat.json.tmp")
            tmp.writeText(json.encodeToString(serializer(), hb))
            tmp.renameTo(f)
        }
    }
}
