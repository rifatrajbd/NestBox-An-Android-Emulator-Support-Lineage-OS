package app.nestbox.service

import android.os.Bundle
import app.nestbox.engine.VmState
import app.nestbox.vm.Health

/** Messages between the UI process (ServiceEngine) and a machine's process (VmService). */
object VmProtocol {
    // UI → service
    const val MSG_REGISTER = 1
    const val MSG_UNREGISTER = 2
    const val MSG_STOP = 3
    const val MSG_SURFACE = 4   // data: "surface" (Surface or absent), "w", "h"
    const val MSG_INPUT = 5     // data: "ev" (MotionEvent)
    const val MSG_KEY = 6       // arg1: keycode
    const val MSG_VISIBLE = 7   // arg1: 1 shown, 0 minimized
    /** A request with an answer: data "call" (id), "op" (OP_*), "files" (paths), "spoof" (JSON). */
    const val MSG_CALL = 8

    // service → UI
    const val MSG_STATE = 10
    const val MSG_HEALTH = 11
    /** Answer to MSG_CALL: data "call", "ok", "text". */
    const val MSG_RESULT = 12

    const val OP_INSTALL = "install"
    const val OP_SPOOF = "spoof"
    const val OP_SYNC = "sync"

    // Intents
    const val ACTION_START = "app.nestbox.action.START"
    const val ACTION_STOP = "app.nestbox.action.STOP"
    const val EXTRA_VM = "vm"           // VmConfig JSON
    const val EXTRA_VM_ID = "vmId"
    const val EXTRA_GPU = "gpu"
    const val EXTRA_SETTINGS = "settings" // AppSettings JSON

    fun stateToBundle(s: VmState) = Bundle().apply {
        when (s) {
            VmState.Stopped -> putString("kind", "stopped")
            VmState.Running -> putString("kind", "running")
            is VmState.Killed -> putString("kind", "killed")
            is VmState.Failed -> {
                putString("kind", "failed")
                putString("message", s.message)
                putString("logs", s.logsDir)
                putStringArrayList("log", ArrayList(s.log.takeLast(60)))
            }
            is VmState.Booting -> {
                putString("kind", "booting")
                putFloat("pct", s.pct)
                putStringArrayList("log", ArrayList(s.log.takeLast(60)))
            }
        }
    }

    fun bundleToState(b: Bundle): VmState = when (b.getString("kind")) {
        "running" -> VmState.Running
        "booting" -> {
            val log = b.getStringArrayList("log").orEmpty()
            VmState.Booting(b.getFloat("pct"), log.lastOrNull().orEmpty(), log)
        }
        "killed" -> VmState.Killed(null)
        "failed" -> VmState.Failed(b.getString("message").orEmpty(), b.getString("logs"), b.getStringArrayList("log").orEmpty())
        else -> VmState.Stopped
    }

    fun healthToBundle(h: Health) = Bundle().apply {
        putInt("fps", h.fps)
        putInt("temp", h.tempC)
        putLong("ram", h.ramBytes)
        putBoolean("throttled", h.throttled)
    }

    fun bundleToHealth(b: Bundle) = Health(b.getInt("fps"), b.getInt("temp"), b.getLong("ram"), b.getBoolean("throttled"))
}
