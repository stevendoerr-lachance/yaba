package dev.steven.s3control

import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.ShiftCheck
import dev.steven.s3control.ble.ShiftCommands
import dev.steven.s3control.ble.ShiftFailure
import dev.steven.s3control.ble.ShiftFailure.Reason
import dev.steven.s3control.ble.ShiftKept
import dev.steven.s3control.ble.ShiftLink
import dev.steven.s3control.ble.ShiftMode
import dev.steven.s3control.ble.ShiftPoints
import dev.steven.s3control.ble.ShiftProfile
import dev.steven.s3control.ble.ShiftProfiles
import dev.steven.s3control.ble.ShiftTarget
import dev.steven.s3control.ble.ShiftWritten
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.checkKept
import dev.steven.s3control.ble.decodeShiftJournal
import dev.steven.s3control.ble.decodeShifting
import dev.steven.s3control.ble.encodeShiftJournal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The shifting commands against a fake bike: fresh reads, skips, region handling, read-back, limits. */
class ShiftCommandsTest {
    private val factory = ShiftProfiles.FACTORY
    private val slot2 = ShiftProfiles.FACTORY_SLOT2
    private val flat = ShiftProfiles.FLAT
    private val custom = ShiftPoints(11, 20, 30, 8, 16, 26)

    /** A bike as the firmware answers: 16-byte decrypted reads, one points slot per region. */
    private class FakeBike(var region: Int = SpeedLimit.EU.code, var mode: Int = 0) : ShiftLink {
        val slots = Array(4) { if (it == SpeedLimit.JP.code) ShiftProfiles.FACTORY_SLOT2.encode() else ShiftProfiles.FACTORY.encode() }
        val failing = mutableSetOf<Gatt.Char>()
        /** False: the bike echoes nothing into RAM (a refused write). */
        var keeps = true
        val writes = mutableListOf<Pair<Gatt.Char, ByteArray>>()
        val reads = mutableListOf<Gatt.Char>()

        override suspend fun read(ch: Gatt.Char): ByteArray {
            reads += ch
            if (ch in failing) throw IllegalStateException("read failed")
            val out = ByteArray(16)
            when (ch) {
                Gatt.SPEED_LIMIT -> out[0] = region.toByte()
                Gatt.E_SHIFTER_MODE -> out[0] = mode.toByte()
                Gatt.E_SHIFT_POINTS -> slots[region].copyInto(out)
                else -> Unit
            }
            return out
        }

        override suspend fun write(ch: Gatt.Char, value: ByteArray) {
            writes += ch to value
            if (!keeps) return
            when (ch) {
                Gatt.E_SHIFTER_MODE -> mode = value[0].toInt()
                Gatt.E_SHIFT_POINTS -> slots[region] = value.copyOf(6)
                else -> Unit
            }
        }
    }

    /** The controller's state as the commands see it, decoded the way BikeController does. */
    private inner class Harness(val bike: FakeBike = FakeBike(), start: BikeState = BikeState(link = Link.READY, shiftSupported = true)) {
        var state = start
        var clock = 1_000_000L
        val sleeps = mutableListOf<Long>()
        val checks = mutableListOf<ShiftCheck>()
        /** Runs before each sleep, to change the world while the command waits (a region push). */
        var duringSleep: (Long) -> Unit = {}
        val commands = ShiftCommands(
            state = { state },
            apply = { ch, v ->
                state = decodeShifting(state, ch, v)
                    ?: if (ch == Gatt.SPEED_LIMIT) state.copy(speedLimit = SpeedLimit.from(v[0].toInt() and 0xFF)) else state
            },
            onCheck = { checks += it },
            now = { clock },
            sleep = { ms -> duringSleep(ms); sleeps += ms; clock += ms },
        )
    }

    private fun failure(block: suspend () -> Unit): ShiftFailure {
        try {
            runBlocking { block() }
        } catch (e: ShiftFailure) {
            return e
        }
        fail("expected a ShiftFailure")
        throw AssertionError()
    }

    // --- Points ----------------------------------------------------------------------------------

    @Test
    fun profileIsWrittenAndReadBack() = runBlocking {
        val h = Harness()
        val w = h.commands.setPoints(h.bike, ShiftTarget.Profile(ShiftProfile.FLAT))
        assertTrue(w.written)
        assertEquals(SpeedLimit.EU, w.region)
        assertEquals(1, h.bike.writes.size)
        assertArrayEquals(flat.encode(), h.bike.writes.single().second)
        assertEquals(flat, h.state.shiftPointsCurrent)
        assertEquals(1, h.commands.writes)
    }

    /** The region is read inside the command: Standard for the JP slot is the 28/25 set, whatever the cache said. */
    @Test
    fun profileResolvedForFreshRegion() = runBlocking {
        val bike = FakeBike(region = SpeedLimit.JP.code).also { it.slots[SpeedLimit.JP.code] = flat.encode() }
        val h = Harness(bike, BikeState(link = Link.READY, shiftSupported = true, speedLimit = SpeedLimit.EU))
        val w = h.commands.setPoints(bike, ShiftTarget.Profile(ShiftProfile.STANDARD))
        assertEquals(SpeedLimit.JP, w.region)
        assertEquals(slot2, w.points)
        assertArrayEquals(slot2.encode(), bike.slots[SpeedLimit.JP.code])
        assertArrayEquals(factory.encode(), bike.slots[SpeedLimit.EU.code])
    }

    @Test
    fun customRefusedWhenRegionMoved() {
        val bike = FakeBike(region = SpeedLimit.US.code)
        val h = Harness(bike)
        val e = failure { h.commands.setPoints(bike, ShiftTarget.Custom(custom, SpeedLimit.EU)) }
        assertEquals(Reason.REGION_MOVED, e.reason)
        assertTrue(bike.writes.isEmpty())
    }

    @Test
    fun refusedWithoutRegion() {
        val h = Harness()
        assertEquals(Reason.NO_REGION, failure { h.commands.setPoints(h.bike, ShiftTarget.Custom(custom, null)) }.reason)
        val unknown = Harness(FakeBike(region = 7))
        assertEquals(Reason.NO_REGION, failure { unknown.commands.setPoints(unknown.bike, ShiftTarget.Profile(ShiftProfile.STANDARD)) }.reason)
        assertTrue(h.bike.writes.isEmpty() && unknown.bike.writes.isEmpty())
    }

    @Test
    fun invalidCustomNeverSent() {
        val h = Harness()
        val e = failure { h.commands.setPoints(h.bike, ShiftTarget.Custom(factory.copy(down21 = 12), SpeedLimit.EU)) }
        assertEquals(Reason.INVALID, e.reason)
        assertTrue(h.bike.reads.isEmpty())
        assertTrue(h.bike.writes.isEmpty())
    }

    /** A failed pre-read stops the command; a cache that already shows the target is no excuse to skip. */
    @Test
    fun failedPreReadFailsInsteadOfTrustingCache() {
        val h = Harness(start = BikeState(link = Link.READY, shiftSupported = true, speedLimit = SpeedLimit.EU, shiftPoints = flat, shiftPointsRegion = SpeedLimit.EU))
        h.bike.failing += Gatt.E_SHIFT_POINTS
        val e = failure { h.commands.setPoints(h.bike, ShiftTarget.Profile(ShiftProfile.FLAT)) }
        assertEquals(Reason.READ_FAILED, e.reason)
        assertTrue(h.bike.writes.isEmpty())
        val mode = Harness().also { it.bike.failing += Gatt.E_SHIFTER_MODE }
        assertEquals(Reason.READ_FAILED, failure { mode.commands.setMode(mode.bike, ShiftMode.MANUAL) }.reason)
        assertTrue(mode.bike.writes.isEmpty())
    }

    /** The skip compares the fresh read: a stale cache showing the target still writes, a stale cache showing something else skips. */
    @Test
    fun skipDecidedOnFreshRead() = runBlocking {
        val stale = Harness(start = BikeState(link = Link.READY, speedLimit = SpeedLimit.EU, shiftPoints = flat, shiftPointsRegion = SpeedLimit.EU))
        assertTrue(stale.commands.setPoints(stale.bike, ShiftTarget.Profile(ShiftProfile.FLAT)).written)
        val already = Harness(start = BikeState(link = Link.READY, speedLimit = SpeedLimit.EU, shiftPoints = flat, shiftPointsRegion = SpeedLimit.EU))
        val w = already.commands.setPoints(already.bike, ShiftTarget.Profile(ShiftProfile.STANDARD))
        assertFalse(w.written)
        assertTrue(already.bike.writes.isEmpty())
        assertEquals(factory, already.state.shiftPointsCurrent)
    }

    /** All zeros on the bike pass the layout check: writing Standard over them must stay possible. */
    @Test
    fun zeroSetOnBikeCanBeReplaced() = runBlocking {
        val h = Harness()
        h.bike.slots[SpeedLimit.EU.code] = ShiftPoints.ZERO.encode()
        assertTrue(h.commands.setPoints(h.bike, ShiftTarget.Profile(ShiftProfile.STANDARD)).written)
        assertEquals(ShiftCheck.OK, h.checks.last())
        assertArrayEquals(factory.encode(), h.bike.slots[SpeedLimit.EU.code])
    }

    @Test
    fun oddValuesBlockEveryWrite() {
        val odd = Harness().also { it.bike.slots[SpeedLimit.EU.code] = ShiftPoints(100, 190, 240, 80, 170, 220).encode() }
        assertEquals(Reason.ODD_VALUES, failure { odd.commands.setPoints(odd.bike, ShiftTarget.Profile(ShiftProfile.STANDARD)) }.reason)
        assertEquals(Reason.ODD_VALUES, failure { odd.commands.setMode(odd.bike, ShiftMode.MANUAL) }.reason)
        assertEquals(ShiftCheck.ODD_POINTS, odd.checks.last())
        assertTrue(odd.bike.writes.isEmpty())
        val mode = Harness(FakeBike(mode = 2))
        assertEquals(Reason.ODD_VALUES, failure { mode.commands.setPoints(mode.bike, ShiftTarget.Profile(ShiftProfile.FLAT)) }.reason)
        assertEquals(ShiftCheck.ODD_MODE, mode.checks.last())
        assertTrue(mode.bike.writes.isEmpty())
    }

    /** A region push while the main board persists: the read-back slot is another one, so the command fails. */
    @Test
    fun regionPushDuringWriteFails() {
        val h = Harness()
        h.duringSleep = { ms -> if (ms == ShiftCommands.POINTS_SETTLE_MS) { h.bike.region = SpeedLimit.US.code; h.state = h.state.copy(speedLimit = SpeedLimit.US) } }
        val e = failure { h.commands.setPoints(h.bike, ShiftTarget.Profile(ShiftProfile.FLAT)) }
        assertEquals(Reason.REGION_CHANGED, e.reason)
        assertEquals(1, h.bike.writes.size)
    }

    @Test
    fun refusedWriteIsReported() {
        val h = Harness().also { it.bike.keeps = false }
        val e = failure { h.commands.setPoints(h.bike, ShiftTarget.Profile(ShiftProfile.FLAT)) }
        assertEquals(Reason.POINTS_REJECTED, e.reason)
        assertEquals(1, h.bike.writes.size)
        assertEquals(ShiftCommands.POINTS_RETRIES, h.sleeps.count { it == ShiftCommands.RETRY_MS })
    }

    // --- Mode ------------------------------------------------------------------------------------

    @Test
    fun modeWrittenSkippedRejected() = runBlocking {
        val h = Harness()
        assertTrue(h.commands.setMode(h.bike, ShiftMode.MANUAL))
        assertEquals(ShiftMode.MANUAL, h.state.shiftMode)
        assertArrayEquals(byteArrayOf(1), h.bike.writes.single().second)
        assertFalse(h.commands.setMode(h.bike, ShiftMode.MANUAL))
        assertEquals(1, h.bike.writes.size)
        val refused = Harness().also { it.bike.keeps = false }
        val e = failure { refused.commands.setMode(refused.bike, ShiftMode.MANUAL) }
        assertEquals(Reason.MODE_REJECTED, e.reason)
    }

    // --- Spacing and cap -------------------------------------------------------------------------

    @Test
    fun writesAreSpacedAndCapped() = runBlocking {
        val h = Harness()
        val times = mutableListOf<Long>()
        val profiles = listOf(ShiftProfile.FLAT, ShiftProfile.HILLY)
        repeat(ShiftCommands.MAX_WRITES) { i ->
            h.commands.setPoints(h.bike, ShiftTarget.Profile(profiles[i % 2]))
            times += h.clock
        }
        // Each write waits out the 3 s spacing (the 2 s settle counts toward it).
        assertEquals(ShiftCommands.MAX_WRITES, h.bike.writes.size)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= ShiftCommands.SPACING_MS })
        assertEquals(Reason.TOO_MANY, failure { h.commands.setPoints(h.bike, ShiftTarget.Profile(ShiftProfile.STANDARD)) }.reason)
        assertEquals(ShiftCommands.MAX_WRITES, h.bike.writes.size)
        h.commands.newConnection()
        assertTrue(h.commands.setPoints(h.bike, ShiftTarget.Profile(ShiftProfile.STANDARD)).written)
    }

    // --- Plausibility, journal -------------------------------------------------------------------

    @Test
    fun shiftCheckCases() {
        val ok = ByteArray(16).also { factory.encode().copyInto(it) }
        assertEquals(ShiftCheck.OK, ShiftCheck.of(byteArrayOf(0), ok))
        assertEquals(ShiftCheck.OK, ShiftCheck.of(byteArrayOf(1), ByteArray(16)))
        assertEquals(ShiftCheck.UNREADABLE, ShiftCheck.of(null, ok))
        assertEquals(ShiftCheck.UNREADABLE, ShiftCheck.of(byteArrayOf(0), ByteArray(5)))
        assertEquals(ShiftCheck.ODD_MODE, ShiftCheck.of(byteArrayOf(2), ok))
        assertEquals(ShiftCheck.ODD_POINTS, ShiftCheck.of(byteArrayOf(0), ShiftPoints(10, 19, 61, 8, 17, 22).encode()))
        assertEquals("5538 = 2 · 5537 = 10 19 24 8 17 22", ShiftCheck.detail(byteArrayOf(2), ok))
        assertEquals("5538 = – · 5537 = –", ShiftCheck.detail(null, null))
    }

    @Test
    fun journalRoundTrip() {
        val map = linkedMapOf(
            "74:D2:85:AB:CD:EF" to ShiftWritten(ShiftMode.MANUAL, SpeedLimit.JP, slot2),
            "AA:BB:CC:DD:EE:FF" to ShiftWritten(mode = ShiftMode.AUTO),
            "11:22:33:44:55:66" to ShiftWritten(region = SpeedLimit.EU, points = custom),
        )
        assertEquals(map, decodeShiftJournal(encodeShiftJournal(map)))
        assertEquals(emptyMap<String, ShiftWritten>(), decodeShiftJournal(encodeShiftJournal(mapOf("X" to ShiftWritten()))))
        for (junk in listOf(null, "", ";;", "MAC", "MAC|m=9", "MAC|r=EU", "MAC|p=1,2,3", "MAC|r=XX|p=1,2,3,4,5,6")) {
            assertEquals("$junk", emptyMap<String, ShiftWritten>(), decodeShiftJournal(junk))
        }
        assertEquals(mapOf("A" to ShiftWritten(mode = ShiftMode.MANUAL)), decodeShiftJournal("A|m=1|r=EU|p=oops;B|zz"))
    }

    @Test
    fun keptOnlyJudgedInSameRegion() {
        val w = ShiftWritten(ShiftMode.MANUAL, SpeedLimit.EU, flat)
        assertEquals(ShiftKept(mode = true, points = true), checkKept(w, ShiftMode.MANUAL, SpeedLimit.EU, flat))
        assertEquals(ShiftKept(mode = false, points = false), checkKept(w, ShiftMode.AUTO, SpeedLimit.EU, factory))
        assertEquals(ShiftKept(mode = null, points = null), checkKept(w, null, SpeedLimit.US, factory))
        assertEquals(ShiftKept(mode = true, points = null), checkKept(w, ShiftMode.MANUAL, SpeedLimit.EU, null))
        assertNull(checkKept(ShiftWritten(mode = ShiftMode.AUTO), ShiftMode.AUTO, SpeedLimit.EU, flat).points)
    }
}
