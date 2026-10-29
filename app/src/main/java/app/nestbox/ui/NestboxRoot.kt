package app.nestbox.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.nestbox.ui.components.ToastCard
import app.nestbox.ui.screens.DetailScreen
import app.nestbox.ui.screens.HomeScreen
import app.nestbox.ui.screens.RunScreen
import app.nestbox.ui.screens.SettingsScreen
import app.nestbox.ui.screens.SetupScreen
import app.nestbox.ui.screens.SpoofScreen
import app.nestbox.ui.screens.TransferScreen
import app.nestbox.ui.screens.WizardScreen
import app.nestbox.ui.theme.Nb

object Routes {
    const val Setup = "setup"
    const val Home = "home"
    const val Settings = "settings"
    const val Wizard = "wizard"
    fun detail(id: String) = "detail/$id"
    fun spoof(id: String) = "spoof/$id"
    fun transfer(id: String) = "transfer/$id"
    fun run(id: String) = "run/$id"
}

/** Goes back to the machine's detail screen, removing the screens above it. */
fun NavHostController.toDetail(id: String) {
    if (!popBackStack(Routes.detail(id), inclusive = false)) {
        navigate(Routes.detail(id)) { popUpTo(Routes.Home) }
    }
}

@Composable
fun NestboxRoot(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val openVm by vm.openVm.collectAsStateWithLifecycle()
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                UiEvent.AskNotifications ->
                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                UiEvent.AskStorage -> askNotifications.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
    LaunchedEffect(openVm) {
        val id = openVm ?: return@LaunchedEffect
        vm.openVm.value = null
        if (nav.currentDestination?.route != "run/{id}" || nav.currentBackStackEntry?.arguments?.getString("id") != id) {
            nav.navigate(Routes.run(id)) { popUpTo(Routes.Home) }
        }
    }
    Box(Modifier.fillMaxSize().background(Nb.Bg)) {
        NavHost(nav, startDestination = if (vm.setupFinished) Routes.Home else Routes.Setup) {
            composable(Routes.Setup) { SetupScreen(vm, nav) }
            composable(Routes.Home) { HomeScreen(vm, nav) }
            composable(Routes.Settings) { SettingsScreen(vm, nav) }
            composable(Routes.Wizard) { WizardScreen(vm, nav) }
            composable("detail/{id}") { DetailScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
            composable("spoof/{id}") { SpoofScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
            composable("transfer/{id}") { TransferScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
            composable("run/{id}") { RunScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
        }
        toast?.let {
            ToastCard(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp))
        }
    }
}
