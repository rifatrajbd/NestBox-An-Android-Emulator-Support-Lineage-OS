package app.nestbox.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.os.RemoteException
import android.view.MotionEvent
import android.view.Surface
import androidx.core.app.ServiceCompat
import androidx.core.os.BundleCompat
import app.nestbox.engine.FakeEngine
import app.nestbox.engine.VmEngine
import app.nestbox.engine.VmState
import app.nestbox.engine.classic.ClassicEngine
import app.nestbox.engine.modern.ModernEngine
import app.nestbox.host.HostInfo
import app.nestbox.settings.AppSettings
import app.nestbox.vm.Catalog
import app.nestbox.vm.VmConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Runs one machine in its own process (":vm1"…). The guest never runs in the UI process.
 * The UI binds with a Messenger for state, health, the display surface and input; the heartbeat
 * file lets the UI notice when HyperOS kills this process.
 */
abstract class VmService : Service() {
    abstract val slot: Int

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private var engine: VmEngine? = null
    private val clients = mutableListOf<Messenger>()
    private var vm: VmConfig? = null
    private var state: VmState = VmState.Stopped
    private var jobs = listOf<Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    @SuppressLint("HandlerLeak")
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val id = vm?.id
            when (msg.what) {
                VmProtocol.MSG_REGISTER -> msg.replyTo?.let {
                    clients += it
                    send(it, VmProtocol.MSG_STATE, VmProtocol.stateToBundle(state))
                }
                VmProtocol.MSG_UNREGISTER -> msg.replyTo?.let { clients -= it }
                VmProtocol.MSG_STOP -> stopMachine()
                VmProtocol.MSG_SURFACE -> if (id != null) {
                    val d = msg.data.apply { classLoader = Surface::class.java.classLoader }
                    val s = BundleCompat.getParcelable(d, "surface", Surface::class.java)
                    engine?.attachSurface(id, s, d.getInt("w"), d.getInt("h"))
                }
                VmProtocol.MSG_INPUT -> if (id != null) {
                    val d = msg.data.apply { classLoader = MotionEvent::class.java.classLoader }
                    BundleCompat.getParcelable(d, "ev", MotionEvent::class.java)?.let { ev ->
                        engine?.sendInput(id, ev)
                        ev.recycle()
                    }
                }
                VmProtocol.MSG_KEY -> if (id != null) engine?.sendKey(id, msg.arg1)
                VmProtocol.MSG_VISIBLE -> if (id != null) engine?.setVisible(id, msg.arg1 == 1)
                VmProtocol.MSG_CALL -> call(msg.data, msg.replyTo)
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /** Install, spoof and shared-folder requests from the UI; the answer goes back to [to]. */
    private fun call(data: android.os.Bundle, to: Messenger?) {
        val callId = data.getInt("call")
        val op = data.getString("op")
        val files = data.getStringArrayList("files").orEmpty()
        val spoofJson = data.getString("spoof")
        scope.launch {
            val id = vm?.id
            val e = engine
            val result: Result<String> = when {
                id == null || e == null || state !is VmState.Running -> Result.failure(IllegalStateException("The machine is not running"))
                op == VmProtocol.OP_INSTALL -> e.installApk(id, files.map { java.io.File(it) })
                op == VmProtocol.OP_SPOOF -> runCatching { json.decodeFromString(app.nestbox.vm.SpoofProfile.serializer(), spoofJson!!) }
                    .mapCatching { p ->
                        vm = vm?.copy(spoof = p)
                        e.applySpoof(id, p).getOrThrow()
                        ""
                    }
                op == VmProtocol.OP_SYNC -> e.syncShared(id).map { "" }
                else -> Result.failure(IllegalArgumentException("Unknown request $op"))
            }
            val reply = android.os.Bundle().apply {
                putInt("call", callId)
                putBoolean("ok", result.isSuccess)
                putString("text", result.getOrNull() ?: result.exceptionOrNull()?.message.orEmpty())
            }
            to?.let { send(it, VmProtocol.MSG_RESULT, reply) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            VmProtocol.ACTION_START -> {
                val config = intent.getStringExtra(VmProtocol.EXTRA_VM)
                    ?.let { runCatching { json.decodeFromString(VmConfig.serializer(), it) }.getOrNull() }
                val settings = intent.getStringExtra(VmProtocol.EXTRA_SETTINGS)
                    ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
                if (config == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startMachine(config, settings)
            }
            VmProtocol.ACTION_STOP -> stopMachine()
            else -> if (vm == null) stopSelf() // restarted by the system with nothing to resume
        }
        return START_NOT_STICKY
    }

    /** Demo engine on emulators (no arm64), otherwise the machine's chosen mode. */
    private fun engineFor(config: VmConfig, settings: AppSettings): VmEngine = when {
        !HostInfo.realEngines -> FakeEngine(HostInfo(this))
        config.engine == Catalog.MODERN -> ModernEngine(this, settings)
        else -> ClassicEngine(this, settings)
    }

    private fun startMachine(config: VmConfig, settings: AppSettings) {
        Notifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_BASE + slot,
            Notifications.machine(this, slot, config.id, config.name, "Starting", javaClass),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (vm?.id == config.id && (state is VmState.Running || state is VmState.Booting)) return
        vm = config
        stopping = false
        val e = engineFor(config, settings).also { engine = it }
        if (config.keepAlive) acquireWakeLock()
        jobs.forEach { it.cancel() }
        jobs = listOf(
            scope.launch { e.start(config, null) },
            scope.launch { e.state(config.id).collect { onState(config, it) } },
            scope.launch {
                e.health(config.id).collect { h ->
                    clients.toList().forEach { send(it, VmProtocol.MSG_HEALTH, VmProtocol.healthToBundle(h)) }
                }
            },
            scope.launch {
                while (isActive) {
                    if (state is VmState.Running || state is VmState.Booting) beat(config.id)
                    delay(Heartbeat.INTERVAL_MS)
                }
            },
        )
    }

    private fun onState(config: VmConfig, s: VmState) {
        val changedKind = s::class != state::class
        state = s
        clients.toList().forEach { send(it, VmProtocol.MSG_STATE, VmProtocol.stateToBundle(s)) }
        if (!changedKind) return
        beat(config.id)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        when (s) {
            is VmState.Running, is VmState.Booting -> {
                val text = if (s is VmState.Running) "Running" else "Starting"
                nm.notify(NOTIFICATION_BASE + slot, Notifications.machine(this, slot, config.id, config.name, text, javaClass))
            }
            is VmState.Failed -> {
                // Keep the reason on screen for the UI, then free the process.
                stopping = true
                scope.launch {
                    delay(1500)
                    finish()
                }
            }
            else -> Unit
        }
    }

    private fun beat(vmId: String) {
        val st = when (state) {
            is VmState.Running -> Heartbeat.RUNNING
            is VmState.Booting -> Heartbeat.BOOTING
            else -> Heartbeat.STOPPED
        }
        Heartbeat.write(this, vmId, Heartbeat(st, System.currentTimeMillis(), slot, Process.myPid()))
    }

    private fun stopMachine() {
        val config = vm
        stopping = true
        if (config != null) {
            Heartbeat.write(this, config.id, Heartbeat(Heartbeat.STOPPED, System.currentTimeMillis(), slot, Process.myPid()))
            scope.launch {
                runCatching { engine?.stop(config.id) }
                finish()
            }
        } else finish()
    }

    private fun finish() {
        vm?.let { Heartbeat.write(this, it.id, Heartbeat(Heartbeat.STOPPED, System.currentTimeMillis(), slot, Process.myPid())) }
        if (state !is VmState.Failed) {
            state = VmState.Stopped
            clients.toList().forEach { send(it, VmProtocol.MSG_STATE, VmProtocol.stateToBundle(VmState.Stopped)) }
        }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
        // A stopped machine frees its memory right away instead of waiting for the system.
        if (stopping) Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    private fun send(to: Messenger, what: Int, data: android.os.Bundle) {
        try {
            to.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            clients -= to
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nestbox:vm$slot").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val NOTIFICATION_BASE = 1000
        const val SLOTS = 4

        fun classFor(slot: Int): Class<out VmService> = when (slot) {
            1 -> VmService1::class.java
            2 -> VmService2::class.java
            3 -> VmService3::class.java
            else -> VmService4::class.java
        }
    }
}

// A manifest service has one process, so each machine slot gets its own class.
class VmService1 : VmService() { override val slot = 1 }
class VmService2 : VmService() { override val slot = 2 }
class VmService3 : VmService() { override val slot = 3 }
class VmService4 : VmService() { override val slot = 4 }
