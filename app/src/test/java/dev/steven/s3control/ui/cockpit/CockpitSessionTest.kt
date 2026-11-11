package dev.steven.s3control.ui.cockpit

import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ride.EventLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitSessionTest {
    private var now = 1_000_000L
    private var seq = 0L
    private val session = CockpitSession({ now }, openSeq = 0)

    private fun riding(
        speed: Int? = 22,
        faults: List<Int> = emptyList(),
        lock: LockState = LockState.UNLOCKED,
        link: Link = Link.READY,
        connectedAt: Long = now - 120_000,
        busy: Set<Op> = emptySet(),
    ) = BikeState(
        link = link,
        speedKmh = speed,
        status = BikeStatus.RIDING,
        lock = lock,
        faultCodes = faults,
        supplyOk = true,
        battery = 70,
        connectedAtMillis = connectedAt,
        busy = busy,
        // A new map instance per state: that is how the session sees fresh data.
        raw = mapOf(Gatt.ERRORS.short to ByteArray(8), "t$now" to ByteArray(1)),
    )

    private fun event(code: EventLog.Code, value: Int? = null, at: Long = now) =
        EventLog.Event(at, EventLog.Kind.ALERT, code.name, null, code, value, ++seq)

    private fun at(ms: Long) {
        now = 1_000_000L + ms
    }

    private fun loggerOn() = session.onRecorder(true, now, 0)

    @Test
    fun shippingReplacesKickLockAndVibratesAgain() {
        session.onState(riding())
        at(10_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        val first = session.view
        assertEquals(TakeoverVariant.KICKLOCK, first.takeover?.variant)
        assertEquals(HapticKind.TAKEOVER_KICK, first.haptic?.kind)
        at(11_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        val second = session.view
        assertEquals(TakeoverVariant.SHIPPING, second.takeover?.variant)
        assertTrue(TakeoverVariant.KICKLOCK in second.takeover!!.also)
        assertEquals(HapticKind.TAKEOVER_LONG, second.haptic?.kind)
        assertTrue(second.haptic!!.seq > first.haptic!!.seq)
    }

    @Test
    fun lowerPriorityOnlyAddsToAlso() {
        session.onState(riding())
        at(10_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        val cue = session.view.haptic
        at(11_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        assertEquals(TakeoverVariant.SHIPPING, session.view.takeover?.variant)
        assertEquals(listOf(TakeoverVariant.KICKLOCK), session.view.takeover?.also)
        assertEquals(cue, session.view.haptic)
    }

    @Test
    fun sameVariantWithin30sAfterDismissOnlyUpdatesMemory() {
        loggerOn()
        session.onState(riding())
        at(10_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        at(12_000)
        session.dismissTakeover()
        assertNull(session.view.takeover)
        at(20_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        assertNull(session.view.takeover)
        val alert = session.view.alert
        assertEquals(AlertKind.MEMORY, alert?.kind)
        assertEquals(2, alert?.n1)
        assertEquals(HapticKind.SIGNAL, session.view.haptic?.kind)
        // 30 s after the last trigger it opens again.
        at(51_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        assertEquals(TakeoverVariant.KICKLOCK, session.view.takeover?.variant)
    }

    @Test
    fun linkLostShortlyAfterCoilFaultMeansBikeOff() {
        session.onState(riding())
        at(10_000)
        session.onState(riding(faults = listOf(42)))
        at(14_000)
        session.onState(riding(link = Link.LOST, speed = null))
        session.onEvent(event(EventLog.Code.LINK_LOST_RIDING))
        val t = session.view.takeover
        assertEquals(TakeoverVariant.OFF, t?.variant)
        assertEquals(PrecursorKind.FAULT, t?.precursor)
        assertEquals(42, t?.precursorCode)
        assertEquals(4, t?.precursorAgoS)
    }

    @Test
    fun linkLostLongAfterThePrecursorIsNotOff() {
        session.onState(riding())
        at(10_000)
        session.onState(riding(faults = listOf(42)))
        at(34_000)
        session.onState(riding(faults = listOf(42)))
        at(35_000)
        session.onState(riding(link = Link.LOST, speed = null))
        session.onEvent(event(EventLog.Code.LINK_LOST_RIDING))
        assertNull(session.view.takeover)
        assertTrue(session.view.lostWhileMoving)
        assertEquals(AlertKind.LINK_LOST, session.view.alert?.kind)
    }

    @Test
    fun linkLostAfterShippingIsNotOff() {
        session.onState(riding())
        at(10_000)
        session.onState(riding(faults = listOf(42)))
        at(11_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        at(14_000)
        session.onState(riding(link = Link.LOST, speed = null))
        session.onEvent(event(EventLog.Code.LINK_LOST_RIDING))
        val t = session.view.takeover
        assertEquals(TakeoverVariant.SHIPPING, t?.variant)
        assertFalse(TakeoverVariant.OFF in t!!.also)
    }

    @Test
    fun kickLockRightAfterReconnectIsIgnored() {
        at(10_000)
        session.onState(riding(connectedAt = now - 2_000))
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        assertNull(session.view.takeover)
        at(14_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        assertEquals(TakeoverVariant.KICKLOCK, session.view.takeover?.variant)
    }

    @Test
    fun eventsFromBeforeTheOpeningDoNotCount() {
        val s = CockpitSession({ now }, openSeq = 5)
        s.onState(riding())
        s.onEvent(EventLog.Event(now, EventLog.Kind.ALERT, "x", null, EventLog.Code.SHIPPING, null, 5))
        assertNull(s.view.takeover)
    }

    @Test
    fun aOnePollFlickerIsHeldForEightSecondsThenHistory() {
        session.onState(riding())
        at(1_000)
        session.onState(riding(faults = listOf(42)))
        assertEquals(AlertKind.FAULT, session.view.alert?.kind)
        assertEquals(42, session.view.alert?.code)
        at(2_500)
        session.onState(riding())
        at(3_000)
        session.tick()
        // The logger-off line is live, but the 42 keeps the slot as history for its 8 s.
        assertEquals(AlertKind.HISTORY, session.view.alert?.kind)
        assertEquals(42, session.view.alert?.code)
        at(9_500)
        session.onState(riding())
        // The logger line had its 8 s; the slot keeps the last signal as history, the logger
        // state moved to the status row.
        assertEquals(AlertKind.HISTORY, session.view.alert?.kind)
        assertTrue(session.view.loggerOffQuiet)
    }

    @Test
    fun loggerOffHoldsTheLineOnlyRightAfterOpeningAndNeverCountsAsMore() {
        session.onState(riding())
        assertEquals(AlertKind.LOGGER_OFF, session.view.alert?.kind)
        assertFalse(session.view.loggerOffQuiet)
        at(8_500)
        session.onState(riding())
        assertNull(session.view.alert)
        assertTrue(session.view.loggerOffQuiet)
        at(9_000)
        session.onState(riding().copy(battery = 12))
        assertEquals(AlertKind.BATTERY_LOW, session.view.alert?.kind)
        assertEquals(0, session.view.alert?.more)
    }

    @Test
    fun aHigherRankBreaksTheHold() {
        loggerOn()
        session.onState(riding())
        at(1_000)
        session.onState(riding(faults = listOf(45)))
        assertEquals(45, session.view.alert?.code)
        at(2_000)
        session.onState(riding(faults = listOf(42, 45)))
        assertEquals(42, session.view.alert?.code)
        assertEquals(1, session.view.alert?.more)
    }

    @Test
    fun baselineFaultCountsOnlyTowardMoreAfterEightSeconds() {
        session.onState(riding(faults = listOf(44)))
        assertEquals(AlertKind.FAULT, session.view.alert?.kind)
        assertEquals(44, session.view.alert?.code)
        at(9_000)
        session.onState(riding(faults = listOf(44)).copy(battery = 12))
        val a = session.view.alert
        assertEquals(AlertKind.BATTERY_LOW, a?.kind)
        assertEquals(1, a?.more)
    }

    @Test
    fun markGateIsOneAndAHalfSeconds() {
        val marks = CockpitMarks()
        assertEquals(1, marks.next(0))
        assertNull(marks.next(1_000))
        assertNull(marks.next(1_499))
        assertEquals(2, marks.next(1_500))
        assertEquals(3, marks.next(5_000))
    }

    @Test
    fun staleAfterFourAndAHalfSecondsButNotWhileReadingTheLog() {
        session.onState(riding())
        at(4_400)
        session.tick()
        assertEquals(CockpitLink.LIVE, session.view.link)
        at(4_600)
        session.tick()
        assertEquals(CockpitLink.STALE, session.view.link)
        assertEquals(MatrixKind.STALE, session.view.matrix)

        val s = CockpitSession({ now }, openSeq = 0)
        at(0)
        s.onState(riding(busy = setOf(Op.BIKE_LOG)))
        at(20_000)
        s.tick()
        assertEquals(CockpitLink.LIVE, s.view.link)
        // Once the read is over, the clock starts again from the end of the pause.
        s.onState(s.view.let { riding(busy = emptySet()) })
        at(23_000)
        s.tick()
        assertEquals(CockpitLink.LIVE, s.view.link)
    }

    @Test
    fun twentySecondsStillRidingHandsTheCockpitBackAndPhaseTwoWaitsForTheStop() {
        loggerOn()
        session.onState(riding())
        at(1_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        at(19_000)
        session.onState(riding(speed = 25))
        assertEquals(1, session.view.takeover?.phase)
        at(21_000)
        session.onState(riding(speed = 24))
        assertNull(session.view.takeover)
        assertEquals(AlertKind.MEMORY, session.view.alert?.kind)
        // Stopping for 3 s brings the steps.
        at(30_000)
        session.onState(riding(speed = 0))
        at(32_000)
        session.tick()
        assertNull(session.view.takeover)
        at(33_100)
        session.tick()
        assertEquals(TakeoverVariant.SHIPPING, session.view.takeover?.variant)
        assertEquals(2, session.view.takeover?.phase)
    }

    @Test
    fun phaseTwoGivesTheCockpitBackWhenRidingOn() {
        loggerOn()
        session.onState(riding())
        at(1_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        at(2_000)
        session.onState(riding(speed = 0))
        at(5_500)
        session.tick()
        assertEquals(2, session.view.takeover?.phase)
        at(6_000)
        session.onState(riding(speed = 18))
        at(11_000)
        session.onState(riding(speed = 20))
        assertEquals(2, session.view.takeover?.phase)
        at(12_100)
        session.onState(riding(speed = 21))
        assertNull(session.view.takeover)
        assertEquals(AlertKind.MEMORY, session.view.alert?.kind)
        // A tap on the memory line while riding on opens it again, and it stays for a look.
        at(15_000)
        session.reopenTakeover()
        assertEquals(2, session.view.takeover?.phase)
        at(16_000)
        session.onState(riding(speed = 22))
        assertEquals(2, session.view.takeover?.phase)
        at(21_100)
        session.onState(riding(speed = 22))
        assertNull(session.view.takeover)
        // Phase 2 was already seen parked: the next stop does not bring it back on its own.
        at(25_000)
        session.onState(riding(speed = 0))
        at(30_000)
        session.tick()
        assertNull(session.view.takeover)
    }

    @Test
    fun pushingTheBikeAtWalkingPaceKeepsPhaseTwo() {
        session.onState(riding())
        at(1_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        // Standing in the speed reading, but the wheel flag (48) still set: pushing the bike.
        at(2_000)
        session.onState(riding(speed = 1, faults = listOf(48)))
        at(5_100)
        session.tick()
        assertEquals(2, session.view.takeover?.phase)
        at(15_000)
        session.onState(riding(speed = 2, faults = listOf(48)))
        assertEquals(2, session.view.takeover?.phase)
    }

    @Test
    fun aHandedBackKickLockKeepsTheLockLineReopenable() {
        loggerOn()
        session.onState(riding())
        at(1_000)
        session.onState(riding(lock = LockState.LOCKED))
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        at(12_000)
        session.onState(riding(lock = LockState.LOCKED, speed = 21))
        session.acknowledge() // still riding: back to the cockpit
        assertNull(session.view.takeover)
        // The live lock report and the moment are one line, on top, and it reopens the moment.
        val alert = session.view.alert
        assertEquals(AlertKind.MEMORY, alert?.kind)
        assertEquals(5, alert?.rank)
        assertEquals(AlertAction.REOPEN, alert?.action)
        assertEquals(0, alert?.more)
        // Unlocked again: the memory line goes back to its own quiet rank.
        at(14_000)
        session.onState(riding(speed = 21))
        at(23_000)
        session.tick()
        assertEquals(12, session.view.alert?.rank)
    }

    @Test
    fun theLogReadIsNotOfferedWhileMoving() {
        loggerOn()
        session.onState(riding())
        at(1_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        at(3_000)
        session.onState(riding(speed = 22))
        session.acknowledge() // riding: back to the cockpit
        assertNull(session.view.takeover)
        session.reopenTakeover() // a tap on the memory line, still riding
        assertEquals(2, session.view.takeover?.phase)
        assertEquals(TakeoverPrimary.READ_AT_STOP, session.view.takeover?.primary)
        at(4_000)
        session.onState(riding(speed = 0))
        at(5_500)
        session.tick()
        assertEquals(TakeoverPrimary.READ_AT_STOP, session.view.takeover?.primary)
        // Standing 3 s is a stop: the read is offered at once.
        at(7_100)
        session.tick()
        assertEquals(TakeoverPrimary.READ_LOG, session.view.takeover?.primary)
    }

    @Test
    fun phaseTwoWhenTheBikeSlowsDown() {
        session.onState(riding())
        at(1_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        at(2_000)
        session.onState(riding(speed = 2))
        at(4_500)
        session.tick()
        assertEquals(1, session.view.takeover?.phase)
        at(5_100)
        session.tick()
        assertEquals(2, session.view.takeover?.phase)
    }

    @Test
    fun phaseTwoWhenTheLinkIsGoneFiveSeconds() {
        session.onState(riding())
        at(1_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        at(2_000)
        session.onState(riding(link = Link.LOST, speed = null))
        at(6_900)
        session.tick()
        assertEquals(1, session.view.takeover?.phase)
        at(7_100)
        session.tick()
        assertEquals(2, session.view.takeover?.phase)
    }

    @Test
    fun understoodIsIgnoredForTheFirstSecondAndAHalf() {
        session.onState(riding(speed = 0).copy(status = BikeStatus.READY))
        at(1_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        at(2_000)
        session.acknowledge()
        assertEquals(1, session.view.takeover?.phase)
        at(2_600)
        session.acknowledge()
        assertEquals(2, session.view.takeover?.phase)
    }

    @Test
    fun understoodWhileRidingGoesBackToTheCockpit() {
        loggerOn()
        session.onState(riding())
        at(1_000)
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        at(3_000)
        session.onState(riding(speed = 23))
        session.acknowledge()
        assertNull(session.view.takeover)
        assertEquals(AlertKind.MEMORY, session.view.alert?.kind)
        assertEquals(TakeoverVariant.KICKLOCK, session.view.alert?.variant)
    }

    @Test
    fun kickLockNamesTheCoilFlickerBeforeIt() {
        session.onState(riding())
        at(10_000)
        session.onState(riding(faults = listOf(42)))
        at(10_800)
        session.onState(riding())
        at(11_500)
        session.onState(riding(lock = LockState.LOCKED))
        session.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        val t = session.view.takeover
        assertEquals(TakeoverVariant.KICKLOCK, t?.variant)
        // The lock report is the trigger itself, not its own precursor.
        assertEquals(listOf(Evidence(PrecursorKind.FAULT, 42, 1_500)), t?.evidence)
        // Older than 12 s does not count.
        val s = CockpitSession({ now }, openSeq = 0)
        at(20_000)
        s.onState(riding())
        at(21_000)
        s.onState(riding(faults = listOf(42)))
        at(22_000)
        s.onState(riding())
        at(34_000)
        s.onEvent(event(EventLog.Code.SHIPPING))
        assertEquals(emptyList<Evidence>(), s.view.takeover?.evidence)
    }

    @Test
    fun theMarkSinceTheTriggerIsKnown() {
        session.onState(riding())
        at(1_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        assertFalse(session.markedSinceTrigger())
        assertEquals(0, session.view.takeover?.markNo)
        assertEquals(1, session.view.takeover?.nextMarkNo)
        at(1_200)
        session.onMark(1)
        assertTrue(session.markedSinceTrigger())
        assertEquals(1, session.view.takeover?.markNo)
    }

    @Test
    fun backWhileMovingAsksTwice() {
        session.onState(riding())
        assertEquals(BackResult.HINT, session.back())
        assertEquals(AlertKind.BACK_AGAIN, session.view.alert?.kind)
        at(1_000)
        assertEquals(BackResult.CLOSE, session.back())
    }

    @Test
    fun backWhenParkedClosesAtOnce() {
        session.onState(riding(speed = 0).copy(status = BikeStatus.READY))
        at(6_000)
        session.tick()
        assertEquals(BackResult.CLOSE, session.back())
    }

    @Test
    fun snapshotFreezesTheMoment() {
        session.onState(riding(speed = 24, faults = listOf(42)))
        at(1_000)
        session.onEvent(event(EventLog.Code.SHIPPING))
        at(3_000)
        session.onState(riding(link = Link.LOST, speed = null))
        val snap = session.view.takeover?.snapshot
        assertNotNull(snap)
        assertEquals(24, snap?.speed)
        assertEquals(listOf(42), snap?.faults)
        assertEquals(1_001_000L, snap?.atMillis)
    }

    @Test
    fun lampCounterCountsRisingEdgesAndStays() {
        loggerOn()
        session.onState(riding())
        at(1_000)
        session.onState(riding(faults = listOf(42)))
        at(2_000)
        session.onState(riding())
        at(3_000)
        session.onState(riding(faults = listOf(42)))
        at(4_000)
        session.onState(riding())
        assertEquals(LampState.OK, session.view.coil.state)
        assertEquals(2, session.view.coil.count)
    }

    @Test
    fun lockWhileRidingTripsTheSwitchLamp() {
        session.onState(riding(lock = LockState.LOCKED))
        assertEquals(LampState.TRIPPED, session.view.switch.state)
        session.onState(riding(speed = 0, lock = LockState.LOCKED).copy(status = BikeStatus.READY))
        assertEquals(LampState.SIGNAL_DOT, session.view.switch.state)
    }

    @Test
    fun aKickLockWhileRidingHandsTheCockpitBackAfterSixSecondsWithItsMark() {
        var marked = 0
        val s = CockpitSession({ now }, openSeq = 0, autoMark = { ++marked })
        s.onRecorder(true, now, 0)
        s.onState(riding())
        at(1_000)
        s.onState(riding(lock = LockState.LOCKED))
        s.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        assertEquals(1, s.view.takeover?.phase)
        at(6_900)
        s.onState(riding(lock = LockState.LOCKED, speed = 21))
        assertEquals("still under 6 s", 1, s.view.takeover?.phase)
        assertEquals(0, marked)
        at(7_100)
        s.tick()
        assertNull(s.view.takeover)
        // The mark the capsule would have set, once, and the moment in the memory line on top.
        assertEquals(1, marked)
        assertEquals(1, s.view.marks)
        assertEquals(AlertKind.MEMORY, s.view.alert?.kind)
        assertEquals(AlertAction.REOPEN, s.view.alert?.action)
        at(8_000)
        s.tick()
        assertEquals(1, marked)
        // Phase 2 still comes at the next stop, with that mark.
        at(10_000)
        s.onState(riding(speed = 0))
        at(13_100)
        s.tick()
        assertEquals(TakeoverVariant.KICKLOCK, s.view.takeover?.variant)
        assertEquals(2, s.view.takeover?.phase)
        assertEquals(1, s.view.takeover?.markNo)
    }

    @Test
    fun aMarkSinceTheKickLockTriggerIsNotSetAgainAndShippingKeepsItsTwentySeconds() {
        var marked = 0
        val s = CockpitSession({ now }, openSeq = 0, autoMark = { ++marked })
        s.onState(riding())
        at(1_000)
        s.onEvent(event(EventLog.Code.KICKLOCK_RIDING))
        at(2_000)
        s.onMark(7)
        at(7_100)
        s.onState(riding(speed = 20))
        assertNull(s.view.takeover)
        assertEquals(0, marked)
        assertEquals(1, s.view.marks)
        // Shipping is not handed back after 6 s.
        at(60_000)
        s.onEvent(event(EventLog.Code.SHIPPING))
        at(67_000)
        s.onState(riding(speed = 22))
        assertEquals(TakeoverVariant.SHIPPING, s.view.takeover?.variant)
        assertEquals(1, s.view.takeover?.phase)
        assertEquals(0, marked)
    }

    @Test
    fun theViewCarriesGearAndAssistLevelOnlyWhileLive() {
        session.onState(riding().copy(gear = 3, powerLevel = 2))
        assertEquals(3, session.view.gear)
        assertEquals(2, session.view.powerLevel)
        session.onState(riding().copy(gear = null, powerLevel = 0))
        assertNull(session.view.gear)
        assertEquals(0, session.view.powerLevel)
        session.onState(riding(link = Link.LOST, speed = null).copy(gear = 3, powerLevel = 2))
        assertNull(session.view.gear)
        assertNull(session.view.powerLevel)
    }
}
