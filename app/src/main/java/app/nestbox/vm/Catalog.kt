package app.nestbox.vm

/** Fixed lists from the design (design/README.md §2.3, §2.5), with the systems the engine boots. */
object Catalog {
    /** The engine: Wayondroid's rootless container (tawcroot + userspace binder + gfxstream). */
    const val MODERN = "modern"

    data class Mode(val id: String, val name: String, val sub: String)

    /** Where a ROM comes from: a direct file, or the user's own file. */
    sealed interface Source {
        data class File(val url: String, val sha256: String, val bytes: Long) : Source
        data object Import : Source
    }

    data class Rom(
        val id: String, val engine: String, val name: String, val tag: String, val short: String,
        val sub: String, val size: String, val source: Source, val gms: Boolean = false,
        /** Guest Android API level. Below 33 there is no ANGLE, so the GPU path is off. */
        val sdk: Int = 33,
        /** Extra guest properties (applied on every start through nestbox.prop). */
        val props: List<String> = emptyList(),
        /** Guest paths removed before boot (bloat), through nestbox.remove. */
        val remove: List<String> = emptyList(),
        /** Commands run in the guest after it boots (exec). */
        val afterBoot: List<List<String>> = emptyList(),
    )
    data class Preset(val id: String, val name: String, val cores: Int, val ramGb: Int, val fps: Int, val sub: String)
    data class Place(val name: String, val coords: String, val lat: Double?, val lng: Double?)
    data class Region(val code: String, val carrier: String, val mcc: String, val tz: String, val locale: String)

    val modes = listOf(
        Mode(MODERN, "Modern", "Wayondroid engine · Android 13 · several machines at once"),
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

    /** What a spoofed device reports (ro.product.*). */
    data class Identity(val brand: String, val manufacturer: String, val model: String, val device: String, val product: String)

    fun identity(device: String): Identity? = when (device) {
        "Xiaomi 14" -> Identity("Xiaomi", "Xiaomi", "23127PN0CG", "houji", "houji_global")
        "Pixel 8" -> Identity("google", "Google", "Pixel 8", "shiba", "shiba")
        "Galaxy S24" -> Identity("samsung", "samsung", "SM-S921B", "e1s", "e1sxxx")
        else -> null
    }

    /** MCC+MNC as one number ("440 / 10" -> "44010") and the SIM country for a region. */
    fun numeric(r: Region) = r.mcc.filter { it.isDigit() }
    fun country(r: Region) = r.code.lowercase(java.util.Locale.ROOT)

    /**
     * Copies of the downloads on NestBox's GitHub (release "roms", uploaded with
     * scripts/rom/mirror_roms.sh). Used when the original host fails; same SHA-256 checks.
     */
    private const val MIRROR = "https://github.com/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS/releases/download/roms/"
    private val MIRRORS = mapOf(
        "https://github.com/yoro1836/wayondroid/releases/download/rom-GAPPS/waydroid-lineage20-gapps.vrom" to "a13gapps-system.vrom",
    )

    fun mirrorOf(url: String): String? = MIRRORS[url]?.let { MIRROR + it }

    /** Old ROM ids from phase 1–2 builds map onto what exists now. */
    /** Systems that no longer exist (Classic 8.1, the Pixel Experience port) map onto Android 13. */
    fun rom(id: String) = roms.firstOrNull { it.id == id } ?: roms.first()

    fun romsFor(engine: String) = roms.filter { it.engine == engine }
    fun mode(id: String) = modes.firstOrNull { it.id == id } ?: modes.first()
    fun preset(id: String) = presets.firstOrNull { it.id == id } ?: presets[1]
    fun place(name: String) = places.firstOrNull { it.name == name } ?: places.first()
    fun region(code: String) = regions.firstOrNull { it.code == code } ?: regions.first()
}
