package app.nestbox.bridge

import app.nestbox.vm.Catalog
import app.nestbox.vm.SpoofProfile
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a [SpoofProfile] into guest system properties and shell commands.
 * Device identity is read-only (ro.*), so it applies from the next start. Carrier values are
 * live properties. Time zone and language are persistent properties: the guest reads the
 * language at start, the time zone is also changed live.
 */
object Spoof {
    private val PARTITIONS = listOf("", "product.", "system.", "vendor.", "odm.", "system_ext.")

    /** Properties set before boot. */
    fun bootProps(p: SpoofProfile): List<String> {
        val out = ArrayList<String>()
        Catalog.identity(p.device)?.let { id ->
            for (part in PARTITIONS) {
                out += "ro.product.${part}brand=${id.brand}"
                out += "ro.product.${part}manufacturer=${id.manufacturer}"
                out += "ro.product.${part}model=${id.model}"
                out += "ro.product.${part}device=${id.device}"
                out += "ro.product.${part}name=${id.product}"
            }
        }
        val r = Catalog.region(p.region)
        if (r.code != "Off") {
            out += "persist.sys.timezone=${r.tz}"
            out += "persist.sys.locale=${r.locale}"
            out += "ro.product.locale=${r.locale}"
            out += carrierProps(r)
        }
        return out
    }

    fun carrierProps(r: Catalog.Region): List<String> = listOf(
        "gsm.sim.operator.numeric=${Catalog.numeric(r)}",
        "gsm.sim.operator.alpha=${r.carrier}",
        "gsm.sim.operator.iso-country=${Catalog.country(r)}",
        "gsm.operator.numeric=${Catalog.numeric(r)}",
        "gsm.operator.alpha=${r.carrier}",
        "gsm.operator.iso-country=${Catalog.country(r)}",
        "gsm.sim.state=READY",
    )

    /** The time zone and language the guest should use: the region's, or the phone's when off. */
    fun timeZone(p: SpoofProfile): String = Catalog.region(p.region).let { if (it.code == "Off") TimeZone.getDefault().id else it.tz }
    fun locale(p: SpoofProfile): String = Catalog.region(p.region).let { if (it.code == "Off") Locale.getDefault().toLanguageTag() else it.locale }

    /**
     * Shell commands that apply the region to a running guest (run as root). The time zone goes
     * through AlarmManager's setTimeZone (transaction 3 from Android 8.1 to 13).
     */
    fun liveCommands(p: SpoofProfile): List<String> {
        val r = Catalog.region(p.region)
        val tz = timeZone(p)
        val cmds = ArrayList<String>()
        cmds += "setprop persist.sys.timezone $tz"
        cmds += "service call alarm 3 s16 $tz"
        cmds += "setprop persist.sys.locale ${locale(p)}"
        if (r.code != "Off") carrierProps(r).forEach { kv ->
            val (k, v) = kv.split('=', limit = 2)
            cmds += "setprop $k ${quote(v)}"
        }
        return cmds
    }

    fun quote(s: String) = "'" + s.replace("'", "'\''") + "'"
}
