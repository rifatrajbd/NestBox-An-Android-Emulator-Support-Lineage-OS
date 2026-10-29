package app.nestbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import app.nestbox.ui.components.PillButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.Routes
import app.nestbox.ui.components.BackBar
import app.nestbox.ui.components.CardRows
import app.nestbox.ui.components.InfoRow
import app.nestbox.ui.components.NbIcon
import app.nestbox.ui.components.NbIcons
import app.nestbox.ui.components.NbToggle
import app.nestbox.ui.components.PrimaryButton
import app.nestbox.ui.components.SecondaryButton
import app.nestbox.ui.components.SectionLabel
import app.nestbox.ui.components.StatusDot
import app.nestbox.vm.Catalog
import app.nestbox.vm.Snapshot
import app.nestbox.vm.VmConfig
import app.nestbox.vm.VmStatus
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono
import app.nestbox.ui.theme.ui

private data class SysToggle(val key: String, val icon: ImageVector, val label: String, val sub: String, val on: (VmConfig) -> Boolean)

private val sysToggles = listOf(
    SysToggle("root", NbIcons.Root, "Root (Magisk)", "Root inside this machine only") { it.root },
    SysToggle("xposed", NbIcons.Extension, "LSPosed", "Load Xposed modules in the guest") { it.xposed },
    SysToggle("gms", NbIcons.Shop, "Google services", "Play Store and Play Services") { it.gms },
    SysToggle("keepAlive", NbIcons.ScreenOff, "Run with screen off", "For AFK games · uses more battery") { it.keepAlive },
)

private data class Maint(val icon: ImageVector, val k: String, val sub: String, val danger: Boolean = false, val toast: String)

@Composable
fun DetailScreen(vm: AppViewModel, nav: NavHostController, id: String) {
    val vms by vm.vms.collectAsStateWithLifecycle()
    vm.states.collectAsStateWithLifecycle().value
    // Deleting removes the machine while this screen is still up.
    val v = vms.firstOrNull { it.id == id } ?: return
    val st = vm.status(v)
    val p = Catalog.preset(v.preset)
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BackBar { nav.popBackStack() }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(v.name, style = ui(Nb.T26, 600).copy(lineHeight = Nb.T26 * 1.15))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(statusColor(st))
                Text(statusLabel(st), style = ui(Nb.T13, 400, Nb.Text2))
                Text("·", style = ui(Nb.T13, 400, Nb.PoweredOff))
                Text(Catalog.rom(v.romId).short + " · " + Catalog.mode(v.engine).name, style = mono(Nb.T12))
            }
            // Why the last start failed, and where its logs went.
            vm.failure(v.id)?.let { f ->
                Text(f.message, style = ui(Nb.T13, 400, Nb.Red).copy(lineHeight = Nb.T13 * 1.4))
                if (f.logsDir != null) Text("Logs: ${f.logsDir}", style = mono(Nb.T11))
            }
        }
        if (st == VmStatus.Running || st == VmStatus.Booting) {
            // Minimized machine, VMOS-style: its last screen with Enter and Power off.
            MachinePreview(vm, v.id, v.res, st == VmStatus.Booting)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Enter", Modifier.weight(1f), NbIcons.OpenInFull) { nav.navigate(Routes.run(v.id)) }
                SecondaryButton("Power off", NbIcons.Power) {
                    vm.stop(v.id)
                    vm.showToast("Machine powered off")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val label = if (st == VmStatus.Killed) "Resume" else "Start"
                PrimaryButton(label, Modifier.weight(1f), NbIcons.Play) {
                    vm.start(v.id)
                    nav.navigate(Routes.run(v.id))
                }
            }
        }
        // Shortcut tiles
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val spoofSub = if (v.spoof.region == "Off" && v.spoof.place == "Real location") "Off" else "${v.spoof.place} · ${v.spoof.region}"
            Tile(NbIcons.Spoof, "Spoof", spoofSub, Modifier.weight(1f)) {
                vm.openSpoof(v.id)
                nav.navigate(Routes.spoof(v.id))
            }
            Tile(NbIcons.Transfer, "Transfer", "Apps · files", Modifier.weight(1f)) { nav.navigate(Routes.transfer(v.id)) }
            Tile(NbIcons.Terminal, "ADB", "port 5556", Modifier.weight(1f)) { vm.copyAdb() }
        }
        Section("System") {
            CardRows(sysToggles) { t ->
                InfoRow(t.icon, t.label, t.sub, onClick = { vm.toggle(v.id, t.key) }) { NbToggle(t.on(v)) }
            }
        }
        Section("Performance") {
            CardRows(
                listOf(
                    Triple(NbIcons.preset(p.id), "Preset", p.name),
                    Triple(NbIcons.Memory, "CPU · memory cap", "${p.cores} cores · ${p.ramGb} GB"),
                    Triple(NbIcons.AspectRatio, "Display", "${v.res} · ${p.fps} fps"),
                    Triple(NbIcons.Thermostat, "Thermal guard", "Throttle at 42°C"),
                )
            ) { (icon, k, value) ->
                InfoRow(icon, k, titleWeight = 400) { Text(value, style = mono(Nb.T12, 400, Nb.Text2)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Snapshots", style = ui(Nb.T13, 400, Nb.Text3), modifier = Modifier.weight(1f))
                Row(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(Nb.R12)).clickable { vm.takeSnapshot(v.id) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NbIcon(NbIcons.Add, 20.dp, Nb.Accent)
                    Text("Take", style = ui(Nb.T14, 500, Nb.Accent))
                }
            }
            val snaps = v.snapshots.ifEmpty { listOf(Snapshot("No snapshots yet", "Take one before big changes", "—")) }
            CardRows(snaps) { s ->
                InfoRow(NbIcons.History, s.name, "${s.time} · ${s.size}", padV = 12.dp, titleWeight = 400, subMono = true) {
                    Text("Restore", style = ui(Nb.T13, 400, Nb.Text2))
                }
            }
        }
        Section("Maintenance") {
            CardRows(
                listOf(
                    Maint(NbIcons.Share, "Export machine", "Save as .nbx to move or back up", toast = "Exporting to /sdcard/Nestbox/${v.name}.nbx"),
                    Maint(NbIcons.Build, "Repair boot", "Reset system files, keep apps and data", toast = "System files restored"),
                    Maint(NbIcons.Clean, "Trim cache", "Free space used by guest caches", toast = "Freed 640 MB"),
                    Maint(NbIcons.RestartAlt, "Factory reset", "Erase everything in this machine", danger = true, toast = "Hold to confirm reset"),
                    Maint(NbIcons.Delete, "Delete machine", "Remove it and free its storage", danger = true, toast = ""),
                )
            ) { m ->
                val c = if (m.danger) Nb.Red else Nb.Text
                InfoRow(
                    m.icon, m.k, m.sub, titleColor = c, iconTint = c, titleWeight = 400, padV = 14.dp,
                    onClick = { if (m.k == "Delete machine") confirmDelete = true else vm.showToast(m.toast) },
                )
            }
        }
    }

    if (confirmDelete) {
        Dialog(onDismissRequest = { if (!deleting) confirmDelete = false }) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(Nb.R24)).background(Nb.Bg).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Delete ${v.name}?", style = ui(Nb.T16, 600))
                Text(
                    "This stops the machine and erases its system, apps, data and snapshots. It cannot be undone.",
                    style = ui(Nb.T14, 400, Nb.Text2).copy(lineHeight = Nb.T14 * 1.5),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(Nb.RPill)).background(Nb.Red)
                            .clickable(enabled = !deleting) {
                                deleting = true
                                vm.deleteMachine(v.id) {
                                    confirmDelete = false
                                    nav.popBackStack(Routes.Home, inclusive = false)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (deleting) "Deleting…" else "Delete", style = ui(Nb.T16, 600, Nb.Bg)) }
                    PillButton("Cancel") { if (!deleting) confirmDelete = false }
                }
            }
        }
    }
}

/** The guest's last frame, saved when it was minimized, with live memory and temperature. */
@Composable
private fun MachinePreview(vm: AppViewModel, id: String, res: String, booting: Boolean) {
    val frames by vm.frames.collectAsStateWithLifecycle()
    val stamp = frames[id] ?: 0L
    val file = vm.lastFrameFile(id)
    val bitmap = remember(stamp, file.lastModified()) {
        if (file.isFile) runCatching { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() else null
    }
    val health by remember(id) { vm.health(id) }.collectAsStateWithLifecycle(null)
    val (w, h) = res.split('×', 'x').map { it.trim().toFloatOrNull() ?: 1f }.let { (it.getOrElse(0) { 9f }) to (it.getOrElse(1) { 16f }) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.height(360.dp).aspectRatio(w / h).clip(RoundedCornerShape(Nb.R20)).background(Nb.BgDeep)
                .border(1.dp, Nb.Surface3, RoundedCornerShape(Nb.R20)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(if (booting) "starting…" else "guest screen", style = mono(Nb.T11, 400, Nb.Text4))
            }
        }
        val hh = health
        if (hh != null) {
            val ram = String.format(java.util.Locale.US, "%.1f GB", hh.ramBytes / 1e9)
            Text("RAM $ram · ${hh.tempC}°C", style = mono(Nb.T11, 400, Nb.Text3))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(Nb.R20)).background(Nb.Surface).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NbIcon(icon, 24.dp, Nb.Accent)
        Text(label, style = ui(Nb.T13, 500))
        Text(sub, style = mono(Nb.T10_5), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
