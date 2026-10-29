package app.nestbox.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.nestbox.engine.VmState
import app.nestbox.settings.NavButtons
import app.nestbox.ui.AppViewModel
import app.nestbox.ui.components.Bar
import app.nestbox.ui.components.NbIcon
import app.nestbox.ui.components.NbIcons
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono
import app.nestbox.ui.theme.ui
import app.nestbox.ui.toDetail
import kotlinx.coroutines.delay

/**
 * The machine in full screen, like VMOS: the guest fills the phone, with no Nestbox controls on top.
 * Phone back = guest back. Three-finger tap (or leaving the app) minimizes to the machine card,
 * which keeps the last screen with Enter and Power off.
 */
@Composable
fun RunScreen(vm: AppViewModel, nav: NavHostController, id: String) {
    val vms by vm.vms.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val v = vms.firstOrNull { it.id == id } ?: return
    val state = states[id] ?: VmState.Stopped
    val running = state == VmState.Running
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var leaving by remember { mutableStateOf(false) }

    // Minimize: keep the last frame for the machine card, then show the card.
    val minimize = minimize@{
        if (leaving) return@minimize
        leaving = true
        // Copy first: the display surface goes away as soon as this screen leaves.
        captureFrame(surfaceView) { bmp ->
            if (bmp != null) vm.saveLastFrame(id, bmp)
            vm.setVisible(id, false)
            nav.toDetail(id)
        }
    }

    ImmersiveMode()
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.setVisible(id, true) }
    // Leaving the app (home, recents) minimizes too, so coming back shows the card.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (running) minimize() }
    BackHandler {
        if (running) vm.sendKey(id, KeyEvent.KEYCODE_BACK) else minimize()
    }
    LaunchedEffect(running) {
        if (running && vm.firstFullScreen()) vm.showToast("Tap with three fingers to minimize")
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (vm.hasGuestDisplay) {
            GuestSurface(vm, id, running, onView = { surfaceView = it }, onThreeFingerTap = minimize)
        }
        if (!running) BootConsole(state, v.res, vm.hasGuestDisplay, onMinimize = minimize)
        if (running && !vm.hasGuestDisplay) DemoScreen(v.res)
        if (running && settings.navButtons != NavButtons.Hidden) {
            Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding()) {
                GuestNavBar(settings.navButtons) { vm.sendKey(id, it) }
            }
        }
    }
}

/** Hides the phone's status and navigation bars while the guest is on screen; swipe shows them briefly. */
@Composable
private fun ImmersiveMode() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Copies the guest's current frame (half size) for the machine card. */
private fun captureFrame(view: SurfaceView?, done: (Bitmap?) -> Unit) {
    if (view == null || view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return done(null)
    val bmp = Bitmap.createBitmap(view.width / 2, view.height / 2, Bitmap.Config.ARGB_8888)
    runCatching {
        PixelCopy.request(view, bmp, { r -> done(if (r == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
    }.onFailure { done(null) }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun GuestSurface(vm: AppViewModel, id: String, running: Boolean, onView: (SurfaceView) -> Unit, onThreeFingerTap: () -> Unit) {
    val live by rememberUpdatedState(running)
    val tap by rememberUpdatedState(onThreeFingerTap)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                onView(this)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = vm.attachSurface(id, h.surface, width, height)
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) = vm.attachSurface(id, h.surface, w, hgt)
                    override fun surfaceDestroyed(h: SurfaceHolder) = vm.attachSurface(id, null, 0, 0)
                })
                val gesture = ThreeFingerTap { tap() }
                setOnTouchListener { _, ev ->
                    gesture.onTouch(ev)
                    if (live) vm.sendInput(id, ev)
                    true
                }
            }
        },
    )
}

/** Three fingers down and up quickly without moving: Nestbox's minimize gesture. */
private class ThreeFingerTap(private val onTap: () -> Unit) {
    private var start = 0L
    private var armed = false
    private val x = FloatArray(3)
    private val y = FloatArray(3)

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> armed = false
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 3) {
                armed = true
                start = ev.eventTime
                for (i in 0 until 3) { x[i] = ev.getX(i); y[i] = ev.getY(i) }
            } else if (ev.pointerCount > 3) armed = false
            MotionEvent.ACTION_MOVE -> if (armed) {
                for (i in 0 until minOf(3, ev.pointerCount)) {
                    if (kotlin.math.abs(ev.getX(i) - x[i]) > 60 || kotlin.math.abs(ev.getY(i) - y[i]) > 60) armed = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (armed && ev.eventTime - start < 400) onTap()
                armed = false
            }
            MotionEvent.ACTION_CANCEL -> armed = false
        }
    }
}

@Composable
private fun BootConsole(state: VmState, res: String, overSurface: Boolean, onMinimize: () -> Unit) {
    val failed = state as? VmState.Failed
    val pct = (state as? VmState.Booting)?.pct ?: if (failed != null) 1f else 0f
    val lines = (state as? VmState.Booting)?.log ?: failed?.log.orEmpty()
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        1f, 0.25f, infiniteRepeatable(keyframes { durationMillis = 1000; 1f at 0; 0.25f at 500 }, RepeatMode.Restart),
        label = "blink",
    )
    Column(
        Modifier.fillMaxSize().background(if (overSurface) Nb.Console else Nb.Console).systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Nb.Touch).clip(RoundedCornerShape(Nb.RPill)).clickable(onClick = onMinimize), contentAlignment = Alignment.Center) {
                NbIcon(NbIcons.Back, 22.dp, Nb.Text2)
            }
            Text(if (failed != null) "Could not start" else "Starting · $res", style = mono(Nb.T12, 400, Nb.Text3))
        }
        val style = mono(Nb.T11, 400, Nb.ConsoleText).copy(lineHeight = Nb.T11 * 1.5)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
            lines.takeLast(34).forEach { Text(it, style = style) }
            if (failed == null) Text("▌", style = style, modifier = Modifier.alpha(if (blink > 0.6f) 1f else 0.25f))
        }
        if (failed != null) {
            Text(failed.message, style = ui(Nb.T13, 500, Nb.Red))
            failed.logsDir?.let { Text("Logs saved to $it", style = mono(Nb.T11, 400, Nb.Text3)) }
        } else {
            Row {
                Text("Starting container", style = mono(Nb.T11, 400, Nb.Text3), modifier = Modifier.weight(1f))
                Text("${(pct * 100).toInt()}%", style = mono(Nb.T11, 400, Nb.Text3))
            }
            Bar(pct, Nb.ConsoleTrack, Nb.Accent, 4.dp, 2.dp)
        }
    }
}

/** Demo engine (emulators): no real guest to show. */
@Composable
private fun DemoScreen(res: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Text("guest display", style = mono(Nb.T12))
        Text("$res · demo engine", style = mono(Nb.T11, 400, Nb.Text4))
    }
}

@Composable
private fun GuestNavBar(mode: NavButtons, onKey: (Int) -> Unit) {
    var shown by remember { mutableStateOf(mode == NavButtons.Always) }
    LaunchedEffect(mode, shown) {
        if (mode == NavButtons.AutoHide && shown) {
            delay(3000)
            shown = false
        }
    }
    when {
        mode == NavButtons.Always || shown -> Row(
            Modifier.fillMaxWidth().height(44.dp).background(Color.Black.copy(alpha = 0.35f)),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavKey(NbIcons.NavBack) { onKey(KeyEvent.KEYCODE_BACK) }
            NavKey(NbIcons.NavHome) { onKey(KeyEvent.KEYCODE_HOME) }
            NavKey(NbIcons.NavRecents) { onKey(KeyEvent.KEYCODE_APP_SWITCH) }
        }
        mode == NavButtons.AutoHide -> Box(Modifier.fillMaxWidth().height(20.dp).clickable { shown = true }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Nb.Text4))
        }
        else -> Unit
    }
}

@Composable
private fun NavKey(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(56.dp, 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { NbIcon(icon, 20.dp, Nb.Text2) }
}
