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
        /**
         * A generic system image (GSI) married to Waydroid's container vendor of the same Android
         * version: the system is an .img.xz (sparse or raw ext4), the vendor sits in a zip.
         */
        data class Port(
            val systemXzUrl: String, val systemXzBytes: Long, val systemXzSha256: String,
            val vendorZipUrl: String, val vendorZipBytes: Long, val vendorZipSha256: String,
            val vendorEntry: String = "vendor.img",
        ) : Source
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
        /**
         * "q": an Android 10 GSI. Gets Waydroid's Android 10 init (container patches) and
         * NestBox's SELinux shims in tawcroot (scripts/patch_tawcroot_selinux.py).
         */
        val compat: String? = null,
        /** Commands run in the guest after it boots (exec). */
        val afterBoot: List<List<String>> = emptyList(),
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
        Rom(
            "pe10", MODERN, "Pixel Experience Plus 10", "PORT", "10 PE+",
            "Android 10 · Pixel apps + Play Store · 0.9 GB download", "0.9 GB",
            Source.Port(
                // Community GSI (AndroidGSI project) on Waydroid's LineageOS 17.1 container vendor
                "https://sourceforge.net/projects/androidgsi/files/PixelExperiencePlus/PixelExperience_Plus-10.0-20201105-GSI-ARM64-AB.img.xz/download",
                858_829_168, "83f13470a6fe6692c0225ec7b91f6a3ec7feeb6fbe77f5f0527d8b35ffc6dd88",
                "https://sourceforge.net/projects/waydroid/files/images/vendor/waydroid_arm64/lineage-17.1-20210925-MAINLINE-waydroid_arm64-vendor.zip/download",
                79_469_894, "93c92e258bc7d04b2e84261fa2cca4fcbf4b2f7c7d82bf3e9f652a08e8c2d060",
            ),
            gms = true, sdk = 29,
            props = PE10_PROPS, remove = PE10_REMOVE, afterBoot = PE10_AFTER_BOOT, compat = "q",
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

    /**
     * Building blocks for ports (docs/PORTING.md). A GSI below Android 13 on this engine needs
     * GSI_64BIT and SOFTWARE_GL; every port gets SPEED_PROPS and PORT_AFTER_BOOT.
     */
    // 64-bit only: these phones have no 32-bit CPU mode
    val GSI_64BIT get() = listOf(
        "ro.zygote=zygote64",
        "ro.product.cpu.abilist=arm64-v8a", "ro.product.cpu.abilist32=", "ro.product.cpu.abilist64=arm64-v8a",
        "ro.system.product.cpu.abilist=arm64-v8a", "ro.system.product.cpu.abilist32=",
        "ro.vendor.product.cpu.abilist=arm64-v8a", "ro.vendor.product.cpu.abilist32=",
    )

    // No ANGLE below Android 13: SwiftShader from Waydroid's vendor, GLES 3.0
    val SOFTWARE_GL get() = listOf(
        "ro.hardware.egl=swiftshader", "ro.hardware.gralloc=default", "ro.opengles.version=196608",
        "debug.hwui.renderer=skiagl",
        "ro.apex.updatable=false",
    )

    // Faster: quick first-boot compile, no boot animation, no setup wizard, no rescue reboots
    val SPEED_PROPS get() = listOf(
        "pm.dexopt.first-boot=verify", "pm.dexopt.boot=verify", "pm.dexopt.install=quicken",
        "debug.sf.nobootanimation=1",
        "ro.setupwizard.mode=DISABLED",
        "persist.sys.disable_rescue=true",
    )

    /** Pixel Experience Plus 10 tuned for a rootless, 64-bit-only, software-rendered container. */
    private val PE10_PROPS get() = GSI_64BIT + SOFTWARE_GL + SPEED_PROPS

    /** Apps that do nothing in a VM (no phone, camera, NFC, face unlock) or only cost memory. */
    private val PE10_REMOVE get() = listOf(
        "system/app/FaceUnlockService-res", "system/priv-app/FaceUnlockService",
        "system/app/StitchImage-res", "system/app/StitchImageService", "system/priv-app/StitchImage",
        "system/app/NfcNci", "system/app/Stk", "system/app/SimAppDialog", "system/app/SecureElement",
        "system/app/RcsService", "system/app/PresencePolling", "system/app/EasterEgg", "system/app/Traceur",
        "system/app/LiveWallpapersPicker", "system/priv-app/CellBroadcastReceiver", "system/priv-app/TouchGestures",
        "system/product/app/Camera2", "system/product/app/Photos", "system/product/app/PrebuiltBugle",
        "system/product/app/GoogleTTS", "system/product/app/talkback", "system/product/app/LocationHistoryPrebuilt",
        "system/product/app/MarkupGoogle", "system/product/app/PixelSetupWizardAodOverlay",
        "system/product/app/PixelSetupWizardOverlay",
        "system/product/priv-app/PixelSetupWizard", "system/product/priv-app/SetupWizardPrebuilt",
        "system/product/priv-app/AndroidMigratePrebuilt", "system/product/priv-app/GoogleDialer",
        "system/product/priv-app/CarrierServices", "system/product/priv-app/ConnMetrics",
        "system/product/priv-app/MatchmakerPrebuiltPixel4", "system/product/priv-app/TurboPrebuilt",
        "system/product/priv-app/Velvet", "system/product/priv-app/WellbeingPrebuilt",
    ).map { "/$it" }

    private val PE10_AFTER_BOOT get() = PORT_AFTER_BOOT

    val PORT_AFTER_BOOT get() = listOf(
        // The setup wizard is removed, so mark the device as set up
        listOf("settings", "put", "global", "device_provisioned", "1"),
        listOf("settings", "put", "secure", "user_setup_complete", "1"),
        // Snappier: half-length animations
        listOf("settings", "put", "global", "window_animation_scale", "0.5"),
        listOf("settings", "put", "global", "transition_animation_scale", "0.5"),
        listOf("settings", "put", "global", "animator_duration_scale", "0.5"),
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
        "https://sourceforge.net/projects/androidgsi/files/PixelExperiencePlus/PixelExperience_Plus-10.0-20201105-GSI-ARM64-AB.img.xz/download" to "pe10-system.img.xz",
        "https://sourceforge.net/projects/waydroid/files/images/vendor/waydroid_arm64/lineage-17.1-20210925-MAINLINE-waydroid_arm64-vendor.zip/download" to "pe10-vendor.zip",
        "https://github.com/twoyi/twoyi/releases/download/0.5.4/twoyi_0.5.4-03211927-release.apk" to "geek-twoyi-0.5.4.apk",
    )

    fun mirrorOf(url: String): String? = MIRRORS[url]?.let { MIRROR + it }

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
