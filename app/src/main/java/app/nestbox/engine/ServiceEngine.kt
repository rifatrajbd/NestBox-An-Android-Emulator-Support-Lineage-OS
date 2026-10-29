package app.nestbox.engine

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.MotionEvent
import android.view.Surface
import androidx.core.content.ContextCompat
import app.nestbox.service.Heartbeat
import app.nestbox.service.VmProtocol
import app.nestbox.service.VmService
import app.nestbox.settings.AppSettings
import app.nestbox.vm.Catalog
import app.nestbox.vm.Health
import app.nestbox.vm.VmConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Thrown when every machine slot is busy. */
class NoFreeSlotException : IllegalStateException("All machine slots are in use")

/** Classic (Twoyi) machines share one fixed rootfs path, so only one runs at a time. */
class ClassicBusyException(val running: String) : IllegalStateException("A Classic machine is already running")

/** Which VmService slot (process) each running machine uses. UI process only. */
class SlotRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("slots", Context.MODE_PRIVATE)

    fun slotOf(vmId: String): Int? = prefs.getInt(vmId, 0).takeIf { it > 0 }

    fun assign(vmId: String): Int {
        slotOf(vmId)?.let { return it }
        val used = prefs.all.values.filterIsInstance<Int>().toSet()
        val free = (1..VmService.SLOTS).firstOrNull { it !in used } ?: throw NoFreeSlotException()
        prefs.edit().putInt(vmId, free).apply()
        return free
    }

    fun release(vmId: String) = prefs.edit().remove(vmId).apply()
}

/**
 * The engine the UI uses. Each machine runs in its own VmService process; this class starts,
 * binds and stops those services, and turns lost connections into [VmState.Killed].
 */
class ServiceEngine(private val context: Context, private val settings: () -> AppSettings) : VmEngine {
    private val json = Json { encodeDefaults = true }
    /** vmId → engine mode, for machines started from this process. */
    private val modes = ConcurrentHashMap<String, String>()
    private val slots = SlotRegistry(context)
    private val states = ConcurrentHashMap<String, MutableStateFlow<VmState>>()
    private val healths = ConcurrentHashMap<String, MutableSharedFlow<Health>>()
    private val links = ConcurrentHashMap<String, Link>()
    private val pendingSurface = ConcurrentHashMap<String, android.os.Bundle>()

    private fun stateFlow(id: String) = states.getOrPut(id) { MutableStateFlow(VmState.Stopped) }
    private fun healthFlow(id: String) = healths.getOrPut(id) { MutableSharedFlow(replay = 1, extraBufferCapacity = 4) }

    /** A binding to one machine's service. */
    private inner class Link(val vmId: String) : ServiceConnection {
        var remote: Messenger? = null
        var userStopped = false

        @SuppressLint("HandlerLeak")
        val inbox = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    VmProtocol.MSG_STATE -> stateFlow(vmId).value = VmProtocol.bundleToState(msg.data)
                    VmProtocol.MSG_HEALTH -> healthFlow(vmId).tryEmit(VmProtocol.bundleToHealth(msg.data))
                }
            }
        })

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            send(VmProtocol.MSG_REGISTER)
            // A display that was attached before the service answered.
            pendingSurface.remove(vmId)?.let { post(VmProtocol.MSG_SURFACE, it) }
        }

        fun post(what: Int, data: android.os.Bundle?, arg1: Int = 0) {
            val r = remote
            if (r == null) {
                if (what == VmProtocol.MSG_SURFACE && data != null) pendingSurface[vmId] = data
                return
            }
            try {
                r.send(Message.obtain(null, what).apply { this.data = data; this.arg1 = arg1 })
            } catch (_: RemoteException) {
            }
        }

        /** The machine's process died: stopped by us, or killed by the system. */
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            val hb = Heartbeat.read(context, vmId)
            val current = stateFlow(vmId).value
            stateFlow(vmId).value = when {
                current is VmState.Failed -> current // keep the reason and the log path on screen
                userStopped || hb == null || hb.stopped -> VmState.Stopped
                else -> VmState.Killed(hb.timeMs)
            }
            unlink(vmId)
        }

        fun send(what: Int) {
            try {
                remote?.send(Message.obtain(null, what).apply { replyTo = inbox })
            } catch (_: RemoteException) {
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private fun link(vmId: String, slot: Int) {
        if (links.containsKey(vmId)) return
        val l = Link(vmId)
        links[vmId] = l
        // No BIND_AUTO_CREATE: we only attach to a service that was started (and is still alive).
        context.bindService(Intent(context, VmService.classFor(slot)), l, 0)
        watch(l)
    }

    /** Until the service answers, keep checking its heartbeat: a dead process never connects. */
    private fun watch(l: Link) {
        main.postDelayed({
            if (links[l.vmId] !== l || l.remote != null) return@postDelayed
            val hb = Heartbeat.read(context, l.vmId)
            when {
                hb == null || hb.stopped -> { stateFlow(l.vmId).value = VmState.Stopped; unlink(l.vmId) }
                !hb.fresh() -> { stateFlow(l.vmId).value = VmState.Killed(hb.timeMs); unlink(l.vmId) }
                else -> watch(l)
            }
        }, Heartbeat.INTERVAL_MS)
    }

    private fun unlink(vmId: String) {
        val l = links.remove(vmId) ?: return
        l.send(VmProtocol.MSG_UNREGISTER)
        runCatching { context.unbindService(l) }
        slots.release(vmId)
    }

    override suspend fun prepare(vm: VmConfig, onProgress: (Float, String) -> Unit) {
        // Phase 3: rootfs extraction happens in the machine's process.
        onProgress(1f, "Ready")
    }

    override suspend fun start(vm: VmConfig, surface: Surface?) {
        val st = stateFlow(vm.id)
        if (st.value is VmState.Running || st.value is VmState.Booting) return
        if (vm.engine == Catalog.CLASSIC) {
            val other = modes.entries.firstOrNull { (id, mode) ->
                id != vm.id && mode == Catalog.CLASSIC && stateFlow(id).value.let { it is VmState.Running || it is VmState.Booting }
            }
            if (other != null) throw ClassicBusyException(other.key)
        }
        modes[vm.id] = vm.engine
        val slot = slots.assign(vm.id)
        val intent = Intent(context, VmService.classFor(slot))
            .setAction(VmProtocol.ACTION_START)
            .putExtra(VmProtocol.EXTRA_VM, json.encodeToString(VmConfig.serializer(), vm))
            .putExtra(VmProtocol.EXTRA_SETTINGS, json.encodeToString(AppSettings.serializer(), settings()))
        ContextCompat.startForegroundService(context, intent)
        st.value = VmState.Booting(0f, "", emptyList())
        link(vm.id, slot)
    }

    override fun attachSurface(vmId: String, surface: Surface?, width: Int, height: Int) {
        val data = android.os.Bundle().apply {
            if (surface != null) putParcelable("surface", surface)
            putInt("w", width)
            putInt("h", height)
        }
        links[vmId]?.post(VmProtocol.MSG_SURFACE, data)
            ?: run { pendingSurface[vmId] = data }
    }

    override fun sendInput(vmId: String, ev: MotionEvent) {
        links[vmId]?.post(VmProtocol.MSG_INPUT, android.os.Bundle().apply { putParcelable("ev", ev) })
    }

    override fun sendKey(vmId: String, keycode: Int) {
        links[vmId]?.post(VmProtocol.MSG_KEY, null, keycode)
    }

    override fun setVisible(vmId: String, visible: Boolean) {
        links[vmId]?.post(VmProtocol.MSG_VISIBLE, null, if (visible) 1 else 0)
    }

    override suspend fun stop(vmId: String) {
        val l = links[vmId]
        if (l?.remote != null) {
            l.userStopped = true
            l.send(VmProtocol.MSG_STOP)
        } else {
            slots.slotOf(vmId)?.let { slot ->
                runCatching { context.startService(Intent(context, VmService.classFor(slot)).setAction(VmProtocol.ACTION_STOP)) }
            }
            unlink(vmId)
        }
        stateFlow(vmId).value = VmState.Stopped
    }

    override fun state(vmId: String): StateFlow<VmState> = stateFlow(vmId)

    override fun health(vmId: String): Flow<Health> = healthFlow(vmId)

    override suspend fun installApk(vmId: String, apk: File): Result<Unit> {
        // Phase 5 sends the APK to the guest over the bridge.
        delay(400)
        return Result.success(Unit)
    }

    override suspend fun reconnect(vm: VmConfig): Boolean {
        val hb = Heartbeat.read(context, vm.id)
        val slot = slots.slotOf(vm.id) ?: hb?.slot
        if (hb == null || hb.stopped || !hb.fresh() || slot == null) {
            slots.release(vm.id)
            stateFlow(vm.id).value = if (hb != null && hb.stopped) VmState.Stopped else VmState.Killed(hb?.timeMs ?: vm.lastSeenMs)
            return false
        }
        stateFlow(vm.id).value = if (hb.state == Heartbeat.BOOTING) VmState.Booting(0f, "", emptyList()) else VmState.Running
        link(vm.id, slot)
        return true
    }
}
