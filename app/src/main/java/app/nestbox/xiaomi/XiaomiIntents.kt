package app.nestbox.xiaomi

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Every Xiaomi (MIUI / HyperOS) deep link lives here. Each one falls back to the
 * app details screen, because HyperOS updates move these screens around.
 */
object XiaomiIntents {

    /** Security › Permissions › Autostart */
    fun autostart(context: Context) = launch(
        context,
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    )

    /** App info › Battery saver › No restrictions */
    @SuppressLint("BatteryLife")
    fun battery(context: Context) = launch(
        context,
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", "Nestbox"),
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)),
    )

    /** Other permissions › Display pop-up windows, then the standard overlay screen. */
    fun popup(context: Context) = launch(
        context,
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)),
    )

    /** Developer options › Wireless debugging (highlighted where the Settings app supports it). */
    fun wirelessDebugging(context: Context) = launch(
        context,
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") }),
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), // developer options are off: tap OS version 7 times
    )

    fun appDetails(context: Context) = launch(context)

    /** Tries each intent in order, then the app details screen. Returns false only if nothing opened. */
    private fun launch(context: Context, vararg intents: Intent): Boolean {
        for (i in intents.toList() + appDetailsIntent(context)) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
}
