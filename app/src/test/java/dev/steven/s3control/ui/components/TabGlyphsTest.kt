package dev.steven.s3control.ui.components

import dev.steven.s3control.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The tab glyphs ([TabGlyphs], [tabGlyph]): one per destination, all on the one 7-row grid, distinct, and each a real
 * symbol rather than a speck or a filled block.
 */
class TabGlyphsTest {
    private val all = Destination.entries.map { it to tabGlyph(it) }

    @Test
    fun everyDestinationHasAGlyph() {
        assertEquals(Destination.entries.size, all.size)
        all.forEach { (d, g) -> assertTrue("$d has no dots", g.dots > 0) }
    }

    @Test
    fun allGlyphsShareOneGrid() {
        all.forEach { (d, g) ->
            assertEquals("$d rows", TabGlyphs.ROWS, g.rows.size)
            g.rows.forEachIndexed { i, row ->
                assertEquals("$d row $i width", TabGlyphs.COLUMNS, row.length)
                assertTrue("$d row $i has only # and .", row.all { it == '#' || it == '.' })
            }
        }
    }

    @Test
    fun noTwoGlyphsAreAlike() {
        val distinct = all.map { it.second.rows }.toSet()
        assertEquals(all.size, distinct.size)
    }

    @Test
    fun eachGlyphHasASensibleNumberOfDots() {
        val cells = TabGlyphs.ROWS * TabGlyphs.COLUMNS
        all.forEach { (d, g) ->
            assertTrue("$d has only ${g.dots} dots", g.dots >= 12)
            assertTrue("$d has ${g.dots} of $cells dots, a block", g.dots <= cells / 2)
        }
    }

    @Test
    fun noGlyphWeighsMoreThanTwiceTheLightest() {
        // A heavy glyph reads darker than the others even unlit, and at the inner pitch as a cluster, not a symbol.
        val light = all.minOf { it.second.dots }
        all.forEach { (d, g) -> assertTrue("$d has ${g.dots} dots, the lightest $light", g.dots <= light * 2) }
    }

    @Test
    fun eachGlyphUsesMostOfTheHeightAndSitsCentred() {
        all.forEach { (d, g) ->
            val usedRows = g.rows.count { '#' in it }
            assertTrue("$d uses only $usedRows rows", usedRows >= TabGlyphs.ROWS - 1)
            assertTrue("$d does not start in the top row", '#' in g.rows.first())
            // Centred on the grid (to one column), so the cover bar's equal boxes stand each glyph over its label's middle.
            val left = g.firstColumn
            val right = TabGlyphs.COLUMNS - 1 - g.lastColumn
            assertTrue("$d is off centre: $left left, $right right", kotlin.math.abs(left - right) <= 1)
        }
    }

    @Test
    fun trimmedWidthIsTheUsedColumns() {
        assertEquals(9, TabGlyphs.RIDE.usedColumns)
        assertEquals(7, TabGlyphs.SOUND.usedColumns)
        assertEquals(11, TabGlyphs.DIAGNOSIS.usedColumns)
        assertEquals(11, TabGlyphs.BIKE.usedColumns)
        assertEquals(1, TabGlyphs.RIDE.firstColumn)
        assertEquals(9, TabGlyphs.RIDE.lastColumn)
    }

    // --- The inner screen's row (planInnerTabs) ----------------------------------------------------------------

    /**
     * Px per sp of each label at 2.75 px/dp: Fahren, Klang, Diagnose, Bike in German (Fahren and Diagnose as measured on
     * the emulator, 138 and 190 px at 16 sp; Klang and Bike in proportion), and a longer set like Russian.
     */
    private val german = floatArrayOf(8.6f, 7.4f, 11.9f, 5.9f)
    private val russian = floatArrayOf(11.2f, 7.0f, 15.6f, 10.4f)
    private val glyphs = listOf(62, 48, 76, 76)
    private val normal = InnerTabsStep(spacing = 33, gap = 17)
    private val compact = InnerTabsStep(spacing = 22, gap = 11)
    private val legacyFixed = 396 // 4 × 18 dp + 3 × 24 dp

    private fun widthsAt(perSp: FloatArray): (Float) -> List<Int> = { size -> perSp.map { (it * size).roundToInt() } }

    private fun plan(perSp: FloatArray, available: Int, steps: List<InnerTabsStep> = listOf(normal, compact)) = planInnerTabs(
        widthsAt = widthsAt(perSp),
        base = 16f,
        min = 12f,
        available = available,
        glyphs = glyphs,
        alertAt = 2,
        dotRoom = 25,
        minTab = 132,
        steps = steps,
        stackedSpacing = compact.spacing,
        legacyFixed = legacyFixed,
    )

    /** The size the old row (an LED before each label) reached, worked out on its own. */
    private fun legacySize(perSp: FloatArray, available: Int): Float {
        var size = 16f
        while (size > 12f && widthsAt(perSp)(size).sum() + legacyFixed > available) size -= 1f
        return size
    }

    @Test
    fun theOwnersInnerScreenKeepsTheOldLabelSize() {
        // The right pane on the Find N6's inner screen: 350 dp of row. The loose row would need 15 sp, the compact one
        // keeps the 16 sp the old LEDs had.
        val p = plan(german, available = 962)
        assertEquals(16f, p.size)
        assertFalse(p.stacked)
        assertEquals(compact.spacing, p.spacing)
        assertEquals(compact.gap, p.gap)
    }

    @Test
    fun roomEnoughKeepsTheLooseRow() {
        val p = plan(german, available = 1100)
        assertEquals(16f, p.size)
        assertFalse(p.stacked)
        assertEquals(normal.spacing, p.spacing)
    }

    @Test
    fun labelsNeverStandSmallerThanBesideTheOldLeds() {
        for (labels in listOf(german, russian)) {
            for (available in 500..1400 step 7) {
                val p = plan(labels, available)
                val old = legacySize(labels, available)
                assertTrue("at $available px: ${p.size} sp, the old row had $old sp", p.size >= old)
                if (!p.stacked) {
                    assertTrue("inline below 14 sp at $available px", p.size >= 14f)
                    val w = widthsAt(labels)(p.size)
                    val row = w.indices.sumOf { i -> maxOf(glyphs[i] + p.gap + w[i] + if (i == 2) 25 else 0, 132) } + p.spacing * 3
                    assertTrue("inline row of $row px in $available px", row <= available)
                }
            }
        }
    }
}
