package app.nestbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.components.BackBar
import app.nestbox.ui.components.CardRows
import app.nestbox.ui.components.ChoiceChip
import app.nestbox.ui.components.InfoRow
import app.nestbox.ui.components.KeyValueRow
import app.nestbox.ui.components.ListCard
import app.nestbox.ui.components.NbIcon
import app.nestbox.ui.components.NbIcons
import app.nestbox.ui.components.NbToggle
import app.nestbox.ui.components.PrimaryButton
import app.nestbox.ui.components.SectionLabel
import app.nestbox.vm.Catalog
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpoofScreen(vm: AppViewModel, nav: NavHostController, id: String) {
    val vms by vm.vms.collectAsStateWithLifecycle()
    val v = vms.firstOrNull { it.id == id } ?: return
    val sp by vm.spoofDraft.collectAsStateWithLifecycle()
    val rg = Catalog.region(sp.region)
    val place = Catalog.place(sp.place)
    val edit = { change: (app.nestbox.vm.SpoofProfile) -> app.nestbox.vm.SpoofProfile -> vm.spoofDraft.value = change(vm.spoofDraft.value) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BackBar(title = "Spoof profile", trailing = v.name) { nav.popBackStack() }
        // Map placeholder (phase 6: real map with long-press to drop a pin)
        Box(
            Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(Nb.R24)).background(Nb.MapBg)
                .drawBehind {
                    val step = 24.dp.toPx()
                    var y = step - 1f
                    while (y < size.height) { drawLine(Nb.MapLine, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()); y += step }
                    var x = step - 1f
                    while (x < size.width) { drawLine(Nb.MapLine, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx()); x += step }
                },
        ) {
            Box(Modifier.align(Alignment.Center).offset(y = (-20).dp)) { NbIcon(NbIcons.Pin, 40.dp, Nb.Accent) }
            Text(
                place.coords, style = mono(Nb.T11_5, 400, Nb.Text),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(10.dp))
                    .background(Nb.CoordsChip).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "map · long-press to drop pin", style = mono(Nb.T10_5, 400, Nb.Text4),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Location")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Catalog.places.forEach { p -> ChoiceChip(p.name, p.name == sp.place) { edit { it.copy(place = p.name) } } }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Region · SIM · timezone")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Catalog.regions.forEach { r ->
                    ChoiceChip(r.code, r.code == sp.region, isMono = true, squared = true) { edit { it.copy(region = r.code) } }
                }
            }
            CardRows(listOf("Carrier" to rg.carrier, "MCC / MNC" to rg.mcc, "Timezone" to rg.tz, "Locale" to rg.locale)) { (k, value) ->
                KeyValueRow(k, value, kSize = Nb.T13, vSize = Nb.T12_5)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Device identity")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Catalog.devices.forEach { d -> ChoiceChip(d, d == sp.device) { edit { it.copy(device = d) } } }
            }
        }
        ListCard {
            InfoRow(
                NbIcons.Gamepad, "Joystick in floating ball", "Walk the pin while apps are open", iconTint = Nb.Text3,
                padV = 14.dp, onClick = { edit { it.copy(joystick = !it.joystick) } },
            ) { NbToggle(sp.joystick) }
        }
        PrimaryButton("Apply to machine") {
            vm.applySpoof(v.id)
            nav.popBackStack()
        }
    }
}
