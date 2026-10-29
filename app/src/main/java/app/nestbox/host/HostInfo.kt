package app.nestbox.host

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/** The phone Nestbox runs on, shown in the home host card and setup. */
class HostInfo(private val context: Context) {

    /** "Redmi Note 14 Pro+" from ro.product.marketname, else the model. */
    val deviceName: String = prop("ro.product.marketname").ifBlank { Build.MODEL }

    /** "HyperOS 3" from ro.mi.os.version.name (OS3.0), else "Android 16". */
    val osName: String = prop("ro.mi.os.version.name").let { v ->
        Regex("""OS(\d+)(?:\.(\d+))?""").find(v)?.let { "HyperOS " + it.groupValues[1] }
            ?: "Android ${Build.VERSION.RELEASE}"
    }

    /** "HyperOS 3.0" for the setup check line. */
    val osVersion: String = prop("ro.mi.os.version.name").let { v ->
        Regex("""OS(\d+\.\d+)""").find(v)?.let { "HyperOS " + it.groupValues[1] } ?: "Android ${Build.VERSION.RELEASE}"
    }

    val socModel: String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE

    val socName: String = SOC_NAMES[socModel.uppercase()] ?: socModel

    val gpu: String = when {
        socModel.uppercase() == "SM7635" -> "Adreno 810"
        socModel.uppercase() == "SM8650" -> "Adreno 750"
        else -> prop("ro.hardware.egl").ifBlank { "GPU" }
    }

    val isArm64: Boolean = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    /** Marketing RAM size: 11.1 GiB of usable memory is sold as 12 GB. */
    val ramGb: Int = run {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val gib = mi.totalMem / (1024.0 * 1024 * 1024)
        listOf(2, 3, 4, 6, 8, 12, 16, 24, 32).firstOrNull { it >= gib } ?: gib.toInt()
    }

    /** Battery temperature, the closest phone temperature an app can read. */
    fun tempC(): Int {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 370) ?: 370
        return (t / 10.0).toInt()
    }

    companion object {
        /**
         * The real engines need a native arm64 phone. x86_64 emulators also list arm64 (through
         * translation), so only the primary ABI counts; everything else uses the demo engine.
         */
        val realEngines: Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

        private val SOC_NAMES = mapOf(
            "SM7635" to "Snapdragon 7s Gen 3",
            "SM8650" to "Snapdragon 8 Gen 3",
            "SM8750" to "Snapdragon 8 Elite",
            "SM8635" to "Snapdragon 8s Gen 3",
            "SM7675" to "Snapdragon 7+ Gen 3",
        )

        @SuppressLint("PrivateApi")
        fun prop(key: String): String = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, key) as String
        }.getOrDefault("")
    }
}
