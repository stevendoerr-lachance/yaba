package dev.steven.s3control.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The labels of the Schalten axis ([shiftAxisLabels]): the limit's label keeps 8 dp clear on both sides of every other label,
 * measured on the bounds as drawn (kept inside the canvas), not between centres. Widths are px at density 1 (1 px = 1 dp):
 * a digit of the caption is about 7 dp wide at font scale 1.0 and 9.1 dp at 1.3.
 */
class ShiftAxisTest {
    private val gap = 8f

    private fun widths(perChar: Float): (String) -> Float = { it.length * perChar }

    private fun texts(labels: List<ShiftAxisLabel>) = labels.map { it.text }

    private val kmh: (Int) -> String = { "$it km/h" }

    /** The cover's profile stack: the axis spans the whole 320 dp canvas. */
    private fun cover(limit: Int?, perChar: Float) =
        shiftAxisLabels(limit, x0 = 0f, axisWidth = 320f, canvasWidth = 320f, gap = gap, withUnit = kmh, widthOf = widths(perChar))

    /** The editor's own axis: inset 16 dp on both sides, as its strip is. */
    private fun editor(limit: Int?, perChar: Float) =
        shiftAxisLabels(limit, x0 = 16f, axisWidth = 288f, canvasWidth = 320f, gap = gap, withUnit = kmh, widthOf = widths(perChar))

    /** The inner screen's wide rows: a 96 dp name column before the strip. */
    private fun wide(limit: Int?, perChar: Float) =
        shiftAxisLabels(limit, x0 = 96f, axisWidth = 304f, canvasWidth = 400f, gap = gap, withUnit = kmh, widthOf = widths(perChar))

    private fun assertClear(labels: List<ShiftAxisLabel>, canvasWidth: Float) {
        val limit = labels.singleOrNull { it.limit }
        for (l in labels) {
            assertTrue("${l.text} inside the canvas", l.left >= 0f && l.right <= canvasWidth + 0.001f)
            if (limit == null || l.limit) continue
            val clear = l.right + gap <= limit.left + 0.001f || l.left + 0.001f >= limit.right + gap
            assertTrue("${l.text} (${l.left}..${l.right}) keeps $gap clear of ${limit.text} (${limit.left}..${limit.right})", clear)
        }
        // a tick that took the unit keeps half the gap to the ticks beside it
        val plain = labels.filter { !it.limit }.sortedBy { it.left }
        for ((i, l) in plain.withIndex()) {
            if (!l.text.endsWith("km/h") || l.text == "40 km/h") continue
            plain.getOrNull(i - 1)?.let { assertTrue("${l.text} after ${it.text}", it.right + gap / 2 <= l.left + 0.001f) }
            plain.getOrNull(i + 1)?.let { assertTrue("${l.text} before ${it.text}", l.right + gap / 2 <= it.left + 0.001f) }
        }
    }

    @Test
    fun withoutALimitAllFiveLabelsStandAndTheUnitEndsOnTheAxisEnd() {
        val labels = cover(null, 7f)
        assertEquals(listOf("0", "10", "20", "30", "40 km/h"), texts(labels))
        assertEquals(0f, labels.first().left, 0.001f)
        assertEquals(320f, labels.last().right, 0.001f)
    }

    @Test
    fun limit32DropsThe30AndKeepsTheUnitOnlyWhereItStandsClear() {
        // font 1.0: "30" ends 2 dp before "32" begins (the finding: "30 32 40 km/h"); the unit starts exactly 8 dp after it
        assertEquals(listOf("0", "10", "20", "40 km/h", "32"), texts(cover(32, 7f)))
        // font 1.3: the unit would start before "32" even ends, so it goes too (the finding: "32 40 km/h" read as one run);
        // the unit then follows the limit, whose number stays centred on its tick
        val labels = cover(32, 9.1f)
        assertEquals(listOf("0", "10", "20", "32 km/h"), texts(labels))
        assertEquals(256f - 9.1f, labels.last().left, 0.001f)
        assertTrue(labels.last().limit)
    }

    @Test
    fun whenTheLimitHasNoRoomForTheUnitTheLastTickWithRoomTakesIt() {
        // 39 km/h at font 1.3: "39 km/h" would come within 8 dp of "30", "30 km/h" would run into the limit
        assertEquals(listOf("0", "10", "20 km/h", "30", "39"), texts(cover(39, 9.1f)))
        // 37 km/h at font 1.0: the limit has room, 30 stays plain
        assertEquals(listOf("0", "10", "20", "30", "37 km/h"), texts(cover(37, 7f)))
    }

    @Test
    fun theAxisAlwaysNamesItsUnitAtUsualFontSizes() {
        for (limit in 0..40) {
            for (perChar in floatArrayOf(6f, 7f, 8f, 9.1f)) {
                for ((name, labels) in listOf("cover" to cover(limit, perChar), "editor" to editor(limit, perChar), "wide" to wide(limit, perChar))) {
                    assertEquals("$name $limit at $perChar: ${texts(labels)}", 1, labels.count { it.text.endsWith(" km/h") })
                }
            }
        }
    }

    @Test
    fun limits25And24LeaveEveryTickLabel() {
        for (perChar in floatArrayOf(7f, 9.1f)) {
            assertEquals(listOf("0", "10", "20", "30", "40 km/h", "25"), texts(cover(25, perChar)))
            assertEquals(listOf("0", "10", "20", "30", "40 km/h", "24"), texts(cover(24, perChar)))
        }
    }

    @Test
    fun theLimitIsAlwaysDrawnLastAndInSignal() {
        for (limit in listOf(24, 25, 32, 37)) {
            val labels = cover(limit, 7f)
            // the number, or the number with the unit when "40 km/h" had no room
            assertTrue(labels.last().text, labels.last().text in setOf("$limit", "$limit km/h"))
            assertTrue(labels.last().limit)
            assertEquals(1, labels.count { it.limit })
        }
    }

    @Test
    fun theGapIsMeasuredOnTheDrawnBoundsNotBetweenCentres() {
        // 27 km/h at 9 dp a digit: the centres of "27" and "30" are 24 dp apart, which the old rule (half widths plus 4 dp,
        // 22 dp) let pass; the drawn bounds are only 6 dp apart, so "30" goes
        val labels = cover(27, 9f)
        val limit = labels.single { it.limit }
        assertTrue("30" !in texts(labels))
        val thirty = shiftAxisLabels(null, 0f, 320f, 320f, gap, kmh, widths(9f)).single { it.text == "30" }
        assertTrue(thirty.left - limit.right < gap)
    }

    @Test
    fun aLimitNearTheEndIsKeptInsideAndStillKeepsItsGap() {
        // 39 km/h: the label is pushed in from the canvas edge; the end label cannot keep 8 dp and goes
        val labels = cover(39, 9.1f)
        val limit = labels.single { it.limit }
        assertEquals(320f, limit.right, 0.001f)
        assertTrue("40 km/h" !in texts(labels))
        assertTrue("30" in texts(labels))
        assertTrue("20 km/h" in texts(labels))
        assertClear(labels, 320f)
    }

    @Test
    fun everyLimitOnEveryAxisKeepsEightDpOnBothSides() {
        for (limit in 0..40) {
            for (perChar in floatArrayOf(6f, 7f, 8f, 9.1f, 10.5f)) {
                assertClear(cover(limit, perChar), 320f)
                assertClear(editor(limit, perChar), 320f)
                assertClear(wide(limit, perChar), 400f)
            }
        }
    }

    @Test
    fun theEditorsAxisFollowsTheSameRule() {
        // the strip is inset 16 dp on both sides: the drawn edges of "30" (centre 232) and "32" (centre 246.4) are 0.4 dp apart
        assertEquals(listOf("0", "10", "20", "40 km/h", "32"), texts(editor(32, 7f)))
        assertEquals(listOf("0", "10", "20", "30", "40 km/h", "25"), texts(editor(25, 7f)))
    }
}
