package dev.steven.s3control

import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.Buttons
import dev.steven.s3control.ble.DerateTable
import dev.steven.s3control.ble.LabProtocol
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.SoundTiers
import dev.steven.s3control.ble.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabProtocolTest {

    // --- Clock 5567 ---------------------------------------------------------------------------
    @Test fun clockWritesBigEndianReadsLittleEndian() {
        val epoch = 1_723_229_090L  // 0x66B663A2
        assertEquals("66b663a2", LabProtocol.clockWriteBE(epoch).toHex())
        assertEquals("a263b666", LabProtocol.clockWriteLE(epoch).toHex())
        // Read-back is little-endian.
        assertEquals(epoch, LabProtocol.clockReadLE(LabProtocol.clockWriteLE(epoch)))
    }

    // --- Light sensor 5584 --------------------------------------------------------------------
    @Test fun lightThresholdIsAlwaysFourBytesBigEndian() {
        val p = LabProtocol.lightThreshold(200)
        assertEquals(4, p.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 200.toByte()), p)
        assertEquals(200 to 500, LabProtocol.decodeLightSensor(byteArrayOf(0, 200.toByte(), 1, 0xF4.toByte())))
    }

    @Test fun lightThresholdRejectsTheFfffTrapButAllowsZero() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), LabProtocol.lightThreshold(0))
        // 0xFFFF is rejected by the firmware; it is clamped to 0xFFFE.
        assertArrayEquals(byteArrayOf(0, 0, 0xFF.toByte(), 0xFE.toByte()), LabProtocol.lightThreshold(0xFFFF))
    }

    // --- Lamps 5582 ---------------------------------------------------------------------------
    @Test fun lampRoundTrip() {
        assertArrayEquals(byteArrayOf(100, 0, 100), LabProtocol.lampWrite(100, 0, 100))
        assertEquals(listOf(100, 0, 100), LabProtocol.decodeLamps(byteArrayOf(100, 0, 100)))
    }

    // --- Assist-cut payloads ------------------------------------------------------------------
    @Test fun sirenStageOnlyTwoToFour() {
        assertArrayEquals(byteArrayOf(2), LabProtocol.sirenPayload(2))
        assertArrayEquals(byteArrayOf(4), LabProtocol.sirenPayload(4))
        for (bad in listOf(0, 1, 5)) {
            try { LabProtocol.sirenPayload(bad); error("stage $bad should throw") } catch (e: IllegalArgumentException) {}
        }
    }

    @Test fun alarmStopSleepSelfTestPayloads() {
        assertArrayEquals(byteArrayOf(0), LabProtocol.alarmStopPayload)
        assertArrayEquals(byteArrayOf(1), LabProtocol.sleepPayload)
        assertArrayEquals(byteArrayOf(3), LabProtocol.selfTestPayload)
    }

    @Test fun logSeekIsOpcodeTwoThenBigEndianEpoch() {
        assertArrayEquals(byteArrayOf(0x02, 0x66, 0xB6.toByte(), 0x63, 0xA2.toByte()), LabProtocol.logSeekPayload(1_723_229_090L))
        try { LabProtocol.logSeekPayload(0); error("epoch 0 should throw") } catch (e: IllegalArgumentException) {}
    }

    // --- Boost in motion 5534 byte 1 ----------------------------------------------------------
    @Test fun boostInMotionEncodesLevelAndFlag() {
        assertArrayEquals(byteArrayOf(3, 1), LabProtocol.boostInMotionWrite(3, true))
        assertArrayEquals(byteArrayOf(2, 0), LabProtocol.boostInMotionWrite(2, false))
        assertEquals(true, LabProtocol.decodeBoostInMotion(byteArrayOf(3, 1)))
        assertEquals(false, LabProtocol.decodeBoostInMotion(byteArrayOf(3, 0)))
    }

    // --- Charge cycles 5541 -------------------------------------------------------------------
    @Test fun chargeCyclesAreBytesTwoThreeLittleEndian() {
        val v = byteArrayOf(80, 100, 0x2C, 0x01, 1, 60, 100, 0x0A, 0x00, 1)  // cycles = 0x012C = 300
        assertEquals(300, LabProtocol.chargeCycles(v))
        assertEquals(true, LabProtocol.powerBankPresent(v))
        assertEquals(10, LabProtocol.powerBankCycles(v))
    }

    // --- Parked-only guard --------------------------------------------------------------------
    private fun state(
        ready: Boolean = true, speed: Int? = 0, status: BikeStatus? = BikeStatus.READY,
        buttons: Buttons? = Buttons(false, false, false), faults: List<Int> = emptyList(),
    ) = BikeState(
        link = if (ready) Link.READY else Link.OFFLINE,
        speedKmh = speed, status = status, buttons = buttons, faultCodes = faults,
    )

    @Test fun parkedUsesMovingNotStatusRiding() {
        assertTrue(LabProtocol.isParked(state()))
        assertFalse(LabProtocol.isParked(state(ready = false)))
        // Moving is speed >= 3 km/h or the wheel-moving bit — never status == RIDING.
        assertFalse(LabProtocol.isParked(state(speed = 4)))
        assertTrue(LabProtocol.isParked(state(speed = 2)))
        // RIDING (0x0C) standing still is unlocked-and-ready, not moving: these writes are allowed.
        assertTrue(LabProtocol.isParked(state(status = BikeStatus.RIDING)))
        assertFalse(LabProtocol.isParked(state(status = BikeStatus.RIDING, speed = 5)))
        assertFalse(LabProtocol.isParked(state(buttons = Buttons(false, true, false))))
        assertFalse(LabProtocol.isParked(state(faults = listOf(dev.steven.s3control.ble.Faults.WHEEL_MOVING))))
        // Unknown buttons (null) do not by themselves block a standstill action.
        assertTrue(LabProtocol.isParked(state(buttons = null)))
    }

    @Test fun clockSettableOnlyNeedsNoBellOrBoostHeld() {
        assertTrue(LabProtocol.clockSettable(state()))
        assertFalse(LabProtocol.clockSettable(state(ready = false)))
        // The clock risk is a stuck-button mis-detect, so only Bell/Boost held blocks it — not motion.
        assertTrue(LabProtocol.clockSettable(state(speed = 9)))
        assertFalse(LabProtocol.clockSettable(state(buttons = Buttons(true, false, false))))
        assertFalse(LabProtocol.clockSettable(state(buttons = Buttons(false, true, false))))
        // The power button alone does not block the clock (it is not a held Bell/Boost).
        assertTrue(LabProtocol.clockSettable(state(buttons = Buttons(false, false, true))))
    }

    // --- Clock read-back verdict (byte-swap decision) -----------------------------------------
    @Test fun clockVerdictAcceptsAPlausibleBigEndianReadBack() {
        val wrote = 1_723_229_090L
        // LE read-back close to what we wrote (ordinary latency): OK, no LE rewrite.
        val raw = LabProtocol.clockWriteLE(wrote + 3)
        assertEquals(LabProtocol.ClockVerdict.OK, LabProtocol.clockVerdict(wrote + 3, wrote, raw))
    }

    @Test fun clockVerdictDetectsASwappedStore() {
        val wrote = 1_723_229_090L  // 0x66B663A2
        // 2.4.1 stored it the other way: the LE reading is garbage, but its byte-swap equals what we wrote.
        val rawStoredBE = LabProtocol.clockWriteBE(wrote)          // bytes 66 B6 63 A2
        val garbageLe = LabProtocol.clockReadLE(rawStoredBE)       // ~0xA263B666, far from `wrote`
        assertEquals(LabProtocol.ClockVerdict.SWAPPED, LabProtocol.clockVerdict(garbageLe, wrote, rawStoredBE))
    }

    @Test fun clockVerdictUnknownWhenNeitherMatches() {
        val wrote = 1_723_229_090L
        val raw = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        assertEquals(LabProtocol.ClockVerdict.UNKNOWN, LabProtocol.clockVerdict(0x44332211L, wrote, raw))
        assertEquals(LabProtocol.ClockVerdict.UNKNOWN, LabProtocol.clockVerdict(null, wrote, ByteArray(0)))
    }

    // --- Derate table (section 1.4) -----------------------------------------------------------
    @Test fun derateMatchesTheDocumentedGrid() {
        // warm (<= 60C): 10/15/20/30 across the SoC bands
        assertEquals(10, DerateTable.limitAmps(13, 25.0))
        assertEquals(15, DerateTable.limitAmps(18, 25.0))
        assertEquals(20, DerateTable.limitAmps(22, 25.0))
        assertEquals(30, DerateTable.limitAmps(80, 25.0))
        // cool (<= 5C): 10/10/15/20
        assertEquals(10, DerateTable.limitAmps(18, 3.0))
        assertEquals(15, DerateTable.limitAmps(22, 3.0))
        assertEquals(20, DerateTable.limitAmps(80, 3.0))
        // freezing (<= 0C): all 10
        assertEquals(10, DerateTable.limitAmps(80, -2.0))
        // empty / saving: no assist
        assertEquals(0, DerateTable.limitAmps(3, 25.0))
        assertEquals(0, DerateTable.limitAmps(5, 25.0))
        assertTrue(DerateTable.predict(80, 25.0).full)
        assertTrue(DerateTable.predict(2, 25.0).noAssist)
    }
}
