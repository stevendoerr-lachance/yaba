package dev.steven.s3control

import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.Handle
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.ShiftCheck
import dev.steven.s3control.ble.ShiftMode
import dev.steven.s3control.ble.ShiftPoints
import dev.steven.s3control.ble.ShiftProfile
import dev.steven.s3control.ble.ShiftProfiles
import dev.steven.s3control.ble.ShiftRules
import dev.steven.s3control.ble.ShiftRules.Rule
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.decodeCustomShift
import dev.steven.s3control.ble.decodeGear
import dev.steven.s3control.ble.decodeShifting
import dev.steven.s3control.ble.encodeCustomShift
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftingTest {
    private val factory = ShiftProfiles.FACTORY
    private val slot2 = ShiftProfiles.FACTORY_SLOT2
    private val flat = ShiftProfiles.FLAT
    private val hilly = ShiftProfiles.HILLY
    private val profiles = listOf(factory, slot2, flat, hilly)
    private val custom = ShiftPoints(11, 20, 30, 8, 16, 26)
    private val seeds = profiles + custom

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // --- Encode and decode -----------------------------------------------------------------------

    @Test
    fun encodeStandard() {
        assertArrayEquals(bytes(0x0A, 0x13, 0x18, 0x08, 0x11, 0x16), factory.encode())
        assertArrayEquals(bytes(0x0A, 0x13, 0x1C, 0x08, 0x11, 0x19), slot2.encode())
        assertArrayEquals(bytes(0x0A, 0x11, 0x1B, 0x07, 0x0E, 0x18), flat.encode())
        assertArrayEquals(bytes(0x0C, 0x15, 0x1B, 0x09, 0x12, 0x18), hilly.encode())
    }

    @Test
    fun decodeTakesFirstSixOfPaddedBlock() {
        val block = ByteArray(16).also { bytes(0x0A, 0x13, 0x18, 0x08, 0x11, 0x16).copyInto(it) }
        assertEquals(factory, ShiftPoints.decode(block))
    }

    @Test
    fun decodeRejectsShortOnly() {
        assertNull(ShiftPoints.decode(bytes(10, 19, 24, 8, 17)))
        assertNull(ShiftPoints.decode(ByteArray(0)))
    }

    /** Six zeros are the most dangerous set a bike can hold: they must show, not vanish as "no data". */
    @Test
    fun allZeroIsSurfacedAsInvalid() {
        assertEquals(ShiftPoints.ZERO, ShiftPoints.decode(ByteArray(6)))
        assertEquals(ShiftPoints.ZERO, ShiftPoints.decode(ByteArray(16)))
        assertFalse(ShiftRules.isValid(ShiftPoints.ZERO))
        // Through the state: shown as the current set (Eigene, invalid), not swallowed.
        val st = BikeState(link = Link.READY, speedLimit = SpeedLimit.EU, shiftPoints = flat, shiftPointsRegion = SpeedLimit.US)
        val after = decodeShifting(st, Gatt.E_SHIFT_POINTS, ByteArray(16))!!
        assertEquals(ShiftPoints.ZERO, after.shiftPointsCurrent)
        assertNull(after.shiftProfile)
    }

    /** A short read changes nothing: an old set must not get the current region's tag. */
    @Test
    fun shortReadKeepsOldTag() {
        val st = BikeState(speedLimit = SpeedLimit.US, shiftPoints = flat, shiftPointsRegion = SpeedLimit.EU)
        val after = decodeShifting(st, Gatt.E_SHIFT_POINTS, bytes(10, 19))!!
        assertEquals(SpeedLimit.EU, after.shiftPointsRegion)
        assertNull(after.shiftPointsCurrent)
        assertNull(decodeShifting(st, Gatt.SPEED, bytes(1)))
    }

    @Test
    fun decodeIsUnsigned() {
        val p = ShiftPoints.decode(bytes(0xC8, 1, 2, 3, 4, 5))!!
        assertEquals(200, p.up12)
    }

    @Test
    fun roundTripAllProfiles() {
        for (p in profiles + custom) assertEquals(p, ShiftPoints.decode(p.encode()))
    }

    @Test
    fun shiftModeFrom() {
        assertEquals(ShiftMode.AUTO, ShiftMode.from(0))
        assertEquals(ShiftMode.MANUAL, ShiftMode.from(1))
        assertNull(ShiftMode.from(2))
        assertNull(ShiftMode.from(null))
    }

    @Test
    fun gearDecode() {
        assertNull(decodeGear(0))
        assertNull(decodeGear(5))
        assertNull(decodeGear(null))
        assertEquals(3, decodeGear(3))
        for (g in 1..4) assertEquals(g, decodeGear(g))
    }

    @Test
    fun handleOrdinalIsWireIndex() {
        val wire = factory.encode()
        for (h in Handle.entries) assertEquals(factory[h], wire[h.ordinal].toInt() and 0xFF)
    }

    // --- Validation ------------------------------------------------------------------------------

    @Test
    fun profilesAreValid() {
        for (p in profiles) assertEquals("$p", emptySet<Rule>(), ShiftRules.violations(p))
        assertTrue(ShiftRules.isValid(custom))
    }

    private fun assertOnly(rule: Rule, p: ShiftPoints) = assertEquals("$p", setOf(rule), ShiftRules.violations(p))

    @Test fun gap12() = assertOnly(Rule.GAP_12, factory.copy(down21 = factory.up12 - 1))
    @Test fun gap23() = assertOnly(Rule.GAP_23, factory.copy(down32 = factory.up23 - 1))
    @Test fun gap34() = assertOnly(Rule.GAP_34, factory.copy(down43 = factory.up34))
    @Test fun upSpacingLow() = assertOnly(Rule.UP_SPACING_LOW, ShiftPoints(10, 12, 24, 5, 8, 22))
    @Test fun upSpacingHigh() = assertOnly(Rule.UP_SPACING_HIGH, ShiftPoints(10, 19, 21, 8, 14, 17))
    @Test fun downSpacingLow() = assertOnly(Rule.DOWN_SPACING_LOW, factory.copy(down32 = factory.down21 + 2))
    @Test fun downSpacingHigh() = assertOnly(Rule.DOWN_SPACING_HIGH, factory.copy(down43 = factory.down32 + 2))
    @Test fun down21Min() = assertOnly(Rule.DOWN21_MIN, factory.copy(down21 = 4))
    @Test fun up12Range() = assertOnly(Rule.UP12_RANGE, ShiftPoints(21, 24, 27, 8, 17, 22))
    @Test fun up34Max() = assertOnly(Rule.UP34_MAX, factory.copy(up34 = 41))

    private fun assertRanges(p: ShiftPoints, vararg expected: IntRange) {
        for (h in Handle.entries) assertEquals("$h", expected[h.ordinal], ShiftRules.range(h, p))
    }

    @Test
    fun rangesForStandard() = assertRanges(factory, 10..16, 19..21, 24..40, 5..8, 11..17, 20..22)

    @Test
    fun rangesForFlat() = assertRanges(flat, 9..14, 16..24, 26..40, 5..8, 10..15, 17..25)

    @Test
    fun validExactlyWhenEveryValueInItsRange() {
        val samples = seeds + listOf(
            ShiftPoints(10, 12, 24, 11, 11, 22), factory.copy(down21 = 9), factory.copy(up34 = 41),
            factory.copy(down43 = 24), ShiftPoints(21, 24, 27, 8, 17, 22),
        )
        for (p in samples) {
            val inRanges = Handle.entries.all { p[it] in ShiftRules.range(it, p) }
            assertEquals("$p", ShiftRules.isValid(p), inRanges)
        }
    }

    @Test
    fun clampNeverProducesInvalid() {
        for (seed in seeds) for (h in Handle.entries) for (v in 0..45) {
            val c = ShiftRules.clamp(h, seed, v)
            assertTrue("$seed $h $v -> $c", ShiftRules.isValid(c))
            assertEquals(v.coerceIn(ShiftRules.range(h, seed)), c[h])
        }
    }

    @Test
    fun blockerNamesNeighbour() {
        val down = ShiftRules.clamp(Handle.UP23, factory, 18)
        assertEquals(19, down.up23)
        assertEquals(Handle.DOWN32, ShiftRules.blocker(Handle.UP23, factory, upward = false))
        val up = ShiftRules.clamp(Handle.UP34, factory, 41)
        assertEquals(40, up.up34)
        assertNull(ShiftRules.blocker(Handle.UP34, factory, upward = true))
        assertEquals(Handle.UP23, ShiftRules.blocker(Handle.UP12, factory, upward = true))
        assertNull(ShiftRules.blocker(Handle.DOWN21, factory, upward = false))
    }

    // --- Matching --------------------------------------------------------------------------------

    @Test
    fun matchStandardPerRegion() {
        for (r in listOf(SpeedLimit.EU, SpeedLimit.US, SpeedLimit.NO_LIMIT, null)) {
            assertEquals(ShiftProfile.STANDARD, ShiftProfiles.match(factory, r))
        }
        assertEquals(ShiftProfile.STANDARD, ShiftProfiles.match(slot2, SpeedLimit.JP))
        assertNull(ShiftProfiles.match(slot2, SpeedLimit.EU))
        assertNull(ShiftProfiles.match(factory, SpeedLimit.JP))
        assertEquals(slot2, ShiftProfiles.points(ShiftProfile.STANDARD, SpeedLimit.JP))
        assertEquals(factory, ShiftProfiles.points(ShiftProfile.STANDARD, SpeedLimit.US))
    }

    @Test
    fun matchFlatHillyAnyRegion() {
        for (r in SpeedLimit.entries.toList() + listOf(null)) {
            assertEquals(ShiftProfile.FLAT, ShiftProfiles.match(flat, r))
            assertEquals(ShiftProfile.HILLY, ShiftProfiles.match(hilly, r))
        }
    }

    @Test
    fun offByOneIsCustom() {
        assertNull(ShiftProfiles.match(flat.copy(up34 = 28), SpeedLimit.EU))
        assertNull(ShiftProfiles.match(custom, SpeedLimit.EU))
    }

    @Test
    fun pointsCurrentFollowsRegion() {
        val stale = BikeState(speedLimit = SpeedLimit.US, shiftPoints = flat, shiftPointsRegion = SpeedLimit.EU)
        assertNull(stale.shiftPointsCurrent)
        assertNull(stale.shiftProfile)
        val fresh = stale.copy(shiftPointsRegion = SpeedLimit.US)
        assertEquals(flat, fresh.shiftPointsCurrent)
        assertEquals(ShiftProfile.FLAT, fresh.shiftProfile)
    }

    // --- Firmware model: shifter.c state 9 (auto), mainware 1.07.06 (no newer source exists) -------

    /** One pass of the auto logic. Speed in 0.1 km/h, thresholds stored ×10, strict comparisons. */
    private fun autoStep(gear: Int, speed: Int, p: ShiftPoints): Int {
        val u12 = p.up12 * 10; val u23 = p.up23 * 10; val u34 = p.up34 * 10
        val d21 = p.down21 * 10; val d32 = p.down32 * 10; val d43 = p.down43 * 10
        var g = gear
        when (gear) {
            1 -> if (u12 < speed) g = 2
            2 -> {
                if (speed < d21) g = 1
                if (u23 < speed) g = 3 // runs last and wins
            }
            3 -> {
                if (speed < d21) g = 1 else if (speed < d32) g = 2
                if (u34 < speed) g = 4
            }
            4 -> if (speed < d21) g = 1 else if (speed < d32) g = 2 else if (speed < d43) g = 3
            5 -> g = 1
        }
        return g
    }

    /** Every set the editor can produce from the seeds by moving one handle anywhere. */
    private val clampedSets: List<ShiftPoints> by lazy {
        seeds.flatMap { seed -> Handle.entries.flatMap { h -> (0..45).map { ShiftRules.clamp(h, seed, it) } } }.distinct()
    }

    @Test
    fun noHuntingAtConstantSpeed() {
        for (p in profiles + clampedSets) for (speed in 0..400) for (start in 1..4) {
            val seq = arrayListOf(start)
            repeat(6) { seq.add(autoStep(seq.last(), speed, p)) }
            val rising = seq.zipWithNext().all { (a, b) -> b >= a }
            val falling = seq.zipWithNext().all { (a, b) -> b <= a }
            assertTrue("$p at $speed from $start: $seq", rising || falling)
            assertTrue("$p at $speed from $start settles late: $seq", seq.drop(3).distinct().size == 1)
        }
    }

    @Test
    fun invalidSetHunts() {
        val bad = factory.copy(down21 = 12)
        assertFalse(ShiftRules.isValid(bad))
        assertTrue(Rule.GAP_12 in ShiftRules.violations(bad))
        var g = 1
        val seq = (0 until 6).map { g = autoStep(g, 110, bad); g }
        assertEquals(listOf(2, 1, 2, 1, 2, 1), seq)
    }

    @Test
    fun rampCountsShifts() {
        for (p in profiles) {
            var g = 1
            var ups = 0
            var downs = 0
            val ramp = (0..400) + (400 downTo 0)
            for (speed in ramp) {
                val next = autoStep(g, speed, p)
                if (next > g) ups++
                if (next < g) downs++
                g = next
            }
            assertEquals("$p", 3, ups)
            assertEquals("$p", 3, downs)
            assertEquals(1, g)
        }
    }

    @Test
    fun jitterAroundUpPoint() {
        val ups = listOf(Handle.UP12, Handle.UP23, Handle.UP34)
        val downs = listOf(Handle.DOWN21, Handle.DOWN32, Handle.DOWN43)
        for (p in profiles) for (k in 0..2) {
            val up = p[ups[k]] * 10
            val swing = (p[ups[k]] - p[downs[k]]) * 10 - 1
            var g = k + 1
            var shifts = 0
            repeat(40) { i ->
                val speed = if (i % 2 == 0) up + swing else up - swing
                val next = autoStep(g, speed, p)
                if (next != g) shifts++
                g = next
            }
            assertTrue("$p boundary ${k + 1}: $shifts shifts", shifts <= 1)
        }
    }

    /** Adoption of the gear the shifter reports (shifter.c state 9 head), then one auto pass. */
    private fun autoPass(gear: Int, reported: Int, speed: Int, p: ShiftPoints): Int {
        val g = if (reported != 0 && reported != 0xFF && reported != gear) reported else gear
        return autoStep(g, speed, p)
    }

    /**
     * At a standstill auto ends in gear 1 from any gear with any set YABA can write (down 2→1 is at
     * least 5 km/h, so speed 0 is below it), and never shifts up. The old test only tried gear 1.
     */
    @Test
    fun standstillSettlesInGearOneFromAnyGear() {
        for (p in profiles + clampedSets) for (start in 1..4) {
            val first = autoStep(start, 0, p)
            assertEquals("$p from $start", 1, first)
            var g = first
            repeat(10) { g = autoStep(g, 0, p) }
            assertEquals(1, g)
        }
    }

    /**
     * Manual to auto while standing, with the shifter still reporting a gear above 1 (manual's shift
     * back to 1 not yet done): auto adopts the reported gear and shifts it down to 1 in one pass.
     */
    @Test
    fun manualToAutoAtStandstillAdoptsReportedGear() {
        for (p in profiles + clampedSets) for (reported in 2..4) {
            assertEquals("$p reported $reported", 1, autoPass(1, reported, 0, p))
        }
    }

    /**
     * The owner allows changes while riding: a mode switch or a new set lands at some speed in some gear.
     * From any gear the bike then moves monotonically (never up and down), at most one gear up per pass,
     * and settles within three passes on the gear the new set gives that speed.
     */
    @Test
    fun changeWhileRidingSettlesMonotone() {
        for (p in profiles + clampedSets) for (speed in 0..400 step 5) for (start in 1..4) {
            val seq = arrayListOf(start)
            repeat(5) { seq.add(autoStep(seq.last(), speed, p)) }
            val steps = seq.zipWithNext()
            assertTrue("$p at $speed from $start: $seq", steps.all { (a, b) -> b >= a } || steps.all { (a, b) -> b <= a })
            assertTrue("$p at $speed from $start: $seq", steps.all { (a, b) -> b - a <= 1 })
            assertEquals("$p at $speed from $start: $seq", 1, seq.drop(3).distinct().size)
        }
    }

    /** Why six zeros are dangerous: any motion climbs to gear 4, and standing still never leaves it. */
    @Test
    fun allZeroSetStartsOffInFourth() {
        var g = 1
        repeat(3) { g = autoStep(g, 1, ShiftPoints.ZERO) }
        assertEquals(4, g)
        repeat(10) { g = autoStep(g, 0, ShiftPoints.ZERO) }
        assertEquals(4, g)
    }

    // --- Guards and settings ---------------------------------------------------------------------

    /** Riding does not lock shifting (the owner's call); a missing or failed plausibility check does. */
    @Test
    fun canChangeShifting() {
        val ok = BikeState(link = Link.READY, speedKmh = 0, status = BikeStatus.READY, shiftSupported = true, shiftCheck = ShiftCheck.OK)
        assertTrue(ok.canChangeShifting)
        assertTrue(ok.shiftWritable)
        assertTrue(ok.copy(speedKmh = 23, faultCodes = listOf(48), status = BikeStatus.RIDING).shiftWritable)
        assertFalse(ok.copy(link = Link.LOST).canChangeShifting)
        assertFalse(ok.copy(link = Link.LOST).shiftWritable)
        assertFalse(ok.copy(shiftCheck = null).shiftWritable)
        assertFalse(ok.copy(shiftCheck = ShiftCheck.ODD_MODE).shiftWritable)
        assertFalse(ok.copy(shiftSupported = false).shiftWritable)
        assertEquals(44, ok.copy(faultCodes = listOf(12, 44)).shifterFault)
    }

    @Test
    fun customShiftRoundTrip() {
        val map = linkedMapOf("74:D2:85:AB:CD:EF" to custom, "AA:BB:CC:DD:EE:FF" to flat, "plain" to factory)
        assertEquals(map, decodeCustomShift(encodeCustomShift(map)))
        assertEquals(emptyMap<String, ShiftPoints>(), decodeCustomShift(encodeCustomShift(emptyMap())))
        for (junk in listOf(null, "", ";;;", "garbage", "=1,2,3,4,5,6", "MAC=1,2,3", "MAC=a,b,c,d,e,f", "MAC=1,2,3,4,5,999")) {
            assertEquals("$junk", emptyMap<String, ShiftPoints>(), decodeCustomShift(junk))
        }
        // One bad entry does not take the good ones with it.
        assertEquals(mapOf("A" to custom), decodeCustomShift("A=11,20,30,8,16,26;B=oops"))
    }
}
