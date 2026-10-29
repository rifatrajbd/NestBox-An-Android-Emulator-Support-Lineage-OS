package app.nestbox.vm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** VM configs as JSON in filesDir/vms/<id>/config.json. */
class VmRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val root = File(context.filesDir, "vms").apply { mkdirs() }
    private val _vms = MutableStateFlow(load())
    val vms: StateFlow<List<VmConfig>> = _vms.asStateFlow()

    fun get(id: String) = _vms.value.firstOrNull { it.id == id }

    fun add(vm: VmConfig) {
        write(vm)
        _vms.update { it + vm }
    }

    fun update(id: String, change: (VmConfig) -> VmConfig) {
        var saved: VmConfig? = null
        _vms.update { list -> list.map { if (it.id == id) change(it).also { n -> saved = n } else it } }
        saved?.let(::write)
    }

    /** Forgets a machine. Its files are deleted by the caller (they may need the engine stopped first). */
    fun remove(id: String) {
        _vms.update { list -> list.filterNot { it.id == id } }
    }

    fun newId(): String = "vm" + UUID.randomUUID().toString().take(8)

    private fun write(vm: VmConfig) {
        val d = File(root, vm.id).apply { mkdirs() }
        val tmp = File(d, "config.json.tmp")
        tmp.writeText(json.encodeToString(VmConfig.serializer(), vm))
        tmp.renameTo(File(d, "config.json"))
    }

    private fun load(): List<VmConfig> {
        val prefs = context.getSharedPreferences("repo", Context.MODE_PRIVATE)
        val all = root.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { d ->
            runCatching { json.decodeFromString(VmConfig.serializer(), File(d, "config.json").readText()) }.getOrNull()
        }
        // Classic (Twoyi) and the Pixel Experience port were removed in 0.8.0: their machines go too
        val (gone, list) = all.partition { it.engine != Catalog.MODERN || it.romId in REMOVED_ROMS }
        if (gone.isNotEmpty()) {
            gone.forEach { app.nestbox.engine.MachineFiles.delete(context, it.id) }
            removeClassicLeftovers()
        }
        if (list.isNotEmpty() || prefs.getBoolean("seeded", false)) return list
        // Only the demo engine (emulators, non-arm64) gets the design's sample machines, so every state can be tried.
        if (app.nestbox.host.HostInfo.realEngines) return list
        prefs.edit().putBoolean("seeded", true).apply()
        return demo().onEach(::write)
    }

    /** Twoyi's fixed rootfs path, its loader link and boot log, from the Classic mode. */
    private fun removeClassicLeftovers() {
        val data = File("/data/data/" + context.packageName)
        data.listFiles { f -> f.name == "rootfs" || f.name.startsWith("rootfs.old-") }
            ?.forEach { app.nestbox.engine.MachineFiles.deleteTree(it) }
        for (n in listOf("loader64", "log.txt")) runCatching { android.system.Os.remove(File(data, n).path) }
        app.nestbox.engine.MachineFiles.deleteTree(File(data, "socket"))
        File(context.filesDir, "roms").listFiles { f -> f.name.endsWith(".7z") || f.name.startsWith("pe10-") }?.forEach { it.delete() }
    }

    private companion object {
        val REMOVED_ROMS = setOf("geek", "imp", "pe10")
    }

    private fun demo() = listOf(
        VmConfig(
            "vm1", "Main", Catalog.MODERN, "a13gapps", preset = "balanced", res = "1080×2400", root = true, xposed = true, gms = false, keepAlive = true,
            spoof = SpoofProfile("Tokyo", 35.6812, 139.7671, "JP", "Pixel 8", joystick = true),
            snapshots = listOf(
                Snapshot("Clean + Magisk", "Oct 12 · 14:02", "1.4 GB"),
                Snapshot("Before module update", "Oct 27 · 09:40", "2.2 GB"),
            ),
        ),
        VmConfig(
            "vm2", "Game AFK", Catalog.MODERN, "a13gapps", preset = "perf", res = "720×1600", root = false, xposed = false, gms = true, keepAlive = true,
            lastStatus = VmStatus.Killed, lastSeenMs = System.currentTimeMillis() - 2 * 60_000,
        ),
        VmConfig("vm3", "Clean test", Catalog.MODERN, "a13gapps", preset = "battery", res = "720×1600", root = false, xposed = false, gms = true, keepAlive = false),
    )
}
