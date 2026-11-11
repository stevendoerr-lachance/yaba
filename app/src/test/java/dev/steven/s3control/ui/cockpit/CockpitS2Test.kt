package dev.steven.s3control.ui.cockpit

import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.S2State
import dev.steven.s3control.ble.featuresFor
import dev.steven.s3control.ride.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cockpit for an S2 (plan A1): speed, battery, its error code and the marks; no S3 lamps, no takeover. */
class CockpitS2Test {
    private var now = 1_000_000L
    private var seq = 0L
    private val session = CockpitSession({ now }, openSeq = 0)

    private fun s2(speed: Int = 20, code: Int? = null, lock: LockState = LockState.UNLOCKED, block: Boolean = true) = BikeState(
        family = BikeFamily.S2,
        features = featuresFor(BikeFamily.S2, false),
        link = Link.READY,
        speedKmh = speed,
        status = BikeStatus.RIDING,
        lock = lock,
        faultCodes = listOfNotNull(code),
        battery = 70,
        moduleBattery = 80,
        connectedAtMillis = now - 120_000,
        raw = if (block) mapOf(S2State.RAW_KEY to ByteArray(16), "t$now" to ByteArray(1)) else mapOf("t$now" to ByteArray(1)),
    )

    private fun event(code: EventLog.Code, value: Int? = null) =
        EventLog.Event(now, EventLog.Kind.ALERT, code.name, null, code, value, ++seq)

    private fun at(ms: Long) {
        now = 1_000_000L + ms
    }

    @Test
    fun noS3LampsButTheErrorCode() {
        session.onState(s2())
        val v = session.view
        assertFalse(v.s3Lamps)
        assertEquals(LampState.OK, v.errorCode.state)
        assertEquals(0, v.errorCode.count)
        for (lamp in listOf(v.coil, v.switch, v.key, v.supply)) assertEquals(LampState.UNKNOWN, lamp.state)
        assertEquals(20, v.speed)
        assertEquals(70, v.battery)
    }

    @Test
    fun anS2HasNoGearButItsAssistLevel() {
        session.onState(s2().copy(gear = 2, powerLevel = 3))
        assertNull(session.view.gear)
        assertEquals(3, session.view.powerLevel)
    }

    @Test
    fun errorCodeTripsAndCountsEachAppearance() {
        session.onState(s2())
        at(1_000)
        session.onState(s2(code = 16))
        assertEquals(LampState.TRIPPED, session.view.errorCode.state)
        assertEquals(16, session.view.errorCode.code)
        assertEquals(1, session.view.errorCode.count)
        at(2_000)
        session.onState(s2())
        assertEquals(LampState.OK, session.view.errorCode.state)
        assertEquals("the count stays after it clears", 1, session.view.errorCode.count)
        at(3_000)
        session.onState(s2(code = 16))
        assertEquals(2, session.view.errorCode.count)
    }

    @Test
    fun errorCodeUnknownUntilAStatusBlockArrived() {
        session.onState(s2(block = false))
        assertEquals(LampState.UNKNOWN, session.view.errorCode.state)
        session.onState(s2().copy(link = Link.LOST))
        assertEquals(LampState.UNKNOWN, session.view.errorCode.state)
    }

    @Test
    fun s2FaultAlertCarriesTheFamilyAndAnOrdinaryRank() {
        session.onState(s2())
        at(1_000)
        // 8 is "BMS output off" on an S3 (rank 7, a precursor); on an S2 it is a torque sensor error.
        session.onState(s2(code = 8))
        val a = session.view.alert
        assertNotNull(a)
        assertEquals(AlertKind.FAULT, a!!.kind)
        assertEquals(8, a.code)
        assertEquals(11, a.rank)
        assertEquals(BikeFamily.S2, a.family)
    }

    @Test
    fun noTakeoverOnAnS2() {
        session.onState(s2())
        at(10_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        assertNull(session.view.takeover)
        session.onEvent(event(EventLog.Code.SHUTDOWN_RIDING))
        assertNull(session.view.takeover)
        session.onEvent(event(EventLog.Code.FAULT, 8))
        session.onEvent(event(EventLog.Code.LINK_LOST_RIDING))
        assertNull(session.view.takeover)
    }

    @Test
    fun lockedWhileRidingIsNoAlertOnAnS2() {
        session.onState(s2())
        at(1_000)
        session.onState(s2(lock = LockState.LOCKED))
        assertTrue(session.view.alert?.kind != AlertKind.LOCK_RIDING)
    }

    @Test
    fun anS3SessionIsUnchanged() {
        val s3 = BikeState(
            link = Link.READY, speedKmh = 22, status = BikeStatus.RIDING, lock = LockState.UNLOCKED, faultCodes = listOf(42),
            supplyOk = true, battery = 70, connectedAtMillis = now - 120_000,
            raw = mapOf(Gatt.ERRORS.short to ByteArray(8)),
        )
        session.onState(s3)
        val v = session.view
        assertTrue(v.s3Lamps)
        assertEquals(LampState.TRIPPED, v.coil.state)
        assertEquals(42, v.coil.code)
        assertEquals(LampState.UNKNOWN, v.errorCode.state)
        assertEquals(4, v.alert?.rank)
        assertEquals(BikeFamily.UNKNOWN, v.alert?.family)
        at(10_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        assertEquals(TakeoverVariant.KICKLOCK, session.view.takeover?.variant)
    }
}
