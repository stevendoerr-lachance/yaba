package dev.steven.s3control.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.steven.s3control.R
import dev.steven.s3control.ui.theme.S3

/** Four stations: sign in, Bluetooth access, find the bike, first unlock. The last one is the slider itself and has no rail. */
internal const val RAIL_STEPS = 4

/**
 * The rail (brand motif 5, the one thing every step may lose first): a capsule of the top tube's kind, 10 dp high inside a
 * 20 dp row, with a lit knob at the station of this step. No dots, no stepper chips, no numbers. The step change sweeps from
 * the left, so the old knob stands to the right of the sweep's edge and the new one to the left: it moves in one stroke.
 */
@Composable
internal fun Rail(n: Int, modifier: Modifier = Modifier) {
    val c = S3.colors
    val description = stringResource(R.string.onb_step_cd, n, RAIL_STEPS)
    Canvas(
        modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth()
            .height(20.dp)
            .semantics(mergeDescendants = true) { stateDescription = description },
    ) {
        val barH = 10.dp.toPx()
        val top = (size.height - barH) / 2f
        val radius = barH / 2f
        drawRoundRect(c.frame, Offset(0f, top), Size(size.width, barH), CornerRadius(radius, radius))
        // The track needs a line the eye can find: rib on a dark ground, ink-toned (fog) in Daylight, where frame on asphalt is only
        // 1.18 : 1 and rib on it 1.2 : 1.
        val line = if (c.isDark) c.rib else c.fog
        drawRoundRect(line, Offset(0f, top), Size(size.width, barH), CornerRadius(radius, radius), style = Stroke(1.dp.toPx()))
        val inset = 5.dp.toPx()
        val x = inset + (n - 0.5f) / RAIL_STEPS * (size.width - 2 * inset)
        drawCircle(c.led, 4.dp.toPx(), Offset(x, size.height / 2f))
    }
}
