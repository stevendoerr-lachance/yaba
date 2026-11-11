package dev.steven.s3control

import dev.steven.s3control.ble.ArmOutcome
import dev.steven.s3control.ble.ArmPath
import dev.steven.s3control.ble.ArmResult
import dev.steven.s3control.ble.GuardAction
import dev.steven.s3control.ble.GuardInput
import dev.steven.s3control.ble.GuardIssue
import dev.steven.s3control.ble.GuardLogLine
import dev.steven.s3control.ble.GuardLogic
import dev.steven.s3control.ble.GuardMachine
import dev.steven.s3control.ble.GuardPhase
import dev.steven.s3control.ble.GuardRules
import dev.steven.s3control.ble.GuardSnapshot
import dev.steven.s3control.ble.GuardTrigger
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.SequenceKind
import dev.steven.s3control.ble.SequenceReadback
import dev.steven.s3control.ble.SirenNote
import dev.steven.s3control.ble.StoredGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wache's pure state machine (the guard design 3, 4, 5; test list 9.5). */
class GuardLogicTest {

    // --- Harness ------------------------------------------------------------------------------

    private class Run(var m: GuardMachine = GuardMachine()) {
        val all = ArrayList<GuardAction>()

        fun feed(i: GuardInput): List<GuardAction> {
            val (next, actions) = GuardLogic.step(m, i)
            m = next
            all += actions
            return actions
        }

        fun see(s: GuardSnapshot) = feed(GuardInput.Snapshot(s))
        fun tick(at: Long) = feed(GuardInput.Tick(at))
    }

    private fun snap(
        at: Long,
        defence: Int? = 2,
        lock: LockState? = LockState.LOCKED,
        status: Int? = 0,
        speed: Int? = 0,
        wheel: Boolean = false,
        button: Boolean = false,
        battery: Boolean? = true,
        ready: Boolean = true,
        same: Boolean = true,
    ) = GuardSnapshot(at, ready, same, lock, defence, status, speed, wheel, button, battery)

    private fun rb(defence: Int?, lock: Int? = 1, writes: Int = 1, ok: Boolean = true) =
        SequenceReadback(defence = defence, lock = lock, writes = writes, ok = ok)

    private fun done(
        at: Long, kind: SequenceKind, readback: SequenceReadback?, ok: Boolean = true, error: String? = null,
        attempted: Int = 0, cancelled: Boolean = false,
    ) = GuardInput.SequenceDone(at, kind, ok, readback, error, attempted, cancelled)

    /** Armed at [T0] (native, alarm mode switched on by the Wache); the quiet window ends at T0 + 3.2 s. */
    private fun armed(
        outcome: ArmOutcome = ArmOutcome.WATCHING,
        first: GuardSnapshot = snap(T0 + 100),
    ): Run {
        val r = Run()
        r.see(snap(T0 - 100))
        r.feed(GuardInput.Arm(T0))
        r.see(first)
        r.feed(
            GuardInput.ArmDone(
                T0 + 200,
                ArmResult(outcome, modeWasOff = true, path = if (outcome == ArmOutcome.WATCHING) ArmPath.NATIVE else null, defence = if (outcome == ArmOutcome.WATCHING) 2 else 1, lock = 1),
            ),
        )
        return r
    }

    /** Armed and the wheel already seen off once (wheel rules live). */
    private fun watching(): Run = armed().also { it.see(snap(A)) }

    private fun List<GuardAction>.alarms() = filterIsInstance<GuardAction.NotifyAlarm>()
    private fun List<GuardAction>.sirens() = count { it == GuardAction.RunSiren }
    private fun List<GuardAction>.rearms() = filterIsInstance<GuardAction.RunRearm>()
    private fun List<GuardAction>.logs() = filterIsInstance<GuardAction.Log>().map { it.line }

    /** A button press at [at] (released just before). */
    private fun Run.press(at: Long, defence: Int? = 2): List<GuardAction> {
        see(snap(at - 50, defence = defence))
        return see(snap(at, defence = defence, button = true))
    }

    // --- 1. Arming ----------------------------------------------------------------------------

    @Test
    fun armRunsTheSequenceAndWatchesOnSuccess() {
        val r = Run()
        assertEquals(listOf(GuardAction.RunArm), r.feed(GuardInput.Arm(T0)))
        assertEquals(GuardPhase.ARMING, r.m.phase)
        val a = r.feed(GuardInput.ArmDone(T0 + 9_000, ArmResult(ArmOutcome.WATCHING, modeWasOff = true, path = ArmPath.NATIVE, defence = 2, lock = 1, waitedMs = 6_500)))
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertTrue(r.m.bikeWatch)
        assertTrue(r.m.restoreAlarmMode)
        assertEquals(ArmPath.NATIVE, r.m.armPath)
        assertTrue(GuardAction.Persist in a)
        assertTrue(a.logs().single() is GuardLogLine.ArmFinished)
    }

    @Test
    fun unconfirmedArmStillWatchesButWithoutBikeWatch() {
        val r = armed(ArmOutcome.UNCONFIRMED)
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertFalse(r.m.bikeWatch)
        assertNull(r.m.armPath)
    }

    @Test
    fun refusedLockEndsTheWacheWithTheValue() {
        val r = Run()
        r.feed(GuardInput.Arm(T0))
        val a = r.feed(GuardInput.ArmDone(T0 + 2_000, ArmResult(ArmOutcome.LOCK_REFUSED, modeWasOff = true, value = 0)))
        assertEquals(GuardPhase.OFF, r.m.phase)
        assertEquals(GuardIssue.ARM_LOCK, r.m.issue)
        assertEquals(0, r.m.issueValue)
        assertTrue(GuardAction.Stop in a)
    }

    @Test
    fun failedArmCarriesTheErrorText() {
        val r = Run()
        r.feed(GuardInput.Arm(T0))
        r.feed(GuardInput.ArmDone(T0 + 2_000, ArmResult(ArmOutcome.FAILED, error = "GATT 133")))
        assertEquals(GuardIssue.ARM_FAILED, r.m.issue)
        assertEquals("GATT 133", r.m.issueDetail)
    }

    @Test
    fun alarmDuringArmingEndsWithItsIssue() {
        val r = Run()
        r.feed(GuardInput.Arm(T0))
        r.feed(GuardInput.ArmDone(T0 + 4_000, ArmResult(ArmOutcome.ALARM_DURING, value = 3)))
        assertEquals(GuardPhase.OFF, r.m.phase)
        assertEquals(GuardIssue.ARM_ALARM_DURING, r.m.issue)
        assertEquals(3, r.m.issueValue)
    }

    @Test
    fun offIgnoresEverything() {
        val r = Run()
        assertTrue(r.see(snap(T0, speed = 20, button = true)).isEmpty())
        assertTrue(r.feed(GuardInput.UnlockRequest(T0)).isEmpty())
        assertTrue(r.tick(T0).isEmpty())
        assertTrue(r.feed(GuardInput.Disarm(T0)).isEmpty())
        assertEquals(GuardPhase.OFF, r.m.phase)
    }

    // --- 2. Wheel ----------------------------------------------------------------------------

    @Test
    fun wheelBitOnceIsNothingButHeldOneSecondCounts() {
        val r = watching()
        val first = r.see(snap(A + 100, wheel = true))
        assertTrue(first.alarms().isEmpty())
        assertTrue(r.tick(A + 600).alarms().isEmpty())
        val held = r.tick(A + 1_100)
        assertEquals(GuardTrigger.WHEEL, held.alarms().single().alarm.trigger)
        assertEquals(1, held.sirens())
        assertEquals(GuardPhase.ALARM, r.m.phase)
    }

    @Test
    fun wheelSignalOffBeforeTheHoldIsNothing() {
        val r = watching()
        r.see(snap(A + 100, speed = 1))
        r.see(snap(A + 700, speed = 0))
        assertTrue(r.tick(A + 1_200).alarms().isEmpty())
        assertEquals(GuardPhase.WATCHING, r.m.phase)
    }

    @Test
    fun oneFastReadingCountsAtOnce() {
        val r = watching()
        val a = r.see(snap(A + 100, speed = GuardRules.WHEEL_FAST_KMH))
        assertEquals(GuardTrigger.WHEEL, a.alarms().single().alarm.trigger)
        assertEquals(SirenNote.YABA, a.alarms().single().alarm.siren)
    }

    @Test
    fun twoWheelStartsWithinFiveSecondsCount() {
        val r = watching()
        assertTrue(r.see(snap(A + 100, speed = 1)).alarms().isEmpty())
        assertTrue(r.see(snap(A + 400, speed = 0)).alarms().isEmpty())
        val second = r.see(snap(A + 2_000, speed = 1))
        assertEquals(GuardTrigger.WHEEL, second.alarms().single().alarm.trigger)
    }

    @Test
    fun twoWheelStartsFurtherApartAreNothing() {
        val r = watching()
        r.see(snap(A + 100, speed = 1))
        r.see(snap(A + 400, speed = 0))
        assertTrue(r.see(snap(A + 6_000, speed = 1)).alarms().isEmpty())
    }

    @Test
    fun aStuckWheelBitCountsOnlyAfterItWasOffOnce() {
        val r = armed(first = snap(T0 + 100, wheel = true))
        assertTrue(r.see(snap(A, wheel = true)).alarms().isEmpty())
        assertTrue(r.tick(A + 5_000).alarms().isEmpty())
        assertTrue(r.see(snap(A + 6_000, speed = 2, wheel = true)).alarms().isEmpty())
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        r.see(snap(A + 7_000, wheel = false))
        r.see(snap(A + 7_100, wheel = true))
        assertEquals(GuardTrigger.WHEEL, r.tick(A + 8_200).alarms().single().alarm.trigger)
    }

    @Test
    fun aWheelThatKeepsTurningCountsAgainEverySirenRepeat() {
        val r = watching()
        r.see(snap(A + 100, speed = 3))
        r.feed(done(A + 800, SequenceKind.SIREN, rb(4)))
        // Still rolling: the movement time stays fresh, nothing new before 35 s.
        for (t in 2..34) assertTrue(r.tick(A + 100 + t * 1_000L).alarms().isEmpty())
        val again = r.tick(A + 100 + GuardRules.SIREN_REPEAT_MS)
        assertEquals(GuardTrigger.WHEEL, again.alarms().single().alarm.trigger)
        assertEquals(1, again.sirens())
        assertTrue(r.all.rearms().isEmpty())
    }

    // --- 3. Unlock requests ----------------------------------------------------------------------

    @Test
    fun unlockRequestWhileWatchingCounts() {
        val r = watching()
        val a = r.feed(GuardInput.UnlockRequest(A + 100))
        assertEquals(GuardTrigger.UNLOCK_REQUEST, a.alarms().single().alarm.trigger)
        assertEquals(1, a.sirens())
    }

    @Test
    fun unlockRequestWhileArmingIsOnlyLogged() {
        val r = Run()
        r.feed(GuardInput.Arm(T0))
        r.see(snap(T0 + 100, defence = 1))
        val a = r.feed(GuardInput.UnlockRequest(T0 + 3_000))
        assertTrue(a.alarms().isEmpty())
        assertEquals(GuardLogLine.Ignored(GuardTrigger.UNLOCK_REQUEST), a.logs().single())
        assertEquals(GuardPhase.ARMING, r.m.phase)
    }

    @Test
    fun unlockRequestInsideTheQuietWindowIsNothing() {
        val r = armed(ArmOutcome.UNCONFIRMED) // no settle: only the quiet window
        assertTrue(r.feed(GuardInput.UnlockRequest(T0 + 1_000)).isEmpty())
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertEquals(GuardTrigger.UNLOCK_REQUEST, r.feed(GuardInput.UnlockRequest(T0 + 3_300)).alarms().single().alarm.trigger)
    }

    @Test
    fun unlockRequestDuringTheBikesSettleAfterANativeArmIsOnlyLogged() {
        // Armed natively at T0 + 200: 5523 read 2 at the start of PRE_M1 (5 s), where the firmware asks the app to unlock
        // on motion. Past the 3 s quiet window but inside the settle: logged, not counted.
        val r = armed()
        val early = r.feed(GuardInput.UnlockRequest(T0 + 4_000))
        assertTrue(early.alarms().isEmpty())
        assertEquals(GuardLogLine.Ignored(GuardTrigger.UNLOCK_REQUEST), early.logs().single())
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertEquals(GuardTrigger.UNLOCK_REQUEST, r.feed(GuardInput.UnlockRequest(T0 + 200 + GuardLogic.SETTLE_MS + 100)).alarms().single().alarm.trigger)
    }

    @Test
    fun statusTwoArrivingDuringTheSettleIsPartOfWatching() {
        // PRE_M1 -> M1 may turn 5561 to 2 a few seconds after 5523 first read 2: that is the watch, not an alarm.
        val r = armed()
        assertTrue(r.see(snap(T0 + 4_000, status = 2)).alarms().isEmpty())
        r.see(snap(A, status = 2))
        assertTrue(r.see(snap(A + 100, status = 2)).alarms().isEmpty())
        assertEquals(GuardPhase.WATCHING, r.m.phase)
    }

    // --- 4. The bike's own alarm ---------------------------------------------------------------

    @Test
    fun theBikeEscalatingToThreeIsBikeAlarmWithoutAWrite() {
        val r = watching()
        val a = r.see(snap(A + 500, defence = 3))
        val n = a.alarms().single()
        assertEquals(GuardTrigger.BIKE_ALARM, n.alarm.trigger)
        assertEquals(SirenNote.BIKE, n.alarm.siren)
        assertEquals(0, a.sirens())
        assertTrue(n.alert)
    }

    // --- 5. "Sirene sofort" off -----------------------------------------------------------------

    @Test
    fun sirenSettingOffNotifiesOnly() {
        val r = watching()
        r.feed(GuardInput.SetSirenImmediate(A, false))
        val a = r.press(A + 1_000)
        assertEquals(SirenNote.OFF_BY_SETTING, a.alarms().single().alarm.siren)
        assertEquals(0, a.sirens())
    }

    // --- 6. Re-trigger limit, cap, new episode ---------------------------------------------------

    @Test
    fun retriggersAreLimitedAndAQuietEpisodeStartsOver() {
        val r = watching()
        var t = A + 1_000
        val first = r.press(t)
        assertEquals(SirenNote.YABA, first.alarms().single().alarm.siren)
        assertEquals(1, first.alarms().single().alarm.cycle)
        assertTrue(first.alarms().single().alert)
        r.feed(done(t + 700, SequenceKind.SIREN, rb(2)))

        // Right after the write, before the poll shows 3: the siren is going, no second write, no second ring.
        val soon = r.press(t + 2_000)
        assertEquals(SirenNote.RUNNING, soon.alarms().single().alarm.siren)
        assertFalse(soon.alarms().single().alert)
        assertEquals(0, soon.sirens())

        for (cycle in 2..GuardRules.MAX_SIREN_CYCLES) {
            t += GuardRules.SIREN_REPEAT_MS + 1_000
            val a = r.press(t)
            assertEquals(SirenNote.YABA, a.alarms().single().alarm.siren)
            assertEquals(cycle, a.alarms().single().alarm.cycle)
            assertTrue(a.alarms().single().alert)
            r.feed(done(t + 700, SequenceKind.SIREN, rb(2)))
        }
        t += GuardRules.SIREN_REPEAT_MS + 1_000
        val capped = r.press(t)
        assertEquals(SirenNote.CAP_REACHED, capped.alarms().single().alarm.siren)
        assertEquals(GuardRules.MAX_SIREN_CYCLES, capped.alarms().single().alarm.cycle)
        assertEquals(0, capped.sirens())

        t += GuardRules.EPISODE_CALM_MS + 1_000
        val fresh = r.press(t)
        assertEquals(SirenNote.YABA, fresh.alarms().single().alarm.siren)
        assertEquals(1, fresh.alarms().single().alarm.cycle)
    }

    @Test
    fun aTriggerWhileTheBikeSoundsItsSirenSaysSoAndWritesNothing() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3)))
        r.see(snap(A + 2_000, defence = 3))
        val again = r.press(A + 12_000, defence = 3)
        assertEquals(SirenNote.BIKE, again.alarms().single().alarm.siren)
        assertFalse(again.alarms().single().alert)
        assertEquals(0, again.sirens())
    }

    @Test
    fun aNewMovementAfterTheSirenEndedIsANewRound() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3)))
        r.see(snap(A + 2_000, defence = 3))
        // Someone keeps at it: presses while the bike sounds its siren, then the siren ends (4) and they go on.
        assertEquals(SirenNote.BIKE, r.press(A + 20_000, defence = 3).alarms().single().alarm.siren)
        r.see(snap(A + 22_000, defence = 4))
        val again = r.press(A + 23_000, defence = 4)
        assertEquals(SirenNote.YABA, again.alarms().single().alarm.siren)
        assertEquals(2, again.alarms().single().alarm.cycle)
        assertEquals(1, again.sirens())
    }

    @Test
    fun aTriggerWhileTheSirenSequenceRunsSaysRunning() {
        val r = watching()
        r.press(A + 1_000)
        assertEquals(SequenceKind.SIREN, r.m.busy)
        val during = r.feed(GuardInput.UnlockRequest(A + 4_500))
        assertTrue(during.isEmpty()) // quiet: the siren write itself makes the bike ask
        val battery = r.see(snap(A + 4_600, battery = false))
        assertEquals(SirenNote.RUNNING, battery.alarms().single().alarm.siren)
        assertEquals(0, battery.sirens())
    }

    // --- 7. Re-arm after tracking, quiet window ----------------------------------------------------

    @Test
    fun trackingAndCalmReArmsThenTheQuietWindowHoldsThenLockOpenedCounts() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3)))
        r.see(snap(A + 2_000, defence = 3)) // inside the quiet window after the siren write
        assertTrue(r.tick(A + 20_000).rearms().isEmpty()) // 3: the siren runs
        val calm = r.see(snap(A + 32_000, defence = 4))
        assertEquals(listOf(GuardAction.RunRearm(owner = false)), calm.rearms())
        assertEquals(GuardPhase.REARMING, r.m.phase)
        // The re-arm's own writes: 0 (unlocked for a moment), lock, 2. All quiet.
        assertTrue(r.see(snap(A + 32_500, defence = 0, lock = LockState.UNLOCKED)).alarms().isEmpty())
        assertTrue(r.see(snap(A + 33_000, defence = 1)).alarms().isEmpty())
        val back = r.feed(done(A + 35_000, SequenceKind.REARM, rb(2, lock = 1, writes = 3)))
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertTrue(r.m.bikeWatch)
        assertTrue(back.logs().any { it is GuardLogLine.Rearmed })
        // Within 3 s of our own writes: nothing.
        assertTrue(r.see(snap(A + 36_000, lock = LockState.UNLOCKED)).alarms().isEmpty())
        r.see(snap(A + 36_500))
        val opened = r.see(snap(A + 38_500, lock = LockState.UNLOCKED))
        assertEquals(GuardTrigger.LOCK_OPENED, opened.alarms().single().alarm.trigger)
    }

    @Test
    fun stillWatchingAndCalmEndsTheAlarmWithoutWrites() {
        val r = watching()
        r.feed(GuardInput.SetSirenImmediate(A, false))
        r.press(A + 1_000)
        assertEquals(GuardPhase.ALARM, r.m.phase)
        assertTrue(r.tick(A + 30_000).isEmpty())
        r.tick(A + 31_100)
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertTrue(r.all.rearms().isEmpty())
        assertEquals(0, r.all.sirens())
    }

    @Test
    fun movementDuringTheReArmIsHandledWhenItIsDone() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(4)))
        r.see(snap(A + 2_000, defence = 4))
        r.tick(A + 7_100)
        assertEquals(GuardPhase.REARMING, r.m.phase)
        // Battery pulled while the re-arm runs: notified at once, the siren waits for the sequence.
        val pulled = r.see(snap(A + 8_000, defence = 1, battery = false))
        assertEquals(SirenNote.NONE, pulled.alarms().single().alarm.siren)
        assertEquals(0, pulled.sirens())
        val after = r.feed(done(A + 12_000, SequenceKind.REARM, rb(2, writes = 3)))
        assertEquals(1, after.sirens())
        assertEquals(GuardTrigger.BATTERY_REMOVED, after.alarms().single().alarm.trigger)
        assertEquals(GuardPhase.ALARM, r.m.phase)
    }

    @Test
    fun aFailedReArmWatchesOnWithoutConfirmation() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(4)))
        r.see(snap(A + 2_000, defence = 4))
        r.tick(A + 7_100)
        val failed = r.feed(done(A + 9_000, SequenceKind.REARM, null, ok = false, error = "timeout", attempted = 1))
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertFalse(r.m.bikeWatch)
        assertEquals(GuardLogLine.SequenceFailed(SequenceKind.REARM, "timeout"), failed.logs().single())
        // Its 5523 = 0 may have opened the lock: the lock (only) is put back at once.
        assertTrue(GuardAction.RunRelock in failed)
        r.feed(done(A + 10_000, SequenceKind.RELOCK, rb(1, lock = 1)))
        assertFalse(r.m.lockOwed)
        // No automatic re-arm loop: only the owner's "Erneut versuchen".
        assertTrue(r.tick(A + 60_000).rearms().isEmpty())
        assertEquals(listOf(GuardAction.RunRearm(owner = true)), r.feed(GuardInput.RetryWatch(A + 61_000)).rearms())
    }

    // --- 8. Battery, button, PIN, status ----------------------------------------------------------

    @Test
    fun batteryRemovedCounts() {
        val r = watching()
        assertEquals(GuardTrigger.BATTERY_REMOVED, r.see(snap(A + 100, battery = false)).alarms().single().alarm.trigger)
    }

    @Test
    fun buttonPressCounts() {
        val r = watching()
        assertEquals(GuardTrigger.BUTTON, r.press(A + 100).alarms().single().alarm.trigger)
    }

    @Test
    fun pinEntryCountsEvenInsideTheQuietWindow() {
        val r = armed()
        r.see(snap(T0 + 1_000))
        assertEquals(GuardTrigger.PIN_ENTRY, r.see(snap(T0 + 1_500, lock = LockState.PIN_ENTRY)).alarms().single().alarm.trigger)
    }

    @Test
    fun statusAlarmCountsOnlyWhenTheBaselineWasNotAlreadyTwo() {
        val r = watching()
        assertEquals(GuardTrigger.STATUS_ALARM, r.see(snap(A + 100, status = 2)).alarms().single().alarm.trigger)

        val plain = armed(first = snap(T0 + 100, status = 2))
        plain.see(snap(A, status = 2))
        plain.see(snap(A + 100, status = 0))
        assertTrue(plain.see(snap(A + 200, status = 2)).alarms().isEmpty())
    }

    @Test
    fun severalAtOnceCountByPriorityAndLogTheOthers() {
        val r = watching()
        val a = r.see(snap(A + 100, battery = false, button = true, speed = 5))
        assertEquals(GuardTrigger.BATTERY_REMOVED, a.alarms().single().alarm.trigger)
        val line = a.logs().filterIsInstance<GuardLogLine.Trigger>().single()
        assertEquals(listOf(GuardTrigger.WHEEL, GuardTrigger.BUTTON), line.others)
    }

    // --- 9. Link lost and back ---------------------------------------------------------------------

    @Test
    fun linkLossNotifiesAfterTwentySecondsOnce() {
        val r = watching()
        val lost = r.see(snap(A + 100, ready = false))
        assertEquals(GuardPhase.LOST, r.m.phase)
        assertEquals(GuardLogLine.Lost, lost.logs().single())
        assertTrue(r.tick(A + 19_000).isEmpty())
        assertEquals(listOf(GuardAction.NotifyLost(true)), r.tick(A + 20_100))
        assertTrue(r.tick(A + 21_100).isEmpty())
        assertTrue(r.m.lostNotified)
    }

    @Test
    fun anotherBikeInTheControllerIsLinkLost() {
        val r = watching()
        r.see(snap(A + 100, same = false))
        assertEquals(GuardPhase.LOST, r.m.phase)
    }

    @Test
    fun reconnectReadsFirstAndReportsAnOfflineAlarm() {
        val r = watching()
        r.see(snap(A + 100, ready = false))
        r.tick(A + 20_100)
        assertEquals(listOf(GuardAction.RunVerify), r.see(snap(A + 60_000)))
        val v = r.feed(done(A + 60_500, SequenceKind.VERIFY, rb(4, writes = 0)))
        assertTrue(GuardAction.ClearLost in v)
        val n = v.alarms().single()
        assertEquals(GuardTrigger.OFFLINE_ALARM, n.alarm.trigger)
        assertEquals(SirenNote.NONE, n.alarm.siren)
        assertEquals(0, v.sirens())
        assertEquals(GuardPhase.ALARM, r.m.phase)
    }

    @Test
    fun reconnectStillWatchingWritesNothing() {
        val r = watching()
        r.see(snap(A + 100, ready = false))
        r.see(snap(A + 5_000))
        val v = r.feed(done(A + 5_500, SequenceKind.VERIFY, rb(2, writes = 0)))
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertTrue(r.m.bikeWatch)
        assertTrue(v.none { it is GuardAction.RunRearm || it == GuardAction.RunSiren || it == GuardAction.RunDisarm })
        assertTrue(v.alarms().isEmpty())
    }

    @Test
    fun reconnectWithTheBikeOutOfItsWatchReArms() {
        val r = watching()
        r.see(snap(A + 100, ready = false))
        r.see(snap(A + 5_000))
        val v = r.feed(done(A + 5_500, SequenceKind.VERIFY, rb(1, writes = 0)))
        assertEquals(listOf(GuardAction.RunRearm(owner = false)), v.rearms())
        assertEquals(GuardPhase.REARMING, r.m.phase)
    }

    @Test
    fun anAlarmThatWasAlreadyRunningBeforeTheLossIsNotReportedAgain() {
        val r = watching()
        r.see(snap(A + 100, defence = 4)) // BIKE_ALARM (straight to tracking): YABA writes the siren
        r.see(snap(A + 200, defence = 4, ready = false))
        r.feed(done(A + 300, SequenceKind.SIREN, null, ok = false, error = "link lost"))
        assertEquals(GuardPhase.LOST, r.m.phase)
        assertEquals(listOf(GuardAction.RunVerify), r.see(snap(A + 5_000, defence = 4)))
        val v = r.feed(done(A + 5_500, SequenceKind.VERIFY, rb(4, writes = 0)))
        assertTrue(v.alarms().isEmpty())
        assertEquals(GuardPhase.ALARM, r.m.phase)
    }

    @Test
    fun restoreAfterARestartReadsBeforeItWrites() {
        val r = Run()
        r.feed(GuardInput.Restore(T0, StoredGuard(armed = true, mac = "AA", armedAt = T0 - 3_600_000, bikeWatch = true, armPath = ArmPath.NATIVE, restoreAlarmMode = true)))
        assertEquals(GuardPhase.LOST, r.m.phase)
        assertTrue(r.m.restoreAlarmMode)
        assertEquals(listOf(GuardAction.RunVerify), r.see(snap(T0 + 4_000)))
        val v = r.feed(done(T0 + 4_500, SequenceKind.VERIFY, rb(2, writes = 0)))
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertTrue(v.none { it is GuardAction.RunRearm || it == GuardAction.RunSiren })
    }

    // --- 10. Disarm ------------------------------------------------------------------------------

    @Test
    fun disarmWorksInAlarmWithARollingBike() {
        val r = watching()
        r.see(snap(A + 100, speed = 12))
        r.feed(done(A + 800, SequenceKind.SIREN, rb(3)))
        r.see(snap(A + 1_000, defence = 3, speed = 12))
        assertEquals(listOf(GuardAction.RunDisarm), r.feed(GuardInput.Disarm(A + 1_500)))
        assertEquals(GuardPhase.DISARMING, r.m.phase)
        val end = r.feed(done(A + 4_000, SequenceKind.DISARM, rb(0, lock = 0, writes = 3, ok = true)))
        assertEquals(GuardPhase.OFF, r.m.phase)
        assertNull(r.m.issue)
        assertTrue(GuardAction.Stop in end)
        assertTrue(GuardAction.ClearAlarm in end)
    }

    @Test
    fun disarmWhileTheSirenSequenceRunsWaitsForIt() {
        val r = watching()
        r.press(A + 1_000)
        assertEquals(SequenceKind.SIREN, r.m.busy)
        // The automatic siren may still be waiting for its gap: dropped if it has not written yet.
        assertEquals(listOf(GuardAction.CancelAuto), r.feed(GuardInput.Disarm(A + 1_200)))
        assertEquals(GuardPhase.DISARMING, r.m.phase)
        // Triggers no longer count once "Wache beenden" was tapped.
        assertTrue(r.see(snap(A + 1_300, battery = false)).alarms().isEmpty())
        val after = r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3)))
        assertEquals(listOf(GuardAction.RunDisarm), after.filter { it == GuardAction.RunDisarm })
        assertEquals(0, after.rearms().size)
    }

    @Test
    fun disarmDuringAReArmIsQueued() {
        val r = watching()
        r.feed(GuardInput.RetryWatch(A))
        assertEquals(GuardPhase.REARMING, r.m.phase)
        assertTrue(r.feed(GuardInput.Disarm(A + 500)).isEmpty())
        val after = r.feed(done(A + 3_000, SequenceKind.REARM, rb(2, writes = 3)))
        assertTrue(GuardAction.RunDisarm in after)
        assertEquals(GuardPhase.DISARMING, r.m.phase)
    }

    @Test
    fun disarmDuringArmingCancelsTheArmFirst() {
        val r = Run()
        r.feed(GuardInput.Arm(T0))
        r.see(snap(T0 + 300, defence = 1))
        val a = r.feed(GuardInput.Disarm(T0 + 2_000))
        assertEquals(listOf(GuardAction.CancelSequence, GuardAction.RunDisarm), a)
        assertEquals(GuardPhase.DISARMING, r.m.phase)
        // A late ArmDone of the cancelled job changes nothing.
        assertTrue(r.feed(GuardInput.ArmDone(T0 + 2_100, ArmResult(ArmOutcome.WATCHING, path = ArmPath.NATIVE))).isEmpty())
        assertEquals(GuardPhase.DISARMING, r.m.phase)
    }

    @Test
    fun disarmWithoutALinkEndsOfflineAfterThirtySeconds() {
        val r = watching()
        r.see(snap(A + 100, ready = false))
        assertTrue(r.feed(GuardInput.Disarm(A + 1_000)).isEmpty())
        assertEquals(GuardPhase.DISARMING, r.m.phase)
        assertTrue(r.tick(A + 30_000).isEmpty())
        val end = r.tick(A + 31_000)
        assertEquals(GuardPhase.OFF, r.m.phase)
        assertEquals(GuardIssue.ENDED_OFFLINE, r.m.issue)
        assertTrue(GuardAction.Stop in end)
        assertEquals(GuardLogLine.DisarmedOffline, end.logs().single())
    }

    @Test
    fun disarmWaitingForTheLinkRunsOnceItIsBack() {
        val r = watching()
        r.see(snap(A + 100, ready = false))
        r.feed(GuardInput.Disarm(A + 1_000))
        assertEquals(listOf(GuardAction.RunDisarm), r.see(snap(A + 9_000)))
    }

    @Test
    fun anUnconfirmedDisarmEndsWithTheValue() {
        val r = watching()
        r.feed(GuardInput.Disarm(A + 100))
        r.feed(done(A + 3_000, SequenceKind.DISARM, rb(3, lock = 1, writes = 4, ok = false)))
        assertEquals(GuardPhase.OFF, r.m.phase)
        assertEquals(GuardIssue.DISARM_UNCONFIRMED, r.m.issue)
        assertEquals(3, r.m.issueValue)
    }

    @Test
    fun disarmEndsTheWacheAndKeepsTheSirenSetting() {
        val r = watching()
        r.feed(GuardInput.SetSirenImmediate(A, false))
        r.feed(GuardInput.Disarm(A + 100))
        r.feed(done(A + 3_000, SequenceKind.DISARM, rb(0, lock = 0, writes = 2, ok = true)))
        assertEquals(GuardMachine(sirenImmediate = false, last = r.m.last), r.m)
    }

    // --- 11. Sirene aus ----------------------------------------------------------------------------

    @Test
    fun silenceReArmsAndSnoozesTheAutomaticSiren() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3)))
        r.see(snap(A + 2_000, defence = 3))
        val s = r.feed(GuardInput.Silence(A + 5_000))
        assertEquals(listOf(GuardAction.RunRearm(owner = true)), s.rearms())
        assertTrue(GuardAction.ClearAlarm in s)
        r.feed(done(A + 8_000, SequenceKind.REARM, rb(2, writes = 3)))
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        r.see(snap(A + 12_000))
        val snoozed = r.press(A + 60_000)
        assertEquals(SirenNote.SNOOZED, snoozed.alarms().single().alarm.siren)
        assertEquals(0, snoozed.sirens())
        val later = r.press(A + 5_000 + GuardRules.SILENCE_SNOOZE_MS + 1_000)
        assertEquals(SirenNote.YABA, later.alarms().single().alarm.siren)
    }

    @Test
    fun silenceWhileWatchingWithoutASirenOnlyClearsTheNotification() {
        val r = watching()
        val a = r.feed(GuardInput.Silence(A + 100))
        assertTrue(a.rearms().isEmpty())
        assertTrue(GuardAction.ClearAlarm in a)
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertEquals(SirenNote.SNOOZED, r.press(A + 5_000).alarms().single().alarm.siren)
    }

    @Test
    fun silenceDuringTheSirenWriteRunsAfterIt() {
        val r = watching()
        r.press(A + 1_000)
        assertTrue(r.feed(GuardInput.Silence(A + 1_100)).rearms().isEmpty())
        val after = r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3)))
        assertEquals(listOf(GuardAction.RunRearm(owner = true)), after.rearms())
    }

    // --- 12. Write budget --------------------------------------------------------------------------

    @Test
    fun theWriteBudgetStopsAutomaticSequencesButNotNotifications() {
        val r = watching()
        r.m = r.m.copy(autoWrites = List(GuardRules.WRITE_BUDGET) { A - 1_000 })
        val a = r.press(A + 1_000)
        assertEquals(SirenNote.BUDGET, a.alarms().single().alarm.siren)
        assertEquals(0, a.sirens())
        assertTrue(a.logs().contains(GuardLogLine.Budget))
        r.see(snap(A + 2_000, defence = 4))
        r.tick(A + 20_000)
        r.tick(A + 30_000)
        assertTrue(r.all.rearms().isEmpty())
        assertEquals(1, r.all.logs().count { it == GuardLogLine.Budget })
        // Ten minutes later the window has room again.
        val later = r.tick(A - 1_000 + GuardRules.WRITE_BUDGET_WINDOW_MS + 1)
        assertEquals(listOf(GuardAction.RunRearm(owner = false)), later.rearms())
    }

    @Test
    fun automaticWritesAreCountedOwnerWritesAreNot() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3, writes = 1)))
        assertEquals(1, r.m.autoWritesIn(A + 2_000))
        r.see(snap(A + 2_000, defence = 3))
        r.feed(GuardInput.Silence(A + 3_000))
        r.feed(done(A + 6_000, SequenceKind.REARM, rb(2, writes = 3)))
        assertEquals(1, r.m.autoWritesIn(A + 7_000))
    }

    // --- Persistence ------------------------------------------------------------------------------

    @Test
    fun theStoredFormCarriesWhatARestartNeeds() {
        val r = watching()
        r.press(A + 1_000)
        val s = r.m.stored("AA:BB")
        assertTrue(s.armed)
        assertEquals("AA:BB", s.mac)
        assertTrue(s.bikeWatch)
        assertTrue(s.restoreAlarmMode)
        assertEquals(GuardTrigger.BUTTON, s.lastAlarmTrigger)
        assertEquals(1, s.sirenCycles)
        assertNotNull(s.lastSirenAt)
    }

    // --- Final fixes (the guard design 12) -------------------------------------------------------------

    /** Link down at A + 100, back at [at] with [back] as the snapshot that starts the reconnect check. */
    private fun Run.lostAndBack(at: Long, back: GuardSnapshot = snap(at)): List<GuardAction> {
        see(snap(A + 100, ready = false))
        return see(back)
    }

    @Test
    fun aLockFoundOpenAfterAReconnectIsReportedAndNothingIsWritten() {
        val r = watching()
        assertEquals(listOf(GuardAction.RunVerify), r.lostAndBack(A + 60_000, snap(A + 60_000, defence = 0, lock = LockState.UNLOCKED, speed = 12)))
        val v = r.feed(done(A + 60_500, SequenceKind.VERIFY, rb(0, lock = 0, writes = 0)))
        val n = v.alarms().single()
        assertEquals(GuardTrigger.LOCK_OPENED, n.alarm.trigger)
        assertEquals(SirenNote.NONE, n.alarm.siren)
        assertTrue(v.none { it is GuardAction.RunRearm || it == GuardAction.RunRelock || it == GuardAction.RunSiren })
        assertTrue(r.m.holdWrites)
        assertEquals(GuardIssue.NOT_LOCKED, r.m.issue)
        assertEquals(0, r.m.issueValue)
        // Whoever rides it is not locked mid-ride, and a parked bike is not locked either until the owner says so.
        r.see(snap(A + 61_000, defence = 0, lock = LockState.UNLOCKED, speed = 0))
        r.tick(A + 70_000)
        r.tick(A + 95_000)
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertTrue(r.all.none { it is GuardAction.RunRearm || it == GuardAction.RunRelock })
        assertEquals(listOf(GuardAction.RunRearm(owner = true)), r.feed(GuardInput.RetryWatch(A + 96_000)).rearms())
        assertFalse(r.m.holdWrites)
    }

    @Test
    fun aReconnectOutOfTheWatchReArmsOnlyOnceTheBikeStands() {
        val r = watching()
        r.lostAndBack(A + 5_000, snap(A + 5_000, defence = 1, speed = 12))
        val v = r.feed(done(A + 5_500, SequenceKind.VERIFY, rb(1, writes = 0)))
        assertTrue(v.rearms().isEmpty())
        assertTrue(r.see(snap(A + 6_000, defence = 1, speed = 9)).rearms().isEmpty())
        assertEquals(listOf(GuardAction.RunRearm(owner = false)), r.see(snap(A + 7_000, defence = 1, speed = 0)).rearms())
    }

    @Test
    fun aReArmThatLeavesTheLockOpenRelocksThenRaisesTheIssue() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(4)))
        r.see(snap(A + 2_000, defence = 4))
        assertEquals(listOf(GuardAction.RunRearm(owner = false)), r.tick(A + 7_100).rearms())
        val rearmed = r.feed(done(A + 10_000, SequenceKind.REARM, rb(2, lock = 0, writes = 3)))
        assertFalse(r.m.bikeWatch)
        assertTrue(r.m.lockOwed)
        assertTrue(GuardAction.RunRelock in rearmed)
        // The bike keeps refusing: two more tries, spaced, then the issue and one loud notification.
        r.feed(done(A + 11_000, SequenceKind.RELOCK, rb(2, lock = 0)))
        assertTrue(GuardAction.RunRelock !in r.tick(A + 12_000))
        assertTrue(GuardAction.RunRelock in r.tick(A + 16_100))
        r.feed(done(A + 17_000, SequenceKind.RELOCK, rb(2, lock = 0)))
        assertTrue(GuardAction.RunRelock in r.tick(A + 32_100))
        val gaveUp = r.feed(done(A + 33_000, SequenceKind.RELOCK, rb(2, lock = 0)))
        assertEquals(GuardIssue.NOT_LOCKED, r.m.issue)
        assertEquals(listOf(GuardAction.NotifyNotLocked(0)), gaveUp.filterIsInstance<GuardAction.NotifyNotLocked>())
        assertTrue(GuardAction.RunRelock !in r.tick(A + 120_000))
        // Re-locks are not budgeted: only the re-arm's 3 writes and the siren's 1 count.
        assertEquals(4, r.m.autoWritesIn(A + 120_000))
        // Locked again (whatever did it): the issue and its notification go.
        val locked = r.see(snap(A + 121_000, defence = 2, lock = LockState.LOCKED))
        assertNull(r.m.issue)
        assertTrue(GuardAction.ClearNotLocked in locked)
        assertFalse(r.m.lockOwed)
    }

    @Test
    fun aReArmThatTheBikeEscalatesThroughIsAnAlarm() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(4)))
        r.see(snap(A + 2_000, defence = 4))
        r.tick(A + 7_100)
        val after = r.feed(done(A + 12_000, SequenceKind.REARM, rb(4, lock = 1, writes = 3)))
        assertEquals(GuardTrigger.BIKE_ALARM, after.alarms().single().alarm.trigger)
        assertEquals(GuardPhase.ALARM, r.m.phase)
    }

    @Test
    fun threeOrFourWhileWatchingCountsOnceTheQuietWindowIsOver() {
        val r = watching()
        r.feed(GuardInput.RetryWatch(A))
        r.see(snap(A + 500, defence = 0, lock = LockState.UNLOCKED))
        r.feed(done(A + 3_000, SequenceKind.REARM, rb(2, writes = 3)))
        assertTrue(r.see(snap(A + 4_000, defence = 4)).alarms().isEmpty()) // the edge falls into the quiet window
        val late = r.see(snap(A + 6_500, defence = 4))
        assertEquals(GuardTrigger.BIKE_ALARM, late.alarms().single().alarm.trigger)
        assertEquals(GuardPhase.ALARM, r.m.phase)
    }

    @Test
    fun noFalseStatusAlarmAfterARestart() {
        val r = Run()
        r.feed(GuardInput.Restore(T0, StoredGuard(armed = true, mac = "AA", armedAt = T0 - 3_600_000, bikeWatch = true, armPath = ArmPath.NATIVE)))
        // Right after the restart the state is still empty: no 5561 yet.
        assertEquals(listOf(GuardAction.RunVerify), r.see(snap(T0 + 4_000, status = null)))
        r.feed(done(T0 + 4_500, SequenceKind.VERIFY, SequenceReadback(defence = 2, lock = 1, status = 2, writes = 0)))
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertEquals(2, r.m.baseline?.status)
        assertTrue(r.see(snap(T0 + 5_000, status = null)).alarms().isEmpty())
        assertTrue(r.see(snap(T0 + 9_000, status = 2)).alarms().isEmpty())
        assertTrue(r.see(snap(T0 + 10_000, status = 2)).alarms().isEmpty())
    }

    @Test
    fun theBikeWatchFollowsTheLiveAlarmState() {
        val r = armed(ArmOutcome.UNCONFIRMED)
        r.see(snap(A, defence = 1))
        assertFalse(r.m.bikeWatch)
        val seen = r.see(snap(A + 100, defence = 2))
        assertTrue(r.m.bikeWatch)
        assertTrue(seen.logs().contains(GuardLogLine.WatchSeen(2)))
        val left = r.see(snap(A + 20_000, defence = 1))
        assertFalse(r.m.bikeWatch)
        assertTrue(left.logs().contains(GuardLogLine.WatchLeft(1)))
        // A moment for a lock or PIN change to show, then the re-arm (the bike stands).
        assertTrue(r.see(snap(A + 21_000, defence = 1)).rearms().isEmpty())
        assertEquals(listOf(GuardAction.RunRearm(owner = false)), r.see(snap(A + 25_100, defence = 1)).rearms())
    }

    @Test
    fun anUnconfirmedWatchWithANotifyOnlyTriggerGoesBackWithoutWrites() {
        val r = armed(ArmOutcome.UNCONFIRMED)
        r.see(snap(A, defence = 1))
        r.feed(GuardInput.SetSirenImmediate(A, false))
        r.press(A + 1_000, defence = 1)
        assertEquals(GuardPhase.ALARM, r.m.phase)
        r.tick(A + 10_000)
        r.tick(A + 31_100)
        assertEquals(GuardPhase.WATCHING, r.m.phase)
        assertTrue(r.all.rearms().isEmpty())
        assertEquals(0, r.all.sirens())
    }

    @Test
    fun theSirenGapHoldsAcrossARestartAndTheTriggerWaitsForIt() {
        val r = Run()
        r.feed(GuardInput.Restore(T0, StoredGuard(armed = true, mac = "AA", bikeWatch = true, lastSirenAt = T0 - 2_000, defence = 2)))
        r.see(snap(T0 + 500))
        r.feed(done(T0 + 600, SequenceKind.VERIFY, rb(2, writes = 0)))
        r.see(snap(T0 + 4_000))
        val early = r.press(T0 + 5_000)
        assertEquals(SirenNote.NONE, early.alarms().single().alarm.siren)
        assertEquals(0, early.sirens())
        assertTrue(r.tick(T0 + 7_000).none { it == GuardAction.RunSiren })
        val later = r.tick(T0 + 8_100)
        assertEquals(1, later.sirens())
        assertEquals(SirenNote.YABA, later.alarms().single().alarm.siren)
    }

    @Test
    fun theWriteBudgetSurvivesARestart() {
        val r = Run()
        r.feed(GuardInput.Restore(T0, StoredGuard(armed = true, mac = "AA", bikeWatch = true, defence = 2, autoWrites = List(GuardRules.WRITE_BUDGET) { T0 - 1_000 })))
        r.see(snap(T0 + 500))
        r.feed(done(T0 + 600, SequenceKind.VERIFY, rb(2, writes = 0)))
        r.see(snap(T0 + 4_000))
        assertEquals(SirenNote.BUDGET, r.press(T0 + 5_000).alarms().single().alarm.siren)
    }

    @Test
    fun anAlarmKnownBeforeTheRestartIsNotReportedAsOffline() {
        val r = Run()
        r.feed(GuardInput.Restore(T0, StoredGuard(armed = true, mac = "AA", bikeWatch = true, defence = 4)))
        r.see(snap(T0 + 500, defence = 4))
        val v = r.feed(done(T0 + 600, SequenceKind.VERIFY, rb(4, writes = 0)))
        assertTrue(v.alarms().isEmpty())
        assertEquals(GuardPhase.ALARM, r.m.phase)
    }

    @Test
    fun sirenOffDropsAnAutomaticSirenThatHasNotWrittenYet() {
        val r = watching()
        r.press(A + 1_000)
        assertEquals(1, r.m.sirenCycles)
        assertTrue(GuardAction.CancelAuto in r.feed(GuardInput.Silence(A + 1_100)))
        val after = r.feed(done(A + 1_200, SequenceKind.SIREN, null, ok = false, cancelled = true))
        assertEquals(0, r.m.sirenCycles)
        assertTrue(after.logs().contains(GuardLogLine.Cancelled(SequenceKind.SIREN)))
        assertEquals(listOf(GuardAction.RunRearm(owner = true)), after.rearms())
        assertEquals(0, r.m.autoWritesIn(A + 2_000))
    }

    @Test
    fun aFailedDisarmWaitsBeforeItsRetryAndSaysWhatItKnows() {
        val r = watching()
        assertEquals(listOf(GuardAction.RunDisarm), r.feed(GuardInput.Disarm(A + 100)))
        r.feed(done(A + 500, SequenceKind.DISARM, null, ok = false, error = "GATT 133", attempted = 1))
        assertEquals(GuardPhase.DISARMING, r.m.phase)
        assertTrue(r.see(snap(A + 1_000)).none { it == GuardAction.RunDisarm })
        assertEquals(listOf(GuardAction.RunDisarm), r.see(snap(A + 3_600)).filter { it == GuardAction.RunDisarm })
        val end = r.feed(done(A + 4_000, SequenceKind.DISARM, null, ok = false, error = "GATT 133"))
        assertEquals(GuardPhase.OFF, r.m.phase)
        // A write went out without an answer: not "nothing changed", not a made-up value.
        assertEquals(GuardIssue.ENDED_PARTLY, r.m.issue)
        assertNull(r.m.issueValue)
        assertEquals(GuardLogLine.DisarmedUnconfirmed, end.logs().last())
    }

    @Test
    fun aDisarmThatNeverWroteEndsOffline() {
        val r = watching()
        r.feed(GuardInput.Disarm(A + 100))
        r.feed(done(A + 500, SequenceKind.DISARM, null, ok = false, error = "not connected"))
        r.see(snap(A + 3_600))
        r.feed(done(A + 3_700, SequenceKind.DISARM, null, ok = false, error = "not connected"))
        assertEquals(GuardIssue.ENDED_OFFLINE, r.m.issue)
    }

    @Test
    fun aDisarmThatStopsTheAlarmButNotTheLockSaysSo() {
        val r = watching()
        r.feed(GuardInput.Disarm(A + 100))
        r.feed(done(A + 3_000, SequenceKind.DISARM, rb(0, lock = 1, writes = 4, ok = false)))
        assertEquals(GuardIssue.DISARM_LOCKED, r.m.issue)
        assertEquals(1, r.m.issueValue)
    }

    @Test
    fun sirenOffWithoutALinkSnoozesAndStopsTheSirenAfterTheCheck() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3)))
        r.see(snap(A + 2_000, defence = 3))
        r.see(snap(A + 5_000, defence = 3, ready = false))
        assertEquals(GuardPhase.LOST, r.m.phase)
        val s = r.feed(GuardInput.Silence(A + 6_000))
        assertTrue(GuardAction.ClearAlarm in s)
        assertTrue(s.rearms().isEmpty())
        r.see(snap(A + 9_000, defence = 3))
        val v = r.feed(done(A + 9_500, SequenceKind.VERIFY, rb(3, writes = 0)))
        assertTrue(v.alarms().isEmpty()) // the alarm was known before the loss
        assertEquals(listOf(GuardAction.RunRearm(owner = true)), v.rearms())
    }

    @Test
    fun aFailingReconnectCheckBacksOffAndLogsOnce() {
        val r = watching()
        r.lostAndBack(A + 5_000)
        val first = r.feed(done(A + 5_100, SequenceKind.VERIFY, null, ok = false, error = "timeout"))
        assertEquals(1, first.logs().size)
        assertTrue(r.see(snap(A + 6_000)).isEmpty())
        assertEquals(listOf(GuardAction.RunVerify), r.see(snap(A + 10_200)))
        assertTrue(r.feed(done(A + 10_300, SequenceKind.VERIFY, null, ok = false, error = "timeout")).logs().isEmpty())
        assertTrue(r.see(snap(A + 15_000)).isEmpty()) // now 10 s
        assertEquals(listOf(GuardAction.RunVerify), r.see(snap(A + 20_400)))
    }

    @Test
    fun anArmThatFailsAfterAWriteSaysTheBikeMayBeLocked() {
        val r = Run()
        r.feed(GuardInput.Arm(T0))
        r.feed(GuardInput.ArmDone(T0 + 2_000, ArmResult(ArmOutcome.FAILED, error = "GATT 133", writes = 2)))
        assertEquals(GuardIssue.ARM_FAILED_PARTLY, r.m.issue)
        assertEquals("GATT 133", r.m.issueDetail)
    }

    @Test
    fun endingTheWacheClearsTheLostNotification() {
        val r = watching()
        r.see(snap(A + 100, ready = false))
        r.tick(A + 20_100)
        assertTrue(GuardAction.ClearLost in r.feed(GuardInput.Disarm(A + 21_000)))
    }

    @Test
    fun theStoredFormKeepsWhatARestartLoopMustNotLose() {
        val r = watching()
        r.press(A + 1_000)
        r.feed(done(A + 1_700, SequenceKind.SIREN, rb(3, writes = 1)))
        r.see(snap(A + 2_000, defence = 3))
        val s = r.m.stored("AA")
        assertEquals(3, s.defence)
        assertEquals(1, s.autoWrites.size)
        assertEquals(A + 1_000, s.lastSirenAt)
    }

    private companion object {
        const val T0 = 1_000_000_000L

        /** Well after the quiet window that follows arming. */
        const val A = T0 + 10_000L
    }
}
