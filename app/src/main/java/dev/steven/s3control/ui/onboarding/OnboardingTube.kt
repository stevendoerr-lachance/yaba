package dev.steven.s3control.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.components.OutburstStage
import dev.steven.s3control.ui.components.TubeDefaults
import dev.steven.s3control.ui.components.drawLedDot
import dev.steven.s3control.ui.components.OutburstTube
import dev.steven.s3control.ui.theme.LocalS3Palette
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Palette
import kotlin.math.roundToInt

/**
 * How the matrix of an onboarding tube is coloured: the call is always led; a state may be traffic red (a fault); [Calm] is
 * the closed lock of the first unlock: fog (not a fault), brightening to led as the slider travels ([OnboardingTube]'s `mix`).
 */
internal enum class TubeTone { Led, Signal, Calm }

/** The tube of every onboarding step has the 36 columns a call needs (30 and 36 columns for the search word). */
internal const val TUBE_COLUMNS = 36

/**
 * The height of a [dev.steven.s3control.ui.components.BikeTube] that is [widthPx] wide: it pads its matrix 40 dp at the
 * sides and 18 dp above and below, and the matrix is 36 columns by 7 rows. The frame needs it before the tube exists.
 */
internal fun tubeHeightPx(widthPx: Int, density: Density): Int = with(density) {
    val matrixW = widthPx - 2 * 40.dp.toPx()
    (matrixW * 7f / TUBE_COLUMNS + 2 * 18.dp.toPx()).roundToInt()
}

/**
 * The one loud element of a step: the top tube with the stage of its call. Its description for TalkBack is the title of the
 * step, never the Dutch word. [tone] only counts while the state is on the tube; a call is always led. For [TubeTone.Calm]
 * the dots are [mix] of the way from fog to led (0..1, in steps of 1/20, so a drag does not rebuild the tube every pixel).
 *
 * [ripple] (read in the draw phase, 0..1, null = none) sends two dots out of the glyph along the middle row to both ends of the
 * matrix: the answer of the tube to the lock that has just opened.
 */
@Composable
internal fun OnboardingTube(
    stage: OutburstStage,
    tone: TubeTone,
    description: String,
    modifier: Modifier = Modifier,
    mix: Float = 0f,
    ripple: (() -> Float)? = null,
) {
    val c = S3.colors
    val effective = if (stage.showingState) tone else TubeTone.Led
    val q = (mix.coerceIn(0f, 1f) * 20f).roundToInt() / 20f
    val palette: S3Palette = remember(c, effective, q) {
        when (effective) {
            TubeTone.Led -> c
            TubeTone.Signal -> c.copy(led = c.signal, ledGlow = if (c.isDark) c.signal.copy(alpha = 0.33f) else Color.Transparent)
            TubeTone.Calm -> c.copy(
                led = lerp(c.fog, c.led, q),
                ledGlow = c.ledGlow.copy(alpha = c.ledGlow.alpha * q),
            )
        }
    }
    CompositionLocalProvider(LocalS3Palette provides palette) {
        if (ripple == null) {
            OutburstTube(stage, lightsOn = false, description = description, modifier = modifier, columns = TUBE_COLUMNS)
        } else {
            Box(modifier) {
                OutburstTube(stage, lightsOn = false, description = description, modifier = Modifier, columns = TUBE_COLUMNS)
                Canvas(Modifier.matchParentSize()) { drawRipple(palette, ripple()) }
            }
        }
    }
}

/** Two dots (head and tail) go out from the glyph in the middle, left and right, along the middle row, [p] 0..1. */
private fun DrawScope.drawRipple(c: S3Palette, p: Float) {
    if (p <= 0f || p >= 1f) return
    val padX = TubeDefaults.PadX.toPx()
    val padY = TubeDefaults.PadY.toPx()
    val pitch = (size.width - 2f * padX) / TUBE_COLUMNS
    val cy = padY + pitch * 3.5f
    // the open lock is 7 columns wide, in 14..20 of 36: the pulses start at its two edges
    val head = (p * 15f).toInt()
    val r = pitch * 0.34f * 1.2f
    for (side in intArrayOf(-1, 1)) {
        for (k in 0..1) {
            val col = if (side > 0) 21 + head - k else 14 - head + k
            if (col < 0 || col >= TUBE_COLUMNS) continue
            val x = padX + pitch * (col + 0.5f)
            val tone = if (k == 0) 1f else 0.5f
            // a lit dot over the ghost dot of the cell (the ghost stays under it, as in the matrix)
            drawLedDot(Offset(x, cy), r, c.led.copy(alpha = c.led.alpha * tone), c.ledGlow, tone)
        }
    }
}
