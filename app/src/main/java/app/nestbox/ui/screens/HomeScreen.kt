package app.nestbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.Routes
import app.nestbox.ui.components.Bar
import app.nestbox.ui.components.IconBtn
import app.nestbox.ui.components.NbIcon
import app.nestbox.ui.components.NbIcons
import app.nestbox.ui.components.PillButton
import app.nestbox.ui.components.StatusDot
import app.nestbox.ui.components.Tag
import app.nestbox.ui.components.stripes
import app.nestbox.vm.Catalog
import app.nestbox.vm.VmConfig
import app.nestbox.vm.VmStatus
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono
import app.nestbox.ui.theme.ui
import java.util.Locale

fun statusLabel(s: VmStatus) = when (s) {
    VmStatus.Running -> "Running"
    VmStatus.Booting -> "Starting"
    VmStatus.Killed -> "Stopped by system"
    VmStatus.Stopped -> "Powered off"
}

fun statusColor(s: VmStatus) = when (s) {
    VmStatus.Running -> Nb.Accent
    VmStatus.Booting -> Nb.Amber
    VmStatus.Killed -> Nb.Amber
    VmStatus.Stopped -> Nb.PoweredOff
}

/** "2 min ago", "3 h ago", "2 days ago". */
fun agoText(ms: Long?): String {
    if (ms == null) return "earlier"
    val min = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(1)
    return when {
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

fun chipsFor(v: VmConfig) = listOfNotNull(
    "ROOT".takeIf { v.root },
    "LSPOSED".takeIf { v.xposed },
    "GMS".takeIf { v.gms },
    v.spoof.region.takeIf { it != "Off" },
    Catalog.preset(v.preset).name.uppercase(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, nav: NavHostController) {
    val vms by vm.vms.collectAsStateWithLifecycle()
    vm.states.collectAsStateWithLifecycle().value // recompose on engine state changes
    val temp by vm.hostTemp.collectAsStateWithLifecycle()
    val running = vms.filter { vm.status(it) == VmStatus.Running || vm.status(it) == VmStatus.Booting }
    val ram by vm.ram.collectAsStateWithLifecycle()
    // Real use (PSS) of the machines that run now.
    val ramUsed = running.sumOf { (ram[it.id] ?: 0L) / 1e9 }
    val frames by vm.frames.collectAsStateWithLifecycle()
    val total = vm.host.ramGb
    val killed = vms.firstOrNull { vm.status(it) == VmStatus.Killed }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Machines", style = ui(Nb.T24, 600), modifier = Modifier.weight(1f))
                IconBtn(NbIcons.Settings, Nb.Text3) { nav.navigate(Routes.Settings) }
                IconBtn(NbIcons.Tune, Nb.Text3) { nav.navigate(Routes.Setup) }
            }
            // Host card
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Nb.R24)).background(Nb.Surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${vm.host.deviceName} · ${vm.host.osName}", style = ui(Nb.T15, 500))
                        Text("${vm.host.socName} · $total GB", style = mono(Nb.T11_5))
                    }
                    Row(
                        Modifier.height(28.dp).clip(RoundedCornerShape(Nb.RPill)).background(Nb.Surface2).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NbIcon(NbIcons.Thermostat, 16.dp, Nb.Text2)
                        Text("$temp°C", style = mono(Nb.T11, 400, Nb.Text2))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text("Memory used by machines", style = ui(Nb.T12, 400, Nb.Text3), modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.1f GB / %d GB", ramUsed, total), style = mono(Nb.T12, 400, Nb.Text))
                    }
                    Bar((ramUsed / total).toFloat(), Nb.Surface3, Nb.Accent, 6.dp, 3.dp)
                }
            }
            // Killed banner
            if (killed != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Nb.R20)).background(Nb.BannerBg)
                        .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NbIcon(NbIcons.Warning, 22.dp, Nb.BannerFg)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${killed.name} was stopped by HyperOS", style = ui(Nb.T14, 500))
                        Text("Last state saved ${agoText(killed.lastSeenMs)}", style = ui(Nb.T12, 400, Nb.BannerSub))
                    }
                    PillButton("Resume", Nb.BannerFg, Nb.BannerOnFg, 600) {
                        vm.start(killed.id)
                        nav.navigate(Routes.run(killed.id))
                    }
                }
            }
            // Machine cards
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                vms.forEach { v ->
                    val st = vm.status(v)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Nb.R24)).background(Nb.Surface)
                            .clickable { nav.navigate(Routes.detail(v.id)) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // Last screen of the machine, saved when it was minimized.
                        val frame = vm.lastFrameFile(v.id)
                        val thumb = remember(frames[v.id], frame.lastModified()) {
                            if (frame.isFile) runCatching {
                                android.graphics.BitmapFactory.decodeFile(frame.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
                                    ?.asImageBitmap()
                            }.getOrNull() else null
                        }
                        Box(
                            Modifier.size(44.dp, 60.dp).clip(RoundedCornerShape(Nb.R8))
                                .border(1.dp, Nb.Outline, RoundedCornerShape(Nb.R8)).stripes(Nb.Stripe1, Nb.Stripe2, 4.dp)
                        ) {
                            if (thumb != null) androidx.compose.foundation.Image(
                                thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(v.name, style = ui(Nb.T16, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusDot(statusColor(st))
                                Text(statusLabel(st), style = ui(Nb.T12, 400, Nb.Text2))
                                Text("·", style = ui(Nb.T12, 400, Nb.PoweredOff))
                                Text(Catalog.rom(v.romId).short, style = mono(Nb.T11))
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                chipsFor(v).forEach { Tag(it) }
                            }
                        }
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Nb.Surface3).clickable {
                                vm.start(v.id)
                                nav.navigate(Routes.run(v.id))
                            },
                            contentAlignment = Alignment.Center,
                        ) { NbIcon(if (st == VmStatus.Running) NbIcons.OpenInFull else NbIcons.Play, 22.dp) }
                    }
                }
            }
        }
        // FAB
        Row(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
                .shadow(24.dp, RoundedCornerShape(Nb.R24), ambientColor = Nb.Bg, spotColor = Nb.Bg)
                .height(56.dp).clip(RoundedCornerShape(Nb.R24)).background(Nb.Accent)
                .clickable {
                    vm.resetWizard()
                    nav.navigate(Routes.Wizard)
                }
                .padding(start = 18.dp, end = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NbIcon(NbIcons.Add, 24.dp, Nb.OnAccent)
            Text("New machine", style = ui(Nb.T15, 600, Nb.OnAccent))
        }
    }
}
