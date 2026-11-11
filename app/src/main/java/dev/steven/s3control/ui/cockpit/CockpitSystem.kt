package dev.steven.s3control.ui.cockpit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.Window
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.ui.BlePermissions
import kotlinx.coroutines.delay

/*
 * What the cockpit asks of the phone while it is open, and gives back when it closes: the screen
 * stays on, the orientation holds still while riding, the status bar icons match the field behind
 * them, the volume keys set marks, and alerts vibrate as alarms.
 */

internal tailrec fun Context.cockpitActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.cockpitActivity()
    else -> null
}

/** Keeps the display on while the cockpit is composed (save, set, restore). */
@Composable
internal fun CockpitKeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

/**
 * Holds the current orientation while [moving] (a bump must not rotate the cockpit away), and gives
 * the previous request back once the bike stood for a few seconds or the cockpit closes. The lock
 * waits until the cockpit has been laid out and the window has settled: a lock requested in the
 * very frame the cockpit opens could catch the window mid-change.
 */
@Composable
internal fun CockpitOrientationLock(moving: Boolean) {
    val activity = LocalContext.current.cockpitActivity() ?: return
    // After a fold the recreated activity may still carry our own lock; that is never the one to restore.
    val saved = remember(activity) {
        activity.requestedOrientation.let { if (it == ActivityInfo.SCREEN_ORIENTATION_LOCKED) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else it }
    }
    var armed by remember(activity) { mutableStateOf(false) }
    LaunchedEffect(activity) {
        withFrameNanos { }
        withFrameNanos { }
        delay(ORIENTATION_SETTLE_MS)
        armed = true
    }
    LaunchedEffect(activity, moving, armed) {
        if (!armed) return@LaunchedEffect
        val wanted = if (moving) ActivityInfo.SCREEN_ORIENTATION_LOCKED else saved
        if (activity.requestedOrientation != wanted) activity.requestedOrientation = wanted
    }
    DisposableEffect(activity) {
        onDispose { if (activity.requestedOrientation != saved) activity.requestedOrientation = saved }
    }
}

private const val ORIENTATION_SETTLE_MS = 1_000L

/**
 * The safe drawing area per side, read from the window's own insets after every layout pass, so a
 * rotation, a fold or a bar that comes back always lays the cockpit out again with the real values.
 * Each new set is logged (tag "Cockpit") next to what Compose reports, to catch a stale reading.
 */
@Composable
internal fun rememberCockpitSafeArea(): PaddingValues {
    val view = LocalView.current
    val compose = WindowInsets.safeDrawing
    val density = LocalDensity.current
    var area by remember(view) { mutableStateOf<IntArray?>(null) }
    DisposableEffect(view) {
        fun read() {
            val insets = ViewCompat.getRootWindowInsets(view) ?: return
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            val next = intArrayOf(i.left, i.top, i.right, i.bottom)
            if (area?.contentEquals(next) != true) {
                val d = view.resources.displayMetrics.density
                val c = intArrayOf(
                    compose.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr), compose.getTop(density),
                    compose.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr), compose.getBottom(density),
                )
                Log.i(
                    "Cockpit",
                    "safe area dp left=${i.left / d} top=${i.top / d} right=${i.right / d} bottom=${i.bottom / d} " +
                        "(compose ${c.joinToString { (it / d).toString() }}) view=${view.width / d}x${view.height / d}",
                )
                area = next
            }
        }
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener { read() }
        observer.addOnGlobalLayoutListener(listener)
        read()
        onDispose {
            val o = if (observer.isAlive) observer else view.viewTreeObserver
            o.removeOnGlobalLayoutListener(listener)
        }
    }
    val a = area ?: return compose.asPaddingValues()
    return with(density) { PaddingValues(a[0].toDp(), a[1].toDp(), a[2].toDp(), a[3].toDp()) }
}

/**
 * Status and navigation bar icons that read on what is behind them: [lightField] means a light
 * background, so the icons turn dark. On close they follow the app theme ([appDark]) again.
 */
@Composable
internal fun CockpitSystemBars(lightField: Boolean, appDark: Boolean) {
    val activity = LocalContext.current.cockpitActivity() ?: return
    val view = LocalView.current
    val latestAppDark by rememberUpdatedState(appDark)
    LaunchedEffect(activity, view, lightField) {
        val c = WindowCompat.getInsetsController(activity.window, view)
        c.isAppearanceLightStatusBars = lightField
        c.isAppearanceLightNavigationBars = lightField
    }
    DisposableEffect(activity, view) {
        onDispose {
            val c = WindowCompat.getInsetsController(activity.window, view)
            c.isAppearanceLightStatusBars = !latestAppDark
            c.isAppearanceLightNavigationBars = !latestAppDark
        }
    }
}

/**
 * Volume up or down, pressed and released within 800 ms, sets a mark. Both keys are consumed while
 * the cockpit is open, so the volume does not change. A mount that clamps a key down never marks:
 * the release comes too late, and repeats are ignored.
 */
@Composable
internal fun CockpitVolumeKeys(onMark: () -> Unit) {
    val activity = LocalContext.current.cockpitActivity() ?: return
    val mark by rememberUpdatedState(onMark)
    DisposableEffect(activity) {
        val window = activity.window
        val original: Window.Callback = window.callback ?: return@DisposableEffect onDispose { }
        var downAt = -1L
        val wrapper = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val code = event.keyCode
                if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
                    return original.dispatchKeyEvent(event)
                }
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) downAt = event.eventTime
                    KeyEvent.ACTION_UP -> {
                        val held = event.eventTime - downAt
                        if (!event.isCanceled && downAt >= 0 && held in 0 until VOLUME_TAP_MS) mark()
                        downAt = -1
                    }
                }
                return true
            }
        }
        window.callback = wrapper
        onDispose { if (window.callback === wrapper) window.callback = original }
    }
}

private const val VOLUME_TAP_MS = 800L

/** Alarm-class vibration through the system vibrator, so it is felt even through a mount. */
internal class CockpitVibrator(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun play(kind: HapticKind) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val strong = if (v.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE
        val effect = when (kind) {
            HapticKind.TAKEOVER_LONG -> waveform(longArrayOf(0, 600, 200, 600, 200, 600), strong)
            HapticKind.TAKEOVER_KICK -> waveform(longArrayOf(0, 150, 100, 150, 100, 600), strong)
            HapticKind.SIGNAL -> waveform(longArrayOf(0, 200, 150, 200), strong)
            HapticKind.LAMP -> waveform(longArrayOf(0, 120), strong)
            // Two firm pulses: felt through a mount and gloves, and unlike any alert pattern.
            HapticKind.MARK -> waveform(longArrayOf(0, 140, 110, 140), strong)
        }
        // Alarm class for all of them: touch feedback can be switched off, a mark confirmation cannot.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
    }

    /** Timings alternate off/on; every "on" segment gets [amplitude]. */
    private fun waveform(timings: LongArray, amplitude: Int): VibrationEffect {
        val amps = IntArray(timings.size) { i -> if (i % 2 == 1) amplitude else 0 }
        return VibrationEffect.createWaveform(timings, amps, -1)
    }
}

@Composable
internal fun rememberCockpitVibrator(): CockpitVibrator {
    val context = LocalContext.current
    return remember(context) { CockpitVibrator(context.applicationContext) }
}

/**
 * Plays each new [cue] once. A cue older than a few seconds was already felt before the screen was
 * recreated (fold, rotation), so it stays silent.
 */
@Composable
internal fun CockpitHaptics(cue: HapticCue?) {
    val vibrator = rememberCockpitVibrator()
    LaunchedEffect(cue?.seq) {
        val c = cue ?: return@LaunchedEffect
        if (System.currentTimeMillis() - c.atMillis <= 2_500) vibrator.play(c.kind)
    }
}

/**
 * The shared "start the ride logger" flow (Diagnose and cockpit). With [mayAsk], Android 13+ first
 * asks for notifications, while the phone is still in the hand; the logger starts either way, and
 * [then] runs after it. Without [mayAsk] nothing is asked. A failed start is kept in
 * [AppViewModel.cockpitLoggerError] for whoever shows it.
 */
class RideLoggerStarter internal constructor(private val launch: (mayAsk: Boolean, then: () -> Unit) -> Unit) {
    fun start(mayAsk: Boolean = true, then: () -> Unit = {}) = launch(mayAsk, then)
}

@Composable
fun rememberRideLoggerStarter(vm: AppViewModel): RideLoggerStarter {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.startRideLoggerReporting()
        pending?.invoke()
        pending = null
    }
    val latestVm by rememberUpdatedState(vm)
    return remember(launcher, context) {
        RideLoggerStarter { mayAsk, then ->
            val needsAsk = Build.VERSION.SDK_INT >= 33 && !BlePermissions.notificationsGranted(context)
            if (mayAsk && needsAsk) {
                pending = then
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure {
                    pending = null
                    latestVm.startRideLoggerReporting()
                    then()
                }
            } else {
                latestVm.startRideLoggerReporting()
                then()
            }
        }
    }
}

/** Ticks [onTick] once a second while [active]; the caller decides what moves. */
@Composable
internal fun CockpitSecondTicker(active: Boolean, onTick: () -> Unit) {
    val tick by rememberUpdatedState(onTick)
    LaunchedEffect(active) {
        while (active) {
            delay(1_000 - System.currentTimeMillis() % 1_000)
            tick()
        }
    }
}
