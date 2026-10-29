package app.nestbox.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.components.CardRows
import app.nestbox.ui.components.BackBar
import app.nestbox.ui.components.InfoRow
import app.nestbox.ui.components.ListCard
import app.nestbox.ui.components.NbIcon
import app.nestbox.ui.components.NbIcons
import app.nestbox.ui.components.NbToggle
import app.nestbox.ui.components.PillButton
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono
import app.nestbox.ui.theme.ui
import java.util.Locale

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    else -> "${(bytes / 1e6).toInt().coerceAtLeast(1)} MB"
}

@Composable
fun TransferScreen(vm: AppViewModel, nav: NavHostController, id: String) {
    val vms by vm.vms.collectAsStateWithLifecycle()
    val v = vms.firstOrNull { it.id == id } ?: return
    val tab by vm.transferTab.collectAsStateWithLifecycle()
    val apps by vm.hostApps.collectAsStateWithLifecycle()
    val copied by vm.copied.collectAsStateWithLifecycle()
    val clip by vm.clipboardSync.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installApkFrom(v.id, uri)
    }
    LaunchedEffect(Unit) { vm.loadHostApps() }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BackBar(title = "Transfer", trailing = v.name) { nav.popBackStack() }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Nb.RPill)).background(Nb.Surface).padding(4.dp)) {
            listOf("apps" to "Apps", "files" to "Files").forEach { (k, label) ->
                val on = tab == k
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(Nb.RPill))
                        .background(if (on) Nb.SegmentActive else Nb.Surface).clickable { vm.transferTab.value = k },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = ui(Nb.T14, 500, if (on) Nb.Text else Nb.Text3)) }
            }
        }
        if (tab == "apps") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Copies the installed APK into the machine. App data stays on the phone.",
                    style = ui(Nb.T12_5, 400, Nb.Text3).copy(lineHeight = Nb.T12_5 * 1.45),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                if (apps.isNotEmpty()) CardRows(apps) { a ->
                    val done = (v.id + a.pkg) in copied
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val icon = a.icon
                        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                        else Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Nb.Surface3))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(a.name, style = ui(Nb.T14, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sizeText(a.sizeBytes), style = mono(Nb.T11))
                        }
                        if (done) PillButton("Copied", Nb.Surface, Nb.Accent) {}
                        else PillButton("Copy") { vm.copyApp(v.id, a) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .drawBehind {
                            drawRoundRect(
                                Nb.Outline, cornerRadius = CornerRadius(20.dp.toPx()),
                                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                            )
                        }
                        .clip(RoundedCornerShape(Nb.R20))
                        .clickable { picker.launch(arrayOf("application/vnd.android.package-archive")) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NbIcon(NbIcons.UploadFile, 20.dp, Nb.Text2)
                    Text("Install APK from storage", style = ui(Nb.T14, 500, Nb.Text2))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Nb.R20)).background(Nb.Surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Shared folder", style = ui(Nb.T12, 400, Nb.Text3))
                    Text("Phone  /sdcard/Nestbox", style = mono(Nb.T13, 400, Nb.Text))
                    Text("Guest  /sdcard/Host", style = mono(Nb.T13, 400, Nb.Text))
                }
                CardRows(vm.sharedFiles) { f ->
                    val icon = when (f.icon) { "image" -> NbIcons.Image; "zip" -> NbIcons.Zip; else -> NbIcons.Text }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NbIcon(icon, 20.dp, Nb.Text3)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(f.name, style = ui(Nb.T14), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.meta, style = mono(Nb.T11))
                        }
                    }
                }
                ListCard {
                    InfoRow(
                        NbIcons.Paste, "Clipboard sync", "Copy on the phone, paste in the machine", padV = 14.dp,
                        onClick = { vm.clipboardSync.value = !clip },
                    ) { NbToggle(clip) }
                }
            }
        }
    }
}
