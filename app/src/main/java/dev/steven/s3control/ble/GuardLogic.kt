package dev.steven.s3control.ble

/*
 * The Wache's pure state machine (the guard design 3). No Android, no I18n, no clock of its own: every input carries its
 * time, every effect is an action the runner ([BikeGuard]) executes. Unit-tested in GuardLogicTest.
 */

/** What the Wache looks at, built by the runner from [BikeState] (the guard design 3.1). */
data class GuardSnapshot(
    val atMillis: Long,
    /** The link is READY. */
    val ready: Boolean,
    /** The controller holds the armed bike. */
    val sameBike: Boolean,
    /** 5521. */
    val lock: LockState? = null,
    /** 5523 byte 0, unknown values kept. */
    val defence: Int? = null,
    /** 5561 byte 0. */
    val status: Int? = null,
    /** 5532 as the bike reports it. */
    val speedRaw: Int? = null,
    /** Fault bit 48. */
    val wheelMoving: Boolean = false,
    /** Bell, boost or power button held. */
    val buttonDown: Boolean = false,
    /** 5542 != 4. */
    val batteryDetected: Boolean? = null,
) {
    /** Something turns the wheel: any speed or bit 48. */
    val wheelSignal: Boolean get() = (speedRaw ?: 0) >= 1 || wheelMoving
}

/** What the store holds of an armed Wache ([GuardStore]); [GuardLogic] resumes from it after a restart. */
data class StoredGuard(
    val armed: Boolean = false,
    val mac: String? = null,
    val armedAt: Long? = null,
    val sirenImmediate: Boolean = true,
    val armPath: ArmPath? = null,
    val bikeWatch: Boolean = false,
    val restoreAlarmMode: Boolean = false,
    val lastAlarmAt: Long? = null,
    val lastAlarmTrigger: GuardTrigger? = null,
    val sirenCycles: Int = 0,
    val episodeStart: Long? = null,
    val lastSirenAt: Long? = null,
    /** 5523 as last seen: a restart does not report an alarm YABA already knew about as new. */
    val defence: Int? = null,
    /** The automatic writes of the last ten minutes: a restart loop cannot reset the write budget. */
    val autoWrites: List<Long> = emptyList(),
    /** YABA wrote 5523 = 0 (which opens the software lock) and has not read 5521 = 1 since. */
    val lockOwed: Boolean = false,
    /** The lock was found open after a reconnect: no automatic lock writes until the owner acts. */
    val holdWrites: Boolean = false,
)

enum class SequenceKind { ARM, VERIFY, SIREN, REARM, RELOCK, DISARM }

sealed interface GuardInput {
    val atMillis: Long

    data class Arm(override val atMillis: Long) : GuardInput
    data class ArmDone(override val atMillis: Long, val outcome: ArmResult) : GuardInput
    data class Snapshot(val s: GuardSnapshot) : GuardInput {
        override val atMillis: Long get() = s.atMillis
    }
    /** 5522 pushed with byte 0 == 1. */
    data class UnlockRequest(override val atMillis: Long) : GuardInput
    /** Every second while active. */
    data class Tick(override val atMillis: Long) : GuardInput
    data class SequenceDone(
        override val atMillis: Long,
        val kind: SequenceKind,
        val ok: Boolean,
        val readback: SequenceReadback?,
        val error: String?,
        /** Writes a failed sequence made before it failed (the bike may have received them). */
        val attempted: Int = 0,
        /** The owner's tap dropped an automatic sequence before it wrote anything ([GuardAction.CancelAuto]). */
        val cancelled: Boolean = false,
    ) : GuardInput
    data class Silence(override val atMillis: Long) : GuardInput
    data class Disarm(override val atMillis: Long) : GuardInput
    data class RetryWatch(override val atMillis: Long) : GuardInput
    data class SetSirenImmediate(override val atMillis: Long, val on: Boolean) : GuardInput
    data class Restore(override val atMillis: Long, val stored: StoredGuard) : GuardInput
}

/** A trigger with what happened to the siren; the runner adds the text ([GuardTexts.alarm]). */
data class AlarmRecord(val atMillis: Long, val trigger: GuardTrigger, val siren: SirenNote, val cycle: Int)

/** A Wache log line; the runner turns it into text ([GuardTexts], strings_guard_core.xml). */
sealed interface GuardLogLine {
    data class ArmFinished(val result: ArmResult) : GuardLogLine
    data class Ignored(val trigger: GuardTrigger) : GuardLogLine
    /** [others]: triggers seen in the same step, logged only. */
    data class Trigger(val alarm: AlarmRecord, val others: List<GuardTrigger>) : GuardLogLine
    data class Siren(val defence: Int?) : GuardLogLine
    data class Rearmed(val defence: Int?, val lock: Int?) : GuardLogLine
    data class Relocked(val lock: Int?) : GuardLogLine
    /** The automatic lock attempts are used up and the bike is still not locked. */
    data class NotLocked(val lock: Int?) : GuardLogLine
    /** The lock was open after a reconnect and YABA had not opened it: no automatic lock writes. */
    data object Held : GuardLogLine
    /** 5523 turned 2 by itself while watching: the bike watches again. */
    data class WatchSeen(val defence: Int?) : GuardLogLine
    /** 5523 left 2 while watching (0 or 1): the bike's own watch is off. */
    data class WatchLeft(val defence: Int?) : GuardLogLine
    data object Silence : GuardLogLine
    data class Disarmed(val defence: Int?, val lock: Int?) : GuardLogLine
    data object DisarmedOffline : GuardLogLine
    /** Ended after disarm attempts that wrote but never got an answer. */
    data object DisarmedUnconfirmed : GuardLogLine
    data object Lost : GuardLogLine
    data class Back(val defence: Int?, val lock: Int?) : GuardLogLine
    data object Budget : GuardLogLine
    data object Restored : GuardLogLine
    data class SequenceFailed(val kind: SequenceKind, val error: String?) : GuardLogLine
    /** An automatic sequence the owner's tap dropped before it wrote anything. */
    data class Cancelled(val kind: SequenceKind) : GuardLogLine
}

sealed interface GuardAction {
    data object RunArm : GuardAction
    /** Read 5523, 5521, 5561 (read only). */
    data object RunVerify : GuardAction
    /** 5523 = 3. */
    data object RunSiren : GuardAction
    /** [owner]: not counted in the write budget. */
    data class RunRearm(val owner: Boolean) : GuardAction
    /** 5521 = 1 only: puts back the lock YABA's own 5523 = 0 opened. */
    data object RunRelock : GuardAction
    data object RunDisarm : GuardAction
    /** Cancels a running ARM job (Disarm during ARMING). */
    data object CancelSequence : GuardAction
    /**
     * Drops the running automatic sequence (siren, re-arm, re-lock) if it has not reached the bike yet; the runner then
     * answers with a cancelled [GuardInput.SequenceDone]. One that already writes runs to its end.
     */
    data object CancelAuto : GuardAction
    data class NotifyAlarm(val alarm: AlarmRecord, val alert: Boolean) : GuardAction
    data class NotifyLost(val bikeWatch: Boolean) : GuardAction
    /** The bike stays unlocked after the automatic lock attempts ([GuardIssue.NOT_LOCKED]). */
    data class NotifyNotLocked(val lock: Int?) : GuardAction
    data object ClearLost : GuardAction
    data object ClearAlarm : GuardAction
    data object ClearNotLocked : GuardAction
    data class Log(val line: GuardLogLine) : GuardAction
    data object Persist : GuardAction
    /** OFF: guardArmed false, poll released, store cleared; the service stops itself. */
    data object Stop : GuardAction
}

/** The machine's whole memory (the guard design 3.1). */
data class GuardMachine(
    val phase: GuardPhase = GuardPhase.OFF,
    val sirenImmediate: Boolean = true,
    val bikeWatch: Boolean = false,
    val armPath: ArmPath? = null,
    val restoreAlarmMode: Boolean = false,
    val armedAt: Long? = null,
    /** 5523 as last seen (snapshot or sequence read). */
    val defence: Int? = null,
    /** The snapshot when watching was last confirmed. */
    val baseline: GuardSnapshot? = null,
    /** The previous snapshot. */
    val last: GuardSnapshot? = null,
    /** The wheel signal was seen off once since watching began (guards against a stuck bit 48). */
    val wheelReady: Boolean = false,
    val wheelOnSince: Long? = null,
    /** Rising edges of the wheel signal within [GuardRules.WHEEL_EDGE_WINDOW_MS]. */
    val wheelEdges: List<Long> = emptyList(),
    /** When the current wheel-on period last counted as a trigger; null while it has not. */
    val wheelFiredAt: Long? = null,
    val quietUntil: Long = 0,
    /** The bike just entered its watch (PRE_M1 settle): its unlock requests and 5561 changes are its own until then. */
    val settleUntil: Long = 0,
    val busy: SequenceKind? = null,
    /** [busy] counts against the write budget (siren, automatic re-arm). */
    val busyAuto: Boolean = false,
    /** When the running re-arm started: movement after it keeps the alarm open. */
    val rearmStartedAt: Long? = null,
    val pendingTrigger: GuardTrigger? = null,
    val pendingDisarm: Boolean = false,
    val pendingSilence: Boolean = false,
    val episodeStart: Long? = null,
    val lastMovementAt: Long? = null,
    val sirenCycles: Int = 0,
    val lastSirenAt: Long? = null,
    /** YABA wrote the siren since watching was last confirmed: the alarm then ends with a re-arm, not by itself. */
    val sirenSinceWatch: Boolean = false,
    /** Shortly after YABA's siren write, before the poll shows 3: a trigger then says the siren is going. A re-arm ends it. */
    val sirenQuietUntil: Long = 0,
    val snoozeUntil: Long = 0,
    val lastAlertAt: Long? = null,
    val linkDownSince: Long? = null,
    val lostNotified: Boolean = false,
    val defenceBeforeLost: Int? = null,
    /** DISARMING without a link: the Wache ends offline at this time. Null while the disarm sequence runs. */
    val disarmDeadline: Long? = null,
    val disarmAttempts: Int = 0,
    /** A failed disarm is tried again no earlier than this. */
    val disarmRetryAt: Long = 0,
    /** Writes that failed disarm sequences made: the bike may have received some of them. */
    val disarmWrites: Int = 0,
    /** A failed read-only check after a reconnect is retried no earlier than this. */
    val verifyRetryAt: Long = 0,
    val verifyFailures: Int = 0,
    /** The bike left its own watch (reconnect, 5523 fell from 2): an automatic re-arm waits for a parked bike. */
    val needRearm: Boolean = false,
    /** [needRearm] waits until then, so a lock or PIN change that comes a moment later still counts as a trigger. */
    val rearmNotBefore: Long = 0,
    /** YABA's own 5523 = 0 opened the software lock and no 5521 = 1 has been read since. */
    val lockOwed: Boolean = false,
    val relockTries: Int = 0,
    val relockAt: Long = 0,
    /** The lock was open after a reconnect and YABA had not opened it: no automatic lock writes until the owner acts. */
    val holdWrites: Boolean = false,
    /** One timestamp per automatic write ([GuardRules.WRITE_BUDGET]). */
    val autoWrites: List<Long> = emptyList(),
    val budgetLogged: Boolean = false,
    val lastAlarm: AlarmRecord? = null,
    val issue: GuardIssue? = null,
    val issueValue: Int? = null,
    val issueDetail: String? = null,
) {
    val active: Boolean get() = phase != GuardPhase.OFF

    /** A Wache sequence runs or ended less than [GuardRules.OWN_WRITE_QUIET_MS] ago: lock/5523/5561/5522 changes are ours. */
    fun quiet(at: Long): Boolean = busy != null || at < quietUntil

    fun autoWritesIn(at: Long): Int = autoWrites.count { at - it < GuardRules.WRITE_BUDGET_WINDOW_MS }

    fun budgetLeft(at: Long): Int = GuardRules.WRITE_BUDGET - autoWritesIn(at)

    /**
     * The bike stands and nobody holds a button: the precondition for every automatic lock write (a re-arm or re-lock
     * locks the bike, which cuts assist). Bit 48 counts only once it was seen off, so a stuck bit cannot block forever.
     */
    fun parked(): Boolean {
        val s = last ?: return false
        return s.ready && s.sameBike && (s.speedRaw ?: 0) == 0 && !(s.wheelMoving && wheelReady) && !s.buttonDown
    }

    /** What the store keeps of this machine. */
    fun stored(mac: String?): StoredGuard = StoredGuard(
        armed = active, mac = mac, armedAt = armedAt, sirenImmediate = sirenImmediate, armPath = armPath,
        bikeWatch = bikeWatch, restoreAlarmMode = restoreAlarmMode, lastAlarmAt = lastAlarm?.atMillis,
        lastAlarmTrigger = lastAlarm?.trigger, sirenCycles = sirenCycles, episodeStart = episodeStart, lastSirenAt = lastSirenAt,
        defence = defence, autoWrites = autoWrites, lockOwed = lockOwed, holdWrites = holdWrites,
    )
}

object GuardLogic {

    /** Several triggers in one step: the first of these counts, the others are logged only (the guard design 3.3). */
    val PRIORITY = listOf(
        GuardTrigger.BIKE_ALARM, GuardTrigger.BATTERY_REMOVED, GuardTrigger.LOCK_OPENED, GuardTrigger.PIN_ENTRY,
        GuardTrigger.WHEEL, GuardTrigger.BUTTON, GuardTrigger.UNLOCK_REQUEST, GuardTrigger.STATUS_ALARM,
    )

    /**
     * After 5523 first reads 2 the firmware may still be in its PRE_M1 settle (5 s), where it asks the app to unlock
     * on motion: 5522 and 5561 changes count only after this.
     */
    const val SETTLE_MS = 6_500L

    /** A YABA siren write at most this often, also across restarts (the stored time of the last one). */
    const val MIN_SIREN_GAP_MS = 10_000L

    /** Automatic 5521 = 1 attempts after YABA's own 5523 = 0 left the bike open, then an issue and a notification. */
    const val MAX_RELOCK_TRIES = 3

    /** The waits before the 2nd and 3rd re-lock attempt. */
    private val RELOCK_BACKOFF_MS = longArrayOf(5_000L, 15_000L)

    /** Writes of one automatic re-arm (5523 = 0, 5521 = 1, 5523 = 2). */
    private const val REARM_WRITES = 3

    /** A failed reconnect check: first retry after this, doubling up to [VERIFY_RETRY_MAX_MS]. */
    private const val VERIFY_RETRY_MS = 5_000L
    private const val VERIFY_RETRY_MAX_MS = 60_000L

    /** A disarm that failed is tried again no sooner than this (the link may still show ready for a moment). */
    const val DISARM_RETRY_MS = 3_000L
    private const val MAX_DISARM_ATTEMPTS = 2

    private val ALARMING = setOf(3, 4)

    private class Step(var m: GuardMachine) {
        val actions = ArrayList<GuardAction>()
        fun add(a: GuardAction) { actions += a }
        fun log(l: GuardLogLine) { actions += GuardAction.Log(l) }
    }

    fun step(m: GuardMachine, input: GuardInput): Pair<GuardMachine, List<GuardAction>> {
        val st = Step(m)
        when (input) {
            is GuardInput.Arm -> arm(st, input.atMillis)
            is GuardInput.ArmDone -> armDone(st, input.atMillis, input.outcome)
            is GuardInput.Snapshot -> snapshot(st, input.s)
            is GuardInput.UnlockRequest -> unlockRequest(st, input.atMillis)
            is GuardInput.Tick -> tick(st, input.atMillis)
            is GuardInput.SequenceDone -> sequenceDone(st, input)
            is GuardInput.Silence -> silence(st, input.atMillis)
            is GuardInput.Disarm -> disarm(st, input.atMillis)
            is GuardInput.RetryWatch -> retryWatch(st, input.atMillis)
            is GuardInput.SetSirenImmediate -> {
                st.m = st.m.copy(sirenImmediate = input.on)
                st.add(GuardAction.Persist)
            }
            is GuardInput.Restore -> restore(st, input.atMillis, input.stored)
        }
        return st.m to st.actions
    }

    // --- Arming ---------------------------------------------------------------------------------

    private fun arm(st: Step, at: Long) {
        if (st.m.active) return
        val keep = st.m
        st.m = GuardMachine(
            phase = GuardPhase.ARMING, sirenImmediate = keep.sirenImmediate, busy = SequenceKind.ARM,
            last = keep.last, armedAt = at,
        )
        st.add(GuardAction.RunArm)
    }

    private fun armDone(st: Step, at: Long, r: ArmResult) {
        val m = st.m
        if (m.phase != GuardPhase.ARMING) return
        st.log(GuardLogLine.ArmFinished(r))
        when (r.outcome) {
            ArmOutcome.WATCHING, ArmOutcome.UNCONFIRMED -> {
                val watch = r.outcome == ArmOutcome.WATCHING
                val base = m.last?.withReads(r.defence, r.lock, null)
                st.m = watching(m.copy(last = base), freshWheel = true).copy(
                    busy = null, bikeWatch = watch, armPath = if (watch) r.path else null,
                    restoreAlarmMode = r.modeWasOff, defence = r.defence, armedAt = at,
                    quietUntil = at + GuardRules.OWN_WRITE_QUIET_MS,
                    settleUntil = if (watch) at + SETTLE_MS else 0,
                )
                st.add(GuardAction.Persist)
            }
            else -> {
                val (issue, value) = when (r.outcome) {
                    ArmOutcome.ALARM_DURING -> GuardIssue.ARM_ALARM_DURING to r.value
                    ArmOutcome.ALARM_MODE_REFUSED -> GuardIssue.ARM_ALARM_MODE to r.value
                    ArmOutcome.LOCK_REFUSED -> GuardIssue.ARM_LOCK to r.value
                    ArmOutcome.BIKE_STATE -> GuardIssue.BIKE_STATE to null
                    // An error after the first write: the bike may be locked or its alarm on (the issue says so).
                    else -> (if (r.writes > 0) GuardIssue.ARM_FAILED_PARTLY else GuardIssue.ARM_FAILED) to null
                }
                st.m = off(m).copy(issue = issue, issueValue = value, issueDetail = r.error)
                st.add(GuardAction.Stop)
            }
        }
    }

    /**
     * Back to plain watching with the latest snapshot as baseline. [freshWheel] (arming, reconnect): the wheel must be
     * seen off once again before it counts. A re-arm keeps the wheel tracking, so a bike that keeps rolling through
     * the re-arm still counts every [GuardRules.SIREN_REPEAT_MS].
     */
    private fun watching(m: GuardMachine, freshWheel: Boolean): GuardMachine {
        val w = m.copy(phase = GuardPhase.WATCHING, baseline = m.last, sirenSinceWatch = false)
        return if (freshWheel) w.copy(wheelReady = false, wheelOnSince = null, wheelEdges = emptyList(), wheelFiredAt = null) else w
    }

    /** OFF with only "Sirene sofort" and the last snapshot kept. */
    private fun off(m: GuardMachine): GuardMachine = GuardMachine(sirenImmediate = m.sirenImmediate, last = m.last)

    /** [this] with what a sequence just read: fresher than a state that may still hold values from before a loss. */
    private fun GuardSnapshot.withReads(defence: Int?, lock: Int?, status: Int?): GuardSnapshot = copy(
        defence = defence ?: this.defence,
        lock = LockState.from(lock) ?: this.lock,
        status = status ?: this.status,
    )

    // --- Snapshots and ticks --------------------------------------------------------------------

    private fun snapshot(st: Step, s: GuardSnapshot) {
        val m = st.m
        val at = s.atMillis
        when (m.phase) {
            GuardPhase.OFF -> st.m = m.copy(last = s)
            GuardPhase.ARMING -> {
                // Nothing counts while arming; real movement signals are logged so the first test shows them.
                val prev = m.last
                if (prev != null && s.ready && s.sameBike) {
                    if (s.buttonDown && !prev.buttonDown) st.log(GuardLogLine.Ignored(GuardTrigger.BUTTON))
                    if (s.wheelSignal && !prev.wheelSignal) st.log(GuardLogLine.Ignored(GuardTrigger.WHEEL))
                    if (prev.batteryDetected == true && s.batteryDetected == false) st.log(GuardLogLine.Ignored(GuardTrigger.BATTERY_REMOVED))
                }
                st.m = st.m.copy(last = s)
            }
            GuardPhase.WATCHING, GuardPhase.ALARM, GuardPhase.REARMING -> {
                if (!s.ready || !s.sameBike) {
                    linkLost(st, s)
                    return
                }
                val defenceChanged = s.defence != null && s.defence != st.m.defence
                st.m = st.m.copy(defence = s.defence ?: st.m.defence)
                // The bike's settle after it entered its watch: a 5561 change then is part of watching, not an alarm.
                val base = st.m.baseline
                if (at < st.m.settleUntil && base != null && s.status != null && s.status != base.status) {
                    st.m = st.m.copy(baseline = base.copy(status = s.status))
                }
                // The lock reads closed again, well after our last write (the poll has read it since): nothing is owed,
                // and a hold for a lock found open is over.
                if (!st.m.quiet(at) && s.lock == LockState.LOCKED && (st.m.lockOwed || st.m.holdWrites || st.m.issue == GuardIssue.NOT_LOCKED)) {
                    st.m = st.m.copy(lockOwed = false, relockTries = 0, holdWrites = false)
                    clearNotLocked(st)
                }
                val prevDefence = st.m.last?.defence
                val found = detect(st, s)
                st.m = st.m.copy(last = s)
                if (found.isNotEmpty()) fire(st, at, found) else followWatch(st, at, prevDefence, s)
                if (defenceChanged) st.add(GuardAction.Persist)
                alarmCalm(st, at)
                automatic(st, at)
            }
            GuardPhase.LOST -> {
                st.m = m.copy(last = s)
                if (s.ready && s.sameBike && m.busy == null && at >= m.verifyRetryAt) {
                    st.m = st.m.copy(busy = SequenceKind.VERIFY, busyAuto = false)
                    st.add(GuardAction.RunVerify)
                }
            }
            GuardPhase.DISARMING -> {
                st.m = m.copy(last = s)
                if (s.ready && s.sameBike && m.busy == null && m.disarmDeadline != null && at >= m.disarmRetryAt) {
                    st.m = st.m.copy(busy = SequenceKind.DISARM, busyAuto = false, disarmDeadline = null)
                    st.add(GuardAction.RunDisarm)
                }
            }
        }
    }

    /**
     * WATCHING, nothing of ours running: [GuardMachine.bikeWatch] follows the live 5523 (the guard design 3.5). 2 appearing
     * by itself confirms the bike's watch; 2 falling to 0 or 1 means the bike left it, and an automatic re-arm follows
     * once the bike stands.
     */
    private fun followWatch(st: Step, at: Long, prevDefence: Int?, s: GuardSnapshot) {
        val m = st.m
        if (m.phase != GuardPhase.WATCHING || m.quiet(at)) return
        val def = s.defence
        if (def == 2 && !m.bikeWatch) {
            st.m = m.copy(bikeWatch = true, needRearm = false, baseline = s, settleUntil = at + SETTLE_MS)
            st.log(GuardLogLine.WatchSeen(def))
            st.add(GuardAction.Persist)
        } else if (def == 2 && m.needRearm) {
            st.m = m.copy(needRearm = false)
        } else if (prevDefence == 2 && (def == 0 || def == 1) && m.bikeWatch) {
            st.m = m.copy(bikeWatch = false, needRearm = true, rearmNotBefore = at + GuardRules.REARM_CALM_MS)
            st.log(GuardLogLine.WatchLeft(def))
            st.add(GuardAction.Persist)
        }
    }

    private fun linkLost(st: Step, s: GuardSnapshot) {
        val m = st.m
        val at = s.atMillis
        if (m.pendingDisarm) {
            // "Wache beenden" was waiting behind a sequence: keep waiting for the link instead.
            st.m = m.copy(
                phase = GuardPhase.DISARMING, pendingDisarm = false, pendingSilence = false, pendingTrigger = null,
                disarmDeadline = at + GuardRules.DISARM_LINK_WAIT_MS, last = s,
            )
            return
        }
        st.m = m.copy(
            phase = GuardPhase.LOST, linkDownSince = at, lostNotified = false,
            defenceBeforeLost = m.last?.defence ?: m.defence, pendingTrigger = null, pendingSilence = false, last = s,
        )
        st.log(GuardLogLine.Lost)
    }

    private fun tick(st: Step, at: Long) {
        val m = st.m
        when (m.phase) {
            GuardPhase.WATCHING, GuardPhase.ALARM, GuardPhase.REARMING -> {
                val s = m.last
                if (s != null && s.ready && s.sameBike) {
                    val found = wheel(st, s, at, rising = false)
                    if (found) fire(st, at, listOf(GuardTrigger.WHEEL))
                }
                // A trigger that waited for the siren gap.
                val pending = st.m.pendingTrigger
                if (pending != null && st.m.busy == null && sirenGapOver(st.m, at)) {
                    st.m = st.m.copy(pendingTrigger = null)
                    fire(st, at, listOf(pending))
                }
                alarmCalm(st, at)
                automatic(st, at)
            }
            GuardPhase.LOST -> {
                val down = m.linkDownSince
                if (!m.lostNotified && down != null && at - down >= GuardRules.LOST_NOTIFY_MS) {
                    st.m = m.copy(lostNotified = true)
                    st.add(GuardAction.NotifyLost(m.bikeWatch))
                }
            }
            GuardPhase.DISARMING -> {
                val deadline = m.disarmDeadline
                if (m.busy == null && deadline != null && at >= deadline) endOffline(st)
            }
            GuardPhase.OFF, GuardPhase.ARMING -> Unit
        }
    }

    private fun sirenGapOver(m: GuardMachine, at: Long): Boolean {
        val lastSiren = m.lastSirenAt
        return lastSiren == null || at - lastSiren >= MIN_SIREN_GAP_MS
    }

    // --- Trigger detection (the guard design 3.3) ---------------------------------------------------

    /** Edge triggers between [Step.m]'s last snapshot and [s], in [PRIORITY] order; updates the wheel tracking. */
    private fun detect(st: Step, s: GuardSnapshot): List<GuardTrigger> {
        val prev = st.m.last
        val at = s.atMillis
        val quiet = st.m.quiet(at)
        val settling = at < st.m.settleUntil
        val found = LinkedHashSet<GuardTrigger>()
        if (prev != null && prev.ready && prev.sameBike) {
            // 3 or 4 while plainly watching is always news: also when it arrived during our own sequence (a re-arm
            // the bike escalated through) and the edge itself fell into the quiet window.
            val watchingNow = st.m.phase == GuardPhase.WATCHING
            if (!quiet && s.defence in ALARMING && (prev.defence !in ALARMING || watchingNow)) found += GuardTrigger.BIKE_ALARM
            if (prev.batteryDetected == true && s.batteryDetected == false) found += GuardTrigger.BATTERY_REMOVED
            if (!quiet && s.lock == LockState.UNLOCKED && prev.lock != null && prev.lock != LockState.UNLOCKED) found += GuardTrigger.LOCK_OPENED
            if (s.lock == LockState.PIN_ENTRY && prev.lock != null && prev.lock != LockState.PIN_ENTRY) found += GuardTrigger.PIN_ENTRY
            if (s.buttonDown && !prev.buttonDown) found += GuardTrigger.BUTTON
            if (!quiet && !settling && s.status == BikeStatus.ALARM.code && prev.status != null && prev.status != BikeStatus.ALARM.code &&
                st.m.baseline?.status != BikeStatus.ALARM.code
            ) found += GuardTrigger.STATUS_ALARM
        }
        val rising = prev != null && prev.ready && prev.sameBike && s.wheelSignal && !prev.wheelSignal
        if (wheel(st, s, at, rising)) found += GuardTrigger.WHEEL
        return PRIORITY.filter { it in found }
    }

    /**
     * The wheel rules for [s] at [at] ([rising]: the signal just came on). True when the wheel counts now: once per
     * wheel-on period, and again every [GuardRules.SIREN_REPEAT_MS] while it keeps turning. Continued turning in
     * ALARM or REARMING keeps the movement time fresh.
     */
    private fun wheel(st: Step, s: GuardSnapshot, at: Long, rising: Boolean): Boolean {
        var m = st.m
        if (!s.wheelSignal) {
            st.m = m.copy(
                wheelReady = true, wheelOnSince = null, wheelFiredAt = null,
                wheelEdges = if (m.wheelReady) m.wheelEdges.filter { at - it < GuardRules.WHEEL_EDGE_WINDOW_MS } else emptyList(),
            )
            return false
        }
        if (rising) {
            m = m.copy(wheelOnSince = at, wheelEdges = m.wheelEdges.filter { at - it < GuardRules.WHEEL_EDGE_WINDOW_MS } + at)
        } else if (m.wheelOnSince == null) {
            m = m.copy(wheelOnSince = at)
        }
        if (!m.wheelReady) {
            st.m = m
            return false
        }
        val since = m.wheelOnSince ?: at
        val counts = (s.speedRaw ?: 0) >= GuardRules.WHEEL_FAST_KMH ||
            at - since >= GuardRules.WHEEL_HOLD_MS ||
            m.wheelEdges.count { at - it < GuardRules.WHEEL_EDGE_WINDOW_MS } >= 2
        if (m.phase == GuardPhase.ALARM || m.phase == GuardPhase.REARMING) m = m.copy(lastMovementAt = at)
        val fired = m.wheelFiredAt
        val fire = counts && (fired == null || at - fired >= GuardRules.SIREN_REPEAT_MS)
        st.m = if (fire) m.copy(wheelFiredAt = at) else m
        return fire
    }

    private fun unlockRequest(st: Step, at: Long) {
        val m = st.m
        when (m.phase) {
            GuardPhase.ARMING -> st.log(GuardLogLine.Ignored(GuardTrigger.UNLOCK_REQUEST))
            GuardPhase.WATCHING, GuardPhase.ALARM, GuardPhase.REARMING -> {
                if (m.quiet(at)) return
                if (m.last?.let { it.ready && it.sameBike } == false) return
                if (at < m.settleUntil) {
                    // The PRE_M1 settle right after the bike entered its watch: the owner may still touch the bike.
                    st.log(GuardLogLine.Ignored(GuardTrigger.UNLOCK_REQUEST))
                    return
                }
                fire(st, at, listOf(GuardTrigger.UNLOCK_REQUEST))
            }
            else -> Unit
        }
    }

    // --- What a trigger does (the guard design 3.4) --------------------------------------------------

    private fun fire(st: Step, at: Long, triggers: List<GuardTrigger>, sirenNone: Boolean = false) {
        val trigger = triggers.first()
        var m = st.m
        // 1. Episode.
        val movedAt = m.lastMovementAt
        if (m.episodeStart == null || movedAt == null || at - movedAt > GuardRules.EPISODE_CALM_MS) {
            m = m.copy(sirenCycles = 0, episodeStart = at)
        }
        m = m.copy(lastMovementAt = at, phase = if (m.phase == GuardPhase.REARMING) GuardPhase.REARMING else GuardPhase.ALARM)
        // 2. Siren.
        val note: SirenNote = when {
            sirenNone -> SirenNote.NONE
            m.defence == 3 -> SirenNote.BIKE
            !m.sirenImmediate -> SirenNote.OFF_BY_SETTING
            at < m.snoozeUntil -> SirenNote.SNOOZED
            // "Die Sirene läuft noch" only where it is true: the siren write is going out, or it went out moments
            // ago and the poll has not shown 3 yet. Once 5523 shows anything but 3 (siren over, stopped by a
            // re-arm, refused), a new movement is a new round, bounded by the cycle cap and the write budget.
            m.busy == SequenceKind.SIREN || at < m.sirenQuietUntil -> SirenNote.RUNNING
            m.sirenCycles >= GuardRules.MAX_SIREN_CYCLES -> SirenNote.CAP_REACHED
            m.budgetLeft(at) < 1 -> {
                if (!m.budgetLogged) {
                    st.log(GuardLogLine.Budget)
                    m = m.copy(budgetLogged = true)
                }
                SirenNote.BUDGET
            }
            m.busy != null || !sirenGapOver(m, at) -> {
                // Decided again when the running sequence is done or the siren gap is over; the owner is told now,
                // without a siren sentence.
                m = m.copy(pendingTrigger = trigger)
                SirenNote.NONE
            }
            else -> {
                m = m.copy(
                    sirenCycles = m.sirenCycles + 1, lastSirenAt = at, busy = SequenceKind.SIREN, busyAuto = true,
                    sirenSinceWatch = true,
                )
                st.add(GuardAction.RunSiren)
                SirenNote.YABA
            }
        }
        // 3. Notification.
        val lastAlert = m.lastAlertAt
        val alert = lastAlert == null || at - lastAlert >= GuardRules.NOTIFY_REPEAT_MS
        if (alert) m = m.copy(lastAlertAt = at)
        val record = AlarmRecord(at, trigger, note, m.sirenCycles)
        m = m.copy(lastAlarm = record)
        st.m = m
        st.add(GuardAction.NotifyAlarm(record, alert))
        // 4. Log and keep.
        st.log(GuardLogLine.Trigger(record, triggers.drop(1)))
        st.add(GuardAction.Persist)
    }

    /** Leaving ALARM (the guard design 3.5), on every snapshot and tick while nothing runs. */
    private fun alarmCalm(st: Step, at: Long) {
        val m = st.m
        if (m.phase != GuardPhase.ALARM || m.busy != null) return
        val moved = m.lastMovementAt ?: return
        val def = m.defence
        val calmLong = at - moved >= GuardRules.ALARM_CALM_MS
        // Nothing of the bike's state changed and YABA wrote no siren: watching goes on as it was, without writes
        // (an unconfirmed watch, or the siren setting off, and only a button or the wheel moved).
        val unchanged = def !in ALARMING && !m.sirenSinceWatch && def == m.baseline?.defence &&
            m.last?.lock != LockState.UNLOCKED
        when {
            def == 3 -> Unit // the siren runs
            def == 2 -> if (calmLong) {
                st.m = watching(m, freshWheel = false).copy(bikeWatch = true, needRearm = false)
                st.add(GuardAction.Persist)
            }
            unchanged -> if (calmLong) {
                st.m = watching(m, freshWheel = false)
                st.add(GuardAction.Persist)
            }
            // The lock was found open after a reconnect: no automatic lock write. Back to watching when calm; with the
            // bike in 3/4 the alarm stays shown (watching would count 3/4 again at once).
            m.holdWrites -> if (calmLong && def !in ALARMING) {
                st.m = watching(m, freshWheel = false)
                st.add(GuardAction.Persist)
            }
            at - moved >= GuardRules.REARM_CALM_MS && m.parked() -> {
                if (m.budgetLeft(at) >= REARM_WRITES) {
                    startRearm(st, m.copy(budgetLogged = false), at, owner = false)
                } else if (!m.budgetLogged) {
                    st.m = m.copy(budgetLogged = true)
                    st.log(GuardLogLine.Budget)
                }
            }
        }
    }

    /**
     * The automatic lock writes outside ALARM's own rules, each only on a parked bike and never under [GuardMachine.holdWrites]:
     * the re-lock that puts back what YABA's own 5523 = 0 opened (not budgeted, at most [MAX_RELOCK_TRIES]), and the
     * re-arm after the bike left its watch ([GuardMachine.needRearm], budgeted).
     */
    private fun automatic(st: Step, at: Long) {
        val m = st.m
        if (m.busy != null || m.holdWrites || !m.parked()) return
        if (m.phase != GuardPhase.WATCHING && m.phase != GuardPhase.ALARM) return
        if (m.lockOwed && m.relockTries < MAX_RELOCK_TRIES && at >= m.relockAt) {
            st.m = m.copy(busy = SequenceKind.RELOCK, busyAuto = false, relockTries = m.relockTries + 1)
            st.add(GuardAction.RunRelock)
            return
        }
        if (m.phase == GuardPhase.WATCHING && m.needRearm && at >= m.rearmNotBefore) {
            if (m.budgetLeft(at) >= REARM_WRITES) {
                startRearm(st, m.copy(needRearm = false, budgetLogged = false), at, owner = false)
            } else if (!m.budgetLogged) {
                st.m = m.copy(budgetLogged = true)
                st.log(GuardLogLine.Budget)
            }
        }
    }

    // --- Sequences ------------------------------------------------------------------------------

    private fun sequenceDone(st: Step, d: GuardInput.SequenceDone) {
        val at = d.atMillis
        var m = st.m
        if (m.busy != d.kind) return // a cancelled or stale sequence
        if (d.cancelled) {
            // Dropped by the owner's tap before it wrote anything.
            m = m.copy(busy = null, busyAuto = false, rearmStartedAt = null)
            if (d.kind == SequenceKind.SIREN) m = m.copy(sirenCycles = (m.sirenCycles - 1).coerceAtLeast(0))
            st.m = m
            st.log(GuardLogLine.Cancelled(d.kind))
            if (d.kind == SequenceKind.REARM && m.phase == GuardPhase.REARMING) st.m = st.m.copy(phase = GuardPhase.ALARM)
            afterSequence(st, at)
            return
        }
        val auto = m.busyAuto
        val writes = d.readback?.writes ?: d.attempted
        m = m.copy(
            busy = null, busyAuto = false,
            quietUntil = at + GuardRules.OWN_WRITE_QUIET_MS,
            autoWrites = if (auto && writes > 0) (m.autoWrites.filter { at - it < GuardRules.WRITE_BUDGET_WINDOW_MS } + List(writes) { at }) else m.autoWrites,
        )
        d.readback?.defence?.let { m = m.copy(defence = it) }
        st.m = m
        if (!d.ok && d.kind != SequenceKind.VERIFY) st.log(GuardLogLine.SequenceFailed(d.kind, d.error))
        when (d.kind) {
            SequenceKind.ARM -> Unit // arming reports through ArmDone
            SequenceKind.SIREN -> {
                if (d.ok) {
                    st.log(GuardLogLine.Siren(d.readback?.defence))
                    st.m = st.m.copy(sirenQuietUntil = at + GuardRules.OWN_WRITE_QUIET_MS)
                }
                afterSequence(st, at)
            }
            SequenceKind.REARM -> {
                rearmDone(st, at, d)
                afterSequence(st, at)
                automatic(st, at)
            }
            SequenceKind.RELOCK -> {
                relockDone(st, at, d)
                afterSequence(st, at)
            }
            SequenceKind.VERIFY -> verified(st, at, d)
            SequenceKind.DISARM -> disarmDone(st, at, d)
        }
    }

    private fun rearmDone(st: Step, at: Long, d: GuardInput.SequenceDone) {
        val m = st.m
        if (m.phase != GuardPhase.REARMING && m.phase != GuardPhase.ALARM) return
        val rb = d.readback
        if (d.ok) st.log(GuardLogLine.Rearmed(rb?.defence, rb?.lock))
        val locked = d.ok && rb?.lock == 1
        val confirmed = locked && rb?.defence == 2
        val w = watching(m.copy(last = m.last?.withReads(rb?.defence, rb?.lock, null)), freshWheel = false).copy(
            bikeWatch = confirmed,
            settleUntil = if (confirmed) at + SETTLE_MS else m.settleUntil,
            // Our 5523 = 0 opened the software lock: unless 5521 read 1 at the end, it is put back (re-lock).
            lockOwed = !locked,
            relockTries = 0,
            relockAt = at,
            needRearm = false,
        )
        // Movement while the re-arm ran (already notified, no siren decided then) keeps the alarm open.
        val moved = w.lastMovementAt
        val started = w.rearmStartedAt
        val stillMoving = moved != null && started != null && moved > started && w.pendingTrigger == null
        st.m = (if (stillMoving) w.copy(phase = GuardPhase.ALARM) else w).copy(rearmStartedAt = null)
        if (locked) clearNotLocked(st)
        // The bike escalated through our re-arm (3 or 4 at the end): its own alarm, handled as a trigger right after.
        if (d.ok && rb?.defence in ALARMING && st.m.pendingTrigger == null) st.m = st.m.copy(pendingTrigger = GuardTrigger.BIKE_ALARM)
        st.add(GuardAction.Persist)
    }

    private fun relockDone(st: Step, at: Long, d: GuardInput.SequenceDone) {
        val m = st.m
        val lock = d.readback?.lock
        if (d.ok) st.log(GuardLogLine.Relocked(lock))
        if (d.ok && lock == 1) {
            st.m = m.copy(lockOwed = false, relockTries = 0)
            clearNotLocked(st)
            st.add(GuardAction.Persist)
            return
        }
        if (m.relockTries >= MAX_RELOCK_TRIES) {
            // The bike stays open: say so loudly, once, and leave the next try to the owner ("Erneut versuchen").
            if (m.issue != GuardIssue.NOT_LOCKED) {
                st.m = m.copy(issue = GuardIssue.NOT_LOCKED, issueValue = lock, issueDetail = null)
                st.log(GuardLogLine.NotLocked(lock))
                st.add(GuardAction.NotifyNotLocked(lock))
            }
        } else {
            st.m = m.copy(relockAt = at + RELOCK_BACKOFF_MS[(m.relockTries - 1).coerceIn(0, RELOCK_BACKOFF_MS.size - 1)])
        }
        st.add(GuardAction.Persist)
    }

    private fun clearNotLocked(st: Step) {
        if (st.m.issue != GuardIssue.NOT_LOCKED) return
        st.m = st.m.copy(issue = null, issueValue = null, issueDetail = null)
        st.add(GuardAction.ClearNotLocked)
    }

    /** What waited behind a sequence: "Wache beenden" first, then "Sirene aus", then a trigger. */
    private fun afterSequence(st: Step, at: Long) {
        val m = st.m
        if (m.phase == GuardPhase.LOST || m.phase == GuardPhase.OFF) return
        when {
            m.pendingDisarm || m.phase == GuardPhase.DISARMING -> {
                st.m = m.copy(phase = GuardPhase.DISARMING, pendingDisarm = false, pendingSilence = false, pendingTrigger = null)
                startDisarm(st, at)
            }
            m.pendingSilence -> startRearm(st, m.copy(pendingSilence = false), at, owner = true)
            else -> m.pendingTrigger?.let { t ->
                // The tick fires it once the siren gap is over (when a siren could follow at all).
                if (!sirenGapOver(m, at) && m.sirenImmediate && m.defence != 3 && at >= m.snoozeUntil) return
                st.m = m.copy(pendingTrigger = null)
                fire(st, at, listOf(t))
            }
        }
    }

    // --- Reconnect (the guard design 4) -------------------------------------------------------------

    private fun verified(st: Step, at: Long, d: GuardInput.SequenceDone) {
        val m = st.m
        if (m.phase != GuardPhase.LOST) {
            afterSequence(st, at)
            return
        }
        if (!d.ok) {
            // Retried with a growing wait; logged on the first failure and then only now and then.
            val failures = m.verifyFailures + 1
            val wait = (VERIFY_RETRY_MS shl (failures - 1).coerceAtMost(4)).coerceAtMost(VERIFY_RETRY_MAX_MS)
            st.m = m.copy(verifyRetryAt = at + wait, verifyFailures = failures)
            if (failures == 1 || failures % 20 == 0) st.log(GuardLogLine.SequenceFailed(d.kind, d.error))
            return
        }
        val rb = d.readback
        val def = rb?.defence
        val lock = rb?.lock
        if (m.lostNotified) st.add(GuardAction.ClearLost)
        st.log(GuardLogLine.Back(def, lock))
        // The reads are fresher than a state that may still hold values from before the loss: they are the baseline.
        val fresh = m.last?.withReads(def, lock, rb?.status)
        val back = watching(m.copy(last = fresh), freshWheel = true).copy(
            lostNotified = false, linkDownSince = null, defence = def, verifyFailures = 0,
            quietUntil = at + GuardRules.OWN_WRITE_QUIET_MS,
            lockOwed = m.lockOwed && lock != 1, relockTries = 0, relockAt = 0, needRearm = false, rearmNotBefore = 0,
        )
        st.m = back
        if (lock == 1) clearNotLocked(st)
        val backed = st.m
        val silenceWaiting = m.pendingSilence
        val before = m.defenceBeforeLost
        when {
            def in ALARMING && before !in ALARMING -> {
                st.m = backed.copy(phase = GuardPhase.ALARM)
                fire(st, at, listOf(GuardTrigger.OFFLINE_ALARM), sirenNone = true)
            }
            def in ALARMING -> {
                st.m = backed.copy(phase = GuardPhase.ALARM, lastMovementAt = m.lastMovementAt ?: at)
                st.add(GuardAction.Persist)
            }
            lock == LockState.PIN_ENTRY.code -> {
                // Someone enters the code right now.
                st.m = backed.copy(bikeWatch = false)
                fire(st, at, listOf(GuardTrigger.PIN_ENTRY))
            }
            lock == LockState.UNLOCKED.code && !m.lockOwed -> {
                // Opened while the phone was away, not by YABA (the owner's code, or someone else): notify, write nothing,
                // and leave locking again to the owner ("Erneut versuchen"). Whoever rides it is not locked mid-ride.
                st.m = backed.copy(bikeWatch = false, needRearm = false, holdWrites = true, issue = GuardIssue.NOT_LOCKED, issueValue = lock, issueDetail = null)
                st.log(GuardLogLine.Held)
                fire(st, at, listOf(GuardTrigger.LOCK_OPENED), sirenNone = true)
            }
            def == 2 -> {
                st.m = backed.copy(bikeWatch = true)
                st.add(GuardAction.Persist)
            }
            else -> {
                // The bike left its watch while the phone was away (for example it restarted), or the lock YABA's own
                // 5523 = 0 opened is still open: watch again once the bike stands.
                st.m = backed.copy(bikeWatch = false, needRearm = true)
                st.add(GuardAction.Persist)
            }
        }
        // "Sirene aus" tapped while the link was down: it still applies if the bike's siren or tracking is on.
        if (silenceWaiting) {
            st.m = st.m.copy(pendingSilence = false)
            if (st.m.phase == GuardPhase.ALARM && def in ALARMING && st.m.busy == null) {
                startRearm(st, st.m.copy(holdWrites = false), at, owner = true)
                return
            }
        }
        automatic(st, at)
    }

    // --- Silence, retry, disarm ------------------------------------------------------------------

    private fun silence(st: Step, at: Long) {
        val m = st.m
        when (m.phase) {
            GuardPhase.OFF, GuardPhase.ARMING, GuardPhase.DISARMING -> return
            GuardPhase.LOST -> {
                // No link: snooze now, stop the siren after the reconnect check if it still runs.
                st.m = m.copy(snoozeUntil = at + GuardRules.SILENCE_SNOOZE_MS, pendingSilence = true)
                st.add(GuardAction.ClearAlarm)
                st.log(GuardLogLine.Silence)
                st.add(GuardAction.Persist)
                return
            }
            GuardPhase.WATCHING -> if (m.defence != 3) {
                // A stale notification action: nothing sounds. The alarm notification goes, the snooze holds.
                st.m = m.copy(snoozeUntil = at + GuardRules.SILENCE_SNOOZE_MS)
                st.add(GuardAction.ClearAlarm)
                st.log(GuardLogLine.Silence)
                st.add(GuardAction.Persist)
                return
            }
            GuardPhase.ALARM, GuardPhase.REARMING -> Unit
        }
        st.m = m.copy(snoozeUntil = at + GuardRules.SILENCE_SNOOZE_MS, pendingTrigger = null)
        st.add(GuardAction.ClearAlarm)
        st.log(GuardLogLine.Silence)
        when {
            m.busy == SequenceKind.REARM -> Unit // already on its way back to watching
            m.busy != null -> {
                // An automatic siren still waiting for its gap is dropped; one already writing finishes first.
                if (m.busyAuto) st.add(GuardAction.CancelAuto)
                st.m = st.m.copy(pendingSilence = true)
            }
            else -> startRearm(st, st.m.copy(holdWrites = false), at, owner = true)
        }
        st.add(GuardAction.Persist)
    }

    private fun retryWatch(st: Step, at: Long) {
        val m = st.m
        if (m.phase != GuardPhase.WATCHING || m.busy != null) return
        startRearm(st, m.copy(holdWrites = false, needRearm = false), at, owner = true)
    }

    /** The re-arm sequence (the guard design 3.5); [owner]: "Sirene aus" or "Erneut versuchen", outside the write budget. */
    private fun startRearm(st: Step, m: GuardMachine, at: Long, owner: Boolean) {
        // Its first write (5523 = 0) stops a sounding siren and opens the software lock: the lock is owed until 5521 reads 1.
        st.m = m.copy(
            phase = GuardPhase.REARMING, busy = SequenceKind.REARM, busyAuto = !owner, rearmStartedAt = at, sirenQuietUntil = 0,
            lockOwed = true, relockTries = if (owner) 0 else m.relockTries,
        )
        st.add(GuardAction.RunRearm(owner))
        st.add(GuardAction.Persist)
    }

    private fun disarm(st: Step, at: Long) {
        val m = st.m
        when (m.phase) {
            GuardPhase.OFF, GuardPhase.DISARMING -> Unit
            GuardPhase.ARMING -> {
                st.add(GuardAction.CancelSequence)
                st.m = m.copy(phase = GuardPhase.DISARMING, busy = null, pendingTrigger = null)
                startDisarm(st, at)
            }
            else -> {
                // The lost notification has done its job once "Wache beenden" was tapped.
                if (m.lostNotified) st.add(GuardAction.ClearLost)
                if (m.busy != null) {
                    // An automatic sequence that has not written yet is dropped; anything else finishes first.
                    if (m.busyAuto) st.add(GuardAction.CancelAuto)
                    st.m = m.copy(phase = GuardPhase.DISARMING, pendingDisarm = true, pendingTrigger = null, pendingSilence = false)
                } else {
                    st.m = m.copy(phase = GuardPhase.DISARMING, pendingTrigger = null, pendingSilence = false)
                    startDisarm(st, at)
                }
            }
        }
    }

    /** Runs the disarm sequence now when the link is up, else waits up to [GuardRules.DISARM_LINK_WAIT_MS] for it. */
    private fun startDisarm(st: Step, at: Long) {
        val m = st.m
        val linked = m.last?.let { it.ready && it.sameBike } == true
        st.m = if (linked) {
            st.add(GuardAction.RunDisarm)
            m.copy(busy = SequenceKind.DISARM, busyAuto = false, disarmDeadline = null, pendingDisarm = false)
        } else {
            m.copy(disarmDeadline = at + GuardRules.DISARM_LINK_WAIT_MS, pendingDisarm = false)
        }
    }

    private fun disarmDone(st: Step, at: Long, d: GuardInput.SequenceDone) {
        val m = st.m
        val rb = d.readback
        if (!d.ok && rb == null) {
            // The sequence did not get through (link dropped, error): wait for the link once more, then end offline.
            val attempts = m.disarmAttempts + 1
            val wrote = m.disarmWrites + d.attempted
            if (attempts < MAX_DISARM_ATTEMPTS) {
                st.m = m.copy(
                    phase = GuardPhase.DISARMING, disarmAttempts = attempts, disarmWrites = wrote,
                    disarmDeadline = at + GuardRules.DISARM_LINK_WAIT_MS, disarmRetryAt = at + DISARM_RETRY_MS,
                )
                return
            }
            st.m = m.copy(disarmWrites = wrote)
            endOffline(st)
            return
        }
        st.log(GuardLogLine.Disarmed(rb?.defence, rb?.lock))
        st.m = when {
            rb?.ok == true -> off(m)
            rb?.defence == null -> off(m).copy(issue = GuardIssue.ENDED_PARTLY)
            rb.defence != 0 && rb.defence != 1 -> off(m).copy(issue = GuardIssue.DISARM_UNCONFIRMED, issueValue = rb.defence)
            // The alarm is off, but the lock did not open.
            else -> off(m).copy(issue = GuardIssue.DISARM_LOCKED, issueValue = rb.lock)
        }
        st.add(GuardAction.ClearAlarm)
        st.add(GuardAction.ClearLost)
        st.add(GuardAction.ClearNotLocked)
        st.add(GuardAction.Stop)
    }

    /**
     * The Wache ends in YABA without an answer from the bike. Nothing reached it ([GuardIssue.ENDED_OFFLINE]: nothing
     * changed there), or a disarm wrote without an answer ([GuardIssue.ENDED_PARTLY]: the state is unknown).
     */
    private fun endOffline(st: Step) {
        val m = st.m
        val wrote = m.disarmWrites > 0
        st.m = off(m).copy(issue = if (wrote) GuardIssue.ENDED_PARTLY else GuardIssue.ENDED_OFFLINE)
        st.log(if (wrote) GuardLogLine.DisarmedUnconfirmed else GuardLogLine.DisarmedOffline)
        st.add(GuardAction.ClearAlarm)
        st.add(GuardAction.ClearLost)
        st.add(GuardAction.ClearNotLocked)
        st.add(GuardAction.Stop)
    }

    // --- Restore --------------------------------------------------------------------------------

    private fun restore(st: Step, at: Long, s: StoredGuard) {
        if (st.m.active || !s.armed) return
        st.m = GuardMachine(
            phase = GuardPhase.LOST, sirenImmediate = s.sirenImmediate, bikeWatch = s.bikeWatch, armPath = s.armPath,
            restoreAlarmMode = s.restoreAlarmMode, armedAt = s.armedAt ?: at, linkDownSince = at,
            // An alarm YABA already knew about before the restart is not news after it.
            defenceBeforeLost = s.defence ?: if (s.bikeWatch) 2 else null, defence = s.defence,
            sirenCycles = s.sirenCycles, episodeStart = s.episodeStart,
            lastSirenAt = s.lastSirenAt, lastMovementAt = s.lastAlarmAt,
            autoWrites = s.autoWrites.filter { at - it < GuardRules.WRITE_BUDGET_WINDOW_MS },
            lockOwed = s.lockOwed, holdWrites = s.holdWrites,
            lastAlarm = if (s.lastAlarmAt != null && s.lastAlarmTrigger != null) AlarmRecord(s.lastAlarmAt, s.lastAlarmTrigger, SirenNote.NONE, s.sirenCycles) else null,
            last = st.m.last,
        )
        st.log(GuardLogLine.Restored)
    }
}
