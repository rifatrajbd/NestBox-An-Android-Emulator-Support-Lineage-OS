package app.nestbox.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.Routes
import app.nestbox.ui.WizardDraft
import app.nestbox.ui.components.BackBar
import app.nestbox.ui.components.CardRows
import app.nestbox.ui.components.ChoiceChip
import app.nestbox.ui.components.InfoRow
import app.nestbox.ui.components.KeyValueRow
import app.nestbox.ui.components.NbIcon
import app.nestbox.ui.components.NbIcons
import app.nestbox.ui.components.NbToggle
import app.nestbox.ui.components.PrimaryButton
import app.nestbox.ui.components.SelectCard
import app.nestbox.ui.components.Tag
import app.nestbox.vm.Catalog
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono
import app.nestbox.ui.theme.ui

private val titles = listOf("Choose a system", "Performance", "Features", "Review")

private data class WizToggle(val label: String, val sub: String, val on: (WizardDraft) -> Boolean, val flip: (WizardDraft) -> WizardDraft)

private val wizToggles = listOf(
    WizToggle("Root (Magisk)", "su inside the machine · phone stays unrooted", { it.root }, { it.copy(root = !it.root) }),
    WizToggle("LSPosed", "Xposed modules for the guest apps", { it.xposed }, { it.copy(xposed = !it.xposed) }),
    WizToggle("Google services", "Adds about 400 MB", { it.gms }, { it.copy(gms = !it.gms) }),
    WizToggle("Shared folder", "/sdcard/Nestbox ↔ /sdcard/Host", { it.shared }, { it.copy(shared = !it.shared) }),
    WizToggle("Run with screen off", "Keeps AFK apps alive", { it.keepAlive }, { it.copy(keepAlive = !it.keepAlive) }),
)

fun tagColor(tag: String) = when (tag) {
    "STABLE" -> Nb.Green
    "BETA" -> Nb.BetaTag
    else -> Nb.ChipText
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WizardScreen(vm: AppViewModel, nav: NavHostController) {
    val step by vm.wizardStep.collectAsStateWithLifecycle()
    val d by vm.draft.collectAsStateWithLifecycle()
    val back = { if (step == 0) nav.popBackStack() else vm.wizardStep.value = step - 1 }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setDraftImport(uri)
    }
    val needsFile = Catalog.rom(d.rom).source == Catalog.Source.Import && d.importUri == null
    BackHandler(enabled = step > 0) { vm.wizardStep.value = step - 1 }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BackBar(if (step == 0) NbIcons.Close else NbIcons.Back, "New machine", "${step + 1} / 4", trailingSize = Nb.T12) { back() }
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= step) Nb.Accent else Nb.Surface3))
            }
        }
        Text(titles[step], style = ui(Nb.T24, 600), modifier = Modifier.padding(start = 4.dp, top = 4.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                0 -> {
                    Catalog.romsFor(d.engine).forEach { r ->
                        val sel = r.id == d.rom
                        val isImport = r.source == Catalog.Source.Import
                        SelectCard(sel, {
                            vm.editDraft { it.copy(rom = r.id) }
                            if (isImport) importPicker.launch(arrayOf("*/*"))
                        }) {
                            NbIcon(NbIcons.rom(r.id), 22.dp, if (sel) Nb.Accent else Nb.Text3)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(r.name, style = ui(Nb.T15, 500))
                                    Tag(r.tag, tagColor(r.tag), small = true)
                                }
                                Text(if (isImport && sel && d.importName != null) d.importName!! else r.sub, style = mono(Nb.T11))
                            }
                        }
                    }
                }

                1 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Name", style = ui(Nb.T12, 400, Nb.Text3))
                        BasicTextField(
                            value = d.name,
                            onValueChange = { n -> vm.editDraft { it.copy(name = n) } },
                            singleLine = true,
                            textStyle = ui(Nb.T15),
                            cursorBrush = SolidColor(Nb.Accent),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                Box(
                                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Nb.R12)).background(Nb.Surface)
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) { inner() }
                            },
                        )
                    }
                    Catalog.presets.forEach { p ->
                        val sel = p.id == d.preset
                        SelectCard(sel, { vm.editDraft { it.copy(preset = p.id) } }) {
                            NbIcon(NbIcons.preset(p.id), 22.dp, if (sel) Nb.Accent else Nb.Text3)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(p.name, style = ui(Nb.T15, 500))
                                Text(p.sub, style = mono(Nb.T11))
                            }
                        }
                    }
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Resolution", style = ui(Nb.T12, 400, Nb.Text3))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Catalog.resolutions.forEach { r ->
                                ChoiceChip(r, r == d.res, isMono = true) { vm.editDraft { it.copy(res = r) } }
                            }
                        }
                    }
                    Text(
                        "Limits are caps, not reservations. The machine shares the phone's kernel and only uses what it needs.",
                        style = ui(Nb.T12, 400, Nb.Text3).copy(lineHeight = Nb.T12 * 1.45),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                2 -> CardRows(wizToggles) { t ->
                    InfoRow(null, t.label, t.sub, titleSize = Nb.T15, padV = 14.dp, onClick = { vm.editDraft(t.flip) }) {
                        NbToggle(t.on(d))
                    }
                }

                else -> {
                    val rom = Catalog.rom(d.rom)
                    val p = Catalog.preset(d.preset)
                    val extras = listOfNotNull("Root".takeIf { d.root }, "LSPosed".takeIf { d.xposed }, "GMS".takeIf { d.gms }, "Share".takeIf { d.shared })
                    CardRows(
                        listOf(
                            "Name" to d.name, "System" to rom.name, "Preset" to p.name,
                            "Limits" to "${p.cores} cores · ${p.ramGb} GB", "Display" to "${d.res} · ${p.fps} fps",
                            "Extras" to (extras.joinToString(" · ").ifEmpty { "None" }),
                        )
                    ) { (k, v) -> KeyValueRow(k, v) }
                    Text(
                        "The system extracts once (${rom.size}). First boot takes about a minute, later boots a few seconds.",
                        style = ui(Nb.T12, 400, Nb.Text3).copy(lineHeight = Nb.T12 * 1.5),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        }
        PrimaryButton(if (step == 3) "Create and start" else "Next") {
            if (step == 0 && needsFile) importPicker.launch(arrayOf("*/*"))
            else if (step < 3) vm.wizardStep.value = step + 1
            else {
                val id = vm.createFromDraft()
                nav.navigate(Routes.run(id)) { popUpTo(Routes.Home) }
            }
        }
    }
}
