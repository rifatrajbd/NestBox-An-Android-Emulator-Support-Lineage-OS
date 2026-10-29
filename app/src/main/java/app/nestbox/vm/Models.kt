package app.nestbox.vm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class VmStatus { Stopped, Booting, Running, Killed }

@Serializable
data class SpoofProfile(
    val place: String = "Real location",
    val lat: Double? = null,
    val lng: Double? = null,
    val region: String = "Off", // Off | JP | US | GB | BD
    val device: String = "Real device",
    val joystick: Boolean = false,
)

@Serializable
data class Snapshot(val name: String, @SerialName("when") val time: String, val size: String)

/** Stored as filesDir/vms/<id>/config.json. */
@Serializable
data class VmConfig(
    val id: String,
    val name: String,
    /** Engine mode: [Catalog.MODERN] (machines from the removed Classic mode are dropped on load). */
    val engine: String = Catalog.MODERN,
    val romId: String,
    /** content:// URI of an imported ROM file (import ROM entries only). */
    val importUri: String? = null,
    val preset: String,
    val res: String,
    val root: Boolean,
    val xposed: Boolean,
    val gms: Boolean,
    val keepAlive: Boolean,
    val shared: Boolean = true,
    val spoof: SpoofProfile = SpoofProfile(),
    val snapshots: List<Snapshot> = emptyList(),
    /** Last status the app saw. Running here at app start means the machine was alive before. */
    val lastStatus: VmStatus = VmStatus.Stopped,
    /** When a machine the system stopped was last known alive (for "Last state saved N min ago"). */
    val lastSeenMs: Long? = null,
)

data class Health(val fps: Int, val tempC: Int, val ramBytes: Long, val throttled: Boolean)
