package dev.steven.s3control

import dev.steven.s3control.ble.S2Frame
import dev.steven.s3control.ui.s2.HitGroup
import dev.steven.s3control.ui.s2.S2ErrorCatcher
import dev.steven.s3control.ui.s2.S2Parts
import dev.steven.s3control.ui.s2.WiggleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The S2 drive diagnosis without Android (ui/s2/S2DriveLogic.kt): error catcher, wiggle test, likely parts. */
class S2DriveLogicTest {

    /** A decrypted status block: module on, [kmh], batteries, error [code] in byte 15 bits 3..7. */
    private fun frame(kmh: Int = 0, code: Int = 0, battery: Int = 66, module: Int = 71, runBits: Int = 0): S2Frame {
        val b = ByteArray(S2Frame.SIZE)
        b[2] = 0x01
        b[4] = kmh.toByte()
        b[5] = battery.toByte()
        b[6] = module.toByte()
        b[15] = ((code shl 3) or runBits).toByte()
        return S2Frame.parse(b)!!
    }

    // --- Error catcher ----------------------------------------------------------------------------

    @Test fun aSingleBlockWithACodeIsCaughtWithItsValues() {
        val c = S2ErrorCatcher()
        assertFalse(c.offer(1_000, frame(kmh = 14)))
        assertTrue(c.offer(2_000, frame(kmh = 13, code = 16, battery = 58, module = 90)))
        assertFalse(c.offer(3_000, frame(kmh = 14)))
        val h = c.hits.single()
        assertEquals(16, h.code)
        assertEquals(2_000L, h.firstAtMillis)
        assertEquals(2_000L, h.lastAtMillis)
        assertEquals(1, h.blocks)
        assertEquals(13, h.kmh)
        assertEquals(58, h.motorBattery)
        assertEquals(90, h.moduleBattery)
        assertEquals(frame(kmh = 13, code = 16, battery = 58, module = 90).hex, h.frameHex)
        assertEquals(3L, c.blocks)
        assertEquals(1_000L, c.sinceMillis)
    }

    @Test fun blocksInARowWithTheSameCodeAreOneEpisode() {
        val c = S2ErrorCatcher()
        c.offer(0, frame(kmh = 5, code = 27))
        c.offer(1_500, frame(kmh = 9, code = 27))
        c.offer(3_000, frame(kmh = 7, code = 27))
        val h = c.hits.single()
        assertEquals(3, h.blocks)
        assertEquals(0L, h.firstAtMillis)
        assertEquals(3_000L, h.lastAtMillis)
        assertEquals(5, h.kmh)
        assertEquals(9, h.topKmh)
    }

    @Test fun aZeroBlockOrAnotherCodeOrALongGapStartsANewEpisode() {
        val c = S2ErrorCatcher()
        c.offer(0, frame(code = 28))
        c.offer(1_000, frame(code = 16))
        c.offer(2_000, frame(code = 28))
        c.offer(3_000, frame(code = 0))
        c.offer(4_000, frame(code = 28))
        c.offer(4_000 + S2ErrorCatcher.EPISODE_GAP_MS + 1, frame(code = 28))
        assertEquals(listOf(28, 16, 28, 28, 28), c.hits.map { it.code })
    }

    @Test fun implausibleBlocksAreCountedNotCaught() {
        val c = S2ErrorCatcher()
        val noise = frame(kmh = 200, code = 31, battery = 250)
        assertFalse(noise.plausible)
        assertFalse(c.offer(0, noise))
        assertTrue(c.hits.isEmpty())
        assertEquals(1, c.implausible)
        assertEquals(0L, c.blocks)
        assertNull(c.sinceMillis)
    }

    @Test fun theListKeepsTheNewestEpisodes() {
        val c = S2ErrorCatcher(capacity = 3)
        for (i in 0 until 5) {
            c.offer(i * 1_000L, frame(code = 16))
            c.offer(i * 1_000L + 500, frame(code = 0))
        }
        assertEquals(listOf(2_000L, 3_000L, 4_000L), c.hits.map { it.firstAtMillis })
    }

    @Test fun eachBikeHasItsOwnEpisodesAndOldLinesStillRead() {
        val c = S2ErrorCatcher()
        c.offer(0, frame(code = 16), mac = "AA")
        c.offer(1_000, frame(code = 16), mac = "BB")
        c.offer(2_000, frame(code = 16), mac = "BB")
        assertEquals(listOf("AA" to 1, "BB" to 2), c.hits.map { it.mac to it.blocks })
        val h = c.hits.last()
        assertEquals(h, S2ErrorCatcher.fromCsv(S2ErrorCatcher.toCsv(h)))
        // A line stored before the mac column (9 fields) is a hit of no known bike.
        val old = S2ErrorCatcher.fromCsv("1,2,16,1,12,12,60,80," + "00".repeat(16))
        assertNotNull(old)
        assertNull(old!!.mac)
        // Only one bike's episodes go; the counters stay.
        c.remove { it.mac == "BB" }
        assertEquals(listOf("AA"), c.hits.map { it.mac })
        assertEquals(3L, c.blocks)
    }

    @Test fun storedLinesRoundTripAndRejectGarbage() {
        val c = S2ErrorCatcher()
        c.offer(1_700_000_000_000, frame(kmh = 21, code = 6))
        c.offer(1_700_000_001_000, frame(kmh = 22, code = 6))
        val h = c.hits.single()
        assertEquals(h, S2ErrorCatcher.fromCsv(S2ErrorCatcher.toCsv(h)))
        assertNull(S2ErrorCatcher.fromCsv(S2ErrorCatcher.CSV_HEADER))
        assertNull(S2ErrorCatcher.fromCsv("1,2,0,1,0,0,0,0," + "00".repeat(16)))
        assertNull(S2ErrorCatcher.fromCsv("1,2,16,1,0,0,0,0,zz"))
        val restored = S2ErrorCatcher().apply { restore(listOf(h)) }
        // A restored episode is closed: the same code later is a new line.
        restored.offer(1_700_000_002_000, frame(code = 6))
        assertEquals(2, restored.hits.size)
    }

    // --- Wiggle test ------------------------------------------------------------------------------

    @Test fun anAbruptDropFromSpinningSpeedToZeroFires() {
        val d = WiggleDetector()
        assertNull(d.offer(0, 0))
        assertNull(d.offer(700, 12))
        assertNull(d.offer(1_400, 14))
        assertEquals(14, d.offer(2_100, 0))
        // Still 0: no second alarm for the same drop.
        assertNull(d.offer(2_800, 0))
        assertNull(d.offer(3_500, 13))
        assertNull(d.offer(4_200, 12))
        assertEquals(12, d.offer(4_900, 0))
    }

    @Test fun aWheelCoastingDownDoesNotFire() {
        val d = WiggleDetector()
        listOf(15, 12, 9, 6, 4, 2, 1, 0).forEachIndexed { i, kmh ->
            assertNull("at $kmh", d.offer(i * 700L, kmh))
        }
    }

    @Test fun aSlowCadenceCoastingBelowTheThresholdDoesNotFire() {
        // At 1.5 s a hand-spun hub can fall from 8 to 0 between two reads (many controllers report 0 below a few km/h).
        val d = WiggleDetector()
        listOf(14, 11, 8, 0).forEachIndexed { i, kmh ->
            assertNull("at $kmh", d.offer(i * 1_500L, kmh))
        }
    }

    @Test fun aHandBrakingTheWheelDoesNotFire() {
        // 16, then 11 (a hand on the tyre), then 0: slowed down first, not a dropped signal.
        val d = WiggleDetector()
        assertNull(d.offer(0, 16))
        assertNull(d.offer(700, 11))
        assertNull(d.offer(1_400, 0))
    }

    @Test fun oneSpinningBlockAloneIsNotEnough() {
        val d = WiggleDetector()
        assertNull(d.offer(0, 3))
        assertNull(d.offer(700, 15))
        assertNull(d.offer(1_400, 0))
    }

    @Test fun aDropAfterALongSilenceDoesNotFire() {
        val d = WiggleDetector()
        d.offer(0, 15)
        d.offer(700, 15)
        assertNull(d.offer(700 + WiggleDetector.MAX_GAP_MS + 1, 0))
        d.reset()
        d.offer(9_300, 15)
        d.offer(10_000, 15)
        assertNotNull(d.offer(10_000 + WiggleDetector.MAX_GAP_MS, 0))
    }

    // --- Likely parts -----------------------------------------------------------------------------

    @Test fun codesPointToTheirCableOrPart() {
        assertEquals(R.string.s2diag_part_hall_cable, S2Parts.likely(16))
        assertEquals(R.string.s2diag_part_controller_line, S2Parts.likely(27))
        assertEquals(R.string.s2diag_part_stall, S2Parts.likely(1))
        assertEquals(R.string.s2diag_part_phase_short, S2Parts.likely(6))
        assertEquals(R.string.s2diag_part_torque, S2Parts.likely(7))
        assertEquals(R.string.s2diag_part_torque, S2Parts.likely(8))
        assertEquals(R.string.s2diag_part_supply, S2Parts.likely(3))
        assertEquals(R.string.s2diag_part_supply, S2Parts.likely(29))
        assertEquals(R.string.s2diag_part_speed_signal, S2Parts.likely(4))
        assertEquals(R.string.s2diag_part_speed_signal, S2Parts.likely(5))
        assertEquals(R.string.s2diag_part_unknown, S2Parts.likely(31))
        assertEquals(setOf(4, 5), S2Parts.disputed)
        assertTrue(S2Parts.drive(16))
        assertFalse(S2Parts.drive(28))
    }

    // --- Catcher list by code ---------------------------------------------------------------------

    @Test fun hitsGroupByCodeWithTheMostRecentCodeFirst() {
        val c = S2ErrorCatcher()
        c.offer(1_000, frame(kmh = 12, code = 16))
        c.offer(2_000, frame(kmh = 12))
        c.offer(3_000, frame(kmh = 14, code = 7))
        c.offer(4_000, frame(kmh = 14))
        c.offer(5_000, frame(kmh = 15, code = 16))
        c.offer(5_700, frame(kmh = 15, code = 16))
        val groups = HitGroup.of(c.hits)
        assertEquals(listOf(16, 7), groups.map { it.code })
        val g16 = groups.first()
        assertEquals(2, g16.count)
        assertEquals(5_000L, g16.lastAtMillis)
        // The single moments, newest first; the second episode spans two blocks.
        assertEquals(listOf(5_000L, 1_000L), g16.hits.map { it.firstAtMillis })
        assertEquals(2, g16.hits.first().blocks)
        assertEquals(1, groups[1].count)
        assertEquals(emptyList<HitGroup>(), HitGroup.of(emptyList()))
    }
}
