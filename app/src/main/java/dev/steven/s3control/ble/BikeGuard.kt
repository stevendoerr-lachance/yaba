package dev.steven.s3control.ble

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ride.GuardJournal
import dev.steven.s3control.ride.GuardNotifications
import dev.steven.s3control.ride.GuardService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Wache (the guard design): armed by the owner's tap, it locks the bike, has the bike watch for movement itself
 * (5523 = 2), keeps the link, sounds the bike's siren (5523 = 3) on movement and notifies this phone.
 *
 * The runner around the pure [GuardLogic]: one actor coroutine reads [GuardInput]s (snapshots of [BikeState], the
 * 1 s clock, 5522 requests, the owner's taps, the sequences' results) and executes the returned [GuardAction]s.
 * Every write goes through [BikeController.guardCommand] (command lock, [Op.GUARD]) and touches only 5521, 5523 and
 * 5524 ([GuardSequences]). It also records every value change the bike reports while armed (log, journal, the
 * "Zuletzt" list), so the owner's first real test shows what the firmware really does.
 */
class BikeGuard(
    context: Context,
    private val bike: BikeController,
    private val log: EventLog,
    private val scope: CoroutineScope,
    /** The saved bikes; null while the vault cannot be read (an armed Wache is then kept in the store, not ended). */
    private val bikes: () -> List<BikeCredentials>?,
) {
    private val app = context.applicationContext
    private val store = GuardStore(app)
    private val journal = GuardJournal(app)

    /** Everything the actor does arrives here: machine inputs and runner chores, in order. */
    private sealed interface Msg
    private class In(val input: GuardInput) : Msg
    private class Do(val block: () -> Unit) : Msg

    private val inbox = Channel<Msg>(Channel.UNLIMITED)

    // --- Actor-owned ------------------------------------------------------------------------------
    private var machine = GuardMachine(sirenImmediate = store.sirenImmediate)
    private var events: List<GuardEvent> = emptyList()
    private var shownAlarm: GuardAlarm? = null
    private var seqJob: Job? = null
    /** The running sequence's gate: 0 waiting (gap, command lock), 1 writing, 2 dropped by [GuardAction.CancelAuto]. */
    private var seqGate: AtomicInteger? = null
    private var lastReconnectAt = 0L
    private val lastLogged = HashMap<String, ByteArray>()
    private var hourKey = -1L
    private var hourCount = 0

    // --- Shared with the callers' threads --------------------------------------------------------
    private val activeFlow = MutableStateFlow(false)
    /** The Wache owns the bike (set by [arm]/[restore], cleared by Stop); gates the snapshot feed and the clock. */
    private var active: Boolean
        get() = activeFlow.value
        set(v) { activeFlow.value = v }
    @Volatile private var armedBike: BikeCredentials? = null
    /** The arm sequence switched ALARM_MODE on: a disarm that cancelled it switches it back off. */
    @Volatile private var armModeWritten = false
    @Volatile private var lastWriteEnd = 0L
    @Volatile private var demoState: GuardState? = null
    @Volatile private var demoNotified = false

    /** [GuardService] keeps running while true: from the arm tap or restore until the Wache is OFF. */
    @Volatile internal var serviceWanted = false
        private set

    private val _state = MutableStateFlow(GuardState(sirenImmediate = store.sirenImmediate))
    private var live: GuardState = _state.value
    val state: StateFlow<GuardState> = _state.asStateFlow()

    init {
        scope.launch {
            for (msg in inbox) {
                try {
                    when (msg) {
                        is In -> handle(msg.input)
                        is Do -> msg.block()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The Wache must keep running whatever one step did; the journal keeps the reason.
                    record(
                        EventLog.Kind.ERROR,
                        I18n.get(R.string.guard_core_log_sequence_failed, I18n.get(R.string.guard_core_step_internal), e.message ?: e.javaClass.simpleName),
                        null, EventLog.Code.GUARD,
                    )
                }
            }
        }
        scope.launch {
            bike.state.collect { s -> if (active) inbox.trySend(In(GuardInput.Snapshot(snapshot(s, now())))) }
        }
        scope.launch {
            bike.valueTap.collect { t -> inbox.trySend(Do { onTap(t) }) }
        }
        // The 1 s clock runs only while the Wache is active (no wake-ups while it is off).
        scope.launch {
            activeFlow.collectLatest { on ->
                while (on && isActive) {
                    delay(1_000)
                    val at = now()
                    inbox.trySend(In(GuardInput.Snapshot(snapshot(bike.state.value, at))))
                    inbox.trySend(In(GuardInput.Tick(at)))
                    inbox.trySend(Do { keepLink(at) })
                }
            }
        }
    }

    // --- Public surface (the guard design 9.3) ------------------------------------------------------

    /** "Wache scharf schalten": checks [GuardRules.armBlocker], starts the service, arms (1.2). */
    fun arm() {
        if (demoState != null || active) return
        val s = bike.state.value
        val blocker = GuardRules.armBlocker(s)
        val b = s.bike
        if (blocker != null || b == null) {
            setIssue(blocker ?: GuardIssue.NOT_CONNECTED)
            return
        }
        // A connectedDevice foreground service needs BLUETOOTH_CONNECT granted, or startForeground throws.
        val btOk = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        serviceWanted = true
        val started = btOk && runCatching {
            ContextCompat.startForegroundService(app, Intent(app, GuardService::class.java).setAction(GuardNotifications.ACTION_ARM))
        }.isSuccess
        if (!started) {
            serviceWanted = false
            setIssue(GuardIssue.SERVICE)
            return
        }
        armedBike = b
        armModeWritten = false
        active = true
        bike.guardArmed = true
        bike.requestPoll(POLL_OWNER, GuardRules.POLL_MS)
        send(GuardInput.Arm(now()))
    }

    /** "Wache beenden": 5523 = 0, unlock, alarm mode back; never blocked by the parked guard (5.1). */
    fun disarm() {
        if (demoState != null) return
        send(GuardInput.Disarm(now()))
    }

    /** "Sirene aus": stop the siren, re-lock, watch again; no automatic siren for 2 minutes (5.2). */
    fun silence() {
        if (demoState != null) return
        send(GuardInput.Silence(now()))
    }

    /** WATCHING without bikeWatch: run the re-arm sequence once more (owner action). */
    fun retryWatch() {
        if (demoState != null) return
        send(GuardInput.RetryWatch(now()))
    }

    fun setSirenImmediate(on: Boolean) {
        store.setSirenImmediate(on)
        send(GuardInput.SetSirenImmediate(now(), on))
    }

    fun clearIssue() {
        inbox.trySend(Do {
            machine = machine.copy(issue = null, issueValue = null, issueDetail = null)
            publish()
        })
    }

    /** ACTION_SEND for the newest guard-yyyyMMdd.txt, or null when there is none. */
    fun journalShareIntent(): Intent? = journal.shareIntent(I18n.get(R.string.guard_core_cmd))

    /** Process start (AppGraph): resume an armed Wache from the store; does not start the service. */
    fun restore() {
        if (active) return
        val stored = store.load()
        if (!stored.armed) return
        // The vault cannot be read right now: keep the armed Wache in the store for the next start, end nothing.
        val saved = bikes() ?: return
        val b = saved.firstOrNull { it.normalizedMac == stored.mac }
        if (b == null) {
            store.clear()
            inbox.trySend(Do {
                machine = machine.copy(issue = GuardIssue.BIKE_GONE, issueValue = null, issueDetail = null)
                record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_bike_gone), null, EventLog.Code.GUARD)
                publish()
            })
            return
        }
        armedBike = b
        active = true
        serviceWanted = true
        bike.guardArmed = true
        bike.requestPoll(POLL_OWNER, GuardRules.POLL_MS)
        val cur = bike.state.value
        if (cur.bike?.normalizedMac != b.normalizedMac || cur.link == Link.OFFLINE) bike.connect(b)
        // The "Zuletzt" list comes back from the journal (before the restore's own line is written).
        val since = stored.armedAt ?: 0L
        inbox.trySend(Do {
            val old = journal.recent(since, MAX_EVENTS).map { GuardEvent(it.atMillis, it.text, it.alarm) }
            events = (events + old).sortedByDescending { it.atMillis }.take(MAX_EVENTS)
            publish()
        })
        send(GuardInput.Restore(now(), stored))
    }

    /** Starts GuardService while active (no-op otherwise); posts the restart notification if Android refuses. */
    fun ensureService() {
        if (!active || !serviceWanted) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, GuardService::class.java))
        } catch (e: Exception) {
            GuardNotifications.postRestart(app)
        }
    }

    /** Debug builds (DemoActivity): show [state] without BLE; null = back to the live state. */
    internal fun demo(state: GuardState?, notify: Boolean = false) {
        demoState = state
        _state.value = state ?: live
        if (state != null && notify) {
            GuardNotifications.ensureChannels(app)
            GuardNotifications.postOngoingDemo(app, state)
            state.lastAlarm?.takeIf { state.phase == GuardPhase.ALARM }?.let { GuardNotifications.postAlarm(app, it, alert = true) }
            if (state.phase == GuardPhase.LOST) GuardNotifications.postLost(app, state.bikeWatch)
            demoNotified = true
        } else if (demoNotified) {
            demoNotified = false
            if (!serviceWanted) GuardNotifications.cancelAll(app)
        }
    }

    // --- The actor --------------------------------------------------------------------------------

    private fun send(input: GuardInput) {
        inbox.trySend(In(input))
    }

    private fun handle(input: GuardInput) {
        val (next, actions) = GuardLogic.step(machine, input)
        machine = next
        for (a in actions) execute(a)
        publish()
    }

    private fun execute(a: GuardAction) {
        when (a) {
            GuardAction.RunArm -> runArm()
            GuardAction.RunVerify -> runSequence(SequenceKind.VERIFY, gap = false) { GuardSequences.verify(it) }
            GuardAction.RunSiren -> runSequence(SequenceKind.SIREN, gap = true) { GuardSequences.siren(it) }
            GuardAction.RunRelock -> runSequence(SequenceKind.RELOCK, gap = true) { GuardSequences.relock(it) }
            is GuardAction.RunRearm -> {
                val path = machine.armPath
                runSequence(SequenceKind.REARM, gap = !a.owner) { GuardSequences.rearm(it, path) }
            }
            GuardAction.RunDisarm -> {
                val restoreMode = machine.restoreAlarmMode || armModeWritten
                runSequence(SequenceKind.DISARM, gap = false) { GuardSequences.disarm(it, restoreMode) }
            }
            GuardAction.CancelSequence -> {
                seqJob?.cancel()
                seqJob = null
            }
            GuardAction.CancelAuto -> {
                // Only a sequence that has not started writing; it then reports itself as cancelled.
                val gate = seqGate
                if (gate != null && gate.compareAndSet(0, 2)) seqJob?.cancel()
            }
            is GuardAction.NotifyAlarm -> GuardNotifications.postAlarm(app, alarmOf(a.alarm), a.alert)
            is GuardAction.NotifyLost -> GuardNotifications.postLost(app, a.bikeWatch)
            GuardAction.ClearLost -> GuardNotifications.cancel(app, GuardNotifications.ID_LOST)
            GuardAction.ClearAlarm -> GuardNotifications.cancel(app, GuardNotifications.ID_ALARM)
            is GuardAction.NotifyNotLocked -> GuardNotifications.postNotLocked(app, a.lock)
            GuardAction.ClearNotLocked -> GuardNotifications.cancel(app, GuardNotifications.ID_NOT_LOCKED)
            is GuardAction.Log -> logLine(a.line)
            GuardAction.Persist -> if (machine.active) store.save(machine.stored(armedBike?.normalizedMac)) else store.setSirenImmediate(machine.sirenImmediate)
            GuardAction.Stop -> stop()
        }
    }

    private fun runArm() {
        armModeWritten = false
        seqGate = null
        seqJob = scope.launch {
            var watch: ArmWatch? = null
            val result = try {
                bike.guardCommand(I18n.get(R.string.guard_core_cmd)) { ops -> GuardSequences.arm(ArmWatch(ops).also { watch = it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArmResult(ArmOutcome.FAILED, error = e.message ?: e.javaClass.simpleName, writes = watch?.writes ?: 0)
            } finally {
                lastWriteEnd = now()
            }
            send(GuardInput.ArmDone(now(), result))
        }
    }

    /**
     * Counts the arm sequence's writes (a failure after the first one is [GuardIssue.ARM_FAILED_PARTLY]) and notes
     * whether it switched ALARM_MODE on, so a cancelled arm still restores it on disarm. Noted before the write: a
     * write cancelled in flight may still have reached the bike.
     */
    private inner class ArmWatch(private val ops: GuardOps) : GuardOps by ops {
        @Volatile var writes = 0
        override suspend fun write(ch: GuardChar, value: Int) {
            writes++
            if (ch == GuardChar.ALARM_MODE && value == 1) armModeWritten = true
            ops.write(ch, value)
            if (ch == GuardChar.ALARM_MODE && value != 1) armModeWritten = false
        }
    }

    /** Counts a sequence's writes, including one that throws (the bike may have received it). */
    private class Counted(private val ops: GuardOps) : GuardOps by ops {
        @Volatile var writes = 0
        override suspend fun write(ch: GuardChar, value: Int) {
            writes++
            ops.write(ch, value)
        }
    }

    /**
     * One sequence on the command lock; automatic ones keep [GuardRules.MIN_WRITE_GAP_MS] after the last Wache write.
     * Until it starts writing, [GuardAction.CancelAuto] may drop it; it then reports a cancelled [GuardInput.SequenceDone].
     */
    private fun runSequence(kind: SequenceKind, gap: Boolean, block: suspend (GuardOps) -> SequenceReadback) {
        val gate = AtomicInteger(0)
        seqGate = gate
        seqJob = scope.launch {
            val counted = arrayOfNulls<Counted>(1)
            val done = try {
                if (gap) {
                    val wait = lastWriteEnd + GuardRules.MIN_WRITE_GAP_MS - now()
                    if (wait > 0) delay(wait)
                }
                val rb = bike.guardCommand(I18n.get(R.string.guard_core_cmd)) { ops ->
                    if (!gate.compareAndSet(0, 1)) throw CancellationException("dropped before writing")
                    block(Counted(ops).also { counted[0] = it })
                }
                GuardInput.SequenceDone(now(), kind, ok = true, readback = rb, error = null)
            } catch (e: CancellationException) {
                if (gate.get() == 2) {
                    send(GuardInput.SequenceDone(now(), kind, ok = false, readback = null, error = null, cancelled = true))
                    return@launch
                }
                throw e
            } catch (e: Exception) {
                GuardInput.SequenceDone(
                    now(), kind, ok = false, readback = null, error = e.message ?: e.javaClass.simpleName,
                    attempted = counted[0]?.writes ?: 0,
                )
            } finally {
                if (kind != SequenceKind.VERIFY && gate.get() == 1) lastWriteEnd = now()
            }
            send(done)
        }
    }

    /**
     * OFF: the controller lets go, the store forgets everything but "Sirene sofort". The service is not stopped from
     * here: one that has not reached startForeground yet would crash the app; it stops itself once [serviceWanted] is
     * false (in onStartCommand or on the next state).
     */
    private fun stop() {
        active = false
        serviceWanted = false
        bike.guardArmed = false
        bike.requestPoll(POLL_OWNER, null)
        store.clear()
        armedBike = null
        armModeWritten = false
        lastLogged.clear()
        seqJob = null
        seqGate = null
        GuardNotifications.cancel(app, GuardNotifications.ID_RESTART)
        GuardNotifications.cancel(app, GuardNotifications.ID_NOT_LOCKED)
    }

    private fun setIssue(issue: GuardIssue) {
        inbox.trySend(Do {
            machine = machine.copy(issue = issue, issueValue = null, issueDetail = null)
            publish()
        })
    }

    /**
     * While active (not ending), a link the controller dropped completely (a manual disconnect) is asked for again;
     * the controller's own loop handles every other loss. Another bike in the controller is not fought over.
     */
    private fun keepLink(at: Long) {
        val m = machine
        if (!m.active || m.phase == GuardPhase.DISARMING) return
        val b = armedBike ?: return
        val s = bike.state.value
        if (s.link != Link.OFFLINE) return
        if (s.bike != null && s.bike.normalizedMac != b.normalizedMac) return
        if (at - lastReconnectAt < RECONNECT_GAP_MS) return
        lastReconnectAt = at
        bike.connect(b)
    }

    private fun publish() {
        val m = machine
        live = GuardState(
            phase = m.phase,
            bikeWatch = m.bikeWatch,
            defence = m.defence,
            sirenImmediate = m.sirenImmediate,
            armedMac = if (m.active) armedBike?.normalizedMac else null,
            armedAtMillis = if (m.active) m.armedAt else null,
            lastAlarm = if (m.active) m.lastAlarm?.let(::alarmOf) else null,
            sirenCycles = m.sirenCycles,
            issue = m.issue,
            issueValue = m.issueValue,
            issueDetail = m.issueDetail,
            events = events,
            lostNotified = m.lostNotified,
        )
        _state.value = demoState ?: live
    }

    /** [r] with its text, made once per record in the app language of that moment. */
    private fun alarmOf(r: AlarmRecord): GuardAlarm {
        val shown = shownAlarm
        if (shown != null && shown.atMillis == r.atMillis && shown.trigger == r.trigger && shown.siren == r.siren && shown.cycle == r.cycle) return shown
        return GuardAlarm(r.atMillis, r.trigger, r.siren, r.cycle, GuardTexts.alarm(r.trigger, r.siren, r.cycle)).also { shownAlarm = it }
    }

    private fun snapshot(s: BikeState, at: Long): GuardSnapshot = GuardSnapshot(
        atMillis = at,
        ready = s.isReady,
        sameBike = armedBike?.let { s.bike?.normalizedMac == it.normalizedMac } ?: false,
        lock = s.lock,
        defence = s.raw[Gatt.ALARM_STATE.short]?.u8() ?: s.defence?.code,
        status = s.raw[Gatt.MODULE_MODE.short]?.u8() ?: s.status?.code,
        speedRaw = s.speedRawKmh,
        wheelMoving = s.wheelMoving,
        buttonDown = s.buttons?.let { it.horn || it.boost || it.powerButton } ?: false,
        batteryDetected = s.batteryDetected,
    )

    // --- Recording (the guard design 2.3) -----------------------------------------------------------

    /** One decoded value from the bike while armed: a line when its significant bytes change (5522/5562: every push). */
    private fun onTap(t: ValueTap) {
        if (!machine.active) return
        val short = t.ch.short
        val width = TAPPED[short] ?: return
        val sig = t.value.copyOf(minOf(width, t.value.size))
        val prev = lastLogged[short]
        val event = t.pushed && (short == Gatt.UNLOCK_REQUEST.short || short == Gatt.MODULE_STATE.short)
        if (event || prev == null || !prev.contentEquals(sig)) {
            lastLogged[short] = sig
            if (underCap(t.atMillis)) {
                val text = I18n.get(
                    R.string.guard_core_log_value,
                    t.ch.label, short, hex(sig), prev?.let(::hex) ?: "–",
                    I18n.get(if (t.pushed) R.string.guard_core_log_pushed else R.string.guard_core_log_read),
                )
                record(EventLog.Kind.STATE, text, null, EventLog.Code.GUARD, at = t.atMillis)
            }
        }
        // 5522 pushed 1: the bike asks to be unlocked; for the Wache a motion report (never an unlock, BikeController).
        if (short == Gatt.UNLOCK_REQUEST.short && t.pushed && t.value.u8() == 1) handle(GuardInput.UnlockRequest(t.atMillis))
    }

    /** At most [VALUE_LINES_PER_HOUR] value lines per clock hour, then one line saying so. */
    private fun underCap(at: Long): Boolean {
        val hour = at / 3_600_000L
        if (hour != hourKey) {
            hourKey = hour
            hourCount = 0
        }
        hourCount++
        if (hourCount <= VALUE_LINES_PER_HOUR) return true
        if (hourCount == VALUE_LINES_PER_HOUR + 1) record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_value_cap), null, EventLog.Code.GUARD, at = at)
        return false
    }

    private fun logLine(line: GuardLogLine) {
        val g = EventLog.Code.GUARD
        when (line) {
            is GuardLogLine.ArmFinished -> {
                val r = line.result
                record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_arming, v(r.mode0), v(r.lock0), v(r.defence0)), null, g)
                when (r.outcome) {
                    ArmOutcome.WATCHING -> if (r.path == ArmPath.DIRECT) {
                        record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_armed_direct), null, g)
                    } else {
                        val seconds = String.format(Locale.getDefault(), "%.1f", r.waitedMs / 1000.0)
                        record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_armed_native, seconds), null, g)
                    }
                    ArmOutcome.UNCONFIRMED -> record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_armed_unconfirmed, v(r.defence)), null, g)
                    else -> record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_arm_failed, armReason(r)), null, g)
                }
            }
            is GuardLogLine.Ignored -> record(EventLog.Kind.STATE, I18n.get(R.string.guard_core_log_ignored, GuardTexts.trigger(line.trigger)), null, g)
            is GuardLogLine.Trigger -> {
                val a = alarmOf(line.alarm)
                val siren = GuardTexts.siren(a.siren, a.cycle)
                val detail = (listOf(siren) + line.others.map(GuardTexts::trigger)).filter { it.isNotEmpty() }.joinToString(" ")
                record(EventLog.Kind.ALERT, GuardTexts.trigger(a.trigger), detail.ifEmpty { null }, EventLog.Code.GUARD_ALARM, alert = true, at = a.atMillis)
            }
            is GuardLogLine.Siren -> record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_siren, v(line.defence)), null, g)
            is GuardLogLine.Rearmed -> record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_rearmed, v(line.defence), v(line.lock)), null, g)
            is GuardLogLine.Relocked -> record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_relocked, v(line.lock)), null, g)
            is GuardLogLine.NotLocked -> record(EventLog.Kind.ALERT, I18n.get(R.string.guard_core_log_not_locked, v(line.lock)), null, g)
            GuardLogLine.Held -> record(EventLog.Kind.ALERT, I18n.get(R.string.guard_core_log_held), null, g)
            is GuardLogLine.WatchSeen -> record(EventLog.Kind.STATE, I18n.get(R.string.guard_core_log_watch_seen), null, g)
            is GuardLogLine.WatchLeft -> record(EventLog.Kind.STATE, I18n.get(R.string.guard_core_log_watch_left, v(line.defence)), null, g)
            is GuardLogLine.Cancelled -> record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_cancelled, stepName(line.kind)), null, g)
            GuardLogLine.DisarmedUnconfirmed -> record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_disarmed_unconfirmed), null, g)
            GuardLogLine.Silence -> record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_silence), null, g)
            is GuardLogLine.Disarmed -> record(EventLog.Kind.COMMAND, I18n.get(R.string.guard_core_log_disarmed, v(line.defence), v(line.lock)), null, g)
            GuardLogLine.DisarmedOffline -> record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_disarmed_offline), null, g)
            GuardLogLine.Lost -> record(EventLog.Kind.ALERT, I18n.get(R.string.guard_core_log_lost), null, g)
            is GuardLogLine.Back -> record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_back, v(line.defence), v(line.lock)), null, g)
            GuardLogLine.Budget -> record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_budget), null, g)
            GuardLogLine.Restored -> record(EventLog.Kind.INFO, I18n.get(R.string.guard_core_log_restored), null, g)
            is GuardLogLine.SequenceFailed -> record(
                EventLog.Kind.ERROR,
                I18n.get(R.string.guard_core_log_sequence_failed, stepName(line.kind), line.error ?: "?"),
                null, g,
            )
        }
    }

    /** Why arming stopped, with the characteristic and the value the bike gave. */
    private fun armReason(r: ArmResult): String = when (r.outcome) {
        ArmOutcome.ALARM_MODE_REFUSED -> "${Gatt.ALARM_MODE.label} (5524) = ${v(r.value)}"
        ArmOutcome.LOCK_REFUSED -> "${Gatt.LOCK_STATE.label} (5521) = ${v(r.value)}"
        ArmOutcome.ALARM_DURING -> "${Gatt.ALARM_STATE.label} (5523) = ${v(r.value)}"
        ArmOutcome.BIKE_STATE -> "${Gatt.MODULE_MODE.label} (5561) = ${v(r.value)}"
        else -> r.error ?: "?"
    }

    /** One Wache line: event log, journal file and the "Zuletzt" list. */
    private fun record(kind: EventLog.Kind, message: String, detail: String?, code: EventLog.Code, alert: Boolean = false, at: Long = now()) {
        log.add(kind, message, detail, code)
        journal.append(at, if (alert) GuardJournal.KIND_ALARM else kind.name, message, detail)
        events = (listOf(GuardEvent(at, message, alert)) + events).take(MAX_EVENTS)
        publish()
    }

    private fun v(x: Int?): String = x?.toString() ?: "?"

    /** A sequence's name in the log, in the app language ("Wache: Wiederherstellen fehlgeschlagen: …"). */
    private fun stepName(kind: SequenceKind): String = I18n.get(
        when (kind) {
            SequenceKind.ARM -> R.string.guard_core_step_arm
            SequenceKind.VERIFY -> R.string.guard_core_step_verify
            SequenceKind.SIREN -> R.string.guard_core_step_siren
            SequenceKind.REARM -> R.string.guard_core_step_rearm
            SequenceKind.RELOCK -> R.string.guard_core_step_relock
            SequenceKind.DISARM -> R.string.guard_core_step_disarm
        },
    )

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val POLL_OWNER = "guard"
        const val MAX_EVENTS = 40
        const val VALUE_LINES_PER_HOUR = 400
        const val RECONNECT_GAP_MS = 30_000L

        /** The characteristics recorded while armed, with how many leading bytes count as their value. */
        val TAPPED: Map<String, Int> = mapOf(
            "5521" to 1, "5522" to 1, "5523" to 1, "5524" to 1, "5532" to 2, "5542" to 1,
            "5561" to 1, "5562" to 1, "5563" to 8, "5568" to 3, "5569" to 1,
        )
    }
}
