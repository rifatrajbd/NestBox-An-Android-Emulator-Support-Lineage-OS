package app.nestbox.engine

import android.view.MotionEvent
import android.view.Surface
import app.nestbox.host.HostInfo
import app.nestbox.vm.Catalog
import app.nestbox.vm.Health
import app.nestbox.vm.VmConfig
import app.nestbox.vm.VmStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/** Simulated boot and health so the whole UI runs on any phone or emulator. */
class FakeEngine(private val host: HostInfo) : VmEngine {
    private val states = ConcurrentHashMap<String, MutableStateFlow<VmState>>()
    private val presets = ConcurrentHashMap<String, String>()

    private fun flowFor(id: String) = states.getOrPut(id) { MutableStateFlow(VmState.Stopped) }

    override suspend fun prepare(vm: VmConfig, onProgress: (Float, String) -> Unit) {
        for (i in 1..10) {
            delay(60)
            onProgress(i / 10f, "Extracting system")
        }
    }

    override suspend fun start(vm: VmConfig, surface: Surface?) {
        val f = flowFor(vm.id)
        presets[vm.id] = vm.preset
        if (f.value == VmState.Running) return
        // A machine that was running or was stopped by the system resumes from its saved state.
        if (vm.lastStatus == VmStatus.Killed || vm.lastStatus == VmStatus.Running) {
            f.value = VmState.Running
            return
        }
        val log = bootLog(vm)
        var pct = 0
        f.value = VmState.Booting(0f, log.first(), log.take(1))
        while (pct < 100) {
            delay(90)
            if (f.value == VmState.Stopped) return
            pct = minOf(100, pct + 5)
            val shown = log.take(maxOf(1, ceil(pct / 100.0 * log.size).toInt()))
            f.value = VmState.Booting(pct / 100f, shown.last(), shown)
        }
        f.value = VmState.Running
    }

    override fun attachSurface(vmId: String, surface: Surface?, width: Int, height: Int) = Unit

    override fun sendInput(vmId: String, ev: MotionEvent) = Unit

    override suspend fun stop(vmId: String) {
        flowFor(vmId).value = VmState.Stopped
    }

    override fun state(vmId: String): StateFlow<VmState> = flowFor(vmId)

    override fun health(vmId: String): Flow<Health> = flow {
        while (true) {
            val p = Catalog.preset(presets[vmId] ?: "balanced")
            val t = System.currentTimeMillis() / 1000.0
            val booting = flowFor(vmId).value is VmState.Booting
            val ramGb = p.ramGb * if (booting) 0.3 else 0.6
            emit(Health(p.fps - (abs(sin(t)) * 3).roundToInt(), host.tempC(), (ramGb * 1e9).toLong(), throttled = false))
            delay(1500)
        }
    }

    override suspend fun installApk(vmId: String, apk: File): Result<Unit> {
        delay(400)
        return Result.success(Unit)
    }

    override suspend fun reconnect(vm: VmConfig): Boolean = flowFor(vm.id).value == VmState.Running

    private fun bootLog(vm: VmConfig) = listOf(
        "[0.00] nestbox: open rootfs /data/user/0/app.nestbox/${vm.id}",
        "[0.08] container: pid/mount ns ready · shared host kernel",
        "[0.31] init: starting servicemanager, vold",
        "[0.64] gralloc: gfxstream · Vulkan · ${host.gpu}",
        "[1.02] surfaceflinger: ${vm.res.replace('×', 'x')} @ ${Catalog.preset(vm.preset).fps}",
        "[1.47] zygote64: started",
        if (vm.root) "[2.30] magisk: su daemon ready" else "[2.30] keystore: ready",
        "[2.88] sys.boot_completed=1",
    )
}
