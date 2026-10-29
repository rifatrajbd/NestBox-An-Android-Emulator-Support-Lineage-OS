package app.nestbox.vm

/** Fixed lists from the design (design/README.md §2.3, §2.5), with the systems each engine can boot. */
object Catalog {
    const val MODERN = "modern"
    const val CLASSIC = "classic"

    data class Mode(val id: String, val name: String, val sub: String)

    /** Where a ROM comes from: a direct file, a file inside a zip (APK), or the user's own file. */
    sealed interface Source {
        data class File(val url: String, val sha256: String, val bytes: Long) : Source
        data class InZip(val url: String, val entry: String, val bytes: Long) : Source
        data object Import : Source
    }

    data class Rom(
        val id: String, val engine: String, val name: String, val tag: String, val short: String,
        val sub: String, val size: String, val source: Source, val gms: Boolean = false,
    )
    data class Preset(val id: String, val name: String, val cores: Int, val ramGb: Int, val fps: Int, val sub: String)
    data class Place(val name: String, val coords: String, val lat: Double?, val lng: Double?)
    data class Region(val code: String, val carrier: String, val mcc: String, val tz: String, val locale: String)

    val modes = listOf(
        Mode(MODERN, "Modern", "Wayondroid engine · Android 13 · several machines at once"),
        Mode(CLASSIC, "Classic", "Twoyi engine, improved · Android 8.1 · one machine at a time"),
    )

    val roms = listOf(
        Rom(
            "a13gapps", MODERN, "Android 13 + Google", "BETA", "13 GApps",
            "LineageOS 20 · Play Store · 1.2 GB download", "1.2 GB",
            Source.File(
                "https://github.com/yoro1836/wayondroid/releases/download/rom-GAPPS/waydroid-lineage20-gapps.vrom",
                "2c5fed8e18a9c8b5857c12bc4f0b564a7501df1ba02163c7cd052bbe6a48cc57", 1_157_079_790,
            ),
            gms = true,
        ),
        Rom("a13imp", MODERN, "Import ROM", "FILE", "13 Custom", ".vrom file for Wayondroid", "local file", Source.Import),
        Rom(
            "geek", CLASSIC, "Android 8.1 Geek", "STABLE", "8.1 Geek",
            "Twoyi ROM · root built in · 0.2 GB download", "0.2 GB",
            Source.InZip(
                "https://github.com/twoyi/twoyi/releases/download/0.5.4/twoyi_0.5.4-03211927-release.apk",
                "assets/rootfs.7z", 209_364_444,
            ),
        ),
        Rom("imp", CLASSIC, "Import rootfs", "FILE", "Custom", "rootfs.7z from a Twoyi release", "local file", Source.Import),
    )

    val presets = listOf(
        Preset("battery", "Battery", 2, 2, 30, "2 cores · 2 GB cap · 30 fps · pauses when hidden"),
        Preset("balanced", "Balanced", 4, 3, 60, "4 cores · 3 GB cap · 60 fps"),
        Preset("perf", "Performance", 6, 4, 90, "6 cores · 4 GB cap · 90 fps · runs warmer"),
    )

    val resolutions = listOf("720×1600", "1080×2400", "900×2000")

    val places = listOf(
        Place("Real location", "device GPS", null, null),
        Place("Tokyo", "35.6812, 139.7671", 35.6812, 139.7671),
        Place("London", "51.5072, -0.1276", 51.5072, -0.1276),
        Place("New York", "40.7128, -74.0060", 40.7128, -74.0060),
        Place("Dhaka", "23.8103, 90.4125", 23.8103, 90.4125),
    )

    val regions = listOf(
        Region("Off", "Real SIM", "—", "Device", "Device"),
        Region("JP", "NTT docomo", "440 / 10", "Asia/Tokyo", "ja-JP"),
        Region("US", "T-Mobile", "310 / 260", "America/New_York", "en-US"),
        Region("GB", "EE", "234 / 30", "Europe/London", "en-GB"),
        Region("BD", "Grameenphone", "470 / 01", "Asia/Dhaka", "bn-BD"),
    )

    val devices = listOf("Real device", "Xiaomi 14", "Pixel 8", "Galaxy S24")

    /** Old ROM ids from phase 1–2 builds map onto what exists now. */
    fun rom(id: String) = roms.firstOrNull { it.id == id } ?: when (id) {
        "gms", "lite", "a10" -> roms.first { it.id == "geek" }
        else -> roms.first { it.id == "geek" }
    }

    fun romsFor(engine: String) = roms.filter { it.engine == engine }
    fun mode(id: String) = modes.firstOrNull { it.id == id } ?: modes.last()
    fun preset(id: String) = presets.firstOrNull { it.id == id } ?: presets[1]
    fun place(name: String) = places.firstOrNull { it.name == name } ?: places.first()
    fun region(code: String) = regions.firstOrNull { it.code == code } ?: regions.first()
}
