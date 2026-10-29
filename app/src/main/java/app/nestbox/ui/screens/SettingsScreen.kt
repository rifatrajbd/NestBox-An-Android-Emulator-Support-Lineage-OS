package app.nestbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.nestbox.BuildConfig
import app.nestbox.settings.AppSettings
import app.nestbox.settings.NavButtons
import app.nestbox.settings.ScreenFit
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.Routes
import app.nestbox.ui.components.BackBar
import app.nestbox.ui.components.CardRows
import app.nestbox.ui.components.InfoRow
import app.nestbox.ui.components.NbIcon
import app.nestbox.ui.components.NbIcons
import app.nestbox.ui.components.NbToggle
import app.nestbox.ui.components.SectionLabel
import app.nestbox.ui.components.SelectCard
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono
import app.nestbox.ui.theme.ui
import app.nestbox.host.HostInfo
import app.nestbox.vm.Catalog

/** One option in a choice dialog. */
private data class Option(val key: String, val label: String, val description: String)

private sealed interface Item {
    data class Switch(val icon: ImageVector, val title: String, val sub: String, val on: (AppSettings) -> Boolean, val flip: (AppSettings) -> AppSettings) : Item
    data class Choice(val icon: ImageVector, val title: String, val options: List<Option>, val selected: (AppSettings) -> String, val pick: (AppSettings, String) -> AppSettings) : Item
    data class Link(val icon: ImageVector, val title: String, val sub: String, val onClick: () -> Unit) : Item
    data class Info(val icon: ImageVector, val title: String, val sub: String) : Item
}

/**
 * App settings: Wayondroid's settings merged into the Nestbox design.
 * Kept from Wayondroid: keep screen on, on-screen buttons, screen fit, cutout, guest internet, GPU, technical info.
 * Mapped: Wayondroid's RAM and CPU sliders became the default preset, Shizuku became Xiaomi setup.
 */
@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavHostController) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Item.Choice?>(null) }

    val sections = listOf(
        "Machine defaults" to listOf(
            Item.Choice(
                NbIcons.Balance, "Default preset",
                Catalog.presets.map { Option(it.id, it.name, it.sub) }, { it.defaultPreset }, { a, k -> a.copy(defaultPreset = k) },
            ),
            Item.Switch(NbIcons.Screen, "Keep screen on", "Stop the phone from sleeping while a machine is on screen", { it.keepScreenOn }) { it.copy(keepScreenOn = !it.keepScreenOn) },
        ),
        "Display" to listOf(
            Item.Choice(
                NbIcons.Touch, "On-screen buttons",
                NavButtons.entries.map { Option(it.name, it.label, it.description) }, { it.navButtons.name },
                { a, k -> a.copy(navButtons = NavButtons.valueOf(k)) },
            ),
            Item.Choice(
                NbIcons.Fullscreen, "Screen fit",
                ScreenFit.entries.map { Option(it.name, it.label, it.description) }, { it.screenFit.name },
                { a, k -> a.copy(screenFit = ScreenFit.valueOf(k)) },
            ),
            Item.Switch(NbIcons.Phone, "Avoid camera cutout", "Keep the guest screen below the camera hole", { it.avoidCutout }) { it.copy(avoidCutout = !it.avoidCutout) },
        ),
        "Engine" to listOf(
            Item.Switch(
                NbIcons.Speed, "GPU acceleration",
                "Render machines on the phone's GPU. Software rendering crashes on this chip. Applies on next start",
                { it.gpuAcceleration },
            ) { it.copy(gpuAcceleration = !it.gpuAcceleration) },
            Item.Switch(NbIcons.Internet, "Guest internet access", "Give machines a private network behind the phone's connection", { it.guestInternet }) { it.copy(guestInternet = !it.guestInternet) },
            Item.Switch(
                NbIcons.Memory, "Low memory mode",
                "Android Go settings, smaller app heaps and fewer cached apps in Modern machines. Applies from the next start",
                { it.lowMemory },
            ) { it.copy(lowMemory = !it.lowMemory) },
            Item.Switch(NbIcons.Bug, "Save boot logs", "Copy guest logs to /sdcard/Nestbox/logs when a machine fails to start", { it.saveBootLogs }) { it.copy(saveBootLogs = !it.saveBootLogs) },
            Item.Switch(NbIcons.Terminal, "Show technical info", "Kernel, ABI and engine details on machine cards", { it.technicalInfo }) { it.copy(technicalInfo = !it.technicalInfo) },
        ),
        "Phone" to listOf(
            Item.Link(NbIcons.Tune, "Xiaomi setup", "Autostart, battery, floating windows, process limit") { nav.navigate(Routes.Setup) },
            Item.Info(NbIcons.Memory, vm.host.deviceName, "${vm.host.socName} · ${vm.host.gpu} · ${vm.host.ramGb} GB · ${vm.host.osVersion}"),
        ),
        "About" to listOf(
            Item.Info(NbIcons.Info, "Nestbox", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
            Item.Info(
                NbIcons.Memory, "Engines",
                if (HostInfo.realEngines) "Modern: Wayondroid (GPL-3.0) · Classic: Twoyi 0.5.4 (MPL-2.0)" else "Demo engine (this device is not arm64)",
            ),
        ),
    )

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BackBar(title = "Settings") { nav.popBackStack() }
        sections.forEach { (title, items) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(title)
                CardRows(items) { item ->
                    when (item) {
                        is Item.Switch -> InfoRow(item.icon, item.title, item.sub, onClick = { vm.updateSettings(item.flip) }) {
                            NbToggle(item.on(s))
                        }
                        is Item.Choice -> InfoRow(item.icon, item.title, onClick = { dialog = item }) {
                            val sel = item.options.firstOrNull { it.key == item.selected(s) }
                            Text(sel?.label.orEmpty(), style = mono(Nb.T12, 400, Nb.Text2))
                        }
                        is Item.Link -> InfoRow(item.icon, item.title, item.sub, onClick = item.onClick)
                        is Item.Info -> InfoRow(item.icon, item.title, item.sub, titleWeight = 400)
                    }
                }
                if (title == "Display") {
                    Text(
                        "Screen fit applies the next time a machine starts.",
                        style = ui(Nb.T12, 400, Nb.Text3), modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    dialog?.let { d ->
        Dialog(onDismissRequest = { dialog = null }) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(Nb.R24)).background(Nb.Bg).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(d.title, style = ui(Nb.T16, 600), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                d.options.forEach { o ->
                    val sel = o.key == d.selected(s)
                    SelectCard(sel, {
                        vm.updateSettings { d.pick(it, o.key) }
                        dialog = null
                    }) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(o.label, style = ui(Nb.T15, 500))
                            Text(o.description, style = ui(Nb.T12, 400, Nb.Text3).copy(lineHeight = Nb.T12 * 1.4))
                        }
                        if (sel) NbIcon(NbIcons.CheckCircle, 22.dp, Nb.Accent)
                    }
                }
            }
        }
    }
}
