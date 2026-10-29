package app.nestbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.nestbox.service.Notifications
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.NestboxRoot
import app.nestbox.ui.theme.NestboxTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            NestboxTheme { NestboxRoot(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A tap on a machine's notification opens its display. */
    private fun handle(intent: Intent?) {
        intent?.getStringExtra(Notifications.EXTRA_OPEN_VM)?.let { vm.openVm.value = it }
    }
}
