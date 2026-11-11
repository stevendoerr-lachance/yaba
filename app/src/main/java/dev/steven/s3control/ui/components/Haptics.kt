package dev.steven.s3control.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

class Haptics(private val view: View) {
    fun tick() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    fun confirm() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
    )

    fun reject() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    fun thud() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

    /** One step of a rising rumble (the build-up before a strike); a segment tick where the system has one. */
    fun ramp() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
    )
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
