package app.nestbox.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** On-screen buttons over the guest display (from Wayondroid). */
@Serializable
enum class NavButtons(val label: String, val description: String) {
    Hidden("Hidden", "No buttons. The back gesture goes back in the guest. Tap with three fingers to minimize."),
    AutoHide("Tap to show", "A small handle at the top. Tap it to show the buttons for a few seconds."),
    Always("Always visible", "The three buttons stay below the guest screen."),
}

/** How the guest screen fits the phone screen (from Wayondroid). */
@Serializable
enum class ScreenFit(val label: String, val description: String) {
    Fill("Fill the whole screen", "The guest gets your phone's screen shape, so it fills the display edge to edge."),
    KeepAspect("Keep 9:16 shape", "The guest keeps a classic 9:16 shape, with black bars when your phone is taller."),
}

/**
 * App-wide settings: Wayondroid's settings merged with Nestbox's own.
 * Per-machine options (root, preset, resolution, spoof) stay on each machine.
 */
@Serializable
data class AppSettings(
    val defaultPreset: String = "balanced",
    val keepScreenOn: Boolean = true,
    // Hidden: the guest gets the whole screen (VMOS-style). Three-finger tap minimizes.
    val navButtons: NavButtons = NavButtons.Hidden,
    val screenFit: ScreenFit = ScreenFit.Fill,
    val avoidCutout: Boolean = true,
    val guestInternet: Boolean = true,
    /** On by default: software rendering crashes on Snapdragon chips without SVE. */
    val gpuAcceleration: Boolean = true,
    val technicalInfo: Boolean = false,
    /** Copies guest logs and crash reports to /sdcard/Nestbox/logs when a boot fails. */
    val saveBootLogs: Boolean = true,
    /** Android Go settings, smaller app heaps and fewer cached apps in Modern guests. */
    val lowMemory: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(
        prefs.getString("app", null)?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
            ?: AppSettings()
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(change: (AppSettings) -> AppSettings) {
        val n = change(_settings.value)
        _settings.value = n
        prefs.edit().putString("app", json.encodeToString(AppSettings.serializer(), n)).apply()
    }
}

/**
 * Xiaomi setup steps the phone cannot report (autostart on some builds, Lock in Recents)
 * plus the process limit, which the pairing flow sets after it verifies the change.
 */
class SetupStore(context: Context) {
    private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private val _done = MutableStateFlow(read())
    val done: StateFlow<Map<String, Boolean>> = _done.asStateFlow()

    private fun read() = KEYS.associateWith { prefs.getBoolean(it, false) }

    /** Re-reads flags written elsewhere (the pairing service). */
    fun refresh() {
        _done.value = read()
    }

    var finished: Boolean
        get() = prefs.getBoolean("finished", false)
        set(v) = prefs.edit().putBoolean("finished", v).apply()

    fun mark(key: String) = markAll(listOf(key))

    fun markAll(keys: List<String> = KEYS) {
        val e = prefs.edit()
        keys.forEach { e.putBoolean(it, true) }
        e.apply()
        _done.value = _done.value + keys.associateWith { true }
    }

    companion object {
        val KEYS = listOf("autostart", "battery", "recents", "popup", "processes")
    }
}
