package dev.steven.s3control.ui.cockpit

import androidx.compose.runtime.Immutable
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ble.S2State
import dev.steven.s3control.ride.EventLog

/*
 * The cockpit's brain, in plain Kotlin (no Android, no Compose runtime calls): it turns bike states,
 * log events and the clock into one immutable [CockpitView] of enums and numbers. Text is resolved
 * in the composables, so this stays testable on the JVM and follows the app language.
 */

/** Every time rule of the cockpit in one place. */
object CockpitTiming {
    const val STALE_MS = 4_500L
    const val HOLD_MS = 8_000L
    const val ALERT_HAPTIC_GAP_MS = 10_000L
    const val BACK_AGAIN_MS = 3_000L
    const val MOVING_RECENT_MS = 5_000L
    const val LOST_MOVING_WINDOW_MS = 10_000L
    const val ASLEEP_MOVING_WINDOW_MS = 3_000L
    const val OFF_WINDOW_MS = 20_000L
    const val SHIPPING_QUIET_MS = 30_000L
    const val REARM_MS = 30_000L
    const val RECONNECT_GUARD_MS = 3_000L
    const val ACK_LOCK_MS = 1_500L
    const val PHASE2_SLOW_MS = 3_000L
    const val PHASE2_NO_LINK_MS = 5_000L
    const val PHASE2_MAX_MS = 20_000L
    /**
     * A kick-lock takeover in phase 1 gives the cockpit back after this long while the bike still rolls: the rider
     * rides on (the lock only bites at the next stop), so the moment gets its mark and waits in the memory line.
     */
    const val KICKLOCK_RIDING_MAX_MS = 6_000L
    const val SHIPPING_COUNTDOWN_MS = 10_000L
    const val SERIES_GAP_MS = 5_000L
    const val SERIES_MAX = 3
    const val JUMP_ALERT_MS = 30_000L
    const val MARK_GAP_MS = 1_500L
    const val MARK_FRESH_MS = 3_000L
    const val SAMPLE_MS = 1_500L
    const val TICK_MS = 1_000L
    const val RING = 16
    const val TIMELINE = 8
    const val SNAPSHOT_SPEED_MS = 3_000L
    const val PARKED_UNLOCK_MS = 3_000L
    /** "Fahrtprotokoll aus" holds the alert line only this long after opening, then moves to the status row. */
    const val LOGGER_ALERT_MS = 8_000L
    /** Phase 2 gives the cockpit back once the bike has been moving this long. */
    const val PHASE2_RIDE_AWAY_MS = 6_000L
    /** Precursors this close before a trigger are named with their age (the evidence line). */
    const val EVIDENCE_MS = 12_000L
}

enum class CockpitLink { LIVE, STALE, SEARCHING, CONNECTING, LOST, OFFLINE }

/** What the speed matrix shows. Changing the class sweeps; changing a value inside it does not. */
enum class MatrixKind(val contentClass: Int) {
    LOG(0), STALE(1), SPEED(2), NO_SPEED(1), BLE_SIGNAL(3), BLE_FOG(3), BLE_OFFLINE(4)
}

enum class LampState { OK, TRIPPED, SIGNAL_DOT, CAUTION, UNKNOWN }

enum class LampValue { NONE, DETECTED, MISSING, QUIET, OPEN, LOCKED, CODE, SUPPLY_OK, SUPPLY_LOW }

/** One lamp. [code] > 0 is printed in the tripped capsule; otherwise the capsule carries [value]. */
@Immutable
data class LampView(val state: LampState, val value: LampValue, val code: Int = 0, val count: Int = 0)

enum class AlertKind {
    BACK_AGAIN, SHIPPING, LINK_LOST, SLEEPING, FAULT, LOCK_RIDING, POWER_BUTTON,
    MEMORY, SUPPLY_LOW, BATTERY_JUMP, BATTERY_LOW, STALE, SEARCHING, LOGGER_OFF, HISTORY,
}

enum class AlertTone { FOG, SIGNAL, LAMP }

enum class AlertAction { NONE, RETRY, START_LOGGER, REOPEN }

/**
 * The one alert in the slot. Arguments by kind: LINK_LOST n1 = last km/h (-1 unknown);
 * BATTERY_JUMP n1 to n2; BATTERY_LOW n1; STALE n1 = seconds; MEMORY variant, n1 = count, t1 = last, t2 = first;
 * HISTORY ref + code, t1 to t2. [since] is when the shown alert became live.
 */
@Immutable
data class AlertView(
    val kind: AlertKind,
    val rank: Int,
    val code: Int = 0,
    val n1: Int = 0,
    val n2: Int = 0,
    val t1: Long = 0,
    val t2: Long = 0,
    val ref: AlertKind? = null,
    val variant: TakeoverVariant? = null,
    val more: Int = 0,
    val since: Long = 0,
    val action: AlertAction = AlertAction.NONE,
    /** Whose fault table names [code]: an S2's numbers mean other things than the S3's. */
    val family: BikeFamily = BikeFamily.UNKNOWN,
) {
    val tone: AlertTone
        get() = when (kind) {
            AlertKind.SUPPLY_LOW, AlertKind.BATTERY_JUMP, AlertKind.BATTERY_LOW -> AlertTone.LAMP
            AlertKind.BACK_AGAIN, AlertKind.STALE, AlertKind.SEARCHING, AlertKind.LOGGER_OFF, AlertKind.HISTORY -> AlertTone.FOG
            else -> AlertTone.SIGNAL
        }

    /** Ranks 1 to 11 interrupt TalkBack; everything else waits its turn. */
    val assertive: Boolean get() = rank in 1..11
}

enum class TakeoverVariant(val priority: Int) { SHIPPING(1), ASLEEP(2), OFF(2), KICKLOCK(3) }

enum class SituationTag(val key: String) { BUMPS("bumps"), STEERING("steering"), RAIN("rain"), CLIMB("climb") }

enum class Sig { OK, TRIPPED, NONE }

enum class PrecursorKind { FAULT, LOCK, SUPPLY, BUTTON, JUMP }

/** READ_AT_STOP: the log read pauses polling, so while the bike moves it is only announced, never offered. */
enum class TakeoverPrimary { CONNECT, RETRY, READ_LOG, READ_AT_STOP, READING_LOG, LOG_OK, LOG_FAILED, UNLOCK }

/** A precursor seen [agoMs] before the trigger: "Spule fehlte 1,5 s vorher (42)". */
@Immutable
data class Evidence(val kind: PrecursorKind, val code: Int, val agoMs: Long)

/** The four rule signals at one moment (one column of the takeover timeline). */
@Immutable
data class SignalSample(val atMillis: Long, val coil: Sig, val switch: Sig, val key: Sig, val supply: Sig, val speed: Int?)

/** What the bike reported at the trigger, frozen. */
@Immutable
data class Snapshot(val atMillis: Long, val speed: Int?, val lock: LockState?, val faults: List<Int>, val supply: Boolean?)

@Immutable
data class TakeoverView(
    val variant: TakeoverVariant,
    val phase: Int,
    val snapshot: Snapshot,
    val also: List<TakeoverVariant>,
    /** SHIPPING only: whole seconds left of the ~10 s before motor and lights go off. */
    val countdown: Int?,
    val linkReady: Boolean,
    val precursor: PrecursorKind?,
    val precursorCode: Int,
    val precursorAgoS: Int,
    /** Every precursor of the last 12 s before the trigger, newest first (at most three). */
    val evidence: List<Evidence>,
    val timeline: List<SignalSample>,
    val tags: Set<SituationTag>,
    val ackEnabled: Boolean,
    val primary: TakeoverPrimary,
    val logPercent: Int?,
    val logAtMillis: Long,
    val logLines: Int,
    /** The mark set since the trigger (0 = none yet): "Verstanden" sets one, once. */
    val markNo: Int,
    val nextMarkNo: Int,
)

enum class HapticKind { TAKEOVER_LONG, TAKEOVER_KICK, SIGNAL, LAMP, MARK }

/**
 * A vibration request; [seq] grows with every new one, so the UI plays each exactly once.
 * [atMillis] lets a screen that was recreated (fold, rotation) skip a cue it already played.
 */
@Immutable
data class HapticCue(val seq: Int, val kind: HapticKind, val atMillis: Long = 0)

enum class BackResult { CLOSE, HINT, PHASE2, DISMISSED }

@Immutable
data class CockpitView(
    val ready: Boolean,
    val link: CockpitLink,
    /** Link lost while riding (or right after): the dot and the matrix turn signal. */
    val lostWhileMoving: Boolean,
    val rawLink: Link,
    val reconnects: Int,
    val matrix: MatrixKind,
    val speed: Int?,
    val logPercent: Int?,
    val mode: BikeStatus?,
    val modeSignal: Boolean,
    val battery: Int?,
    val batteryLow: Boolean,
    val chargerIn: Boolean,
    val coil: LampView,
    val switch: LampView,
    val key: LampView,
    val supply: LampView,
    val alert: AlertView?,
    val marks: Int,
    val lastMarkNo: Int,
    val lastMarkAt: Long,
    val justMarked: Boolean,
    val loggerActive: Boolean,
    val loggerSince: Long?,
    val loggerSamples: Int,
    /** Logger off and its alert-line time is over: a grey dot and word in the status row. */
    val loggerOffQuiet: Boolean,
    val connectedAt: Long?,
    val takeover: TakeoverView?,
    val haptic: HapticCue?,
    /** Moving now or within the last 5 s: lock the orientation, ask for a second back. */
    val movingRecently: Boolean,
    /**
     * S3 (and a bike of unknown family): the four lamps of the shipping rule. An S2 reports none of their
     * signals (plan A1), so its cockpit shows speed, battery, [errorCode] and the marks, and no takeover.
     */
    val s3Lamps: Boolean = true,
    /** S2 only: its one error code as a lamp, tripped with the code or OK for none; [LampView.count] = times it came up. */
    val errorCode: LampView = LampView(LampState.UNKNOWN, LampValue.NONE),
    /** The gear the bike last commanded (1..4) while live; null on an S2, which has no e-shifter, or when unknown. */
    val gear: Int? = null,
    /** The assist level while live (0 = off); null when unknown. */
    val powerLevel: Int? = null,
)

/** Process-wide mark numbering with the 1.5 s gate that touch and volume keys share. */
class CockpitMarks(private val gapMs: Long = CockpitTiming.MARK_GAP_MS) {
    private var lastAt = NEVER
    private var counter = 0

    /** The next mark number, or null when the last accepted mark is less than [gapMs] old. */
    @Synchronized
    fun next(now: Long): Int? {
        if (now - lastAt < gapMs) return null
        lastAt = now
        return ++counter
    }

    /** The number the next accepted mark will get. */
    @Synchronized
    fun peek(): Int = counter + 1
}

/** Battery up 15 points or more between two readings, without a charger, within 10 minutes. */
object BatteryJump {
    const val MIN_POINTS = 15
    const val MAX_GAP_MS = 10 * 60_000L

    fun isJump(before: Int?, beforeAt: Long, after: Int?, afterAt: Long, chargerPlugged: Boolean?): Boolean =
        before != null && after != null && after - before >= MIN_POINTS && chargerPlugged != true &&
            afterAt - beforeAt in 0..MAX_GAP_MS
}

private const val NEVER = Long.MIN_VALUE / 4

/** Faults that precede a shutdown in the shipping-mode notes: BMS output off, battery not detected, supply low, coil open. */
private val PRECURSOR_FAULTS = setOf(8, 18, 20, 42)

/** Fixed alert ranks of the faults that matter for the shipping hunt; every other fault is 11. */
fun faultRank(code: Int): Int = when (code) {
    42 -> 4
    18 -> 6
    8 -> 7
    20 -> 8
    45 -> 10
    else -> 11
}

class CockpitSession(
    private val clock: () -> Long,
    private val openSeq: Long,
    carry: Carry? = null,
    /** The number the next mark gets (process-wide in the app; the session's own count in tests). */
    private val nextMark: (() -> Int)? = null,
    /**
     * Sets a mark the way the "Verstanden" capsule does (event log, ride log) and returns its number, or null when the
     * 1.5 s gate refused it. Called by the session itself when a kick-lock takeover hands the cockpit back while riding.
     */
    private val autoMark: (() -> Int?)? = null,
) {

    /**
     * What a closed cockpit hands to the next one: marks keep counting and a dismissed takeover
     * stays in memory (and can be reopened), so closing never loses the moment.
     */
    class Carry internal constructor(
        internal val marks: Int,
        internal val lastMarkNo: Int,
        internal val lastMarkAt: Long,
        internal val memory: Memory?,
        internal val lastDismissed: Takeover?,
        internal val dismissed: Set<TakeoverVariant>,
        internal val lastTrigger: Map<TakeoverVariant, Long>,
        internal val triggerCount: Map<TakeoverVariant, Int>,
        internal val firstTrigger: Map<TakeoverVariant, Long>,
    )

    private class Entry(
        val kind: AlertKind,
        val rank: Int,
        val code: Int = 0,
        val n1: Int = 0,
        val n2: Int = 0,
        val t1: Long = 0,
        val t2: Long = 0,
        val variant: TakeoverVariant? = null,
        val action: AlertAction = AlertAction.NONE,
        val suppressed: Boolean = false,
    ) {
        val key: String get() = if (kind == AlertKind.FAULT) "FAULT:$code" else kind.name
        val signal: Boolean get() = rank in 2..11
    }

    private class Held(val key: String, val kind: AlertKind, val rank: Int, val code: Int, val start: Long)

    private class Cleared(val kind: AlertKind, val code: Int, val from: Long, val to: Long)

    internal class Memory(val variant: TakeoverVariant, val count: Int, val firstAt: Long, val lastAt: Long)

    internal class Takeover(
        val variant: TakeoverVariant,
        val at: Long,
        val snapshot: Snapshot,
        val timeline: List<SignalSample>,
        val also: MutableSet<TakeoverVariant>,
        val precursor: PrecursorKind?,
        val precursorCode: Int,
        val precursorAt: Long,
        val evidence: List<Evidence>,
    ) {
        var phase = 1
        val tags = LinkedHashSet<SituationTag>()
        var series = 1
        var lastSeriesAt = at
        /** When phase 2 last came up (riding away is counted from here). */
        var phase2At = at
        /** Handed back to the cockpit while riding at this moment (phase 2 waits for the stop). */
        var returnedAt = at
        /** Phase 2 has been on screen with the bike parked: it does not come back on its own again. */
        var stopShown = false
    }

    private class Pre(val kind: PrecursorKind, val code: Int, val at: Long)

    private val openedAt = clock()
    private var s = BikeState()
    private var hasState = false
    private var lastRaw: Map<String, ByteArray>? = null
    /** Rolling at 3 km/h or more since (the counterpart of [slowSince]; the wheel flag alone does not count). */
    private var rollingSince: Long? = null
    private val pres = ArrayDeque<Pre>()
    /** A takeover handed back to the cockpit while riding: phase 2 comes up at the next stop. */
    private var pendingStop: Takeover? = null

    /** When the last value arrived from the bike (any read or push). */
    var lastRxAtMillis: Long = clock()
        private set

    /** Last moment the controller paused polling for a log read or upload (data age does not count then). */
    private var lastPausedAt = NEVER
    private var lastMovingAt = NEVER
    private var lostWhileMoving = false
    private var lostSpeed: Int? = null
    private var lastSpeed: Int? = null
    private var lastSpeedAt = NEVER
    private var slowSince: Long? = null
    private var notReadySince: Long? = null
    private var parkedSince: Long? = null
    private var batteryAt = NEVER

    private var reconnectBase = -1
    private var reconnectAcc = 0
    private var reconnectLast = 0

    private var baselineStart: Long? = null
    private val baseline = HashSet<Int>()

    private val lampCounts = IntArray(4)
    private val lampWasTripped = BooleanArray(4)

    /** S2: how often an error code came up in this session; it rarely shows, so each one counts. */
    private var errorTrips = 0
    private var errorWasTripped = false

    /** The bike is an S2: no shipping-rule signals, its own error codes, no takeovers (plan A1). */
    private val s2: Boolean get() = s.family == BikeFamily.S2

    /** The fault ranks are S3 codes; every S2 error code is an ordinary fault (11). */
    private fun rank(code: Int): Int = if (s2) 11 else faultRank(code)

    private var precursorAt: Long? = null
    private var precursorKind: PrecursorKind? = null
    private var precursorCode = 0
    private var lastShippingAt: Long? = null
    private var asleepAt: Long? = null
    private var jumpFrom = 0
    private var jumpTo = 0
    private var jumpAt = NEVER

    private val liveSince = HashMap<String, Long>()
    private val liveSignal = HashMap<String, Entry>()
    private var held: Held? = null
    private var shownKey: String? = null
    private var lastCleared: Cleared? = null
    private var lastAlertHapticAt = NEVER
    private var backHintUntil = NEVER

    private var active: Takeover? = null
    private var lastDismissed: Takeover? = null
    private val dismissed = HashSet<TakeoverVariant>()
    private val lastTrigger = HashMap<TakeoverVariant, Long>()
    private val triggerCount = HashMap<TakeoverVariant, Int>()
    private val firstTrigger = HashMap<TakeoverVariant, Long>()
    private var memory: Memory? = null

    private val ring = ArrayDeque<SignalSample>()

    private var marks = 0
    private var lastMarkNo = 0
    private var lastMarkAt = NEVER

    private var loggerActive = false
    private var loggerSince: Long? = null
    private var loggerSamples = 0

    private var logAt = NEVER
    private var logFailed = false
    private var logLines = 0

    private var hapticSeq = 0
    private var haptic: HapticCue? = null

    init {
        if (carry != null) {
            marks = carry.marks
            lastMarkNo = carry.lastMarkNo
            lastMarkAt = carry.lastMarkAt
            memory = carry.memory
            lastDismissed = carry.lastDismissed
            dismissed += carry.dismissed
            lastTrigger += carry.lastTrigger
            triggerCount += carry.triggerCount
            firstTrigger += carry.firstTrigger
        }
    }

    var view: CockpitView = build(clock())
        private set

    /** Snapshot for the next session; an open takeover counts as dismissed. */
    fun carry(): Carry {
        active?.let { t ->
            t.phase = 2
            lastDismissed = t
            dismissed += t.variant
            memory = Memory(t.variant, triggerCount[t.variant] ?: 1, firstTrigger[t.variant] ?: t.at, lastTrigger[t.variant] ?: t.at)
            active = null
        }
        return Carry(
            marks, lastMarkNo, lastMarkAt, memory, lastDismissed,
            dismissed.toSet(), lastTrigger.toMap(), triggerCount.toMap(), firstTrigger.toMap(),
        )
    }

    // --- Inputs ----------------------------------------------------------------------------------

    fun onState(next: BikeState) {
        val now = clock()
        val prev = s
        val first = !hasState
        val prevBatteryAt = batteryAt
        s = next
        hasState = true
        val ready = next.link == Link.READY
        val wasReady = !first && prev.link == Link.READY

        if (next.raw !== lastRaw) {
            lastRaw = next.raw
            if (!first) lastRxAtMillis = now
        }
        if (ready && !wasReady) lastRxAtMillis = now

        if (reconnectBase < 0) {
            reconnectBase = next.reconnects
        } else if (next.reconnects < reconnectLast) {
            // A fresh connect() starts the controller's counter at 0 again.
            reconnectAcc += reconnectLast - reconnectBase
            reconnectBase = next.reconnects
        }
        reconnectLast = next.reconnects

        val moving = ready && next.moving
        if (moving) lastMovingAt = now
        rollingSince = if (ready && (next.speedKmh ?: 0) >= 3) rollingSince ?: now else null
        if (ready && next.speedKmh != null) {
            lastSpeed = next.speedKmh
            lastSpeedAt = now
        }

        if (!first && next.link == Link.LOST && prev.link != Link.LOST && now - lastMovingAt <= CockpitTiming.LOST_MOVING_WINDOW_MS) {
            lostWhileMoving = true
            lostSpeed = lastSpeed?.takeIf { now - lastSpeedAt <= CockpitTiming.LOST_MOVING_WINDOW_MS }
        }
        if (ready || next.link == Link.OFFLINE) lostWhileMoving = false

        val speed = next.speedKmh
        slowSince = if (ready && speed != null && speed < 3) slowSince ?: now else null
        parkedSince = if (ready && !moving) parkedSince ?: now else null
        notReadySince = if (!ready) notReadySince ?: now else null

        if (ready) {
            val start = baselineStart ?: now.also { baselineStart = it }
            if (now - start < CockpitTiming.HOLD_MS) {
                next.faults.filterTo(baseline) { rank(it) == 11 }
            }
            baseline.retainAll(next.faults.toSet())
        }

        if (!first && ready) {
            val guard = next.connectedAtMillis?.let { now - it in 0 until CockpitTiming.RECONNECT_GUARD_MS } ?: false
            for (code in next.faults - prev.faults.toSet()) {
                if (!s2 && code in PRECURSOR_FAULTS) precursor(PrecursorKind.FAULT, code, now)
            }
            if (prev.supplyOk == true && next.supplyOk == false) precursor(PrecursorKind.SUPPLY, 0, now)
            if (prev.buttons?.powerButton == false && next.buttons?.powerButton == true &&
                now - lastMovingAt <= CockpitTiming.MOVING_RECENT_MS
            ) {
                precursor(PrecursorKind.BUTTON, 0, now)
            }
            if (prev.status != BikeStatus.SLEEPING && next.status == BikeStatus.SLEEPING &&
                now - lastMovingAt <= CockpitTiming.ASLEEP_MOVING_WINDOW_MS && !guard
            ) {
                asleepAt = now
                trigger(TakeoverVariant.ASLEEP, now)
            }
            if (next.battery != prev.battery && next.battery != null) {
                if (BatteryJump.isJump(prev.battery, prevBatteryAt, next.battery, now, next.chargerPlugged)) {
                    jumpFrom = prev.battery ?: 0
                    jumpTo = next.battery
                    jumpAt = now
                    precursor(PrecursorKind.JUMP, 0, now)
                }
            }
        }
        if (ready && next.battery != null) batteryAt = now
        recompute(now)
    }

    fun onEvent(e: EventLog.Event) {
        if (e.seq <= openSeq) return
        val at = e.atMillis
        when (e.code) {
            EventLog.Code.SHIPPING -> {
                lastShippingAt = at
                trigger(TakeoverVariant.SHIPPING, at)
            }
            EventLog.Code.KICKLOCK_RIDING -> {
                precursor(PrecursorKind.LOCK, 0, at)
                if (!guarded(at)) trigger(TakeoverVariant.KICKLOCK, at)
            }
            EventLog.Code.SHUTDOWN_RIDING -> {
                val seen = asleepAt
                if (!guarded(at) && (seen == null || kotlin.math.abs(at - seen) > 2_000)) trigger(TakeoverVariant.ASLEEP, at)
            }
            EventLog.Code.LINK_LOST_RIDING -> {
                val p = precursorAt
                val ship = lastShippingAt
                if (p != null && at - p in 0..CockpitTiming.OFF_WINDOW_MS && (ship == null || at - ship >= CockpitTiming.SHIPPING_QUIET_MS)) {
                    trigger(TakeoverVariant.OFF, at)
                }
            }
            EventLog.Code.POWER_BUTTON_RIDING -> precursor(PrecursorKind.BUTTON, 0, at)
            EventLog.Code.SUPPLY_LOW -> precursor(PrecursorKind.SUPPLY, 0, at)
            EventLog.Code.BATTERY_JUMP -> precursor(PrecursorKind.JUMP, 0, at)
            EventLog.Code.FAULT -> e.value?.takeIf { !s2 && it in PRECURSOR_FAULTS }?.let { precursor(PrecursorKind.FAULT, it, at) }
            else -> Unit
        }
        recompute(clock())
    }

    fun onRecorder(active: Boolean, since: Long?, samples: Int) {
        if (active == loggerActive && since == loggerSince && samples == loggerSamples) return
        loggerActive = active
        loggerSince = since
        loggerSamples = samples
        recompute(clock())
    }

    fun onBikeLog(atMillis: Long?, failed: Boolean, lines: Int) {
        logAt = atMillis ?: NEVER
        logFailed = failed
        logLines = lines
        recompute(clock())
    }

    /** Once a second: staleness, holds, countdowns and the takeover phases move on time alone. */
    fun tick() = recompute(clock())

    /** Every 1.5 s, independent of the logger: the ring the takeover timeline is cut from. */
    fun sample() {
        val now = clock()
        ring.addLast(signalsAt(now))
        while (ring.size > CockpitTiming.RING) ring.removeFirst()
    }

    fun onMark(number: Int) {
        val now = clock()
        noteMark(number, now)
        recompute(now)
    }

    private fun noteMark(number: Int, now: Long) {
        marks++
        lastMarkNo = number
        lastMarkAt = now
        cue(HapticKind.MARK)
    }

    /** Phase 2 tag. Returns the takeover it belongs to (for the log line), or null if already set. */
    fun tag(tag: SituationTag): Pair<TakeoverVariant, Long>? {
        val t = active ?: return null
        if (!t.tags.add(tag)) return null
        recompute(clock())
        return t.variant to t.at
    }

    /**
     * "Verstanden" in phase 1 (ignored for the first 1.5 s). While the bike moves it hands the
     * cockpit back with the memory line, and phase 2 waits for the next stop; parked, phase 2 now.
     */
    fun acknowledge() {
        val t = active ?: return
        val now = clock()
        if (t.phase != 1 || now - t.at < CockpitTiming.ACK_LOCK_MS) return
        if (movingNow()) stash(t, resume = true, now) else toPhase2(t, now)
        recompute(now)
    }

    /** "Zurück zum Cockpit" (or back in phase 2): the moment stays in the memory line. */
    fun dismissTakeover() {
        val t = active ?: return
        stash(t, resume = false, now = clock())
        recompute(clock())
    }

    fun reopenTakeover() {
        val t = lastDismissed ?: return
        if (active != null) return
        val now = clock()
        active = t
        pendingStop = null
        // A fresh start for the ride-away count: a moment that was in phase 2 before must not be
        // handed straight back because the bike has been riding since.
        t.phase = 2
        t.phase2At = now
        recompute(now)
    }

    /** True once a mark was set since the open takeover's trigger. */
    fun markedSinceTrigger(): Boolean = active?.let { marks > 0 && lastMarkAt >= it.at } ?: false

    fun back(): BackResult {
        val now = clock()
        val t = active
        if (t != null) {
            return if (t.phase == 1) {
                if (movingNow()) stash(t, resume = true, now) else toPhase2(t, now)
                recompute(now)
                if (active == null) BackResult.DISMISSED else BackResult.PHASE2
            } else {
                dismissTakeover()
                BackResult.DISMISSED
            }
        }
        if (!movingRecently(now)) return BackResult.CLOSE
        if (now < backHintUntil) return BackResult.CLOSE
        backHintUntil = now + CockpitTiming.BACK_AGAIN_MS
        recompute(now)
        return BackResult.HINT
    }

    // --- Rules -------------------------------------------------------------------------------------

    private fun guarded(at: Long): Boolean =
        s.connectedAtMillis?.let { at - it in 0 until CockpitTiming.RECONNECT_GUARD_MS } ?: false

    private fun precursor(kind: PrecursorKind, code: Int, at: Long) {
        // The same signal from the state diff and from its log line counts once.
        if (pres.none { it.kind == kind && it.code == code && kotlin.math.abs(it.at - at) < 2_000 }) {
            pres.addLast(Pre(kind, code, at))
            while (pres.size > 16) pres.removeFirst()
        }
        val p = precursorAt
        if (p != null && at < p) return
        precursorAt = at
        precursorKind = kind
        precursorCode = code
    }

    private fun movingRecently(now: Long): Boolean = now - lastMovingAt <= CockpitTiming.MOVING_RECENT_MS

    private fun movingNow(): Boolean = s.link == Link.READY && s.moving

    private fun toPhase2(t: Takeover, now: Long) {
        if (t.phase != 2) t.phase2At = now
        t.phase = 2
    }

    /**
     * Back to the cockpit: the moment goes into the memory line (tap reopens it). With [resume]
     * the takeover comes back as phase 2 once the bike stands, unless phase 2 was already seen parked.
     */
    private fun stash(t: Takeover, resume: Boolean, now: Long) {
        lastDismissed = t
        dismissed += t.variant
        memory = Memory(t.variant, triggerCount[t.variant] ?: 1, firstTrigger[t.variant] ?: t.at, lastTrigger[t.variant] ?: t.at)
        active = null
        t.returnedAt = now
        pendingStop = if (resume && !t.stopShown) t else null
    }

    private fun parkedFor(now: Long, from: Long): Boolean =
        slowSince?.let { now - maxOf(it, from) >= CockpitTiming.PHASE2_SLOW_MS } ?: false

    private fun trigger(v: TakeoverVariant, at: Long) {
        // The takeovers read the S3 shipping rule (lamps, bike log); an S2 has neither, it keeps the cockpit.
        if (s2) return
        val now = clock()
        val previous = lastTrigger[v]
        lastTrigger[v] = at
        triggerCount[v] = (triggerCount[v] ?: 0) + 1
        firstTrigger.getOrPut(v) { at }
        val a = active
        if (a != null) {
            if (v.priority < a.variant.priority) {
                active = open(v, at, also = (a.also + a.variant).filter { it != v })
                cue(if (v == TakeoverVariant.KICKLOCK) HapticKind.TAKEOVER_KICK else HapticKind.TAKEOVER_LONG)
            } else if (v != a.variant) {
                a.also += v
            }
            return
        }
        if (v in dismissed && previous != null && at - previous < CockpitTiming.REARM_MS) {
            memory = Memory(v, triggerCount[v] ?: 1, firstTrigger[v] ?: at, at)
            alertCue(HapticKind.SIGNAL, now)
            return
        }
        active = open(v, at, also = emptyList())
        cue(if (v == TakeoverVariant.KICKLOCK) HapticKind.TAKEOVER_KICK else HapticKind.TAKEOVER_LONG)
    }

    private fun open(v: TakeoverVariant, at: Long, also: List<TakeoverVariant>): Takeover {
        val now = clock()
        val speed = when {
            kotlin.math.abs(at - lastSpeedAt) <= CockpitTiming.SNAPSHOT_SPEED_MS -> lastSpeed
            else -> ring.lastOrNull { it.speed != null && at - it.atMillis in 0..CockpitTiming.SNAPSHOT_SPEED_MS }?.speed
        }
        val snapshot = Snapshot(at, speed, s.lock, s.faults.sorted(), s.supplyOk)
        val before = ring.filter { it.atMillis <= at }.toMutableList()
        if (before.isEmpty() || at - before.last().atMillis > CockpitTiming.SAMPLE_MS / 2) before += signalsAt(now).copy(atMillis = at)
        val cut = before.takeLast(CockpitTiming.TIMELINE)
        val pad = List(CockpitTiming.TIMELINE - cut.size) { SignalSample(0, Sig.NONE, Sig.NONE, Sig.NONE, Sig.NONE, null) }
        val p = precursorAt
        // A kick-lock trigger is itself the lock report; it is not its own precursor.
        val evidence = pres.asSequence()
            .filter { at - it.at in 0..CockpitTiming.EVIDENCE_MS }
            .filterNot { v == TakeoverVariant.KICKLOCK && it.kind == PrecursorKind.LOCK && at - it.at < 1_000 }
            .sortedBy { at - it.at }
            .distinctBy { it.kind to it.code }
            .take(3)
            .map { Evidence(it.kind, it.code, at - it.at) }
            .toList()
        return Takeover(
            variant = v,
            at = at,
            snapshot = snapshot,
            timeline = pad + cut,
            also = also.toMutableSet(),
            precursor = precursorKind.takeIf { p != null && at - p in 0..CockpitTiming.OFF_WINDOW_MS },
            precursorCode = precursorCode,
            precursorAt = p ?: at,
            evidence = evidence,
        )
    }

    private fun signalsAt(now: Long): SignalSample {
        val ready = s.link == Link.READY
        val errorsKnown = ready && Gatt.ERRORS.short in s.raw
        val faults = s.faults
        return SignalSample(
            atMillis = now,
            coil = if (!errorsKnown) Sig.NONE else if (42 in faults) Sig.TRIPPED else Sig.OK,
            switch = when {
                !ready || s.lock == null -> Sig.NONE
                s.lock == LockState.LOCKED -> Sig.TRIPPED
                else -> Sig.OK
            },
            key = if (!errorsKnown) Sig.NONE else if (18 in faults) Sig.TRIPPED else Sig.OK,
            supply = when {
                !ready -> Sig.NONE
                s.supplyOk == false || (!s2 && (8 in faults || 20 in faults)) -> Sig.TRIPPED
                s.supplyOk == true -> Sig.OK
                else -> Sig.NONE
            },
            speed = s.speedKmh.takeIf { ready },
        )
    }

    private fun cue(kind: HapticKind) {
        haptic = HapticCue(++hapticSeq, kind, clock())
    }

    private fun alertCue(kind: HapticKind, now: Long) {
        if (now - lastAlertHapticAt < CockpitTiming.ALERT_HAPTIC_GAP_MS) return
        lastAlertHapticAt = now
        cue(kind)
    }

    private fun freshAt(): Long = maxOf(lastRxAtMillis, lastPausedAt)

    private fun isStale(now: Long): Boolean {
        if (s.link != Link.READY) return false
        if (Op.BIKE_LOG in s.busy || Op.UPLOAD in s.busy) {
            lastPausedAt = now
            return false
        }
        return now - freshAt() > CockpitTiming.STALE_MS
    }

    private fun lamps(moving: Boolean): List<LampView> {
        if (s.link != Link.READY || s2) return List(4) { LampView(LampState.UNKNOWN, LampValue.NONE) }
        val faults = s.faults
        val errorsKnown = Gatt.ERRORS.short in s.raw
        val coil = when {
            !errorsKnown -> LampView(LampState.UNKNOWN, LampValue.NONE)
            42 in faults -> LampView(LampState.TRIPPED, LampValue.MISSING, code = 42)
            else -> LampView(LampState.OK, LampValue.DETECTED)
        }
        val switch = when (s.lock) {
            null -> LampView(LampState.UNKNOWN, LampValue.NONE)
            LockState.LOCKED -> if (moving) LampView(LampState.TRIPPED, LampValue.LOCKED) else LampView(LampState.SIGNAL_DOT, LampValue.LOCKED)
            LockState.PIN_ENTRY -> LampView(LampState.SIGNAL_DOT, LampValue.CODE)
            LockState.UNLOCKED -> LampView(LampState.OK, LampValue.OPEN)
        }
        // Two sources: 5542 = 4 is the pack-present line (BAT_KEY_IN, PC10) itself, live; fault 18 is the
        // firmware's own record of it. Either one trips the lamp.
        val key = when {
            s.batteryDetected == false || 18 in faults ->
                LampView(LampState.TRIPPED, LampValue.MISSING, code = if (18 in faults) 18 else 0)
            !errorsKnown && s.batteryDetected == null -> LampView(LampState.UNKNOWN, LampValue.NONE)
            else -> LampView(LampState.OK, LampValue.QUIET)
        }
        val supplyValue = when (s.supplyOk) {
            true -> LampValue.SUPPLY_OK
            false -> LampValue.SUPPLY_LOW
            null -> LampValue.NONE
        }
        val supplyFault = faults.filter { it == 8 || it == 20 }.minOrNull()
        val supply = when {
            supplyFault != null -> LampView(LampState.TRIPPED, supplyValue, code = supplyFault)
            s.supplyOk == false -> LampView(LampState.CAUTION, LampValue.SUPPLY_LOW)
            s.supplyOk == true -> LampView(LampState.OK, LampValue.SUPPLY_OK)
            else -> LampView(LampState.UNKNOWN, LampValue.NONE)
        }
        return listOf(coil, switch, key, supply)
    }

    private fun liveEntries(now: Long, ready: Boolean, moving: Boolean, stale: Boolean): List<Entry> {
        val out = ArrayList<Entry>()
        val mem = memory
        // The S2's lock bit is unconfirmed while riding (plan A6): no lock alarm on it.
        val lockRiding = !s2 && ready && s.lock == LockState.LOCKED && moving
        // The handed-back kick-lock moment and the live lock report are one thing: the memory line
        // takes the lock's rank, so it stays on top and a tap still reopens the moment.
        val lockIsMemory = lockRiding && mem?.variant == TakeoverVariant.KICKLOCK
        if (s.status == BikeStatus.SHIPPING || s.shippingImminent) out += Entry(AlertKind.SHIPPING, 1)
        if (!ready && lostWhileMoving) out += Entry(AlertKind.LINK_LOST, 2, n1 = lostSpeed ?: -1, action = AlertAction.RETRY)
        if (ready) {
            if (s.status == BikeStatus.SLEEPING && moving) out += Entry(AlertKind.SLEEPING, 3)
            val baselineOver = baselineStart?.let { now - it >= CockpitTiming.HOLD_MS } ?: false
            for (code in s.faults.sorted()) {
                val rank = rank(code)
                out += Entry(AlertKind.FAULT, rank, code = code, suppressed = rank == 11 && baselineOver && code in baseline)
            }
            if (lockRiding && !lockIsMemory) out += Entry(AlertKind.LOCK_RIDING, 5)
            if (s.buttons?.powerButton == true && moving) out += Entry(AlertKind.POWER_BUTTON, 9)
        }
        if (mem != null) {
            out += Entry(
                AlertKind.MEMORY, if (lockIsMemory) 5 else 12,
                n1 = mem.count, t1 = mem.lastAt, t2 = mem.firstAt, variant = mem.variant, action = AlertAction.REOPEN,
            )
        }
        if (ready && s.supplyOk == false) out += Entry(AlertKind.SUPPLY_LOW, 13)
        if (now - jumpAt < CockpitTiming.JUMP_ALERT_MS) out += Entry(AlertKind.BATTERY_JUMP, 14, n1 = jumpFrom, n2 = jumpTo)
        val battery = s.battery
        if (ready && battery != null && battery <= 15) out += Entry(AlertKind.BATTERY_LOW, 15, n1 = battery)
        if (stale) out += Entry(AlertKind.STALE, 16, n1 = ((now - freshAt()) / 1000).toInt())
        if (!ready && s.link != Link.OFFLINE && !lostWhileMoving) {
            out += Entry(AlertKind.SEARCHING, 17, action = if (s.link == Link.LOST) AlertAction.RETRY else AlertAction.NONE)
        }
        // Only right after opening: after that it is a grey word in the status row, so the one
        // line keeps meaning "look at me".
        if (!loggerActive && now - openedAt < CockpitTiming.LOGGER_ALERT_MS) {
            out += Entry(AlertKind.LOGGER_OFF, 18, action = AlertAction.START_LOGGER)
        }
        out.sortWith(compareBy<Entry>({ it.rank }, { it.code }))
        return out
    }

    private fun recompute(now: Long) {
        val ready = s.link == Link.READY
        val moving = ready && s.moving
        if (moving) lastMovingAt = now
        val stale = isStale(now)

        // Phase 2 is for standing. Phase 1 turns into it once parked for 3 s or without a link for
        // 5 s; still riding, the cockpit comes back with the memory line instead: after 6 s for the kick
        // lock (with the mark the capsule would set), after 20 s for everything else.
        val t = active
        if (t != null && t.phase == 1) {
            val parked = parkedFor(now, t.at)
            val noLink = notReadySince?.let { now - maxOf(it, t.at) >= CockpitTiming.PHASE2_NO_LINK_MS } ?: false
            when {
                parked -> {
                    toPhase2(t, now)
                    t.stopShown = true
                }
                noLink -> toPhase2(t, now)
                t.variant == TakeoverVariant.KICKLOCK && moving && now - t.at >= CockpitTiming.KICKLOCK_RIDING_MAX_MS -> {
                    if (!markedSinceTrigger()) autoMark?.invoke()?.let { noteMark(it, now) }
                    stash(t, resume = true, now)
                }
                now - t.at >= CockpitTiming.PHASE2_MAX_MS -> if (moving) stash(t, resume = true, now) else toPhase2(t, now)
            }
        } else if (t != null) {
            if (parkedFor(now, t.phase2At)) t.stopShown = true
            // Riding away means real speed again: pushing the bike at walking pace keeps phase 2.
            val since = rollingSince
            if (since != null && now - maxOf(since, t.phase2At) >= CockpitTiming.PHASE2_RIDE_AWAY_MS) {
                stash(t, resume = true, now)
            }
        }
        val pending = pendingStop
        if (active == null && pending != null && parkedFor(now, pending.returnedAt)) {
            pendingStop = null
            active = pending
            toPhase2(pending, now)
            pending.stopShown = true
        }

        val lampList = lamps(moving)
        lampList.forEachIndexed { i, lamp ->
            val tripped = lamp.state == LampState.TRIPPED
            if (tripped && !lampWasTripped[i]) lampCounts[i]++
            lampWasTripped[i] = tripped
        }
        val errorLamp = errorLamp(ready)

        val live = liveEntries(now, ready, moving, stale)
        val liveKeys = live.mapTo(HashSet()) { it.key }
        // Track when each entry became live, and remember the last signal alert that cleared.
        val gone = liveSince.keys.filter { it !in liveKeys }
        for (k in gone) {
            val since = liveSince.remove(k) ?: continue
            val e = liveSignal.remove(k) ?: continue
            lastCleared = Cleared(e.kind, e.code, since, now)
        }
        for (e in live) {
            liveSince.getOrPut(e.key) { now }
            if (e.signal) liveSignal[e.key] = e
        }

        var shownAlert: AlertView? = null
        var shownEntry: Entry? = null
        if (now < backHintUntil) {
            shownAlert = AlertView(AlertKind.BACK_AGAIN, 0)
        } else {
            val top = live.firstOrNull { !it.suppressed }
            val h = held
            val holding = h != null && now - h.start < CockpitTiming.HOLD_MS && (top == null || top.rank >= h.rank)
            if (holding && h != null) {
                // The live lock report and the handed-back kick-lock moment are one line (see liveEntries): the
                // hold carries over to the memory line instead of showing the lock report as gone.
                val still = live.firstOrNull { it.key == h.key }
                    ?: live.firstOrNull {
                        h.kind == AlertKind.LOCK_RIDING && it.kind == AlertKind.MEMORY && it.variant == TakeoverVariant.KICKLOCK && it.rank == h.rank
                    }
                if (still != null) {
                    shownEntry = still
                } else {
                    val c = lastCleared?.takeIf { it.kind == h.kind && it.code == h.code }
                    shownAlert = AlertView(
                        AlertKind.HISTORY, 19, code = h.code, ref = h.kind,
                        t1 = c?.from ?: h.start, t2 = c?.to ?: now,
                    )
                }
            } else {
                shownEntry = top
                val topStart = top?.let { liveSince[it.key] } ?: now
                if (top != null && top.signal && (h == null || h.key != top.key || h.start != topStart)) {
                    held = Held(top.key, top.kind, top.rank, top.code, topStart)
                } else if (top != null && !top.signal && h != null && now - h.start >= CockpitTiming.HOLD_MS) {
                    held = null
                }
                if (top == null) {
                    val c = lastCleared
                    if (c != null) shownAlert = AlertView(AlertKind.HISTORY, 19, code = c.code, ref = c.kind, t1 = c.from, t2 = c.to)
                }
            }
        }
        val e = shownEntry
        if (e != null) {
            shownAlert = AlertView(
                kind = e.kind, rank = e.rank, code = e.code, n1 = e.n1, n2 = e.n2, t1 = e.t1, t2 = e.t2,
                variant = e.variant, since = liveSince[e.key] ?: now, action = e.action,
            )
        }
        val key = e?.key ?: shownAlert?.let { "${it.kind}:${it.code}" }
        if (key != shownKey) {
            shownKey = key
            // The memory line is the moment just seen (a repeat cues on its own in trigger()).
            if (e != null && active == null && e.kind != AlertKind.MEMORY) {
                when (e.rank) {
                    in 2..11 -> alertCue(HapticKind.SIGNAL, now)
                    in 13..15 -> alertCue(HapticKind.LAMP, now)
                }
            }
        }
        // The logger state is not an alert: it never inflates "+n".
        val more = live.count { it.rank in 1..17 && it.key != e?.key }
        if (shownAlert != null) {
            shownAlert = shownAlert.copy(more = if (shownAlert.kind == AlertKind.BACK_AGAIN) 0 else more, family = s.family)
        }

        if (t != null && t === active && t.phase == 1 && t.variant != TakeoverVariant.KICKLOCK && moving &&
            t.series < CockpitTiming.SERIES_MAX && now - t.lastSeriesAt >= CockpitTiming.SERIES_GAP_MS
        ) {
            t.series++
            t.lastSeriesAt = now
            cue(HapticKind.TAKEOVER_LONG)
        }

        view = build(now, ready, moving, stale, lampList, shownAlert, errorLamp)
    }

    /** S2: the error code from the status block; unknown until a block has arrived on this link. */
    private fun errorLamp(ready: Boolean): LampView {
        if (!s2) return LampView(LampState.UNKNOWN, LampValue.NONE)
        val code = s.faults.minOrNull()
        val tripped = ready && code != null
        if (tripped && !errorWasTripped) errorTrips++
        errorWasTripped = tripped
        return when {
            !ready || S2State.RAW_KEY !in s.raw -> LampView(LampState.UNKNOWN, LampValue.NONE, count = errorTrips)
            code != null -> LampView(LampState.TRIPPED, LampValue.NONE, code = code, count = errorTrips)
            else -> LampView(LampState.OK, LampValue.NONE, count = errorTrips)
        }
    }

    private fun build(
        now: Long,
        ready: Boolean = false,
        moving: Boolean = false,
        stale: Boolean = false,
        lampList: List<LampView> = List(4) { LampView(LampState.UNKNOWN, LampValue.NONE) },
        alert: AlertView? = null,
        errorLamp: LampView = LampView(LampState.UNKNOWN, LampValue.NONE),
    ): CockpitView {
        val link = when (s.link) {
            Link.READY -> if (stale) CockpitLink.STALE else CockpitLink.LIVE
            Link.SEARCHING -> CockpitLink.SEARCHING
            Link.CONNECTING, Link.AUTHENTICATING -> CockpitLink.CONNECTING
            Link.LOST -> CockpitLink.LOST
            Link.OFFLINE -> CockpitLink.OFFLINE
        }
        val matrix = when {
            s.logProgress != null -> MatrixKind.LOG
            ready && stale -> MatrixKind.STALE
            ready && s.speedKmh != null -> MatrixKind.SPEED
            ready -> MatrixKind.NO_SPEED
            lostWhileMoving -> MatrixKind.BLE_SIGNAL
            s.link == Link.OFFLINE -> MatrixKind.BLE_OFFLINE
            else -> MatrixKind.BLE_FOG
        }
        val status = s.status.takeIf { ready }
        val battery = s.battery.takeIf { ready }
        val lamps = lampList.mapIndexed { i, l -> l.copy(count = lampCounts[i]) }
        return CockpitView(
            ready = ready,
            link = link,
            lostWhileMoving = lostWhileMoving && !ready,
            rawLink = s.link,
            reconnects = reconnectAcc + (reconnectLast - reconnectBase).coerceAtLeast(0),
            matrix = matrix,
            speed = s.speedKmh.takeIf { ready },
            logPercent = s.logProgress?.let { (it * 100).toInt().coerceIn(0, 100) },
            mode = status,
            modeSignal = ready && (
                status == BikeStatus.SHIPPING || status == BikeStatus.ALARM || s.shippingImminent ||
                    (status == BikeStatus.SLEEPING && moving)
                ),
            battery = battery,
            batteryLow = battery != null && battery <= 15,
            chargerIn = ready && s.chargerPlugged == true,
            coil = lamps[0],
            switch = lamps[1],
            key = lamps[2],
            supply = lamps[3],
            alert = alert,
            marks = marks,
            lastMarkNo = lastMarkNo,
            lastMarkAt = if (marks > 0) lastMarkAt else 0,
            justMarked = marks > 0 && now - lastMarkAt < CockpitTiming.MARK_FRESH_MS,
            loggerActive = loggerActive,
            loggerSince = loggerSince,
            loggerSamples = loggerSamples,
            loggerOffQuiet = !loggerActive && alert?.kind != AlertKind.LOGGER_OFF,
            connectedAt = s.connectedAtMillis.takeIf { ready },
            takeover = active?.let { takeoverView(it, now, ready, moving) },
            haptic = haptic,
            movingRecently = moving || movingRecently(now),
            s3Lamps = !s2,
            errorCode = errorLamp,
            gear = s.gear.takeIf { ready && !s2 && it in 1..4 },
            powerLevel = s.powerLevel.takeIf { ready },
        )
    }

    private fun takeoverView(t: Takeover, now: Long, ready: Boolean, moving: Boolean): TakeoverView {
        val reading = s.logProgress != null || Op.BIKE_LOG in s.busy
        val logAfter = logAt > t.at
        val parkedLong = parkedSince?.let { now - it >= CockpitTiming.PARKED_UNLOCK_MS } ?: false
        // Reading the log pauses polling: never offered while the bike moves or stood less than the
        // 3 s that make a stop (the same 3 s that bring phase 2 up, so the stop offers it at once).
        val rolling = moving || now - lastMovingAt < CockpitTiming.PHASE2_SLOW_MS
        val primary = when {
            t.variant == TakeoverVariant.KICKLOCK && ready && !moving && parkedLong && s.lock == LockState.LOCKED -> TakeoverPrimary.UNLOCK
            s.link == Link.OFFLINE -> TakeoverPrimary.CONNECT
            !ready -> TakeoverPrimary.RETRY
            reading -> TakeoverPrimary.READING_LOG
            (!logAfter || logFailed) && rolling -> TakeoverPrimary.READ_AT_STOP
            !logAfter -> TakeoverPrimary.READ_LOG
            logFailed -> TakeoverPrimary.LOG_FAILED
            else -> TakeoverPrimary.LOG_OK
        }
        val markNo = if (marks > 0 && lastMarkAt >= t.at) lastMarkNo else 0
        val countdown = if (t.variant == TakeoverVariant.SHIPPING) {
            val left = t.at + CockpitTiming.SHIPPING_COUNTDOWN_MS - now
            ((left + 999) / 1000).toInt().coerceAtLeast(0)
        } else null
        return TakeoverView(
            variant = t.variant,
            phase = t.phase,
            snapshot = t.snapshot,
            also = t.also.sortedBy { it.priority },
            countdown = countdown,
            linkReady = ready,
            precursor = t.precursor,
            precursorCode = t.precursorCode,
            precursorAgoS = ((t.at - t.precursorAt) / 1000).toInt().coerceAtLeast(0),
            evidence = t.evidence,
            timeline = t.timeline,
            tags = t.tags.toSet(),
            ackEnabled = now - t.at >= CockpitTiming.ACK_LOCK_MS,
            primary = primary,
            logPercent = s.logProgress?.let { (it * 100).toInt().coerceIn(0, 100) },
            logAtMillis = if (logAfter) logAt else 0,
            logLines = if (logAfter) logLines else 0,
            markNo = markNo,
            nextMarkNo = nextMark?.invoke() ?: (lastMarkNo + 1),
        )
    }
}
