package dev.steven.s3control.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.theme.S3
import kotlin.math.max

/*
 * The top tube and its matrix exactly as they were before they were split into draw functions (TubeDraw.kt),
 * copied from the revision before the split. The parity test draws both and demands the same pixels.
 */

@Composable
internal fun LegacyMatrixDisplay(
    frame: MatrixFrame,
    modifier: Modifier = Modifier,
    columns: Int = 36,
    lit: Color = S3.colors.led,
    unlit: Color = S3.colors.ledOff,
    glow: Color = S3.colors.ledGlow,
    sweepMillis: Int = 520,
    sweepEasing: Easing = FastOutSlowInEasing,
    look: DotLook = DotLook.Calm,
) {
    val reduceMotion = rememberReducedMotion()
    var shown by remember { mutableStateOf(MatrixFrame.Empty) }
    var previous by remember { mutableStateOf(MatrixFrame.Empty) }
    val sweep = remember { Animatable(1f) }

    LaunchedEffect(frame.columns.contentHashCode()) {
        previous = shown
        shown = frame
        if (reduceMotion || sweepMillis == 0) {
            sweep.snapTo(1f)
        } else {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(durationMillis = sweepMillis, easing = sweepEasing))
        }
    }

    val overflow = max(0, shown.width - columns)
    val marqueeState = if (overflow > 0 && !reduceMotion) {
        val t = rememberInfiniteTransition(label = "matrix-marquee")
        t.animateFloat(
            initialValue = 0f,
            targetValue = overflow.toFloat(),
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 180 * overflow + 1200, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "matrix-marquee-offset",
        )
    } else null

    Canvas(modifier.aspectRatio(columns / MatrixFont.ROWS.toFloat())) {
        val pitch = size.width / columns
        val r = pitch * 0.34f * look.dot()
        val ghost = pitch * 0.34f * 0.78f
        val glowScale = look.glow()
        val litScale = look.lit()
        val shiftCols = look.shift()
        val marquee = marqueeState?.value ?: 0f
        val newStart = ((columns - shown.width) / 2).coerceAtLeast(0) - marquee.toInt() + shiftCols
        val oldStart = ((columns - previous.width) / 2).coerceAtLeast(0) + shiftCols
        val sweepCol = sweep.value * (columns + 2)
        val litNow = lit.copy(alpha = lit.alpha * litScale)
        val litLeading = lit.copy(alpha = 0.35f * litScale)

        for (c in 0 until columns) {
            val useNew = c < sweepCol
            val f = if (useNew) shown else previous
            val start = if (useNew) newStart else oldStart
            val mask = f.columns.getOrElse(c - start) { 0 }
            val leading = useNew && c >= sweepCol - 1.5f && sweep.value < 1f
            for (y in 0 until MatrixFont.ROWS) {
                val on = mask and (1 shl y) != 0
                val center = Offset(pitch * (c + 0.5f), pitch * (y + 0.5f))
                if (on || leading) {
                    if (glow.alpha > 0f && glowScale > 0f) {
                        val a = glow.alpha * glowScale
                        drawCircle(glow.copy(alpha = (a * 0.45f).coerceAtMost(1f)), r * 2.3f, center)
                        drawCircle(glow.copy(alpha = a.coerceAtMost(1f)), r * 1.55f, center)
                    }
                    drawCircle(if (on) litNow else litLeading, r, center)
                } else {
                    drawCircle(unlit, ghost, center)
                }
            }
        }
    }
}

@Composable
internal fun LegacyBikeTube(
    frame: MatrixFrame,
    lightsOn: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = 40,
    lit: Color = S3.colors.led,
    glow: Color = S3.colors.ledGlow,
    sweepMillis: Int = 520,
    sweepEasing: Easing = FastOutSlowInEasing,
    look: DotLook = DotLook.Calm,
) {
    val c = S3.colors
    val lamp by animateFloatAsState(if (lightsOn) 1f else 0f, tween(600), label = "lamp")
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50))) {
        Canvas(Modifier.matchParentSize()) {
            val h = size.height
            val r = h / 2f
            drawRoundRect(c.frame, cornerRadius = CornerRadius(r, r))
            drawRoundRect(c.rib, cornerRadius = CornerRadius(r, r), style = Stroke(width = 1.dp.toPx()))
            val barW = 4.dp.toPx()
            val barH = h * 0.42f
            val rear = lerpColor(c.rib, c.signal, lamp)
            val head = lerpColor(c.rib, if (c.isDark) Color.White else c.lamp, lamp)
            if (lamp > 0f && c.isDark) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(c.signal.copy(alpha = 0.32f * lamp), Color.Transparent),
                        center = Offset(r * 0.55f, h / 2), radius = r * 1.1f,
                    ),
                    radius = r * 1.1f, center = Offset(r * 0.55f, h / 2),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.22f * lamp), Color.Transparent),
                        center = Offset(size.width - r * 0.55f, h / 2), radius = r * 1.2f,
                    ),
                    radius = r * 1.2f, center = Offset(size.width - r * 0.55f, h / 2),
                )
            }
            drawRoundRect(rear, Offset(r * 0.55f - barW / 2, (h - barH) / 2), Size(barW, barH), CornerRadius(barW, barW))
            drawRoundRect(head, Offset(size.width - r * 0.55f - barW / 2, (h - barH) / 2), Size(barW, barH), CornerRadius(barW, barW))
        }
        LegacyMatrixDisplay(
            frame = frame,
            columns = columns,
            lit = lit,
            glow = glow,
            sweepMillis = sweepMillis,
            sweepEasing = sweepEasing,
            look = look,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 18.dp),
        )
    }
}
