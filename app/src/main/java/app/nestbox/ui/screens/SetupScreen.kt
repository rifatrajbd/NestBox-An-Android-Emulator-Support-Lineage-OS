package app.nestbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.nestbox.adb.PairState
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.Routes
import app.nestbox.ui.components.NbIcon
import app.nestbox.ui.components.NbIcons
import app.nestbox.ui.components.PillButton
import app.nestbox.ui.components.PrimaryButton
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono
import app.nestbox.ui.theme.ui

private data class Check(val key: String?, val icon: ImageVector, val title: String, val sub: String)

@Composable
fun SetupScreen(vm: AppViewModel, nav: NavHostController) {
    val done by vm.setupDone.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    // Coming back from a HyperOS settings screen: read the checks again.
    LifecycleResumeEffect(Unit) {
        vm.refreshSetup()
        onPauseOrDispose { }
    }
    val engineOk = vm.host.isArm64
    val checks = listOf(
        Check(null, NbIcons.Memory, "Container engine",
            if (engineOk) "Supported · arm64 · ${vm.host.osVersion}" else "Not supported · needs arm64"),
        Check("autostart", NbIcons.RestartAlt, "Autostart", "Security › Permissions › Autostart"),
        Check("battery", NbIcons.BatterySaver, "Battery: No restrictions", "App info › Battery saver"),
        Check("recents", NbIcons.Lock, "Lock in Recents", "Long-press Nestbox card › Lock"),
        Check("popup", NbIcons.Pip, "Floating windows", "Other permissions › Display pop-up"),
        // Approved addition: HyperOS kills guest processes past 32 unless this limit is off.
        Check("processes", NbIcons.DeveloperMode, "Background process limit", "Developer options › Wireless debugging"),
    )
    val isDone = { c: Check -> if (c.key == null) engineOk else done[c.key] == true }
    val allDone = checks.all(isDone)
    val fix = { key: String ->
        when (key) {
            "recents", "processes" -> dialog = key
            else -> vm.openFix(key)
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NESTBOX · XIAOMI SETUP", style = mono(Nb.T12, 400, Nb.Accent).copy(letterSpacing = Nb.T12 * 0.08))
                Text("Prepare this phone", style = ui(Nb.T28, 600).copy(lineHeight = Nb.T28 * 1.15))
                Text(
                    "HyperOS closes background apps aggressively. These settings keep virtual machines running.",
                    style = ui(Nb.T14, 400, Nb.Text3).copy(lineHeight = Nb.T14 * 1.5),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                checks.forEach { c ->
                    val ok = isDone(c)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Nb.R20)).background(Nb.Surface)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        NbIcon(c.icon, 22.dp, Nb.Text3)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(c.title, style = ui(Nb.T15, 500))
                            Text(if (ok && c.key != null) "Allowed" else c.sub, style = mono(Nb.T11))
                        }
                        if (ok) NbIcon(NbIcons.CheckCircle, 22.dp, Nb.Accent)
                        else if (c.key != null) PillButton("Fix") { fix(c.key) }
                    }
                }
            }
        }
        PrimaryButton(if (allDone) "Continue" else "Fix all") {
            if (allDone) {
                vm.finishSetup()
                if (!nav.popBackStack()) nav.navigate(Routes.Home) { popUpTo(Routes.Setup) { inclusive = true } }
            } else {
                // One HyperOS screen at a time: open the next step that is not done.
                checks.firstOrNull { it.key != null && !isDone(it) }?.key?.let(fix)
            }
        }
    }

    when (dialog) {
        "recents" -> SetupDialog(
            title = "Lock in Recents",
            body = "Open Recents, long-press the Nestbox card, then tap the lock. HyperOS will not clear a locked app.",
            confirm = "Done",
            onConfirm = { vm.confirmFix("recents"); dialog = null },
            onDismiss = { dialog = null },
        )
        "processes" -> PairingDialog(vm) { dialog = null }
    }
}

@Composable
private fun SetupDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(Nb.R24)).background(Nb.Bg).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = ui(Nb.T16, 600))
            Text(body, style = ui(Nb.T14, 400, Nb.Text2).copy(lineHeight = Nb.T14 * 1.5))
            PrimaryButton(confirm, onClick = onConfirm)
        }
    }
}

/** Wireless debugging pairing: the code can be typed here (split screen) or in the notification. */
@Composable
private fun PairingDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val state by vm.pairState.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(Nb.R24)).background(Nb.Bg).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Background process limit", style = ui(Nb.T16, 600))
            Text(
                "A machine runs more than 32 processes, and HyperOS stops apps past that. Nestbox turns the limit off through Wireless debugging on this phone. No PC needed.",
                style = ui(Nb.T14, 400, Nb.Text2).copy(lineHeight = Nb.T14 * 1.5),
            )
            listOf(
                "Connect to Wi-Fi.",
                "In Wireless debugging, turn it on and tap Pair device with pairing code.",
                "Type the 6-digit code in the Nestbox notification, or here in split screen.",
            ).forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${i + 1}", style = mono(Nb.T13, 500, Nb.Accent))
                    Text(step, style = ui(Nb.T13, 400, Nb.Text2).copy(lineHeight = Nb.T13 * 1.45))
                }
            }
            val status = when (val s = state) {
                PairState.Idle -> null
                PairState.Searching -> "Waiting for the pairing screen…"
                is PairState.Ready -> "Pairing screen found · port ${s.port}"
                is PairState.Working -> s.step + "…"
                PairState.Done -> "Done · the limit is off"
                is PairState.Failed -> s.message
            }
            if (status != null) {
                Text(
                    status,
                    style = mono(Nb.T12, 400, when (state) {
                        is PairState.Failed -> Nb.Red
                        PairState.Done -> Nb.Green
                        else -> Nb.Text3
                    }),
                )
            }
            when (state) {
                PairState.Done -> PrimaryButton("Close", onClick = onDismiss)
                is PairState.Ready, is PairState.Failed -> {
                    BasicTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        singleLine = true,
                        textStyle = mono(Nb.T16, 500, Nb.Text),
                        cursorBrush = SolidColor(Nb.Accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Nb.R12)).background(Nb.Surface)
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (code.isEmpty()) Text("Pairing code", style = mono(Nb.T14, 400, Nb.Text4))
                                inner()
                            }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Pair", Modifier.weight(1f)) { if (code.length == 6) vm.submitPairingCode(code) }
                        PillButton("Open settings") { vm.startPairing() }
                    }
                }
                is PairState.Working -> Unit
                else -> PrimaryButton("Open Wireless debugging") { vm.startPairing() }
            }
        }
    }
}
