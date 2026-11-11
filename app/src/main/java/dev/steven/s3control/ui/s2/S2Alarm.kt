package dev.steven.s3control.ui.s2

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The wiggle test's signal when the speed drops to 0 while the wheel spins: a firm double buzz and two short beeps,
 * so the rider notices it with both hands on the cable and the phone on the floor. The beep uses the alarm stream,
 * which a silent ring mode does not mute.
 */
internal class DropAlarm(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var tone: ToneGenerator? = null

    fun fire() {
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 90, 260), -1)) }
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, VOLUME).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }

    private companion object {
        /** Of ToneGenerator's 0..100. */
        const val VOLUME = 85
    }
}

@Composable
internal fun rememberDropAlarm(): DropAlarm {
    val context = LocalContext.current
    val alarm = remember(context) { DropAlarm(context.applicationContext) }
    DisposableEffect(alarm) { onDispose { alarm.release() } }
    return alarm
}
