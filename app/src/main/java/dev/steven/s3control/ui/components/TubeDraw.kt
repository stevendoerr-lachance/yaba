package dev.steven.s3control.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.theme.S3Palette

/*
 * The top tube and its dot field as draw functions, so that the screens ([BikeTube], [MatrixDisplay]) and
 * the opening film draw the very same pixels. The Brush of the light spill lives here and nowhere else.
 */

object TubeDefaults {
    /** The dot field sits this far inside the capsule, left and right. */
    val PadX = 40.dp
    val PadY = 18.dp
    val BarWidth = 4.dp
    const val Columns = 36
}

/**
 * The capsule (frame fill, rib stroke), the two light bars (rear lerps rib to signal, head rib to white on
 * night / lamp on day) and, on a dark palette while [lamp] > 0, a soft radial spill of both lights. The matrix
 * is not part of it, see [drawMatrixField].
 *
 * [clip] clips everything to the capsule: [BikeTube] does that with its own Box, anything that draws the tube
 * into an arbitrary rectangle (the film) asks for it.
 */
fun DrawScope.drawTubeBody(
    c: S3Palette,
    rect: Rect,
    lamp: Float,
    barWidthPx: Float,
    strokePx: Float,
    clip: Boolean = false,
) {
    val h = rect.height
    val r = h / 2f
    val corner = CornerRadius(r, r)
    val body: DrawScope.() -> Unit = {
        drawRoundRect(c.frame, rect.topLeft, rect.size, corner)
        drawRoundRect(c.rib, rect.topLeft, rect.size, corner, style = Stroke(width = strokePx))
        // rear light (left) and head light (right): short bars at the tube ends
        val barW = barWidthPx
        val barH = h * 0.42f
        val rear = lerpColor(c.rib, c.signal, lamp)
        val head = lerpColor(c.rib, if (c.isDark) Color.White else c.lamp, lamp)
        val midY = rect.top + h / 2f
        if (lamp > 0f && c.isDark) {
            // Soft spill of the lights onto the tube ends.
            val rearAt = Offset(rect.left + r * 0.55f, midY)
            val headAt = Offset(rect.right - r * 0.55f, midY)
            drawCircle(
                Brush.radialGradient(
                    listOf(c.signal.copy(alpha = 0.32f * lamp), Color.Transparent),
                    center = rearAt, radius = r * 1.1f,
                ),
                radius = r * 1.1f, center = rearAt,
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.22f * lamp), Color.Transparent),
                    center = headAt, radius = r * 1.2f,
                ),
                radius = r * 1.2f, center = headAt,
            )
        }
        drawRoundRect(rear, Offset(rect.left + r * 0.55f - barW / 2, rect.top + (h - barH) / 2), Size(barW, barH), CornerRadius(barW, barW))
        drawRoundRect(head, Offset(rect.right - r * 0.55f - barW / 2, rect.top + (h - barH) / 2), Size(barW, barH), CornerRadius(barW, barW))
    }
    if (clip) {
        clipPath(Path().apply { addRoundRect(RoundRect(rect, corner)) }) { body() }
    } else {
        body()
    }
}

/** One lit dot with its two glow circles (the glow only where [glow] has alpha and [glowScale] is above 0). */
fun DrawScope.drawLedDot(center: Offset, r: Float, color: Color, glow: Color, glowScale: Float = 1f) {
    if (glow.alpha > 0f && glowScale > 0f) {
        val a = glow.alpha * glowScale
        drawCircle(glow.copy(alpha = (a * 0.45f).coerceAtMost(1f)), r * 2.3f, center)
        drawCircle(glow.copy(alpha = a.coerceAtMost(1f)), r * 1.55f, center)
    }
    drawCircle(color, r, center)
}

/**
 * The dot field of the matrix: [columns] × 7 cells of size [pitch] starting at [origin]. [shown] is what has been
 * swept in up to column [sweepCol], [previous] stands to the right of it; [sweeping] adds the dim leading edge.
 * [dot] scales the lit dot (1.0 = r 0.34 p), [glowScale] the glow, [litScale] the brightness, [shiftCols]
 * moves the field by whole columns, [marquee] scrolls an over-long frame. Ghost dots keep their size.
 */
fun DrawScope.drawMatrixField(
    shown: MatrixFrame,
    previous: MatrixFrame,
    sweepCol: Float,
    sweeping: Boolean,
    columns: Int,
    origin: Offset,
    pitch: Float,
    lit: Color,
    unlit: Color,
    glow: Color,
    dot: Float = 1f,
    glowScale: Float = 1f,
    litScale: Float = 1f,
    shiftCols: Int = 0,
    marquee: Float = 0f,
) {
    // A ghost dot keeps pitch * 0.34 * 0.78 whatever the look does.
    val r = pitch * 0.34f * dot
    val ghost = pitch * 0.34f * 0.78f
    val newStart = ((columns - shown.width) / 2).coerceAtLeast(0) - marquee.toInt() + shiftCols
    val oldStart = ((columns - previous.width) / 2).coerceAtLeast(0) + shiftCols
    val litNow = lit.copy(alpha = lit.alpha * litScale)
    val litLeading = lit.copy(alpha = 0.35f * litScale)

    for (c in 0 until columns) {
        val useNew = c < sweepCol
        val f = if (useNew) shown else previous
        val start = if (useNew) newStart else oldStart
        val mask = f.columns.getOrElse(c - start) { 0 }
        val leading = useNew && c >= sweepCol - 1.5f && sweeping
        for (y in 0 until MatrixFont.ROWS) {
            val on = mask and (1 shl y) != 0
            val center = Offset(origin.x + pitch * (c + 0.5f), origin.y + pitch * (y + 0.5f))
            if (on || leading) {
                drawLedDot(center, r, if (on) litNow else litLeading, glow, glowScale)
            } else {
                drawCircle(unlit, ghost, center)
            }
        }
    }
}
