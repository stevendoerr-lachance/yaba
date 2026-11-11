package dev.steven.s3control.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.steven.s3control.ui.theme.S3
import kotlin.math.max

/**
 * A frame for the top-tube dot matrix: one 7-bit mask per column, bit 0 = top row.
 */
class MatrixFrame(val columns: IntArray) {
    val width: Int get() = columns.size

    operator fun plus(other: MatrixFrame) = MatrixFrame(columns + other.columns)

    companion object {
        val Empty = MatrixFrame(IntArray(0))
        fun gap(n: Int) = MatrixFrame(IntArray(n))
        fun text(text: String) = MatrixFrame(MatrixFont.render(text))

        /** A horizontal level bar, [width] columns wide, filled to [fraction]. */
        fun bar(fraction: Float, width: Int): MatrixFrame {
            val filled = (fraction.coerceIn(0f, 1f) * width).toInt()
            return MatrixFrame(IntArray(width) { i ->
                when {
                    // end caps, a frame top and bottom, and a fill three dots high with air around it
                    i == 0 || i == width - 1 -> 0b1111111
                    i < filled -> 0b1011101
                    else -> 0b1000001
                }
            })
        }
    }
}

object MatrixFont {
    const val ROWS = 7

    private val glyphs: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to arrayOf(".#.", "##.", ".#.", ".#.", ".#.", ".#.", "###"),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        'A' to arrayOf(".###.", "#...#", "#...#", "#...#", "#####", "#...#", "#...#"),
        'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to arrayOf("###..", "#..#.", "#...#", "#...#", "#...#", "#..#.", "###.."),
        'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to arrayOf("###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"),
        'J' to arrayOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to arrayOf("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
        'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to arrayOf("#...#", "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#.."),
        'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        'Ä' to arrayOf("#...#", ".###.", "#...#", "#...#", "#####", "#...#", "#...#"),
        'Ö' to arrayOf("#...#", ".###.", "#...#", "#...#", "#...#", "#...#", ".###."),
        'Ü' to arrayOf("#...#", ".....", "#...#", "#...#", "#...#", "#...#", ".###."),
        // The only accent in the font, drawn for the Dutch call "HÈ HÈ" (without it the word reads as a giggle).
        'È' to arrayOf(".#...", "#####", "#....", "####.", "#....", "#....", "#####"),
        '%' to arrayOf("##...", "##..#", "...#.", "..#..", ".#...", "#..##", "...##"),
        '-' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '.' to arrayOf(".", ".", ".", ".", ".", "#", "#"),
        ':' to arrayOf(".", "#", "#", ".", "#", "#", "."),
        '/' to arrayOf(".....", "....#", "...#.", "..#..", ".#...", "#....", "....."),
        '!' to arrayOf("#", "#", "#", "#", "#", ".", "#"),
        '?' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
        '+' to arrayOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        // Private-use glyphs for states the bike itself shows.
        LOCKED to arrayOf("..###..", ".#...#.", ".#...#.", "#######", "###.###", "###.###", "#######"),
        UNLOCKED to arrayOf("..###..", ".#...#.", ".#.....", "#######", "###.###", "###.###", "#######"),
        BOLT to arrayOf("...#.", "..#..", ".#...", "#####", "...#.", "..#..", ".#..."),
        BELL to arrayOf("..#..", ".###.", ".###.", ".###.", "#####", ".....", "..#.."),
        BLE to arrayOf("..#..", "#.##.", ".##.#", "..#..", ".##.#", "#.##.", "..#.."),
    )

    const val LOCKED = ''
    const val UNLOCKED = ''
    const val BOLT = ''
    const val BELL = ''
    const val BLE = ''

    fun render(text: String): IntArray {
        val out = ArrayList<Int>()
        text.uppercase().forEachIndexed { index, ch ->
            if (index > 0) out += 0
            if (ch == ' ') {
                out += 0; out += 0
                return@forEachIndexed
            }
            val rows = glyphs[ch] ?: glyphs['?']!!
            val w = rows[0].length
            for (x in 0 until w) {
                var mask = 0
                for (y in 0 until ROWS) if (rows[y][x] == '#') mask = mask or (1 shl y)
                out += mask
            }
        }
        return out.toIntArray()
    }
}

/**
 * How the dots look right now, as four lambdas that are read only in the draw phase, so animating
 * them never recomposes anything. [dot] scales the lit dot (1.0 = r 0.34 p, 1.206 = r 0.41 p, the
 * heavy cut of the brand mark), [glow] scales the glow, [lit] scales the brightness of the lit dots,
 * [shift] moves the whole field by whole columns. Ghost dots keep their size. [Calm] changes nothing.
 */
@Stable
class DotLook(
    val dot: () -> Float = { 1f },
    val glow: () -> Float = { 1f },
    val lit: () -> Float = { 1f },
    val shift: () -> Int = { 0 },
) {
    companion object {
        val Calm = DotLook()
    }
}

/**
 * The S3 top-tube display. Changing [frame] sweeps the new content in from the left,
 * the way the bike's own matrix redraws. The first frame boots from dark.
 *
 * [sweepMillis] 0 is a hard cut; [sweepEasing] shapes the sweep; [look] animates dot size, glow,
 * brightness and a column shift (see [DotLook]). The defaults leave every existing call unchanged.
 */
@Composable
fun MatrixDisplay(
    frame: MatrixFrame,
    modifier: Modifier = Modifier,
    columns: Int = 36,
    lit: Color = S3.colors.led,
    unlit: Color = S3.colors.ledOff,
    glow: Color = S3.colors.ledGlow,
    description: String? = null,
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
    // The marquee State is read inside the Canvas, so scrolling redraws without recomposing.
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

    Canvas(
        modifier
            .aspectRatio(columns / MatrixFont.ROWS.toFloat())
            .semantics { if (description != null) contentDescription = description },
    ) {
        // The look is read here, in the draw phase, so animating it never recomposes.
        drawMatrixField(
            shown = shown,
            previous = previous,
            sweepCol = sweep.value * (columns + 2),
            sweeping = sweep.value < 1f,
            columns = columns,
            origin = Offset.Zero,
            pitch = size.width / columns,
            lit = lit,
            unlit = unlit,
            glow = glow,
            dot = look.dot(),
            glowScale = look.glow(),
            litScale = look.lit(),
            shiftCols = look.shift(),
            marquee = marqueeState?.value ?: 0f,
        )
    }
}

@Composable
fun MatrixDisplay(
    text: String,
    modifier: Modifier = Modifier,
    columns: Int = 36,
    lit: Color = S3.colors.led,
    unlit: Color = S3.colors.ledOff,
    glow: Color = S3.colors.ledGlow,
) = MatrixDisplay(
    frame = remember(text) { MatrixFrame.text(text) },
    modifier = modifier,
    columns = columns,
    lit = lit,
    unlit = unlit,
    glow = glow,
    description = text,
)

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
