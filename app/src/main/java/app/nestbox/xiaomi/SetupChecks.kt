package app.nestbox.xiaomi

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** Reads what the phone allows. null means HyperOS gives no way to check, so the user confirms. */
class SetupChecks(private val context: Context) {

    val isXiaomi: Boolean = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** MIUI keeps autostart in a private app op (10008). */
    fun autostart(): Boolean? = miuiOp(10008)

    fun battery(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun popup(): Boolean = Settings.canDrawOverlays(context)

    private fun miuiOp(op: Int): Boolean? {
        if (!isXiaomi) return null
        return runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            (m.invoke(ops, op, Process.myUid(), context.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }
}
