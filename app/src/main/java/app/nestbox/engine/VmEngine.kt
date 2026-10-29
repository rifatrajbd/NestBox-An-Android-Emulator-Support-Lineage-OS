package app.nestbox.engine

import android.view.MotionEvent
import android.view.Surface
import app.nestbox.vm.Health
import app.nestbox.vm.SpoofProfile
import app.nestbox.vm.VmConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface VmState {
    data object Stopped : VmState
    /** [log] holds every boot log line so far; [logLine] is the newest. */
    data class Booting(val pct: Float, val logLine: String, val log: List<String>) : VmState
    data object Running : VmState
    /** The system stopped the machine. [lastSeenMs] is when it was last known alive. */
    data class Killed(val lastSeenMs: Long?) : VmState
    /** Start failed. [logsDir] is where the guest's logs were saved, if they could be. */
    data class Failed(val message: String, val logsDir: String?, val log: List<String> = emptyList()) : VmState
}

/** The UI talks only to this. FakeEngine for UI work, the native engines for real guests. */
interface VmEngine {
    suspend fun prepare(vm: VmConfig, onProgress: (Float, String) -> Unit) // download and extract the system
    suspend fun start(vm: VmConfig, surface: Surface?)                    // boot, attach display
    /** Full ↔ floating window. [width]/[height] are the view's size in pixels (touch scaling). */
    fun attachSurface(vmId: String, surface: Surface?, width: Int = 0, height: Int = 0)
    fun sendInput(vmId: String, ev: MotionEvent)
    /** Guest navigation keys (KeyEvent.KEYCODE_BACK, _HOME, _APP_SWITCH). */
    fun sendKey(vmId: String, keycode: Int) = Unit
    /** The machine's screen is shown (true) or minimized (false). The Battery preset pauses while hidden. */
    fun setVisible(vmId: String, visible: Boolean) = Unit
    suspend fun stop(vmId: String)
    fun state(vmId: String): StateFlow<VmState>
    fun health(vmId: String): Flow<Health>
    /**
     * Installs an app in the running guest. [apks] is the base APK followed by its split APKs.
     * Returns the package manager's answer.
     */
    suspend fun installApk(vmId: String, apks: List<File>): Result<String>
    /** Applies a spoof profile to the running guest; the rest applies from the next start. */
    suspend fun applySpoof(vmId: String, spoof: SpoofProfile): Result<Unit> = Result.success(Unit)
    /** Brings the shared folder up to date (Classic copies files; Modern shares the folder live). */
    suspend fun syncShared(vmId: String): Result<Unit> = Result.success(Unit)
    /**
     * Reconnects to a machine that was running when the app last closed.
     * Returns false and reports [VmState.Killed] if the system stopped it meanwhile.
     */
    suspend fun reconnect(vm: VmConfig): Boolean
}
