package dev.steven.s3control.ui.cockpit

import dev.steven.s3control.ui.components.MatrixFont
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitLayoutTest {

    private fun plan(w: Float, h: Float, fold: CockpitFold? = null, font: Float = 1f): CockpitPlan =
        cockpitPlan(cockpitWindow(w, h, fold), w, h, font)

    private fun assertFits(p: CockpitPlan, h: Float) {
        assertTrue("plan uses ${p.usedH} of $h", p.usedH <= h + 0.5f)
        assertTrue("pitch ${p.pitch}", p.pitch in CockpitSizes.P_MIN..CockpitSizes.P_MAX)
    }

    @Test
    fun coverPortraitSizes() {
        for ((w, h) in listOf(320f to 640f, 360f to 680f, 380f to 808f, 412f to 915f)) {
            for (font in listOf(1f, 1.3f, 2f)) {
                val p = plan(w, h, font = font)
                assertEquals(CockpitWindow.CoverPortrait, p.window)
                assertTrue("capsule ${p.capsuleH} at $w×$h ×$font", p.capsuleH >= CockpitSizes.CAPSULE_MIN_COVER)
                assertFits(p, h)
            }
        }
    }

    @Test
    fun coverPortraitReference() {
        val p = plan(380f, 808f)
        assertEquals(30.9f, p.pitch, 0.05f)
        assertEquals(180f, p.capsuleH, 1f)
        assertTrue(p.dropped.isEmpty())
    }

    @Test
    fun smallCoverDropsInOrder() {
        val p = plan(360f, 680f)
        assertEquals(listOf(CockpitDrop.MEASURE, CockpitDrop.LAMP_VALUES, CockpitDrop.BATTERY_ROW), p.dropped)
        assertEquals(29.1f, p.pitch, 0.05f)
        assertEquals(198f, p.capsuleH, 1f)
    }

    @Test
    fun coverLandscapeReference() {
        val p = plan(830f, 324f)
        assertEquals(CockpitWindow.CoverLandscape, p.window)
        assertEquals(33.1f, p.pitch, 0.05f)
        assertEquals(122f, p.capsuleH, 1f)
        assertTrue(p.capsuleH >= CockpitSizes.CAPSULE_MIN_COVER)
        assertFits(p, 324f)
    }

    @Test
    fun bookReference() {
        val p = plan(749f, 771f, CockpitFold(vertical = true, start = 374f, end = 374f))
        assertTrue(p.window is CockpitWindow.Book)
        assertEquals(326f, p.columnW, 0.5f)
        assertEquals(29.6f, p.pitch, 0.1f)
        assertEquals(6, p.recentRows)
        // Capped at 0.6 of its width: a wide capsule, never a near-circle.
        assertEquals(196.2f, p.capsuleH, 0.5f)
        assertTrue(p.capsuleH >= CockpitSizes.CAPSULE_MIN_BOOK)
        assertTrue(p.facts)
        assertFits(p, 771f)
    }

    @Test
    fun tableReference() {
        val p = plan(803f, 693f, CockpitFold(vertical = false, start = 346f, end = 346f))
        assertTrue(p.window is CockpitWindow.Table)
        assertEquals(31.7f, p.pitch, 0.05f)
        assertEquals(229.2f, p.capsuleH, 0.5f)
        assertEquals(382f, p.capsuleW, 1f)
        assertTrue(p.facts)
        assertFits(p, 693f)
    }

    @Test
    fun innerCapsulesAreWiderThanTall() {
        val windows = listOf(
            plan(749f, 771f, CockpitFold(vertical = true, start = 374f, end = 374f)),
            plan(803f, 693f, CockpitFold(vertical = false, start = 346f, end = 346f)),
            plan(882.9f, 763.9f, CockpitFold(vertical = false, start = 370f, end = 370f)),
            plan(900f, 820f, CockpitFold(vertical = true, start = 450f, end = 450f), font = 1.3f),
        )
        for (p in windows) {
            assertTrue("${p.window} ${p.capsuleW}x${p.capsuleH}", p.capsuleH <= p.capsuleW * CockpitSizes.CAPSULE_RATIO + 0.5f)
        }
    }

    @Test
    fun coverLandscapeShortWindowFoldsTheUnitRowAndFits() {
        // The 951 × 212 dp window seen when the insets came in 126 dp too tall.
        val p = plan(951.3f, 212.4f)
        assertEquals(CockpitWindow.CoverLandscape, p.window)
        assertTrue(p.unitInStatus)
        assertTrue(p.exitInAlert)
        assertTrue("capsule ${p.capsuleH}", p.capsuleH >= CockpitSizes.CAPSULE_MIN_COVER)
        assertFits(p, 212.4f)
        // The full window keeps everything.
        val full = plan(951.3f, 338.5f)
        assertTrue(!full.unitInStatus && !full.exitInAlert && full.lamps)
        assertFits(full, 338.5f)
        for (font in listOf(1f, 1.3f)) {
            for (h in listOf(200f, 212.4f, 240f, 280f, 324f)) assertFits(plan(951.3f, h, font = font), h)
        }
    }

    @Test
    fun noFoldOnALargeWindowStillSplits() {
        assertTrue(cockpitWindow(700f, 700f, null) is CockpitWindow.Book)
        assertTrue(cockpitWindow(900f, 640f, null) is CockpitWindow.Table)
        assertEquals(CockpitWindow.CoverLandscape, cockpitWindow(830f, 324f, null))
        assertEquals(CockpitWindow.CoverPortrait, cockpitWindow(380f, 808f, null))
    }

    @Test
    fun aFoldNearTheEdgeIsIgnored() {
        assertEquals(CockpitWindow.CoverPortrait, cockpitWindow(380f, 808f, CockpitFold(true, 20f, 20f)))
    }

    @Test
    fun everyFrameIsElevenColumns() {
        for (kmh in 0..99) {
            val f = CockpitFrames.speed(kmh)
            assertEquals(11, f.size)
            assertEquals("gap column at $kmh", 0, f[5])
        }
        assertEquals(11, CockpitFrames.dashes().size)
        assertEquals(11, CockpitFrames.bleQuery().size)
        assertEquals(11, CockpitFrames.bleCentred().size)
        assertEquals(11, CockpitFrames.bar(0.4f).size)
        assertEquals(11, CockpitFrames.ship().size)
        assertEquals(11, CockpitFrames.locked().size)
        assertEquals(11, CockpitFrames.code(42).size)
        assertEquals(11, CockpitFrames.code(null).size)
    }

    @Test
    fun sevenSitsInCellB() {
        val f = CockpitFrames.speed(7)
        for (i in 0..5) assertEquals(0, f[i])
        assertArrayEquals(MatrixFont.render("7"), f.copyOfRange(6, 11))
    }

    @Test
    fun digitsNeverShiftColumns() {
        // 1 → 2 and 19 → 20: the units always sit in cell B, the tens in cell A.
        val one = CockpitFrames.speed(1)
        val two = CockpitFrames.speed(2)
        assertTrue(one.copyOfRange(0, 6).all { it == 0 })
        assertTrue(two.copyOfRange(0, 6).all { it == 0 })
        assertEquals(0, one[6]) // a 3-wide "1" is centred in its cell: offset 1
        assertTrue(one[7] != 0)
        val nineteen = CockpitFrames.speed(19)
        val twenty = CockpitFrames.speed(20)
        assertArrayEquals(CockpitFrames.speed(9).copyOfRange(6, 11), nineteen.copyOfRange(6, 11))
        assertArrayEquals(CockpitFrames.speed(10).copyOfRange(6, 11), twenty.copyOfRange(6, 11))
        assertArrayEquals(CockpitFrames.speed(29).copyOfRange(0, 5), twenty.copyOfRange(0, 5))
    }

    @Test
    fun shipMaskAndLockOffset() {
        assertArrayEquals(intArrayOf(0x48, 0x18, 0x78, 0x3C, 0x7E, 0x3F, 0x7E, 0x3C, 0x78, 0x18, 0x48), CockpitFrames.ship())
        val lock = CockpitFrames.locked()
        val glyph = MatrixFont.render(MatrixFont.LOCKED.toString())
        assertEquals(0, lock[0])
        assertEquals(0, lock[1])
        assertArrayEquals(glyph, lock.copyOfRange(2, 2 + glyph.size))
    }
}
