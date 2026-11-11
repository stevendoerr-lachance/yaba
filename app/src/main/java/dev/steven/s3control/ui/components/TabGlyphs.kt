package dev.steven.s3control.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.Destination
import dev.steven.s3control.ui.theme.S3

/**
 * A destination's symbol, drawn in dots on the top tube's grid: [TabGlyphs.ROWS] rows of [TabGlyphs.COLUMNS] cells,
 * `#` a dot, `.` none. Every glyph is centred on the same grid, so the cover screen's bar can stand them in one row
 * of equal boxes.
 */
class TabGlyph(vararg rows: String) {
    val rows: List<String> = rows.toList()

    fun lit(row: Int, column: Int): Boolean = rows[row][column] == '#'

    val dots: Int get() = rows.sumOf { r -> r.count { it == '#' } }

    private fun used(column: Int) = rows.any { it.getOrNull(column) == '#' }

    /**
     * The first and the last column that carries a dot. The inner screen's tabs draw only these, so every label stands
     * the same gap after its symbol, however narrow the symbol is.
     */
    val firstColumn: Int = (0 until TabGlyphs.COLUMNS).firstOrNull(::used) ?: 0
    val lastColumn: Int = (0 until TabGlyphs.COLUMNS).lastOrNull(::used) ?: (TabGlyphs.COLUMNS - 1)
    val usedColumns: Int get() = lastColumn - firstColumn + 1
}

object TabGlyphs {
    const val ROWS = 7
    const val COLUMNS = 11

    /** Fahren: the speedometer, open at the bottom, its needle up to the right. */
    val RIDE = TabGlyph(
        "...#####...",
        "..#.....#..",
        ".#.....#.#.",
        ".#....#..#.",
        ".#...#...#.",
        "..#.....#..",
        "...........",
    )

    /**
     * Klang: the bell, as an outline like the other three. The tube's own BELL (MatrixFont, solid, 5 wide) reads as a
     * little fir tree at this size and stands thin beside the bike.
     */
    val SOUND = TabGlyph(
        "....###....",
        "...#...#...",
        "...#...#...",
        "...#...#...",
        "..#.....#..",
        "..#######..",
        ".....#.....",
    )

    /** Diagnose: one beat of a pulse, the trace the live check and the ride logger draw. */
    val DIAGNOSIS = TabGlyph(
        "....#......",
        "....#......",
        "...#.#.....",
        "###..#..###",
        ".....#.#...",
        "......#....",
        "...........",
    )

    /**
     * Bike: the bicycle from the side, saddle left, handlebar right, the top tube between them. Wheels four dots
     * across with three empty columns between them: with five-dot wheels and a down tube the bike turned into a heavy
     * cluster at the inner screen's pitch (33 dots against the pulse's 15).
     */
    val BIKE = TabGlyph(
        ".##.....##.",
        "..#.....#..",
        "..######...",
        ".##.....##.",
        "#..#...#..#",
        "#..#...#..#",
        ".##.....##.",
    )

    /** Dot pitch (centre to centre) over the cover screen's labels: 22.75 dp high, the bike 35.75 dp wide. */
    val CoverPitch = 3.25.dp

    /** Dot pitch before the inner screen's labels: 17.5 dp high, about the old LED's 18 dp. */
    val InnerPitch = 2.5.dp

    /**
     * The inner screen's row, glyph before label: the space between two tabs, then the gap between a trimmed glyph and
     * its own label, always half of it. [InnerSpacing]/[InnerGap] first, [InnerSpacingCompact]/[InnerGapCompact] when
     * the labels would otherwise stand smaller than they did beside the old single LED (see [planInnerTabs]).
     */
    val InnerSpacing = 12.dp
    val InnerGap = 6.dp
    val InnerSpacingCompact = 8.dp
    val InnerGapCompact = 4.dp

    /** The old inner row: an 18 dp LED canvas before each label, 24 dp between tabs (16 dp on a narrow pane). */
    val LegacyLed = 18.dp
    val LegacySpacing = 24.dp
    val LegacySpacingTight = 16.dp
}

/** Dot radius in pitches: a quiet (inactive) glyph, a lit (active) one. */
private const val QuietDot = 0.38f
private const val LitDot = 0.41f

/** The glyph of [destination]. The `when` makes a new destination fail to compile until it has one. */
fun tabGlyph(destination: Destination): TabGlyph = when (destination) {
    Destination.RIDE -> TabGlyphs.RIDE
    Destination.SOUND -> TabGlyphs.SOUND
    Destination.DIAGNOSIS -> TabGlyphs.DIAGNOSIS
    Destination.BIKE -> TabGlyphs.BIKE
}

/** The width [glyph] takes at [pitch]: the whole grid, or with [trim] only its used columns. */
fun tabGlyphWidth(glyph: TabGlyph, pitch: Dp, trim: Boolean): Dp =
    pitch * (if (trim) glyph.usedColumns else TabGlyphs.COLUMNS)

/**
 * A tab's glyph. Inactive its dots stand in fog at r 0.38 p, a touch above the matrix's instrument size (0.34 p) so
 * the symbol holds at this small size. Active they light in led at the brand mark's fat cut (r 0.41 p) and, in the
 * dark theme, glow like the tube; the change runs 220 ms like [ShellLed], a cut under reduced motion. No ghost dots: four small matrices in
 * one bar would turn the bar into a pattern. Decorative for TalkBack, the tab itself says what it is.
 *
 * [trim] draws only the used columns (the inner screen's tabs, glyph before the label); otherwise the whole grid,
 * so glyphs of different widths stand centred in boxes of one size (the cover screen's bar).
 */
@Composable
fun ShellTabGlyph(
    glyph: TabGlyph,
    active: Boolean,
    modifier: Modifier = Modifier,
    pitch: Dp = TabGlyphs.CoverPitch,
    trim: Boolean = false,
) {
    val c = S3.colors
    val reduce = rememberReducedMotion()
    val level by animateFloatAsState(
        if (active) 1f else 0f,
        if (reduce) snap() else tween(220),
        label = "tab-glyph",
    )
    val from = if (trim) glyph.firstColumn else 0
    val to = if (trim) glyph.lastColumn else TabGlyphs.COLUMNS - 1
    val glow = if (c.isDark) c.ledGlow else Color.Transparent
    Canvas(
        modifier
            .size(tabGlyphWidth(glyph, pitch, trim), pitch * TabGlyphs.ROWS)
            .clearAndSetSemantics {},
    ) {
        val p = pitch.toPx()
        val r = p * (QuietDot + (LitDot - QuietDot) * level)
        val color = lerpColor(c.fog, c.led, level)
        for (row in 0 until TabGlyphs.ROWS) {
            for (col in from..to) {
                if (!glyph.lit(row, col)) continue
                val center = Offset(p * (col - from + 0.5f), p * (row + 0.5f))
                drawLedDot(center, r, color, glow, glowScale = level)
            }
        }
    }
}

/** One way to set the inner screen's inline row, in px: between two tabs ([spacing]), glyph to label ([gap]). */
class InnerTabsStep(val spacing: Int, val gap: Int)

/**
 * How the inner screen's tabs stand: every label at [size] sp, glyph over label if [stacked] (else glyph before label
 * with [spacing] and [gap], px), and whether the row [fits] (if not, it scrolls).
 */
class InnerTabsPlan(val size: Float, val stacked: Boolean, val fits: Boolean, val spacing: Int, val gap: Int)

/**
 * The inner screen's tab row, decided on measured widths alone. [widthsAt] gives every label's width in px at a font
 * size in sp; all other lengths are px. [glyphs] are the trimmed glyph widths in tab order, [alertAt] the Diagnose tab
 * (-1 if none), whose fault dot always keeps its room ([dotRoom]: after the label inline, on both sides of the centred
 * label stacked) so nothing moves when it comes.
 *
 * 1. Glyph before label, at the first of [steps] (loosest first) where the labels stand at least as large as they did
 *    beside the old single LED ([legacyFixed]: its LEDs and spacing) and at least [inlineFloor].
 * 2. Otherwise glyph over label, the row spread over the width with at least [stackedSpacing] between two tabs. A tab
 *    then needs its label (or 48 dp) instead of label plus LED, and the tabs stand closer, so for real labels this row
 *    needs less than the old one and keeps their old size. [InnerTabsPlan.fits] is false only when even [min] is too
 *    wide.
 *
 * Sizes step down 1 sp at a time from [base] to [min], like [shellFitLabels].
 */
fun planInnerTabs(
    widthsAt: (Float) -> List<Int>,
    base: Float,
    min: Float,
    available: Int,
    glyphs: List<Int>,
    alertAt: Int,
    dotRoom: Int,
    minTab: Int,
    steps: List<InnerTabsStep>,
    stackedSpacing: Int,
    legacyFixed: Int,
    inlineFloor: Float = 14f,
): InnerTabsPlan {
    val n = glyphs.size
    val (legacy, _) = largestFit(base, min) { size -> widthsAt(size).sum() + legacyFixed <= available }
    val floor = maxOf(legacy, inlineFloor)
    for (step in steps) {
        val (size, fits) = largestFit(base, min) { s ->
            val widths = widthsAt(s)
            val tabs = (0 until n).sumOf { i -> maxOf(glyphs[i] + step.gap + widths[i] + if (i == alertAt) dotRoom else 0, minTab) }
            tabs + step.spacing * (n - 1).coerceAtLeast(0) <= available
        }
        if (fits && size >= floor) return InnerTabsPlan(size, stacked = false, fits = true, spacing = step.spacing, gap = step.gap)
    }
    val (size, fits) = largestFit(base, min) { s ->
        val widths = widthsAt(s)
        val tabs = (0 until n).sumOf { i -> maxOf(widths[i] + if (i == alertAt) dotRoom * 2 else 0, glyphs[i], minTab) }
        tabs + stackedSpacing * (n - 1).coerceAtLeast(0) <= available
    }
    return InnerTabsPlan(size, stacked = true, fits = fits, spacing = stackedSpacing, gap = 0)
}

/** The largest size from [base] down to [min] in 1 sp steps that [fits], and whether any did (else the smallest). */
private fun largestFit(base: Float, min: Float, fits: (Float) -> Boolean): Pair<Float, Boolean> {
    var size = base
    while (true) {
        if (fits(size)) return size to true
        if (size - 1f < min) return size to false
        size -= 1f
    }
}
