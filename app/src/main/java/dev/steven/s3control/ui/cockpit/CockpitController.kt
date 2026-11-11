package dev.steven.s3control.ui.cockpit

import android.content.Context
import androidx.annotation.StringRes
import dev.steven.s3control.AppGraph
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ui.FamilyGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/** Cockpit colours: like the app, or fixed to Daylight (sun) or NightRide. */
enum class CockpitTheme { APP, DAY, NIGHT }

data class CockpitPrefsState(val theme: CockpitTheme = CockpitTheme.APP, val startsLogger: Boolean = true)

/** The cockpit's own two settings, in their own preferences file. */
class CockpitPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("cockpit", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<CockpitPrefsState> = _state.asStateFlow()

    fun update(transform: (CockpitPrefsState) -> CockpitPrefsState) {
        val next = transform(_state.value)
        prefs.edit()
            .putString(KEY_THEME, next.theme.name)
            .putBoolean(KEY_LOGGER, next.startsLogger)
            .apply()
        _state.value = next
    }

    private fun read() = CockpitPrefsState(
        theme = runCatching { CockpitTheme.valueOf(prefs.getString(KEY_THEME, null) ?: "APP") }.getOrDefault(CockpitTheme.APP),
        startsLogger = prefs.getBoolean(KEY_LOGGER, true),
    )

    private companion object {
        const val KEY_THEME = "cockpitTheme"
        const val KEY_LOGGER = "cockpitStartsLogger"
    }
}

/**
 * What outlives a single cockpit (and a single ViewModel): mark numbering, the fast-poll claims,
 * the battery-jump watch, the logger start error and the riding suggestion's dismissal.
 */
object CockpitRuntime {
    val marks = CockpitMarks()

    @Volatile private var prefsInstance: CockpitPrefs? = null

    fun prefs(context: Context): CockpitPrefs =
        prefsInstance ?: synchronized(this) {
            prefsInstance ?: CockpitPrefs(context.applicationContext).also { prefsInstance = it }
        }

    /**
     * An open cockpit's claim on the 1.5 s poll, in the controller's registry ([BikeController.requestPoll]): the
     * ride logger and the S2 drive diagnosis hold their own claims there, and the fastest one wins.
     */
    fun requestFastPoll(bike: BikeController, owner: String, on: Boolean) {
        bike.requestPoll(owner, if (on) FAST_POLL_MS else null)
    }

    private val openOnLaunch = AtomicBoolean(false)

    /** Debug demo: the next ViewModel opens the cockpit right away. */
    fun requestOpenOnLaunch() = openOnLaunch.set(true)

    fun consumeOpenOnLaunch(): Boolean = openOnLaunch.getAndSet(false)

    /** Raw cause of the last failed logger start (null after a successful one). */
    val loggerError = MutableStateFlow<String?>(null)

    /** "Nicht jetzt" on the riding offer holds until the process ends; observable, so the offer leaves at once. */
    val suggestDismissedState = MutableStateFlow(false)

    var suggestDismissed: Boolean
        get() = suggestDismissedState.value
        set(value) { suggestDismissedState.value = value }

    private val watching = AtomicBoolean(false)

    /**
     * Logs a battery that jumps 15 points or more without a charger (the shipping-mode notes: the battery side
     * collapsing, then reporting nearly full after the reset). Runs once per process.
     */
    fun ensureBatteryWatch(graph: AppGraph) {
        if (!watching.compareAndSet(false, true)) return
        graph.scope.launch {
            var mac: String? = null
            var last: Int? = null
            var lastAt = 0L
            graph.bike.state.collect { st ->
                val bikeMac = st.bike?.normalizedMac
                if (bikeMac != mac) {
                    mac = bikeMac
                    last = null
                }
                if (st.link != Link.READY) return@collect
                val b = st.battery ?: return@collect
                if (b == last) {
                    lastAt = System.currentTimeMillis()
                    return@collect
                }
                val now = System.currentTimeMillis()
                val before = last
                if (BatteryJump.isJump(before, lastAt, b, now, st.chargerPlugged) && before != null) {
                    graph.log.add(
                        EventLog.Kind.ALERT,
                        I18n.get(R.string.cockpit_log_battery_jump, before, b),
                        cockpitSnapshotDetail(st),
                        EventLog.Code.BATTERY_JUMP,
                        value = b,
                    )
                }
                last = b
                lastAt = now
            }
        }
    }

    const val FAST_POLL_MS = 1_500L
    const val SLOW_POLL_MS = BikeController.SLOW_POLL_MS
}

/** The state as one log detail line, the same words the controller uses when the link drops. */
fun cockpitSnapshotDetail(st: BikeState): String = buildList {
    add(I18n.get(R.string.core_detail_status, st.status?.label ?: "?"))
    if (st.shippingImminent) add(I18n.get(R.string.core_detail_shipping))
    st.battery?.let { add(I18n.get(R.string.core_detail_battery, it)) }
    st.speedKmh?.let { add(I18n.get(R.string.core_detail_speed, it)) }
    st.supplyOk?.let { add(I18n.get(if (it) R.string.core_detail_supply_ok else R.string.core_detail_supply_low)) }
    st.lock?.let { add(I18n.get(R.string.core_detail_lock, BikeController.lockLabel(it))) }
    if (st.faults.isNotEmpty()) add(I18n.get(R.string.core_detail_faults, st.faults.joinToString()))
}.joinToString(", ")

@StringRes
fun TakeoverVariant.headlineRes(): Int = when (this) {
    TakeoverVariant.SHIPPING -> R.string.core_status_shipping
    TakeoverVariant.ASLEEP -> R.string.cockpit_alert_sleeping
    TakeoverVariant.OFF -> R.string.cockpit_to_off
    TakeoverVariant.KICKLOCK -> R.string.cockpit_to_kicklock
}

@StringRes
fun SituationTag.labelRes(): Int = when (this) {
    SituationTag.BUMPS -> R.string.cockpit_tag_bumps
    SituationTag.STEERING -> R.string.cockpit_tag_steering
    SituationTag.RAIN -> R.string.cockpit_tag_rain
    SituationTag.CLIMB -> R.string.cockpit_tag_climb
}

private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * One cockpit per ViewModel: owns the [CockpitSession] while the cockpit is open and feeds it
 * bike states, log events, the logger, the bike log and the clock. Everything runs on the
 * ViewModel's main-thread scope, so the session needs no locking.
 */
class CockpitController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val bikeLog: StateFlow<AppViewModel.BikeLog?>,
) {
    private val owner = "cockpit@" + Integer.toHexString(System.identityHashCode(this))
    private val clock: () -> Long = { System.currentTimeMillis() }

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _view = MutableStateFlow<CockpitView?>(null)
    val view: StateFlow<CockpitView?> = _view.asStateFlow()

    private var session: CockpitSession? = null
    private var carry: CockpitSession.Carry? = null
    private var job: Job? = null

    fun open() {
        if (_open.value) return
        var created: CockpitSession? = null
        val s = CockpitSession(
            clock, graph.log.lastSeq, carry,
            nextMark = { CockpitRuntime.marks.peek() },
            // The kick-lock takeover's hand-back while riding sets the capsule's mark itself.
            autoMark = { created?.let { logMark(it, viaKey = false) } },
        )
        created = s
        session = s
        s.onState(gated(graph.bike.state.value))
        val rec = graph.recorder.status.value
        s.onRecorder(rec.active, rec.startedAtMillis, rec.samples)
        bikeLog.value.let { b -> s.onBikeLog(b?.atMillis, b?.failed ?: false, lineCount(b)) }
        publish()
        _open.value = true
        CockpitRuntime.requestFastPoll(graph.bike, owner, true)
        job = scope.launch {
            launch { graph.bike.state.collect { s.onState(gated(it)); publish() } }
            launch {
                var seen = graph.log.lastSeq
                graph.log.events.collect { list ->
                    val fresh = list.asReversed().takeWhile { it.seq > seen }.asReversed()
                    if (fresh.isEmpty()) return@collect
                    for (e in fresh) s.onEvent(e)
                    seen = fresh.last().seq
                    publish()
                }
            }
            launch {
                graph.recorder.status.collect { r ->
                    s.onRecorder(r.active, r.startedAtMillis, r.samples)
                    publish()
                }
            }
            launch { bikeLog.collect { b -> s.onBikeLog(b?.atMillis, b?.failed ?: false, lineCount(b)); publish() } }
            launch {
                while (isActive) {
                    delay(CockpitTiming.TICK_MS - clock() % CockpitTiming.TICK_MS)
                    s.tick()
                    publish()
                }
            }
            launch {
                while (isActive) {
                    s.sample()
                    delay(CockpitTiming.SAMPLE_MS)
                }
            }
        }
    }

    /**
     * The state the cockpit reads its layout from: before the first connect the stored bike's family (the last bike,
     * else the only one; the rule of [AppViewModel.preferredBike]), so an S2 gets no S3 lamps. Values stay live.
     */
    private fun gated(st: BikeState): BikeState {
        if (st.bike != null) return st
        val c = graph.account.contents.value
        val preferred = c.bikes.firstOrNull { it.normalizedMac == c.lastBikeMac } ?: c.bikes.singleOrNull()
        return FamilyGate.beforeConnect(st, preferred, graph.bike.s2Prefs.testMode.value)
    }

    fun close() {
        if (!_open.value) return
        job?.cancel()
        job = null
        carry = session?.carry()
        session = null
        _open.value = false
        _view.value = null
        CockpitRuntime.requestFastPoll(graph.bike, owner, false)
    }

    /** Writes "Marke n" into the event log (and so into the running ride log). Null inside the 1.5 s gate. */
    fun mark(viaKey: Boolean): Int? {
        val s = session ?: return null
        val n = logMark(s, viaKey) ?: return null
        s.onMark(n)
        publish()
        return n
    }

    /** The log half of [mark]: the next number through the shared 1.5 s gate, with the bike's state as detail. */
    private fun logMark(s: CockpitSession, viaKey: Boolean): Int? {
        val now = clock()
        val n = CockpitRuntime.marks.next(now) ?: return null
        val st = graph.bike.state.value
        val age = (now - s.lastRxAtMillis).coerceAtLeast(0) / 1000.0
        val detail = buildList {
            add(cockpitSnapshotDetail(st))
            add(I18n.get(R.string.cockpit_detail_data_age, age))
            if (viaKey) add(I18n.get(R.string.cockpit_detail_via_key))
        }.joinToString(", ")
        graph.log.add(EventLog.Kind.MARK, I18n.get(R.string.cockpit_mark_event, n), detail, EventLog.Code.MARK, value = n)
        return n
    }

    fun tag(tag: SituationTag) {
        val s = session ?: return
        val (variant, at) = s.tag(tag) ?: return
        publish()
        val name = I18n.get(tag.labelRes())
        val headline = I18n.get(variant.headlineRes())
        graph.log.add(
            EventLog.Kind.MARK,
            I18n.get(R.string.cockpit_tag_event, name),
            I18n.get(R.string.cockpit_tag_detail, headline, LOG_TIME.format(Instant.ofEpochMilli(at))),
            EventLog.Code.MARK,
        )
    }

    fun acknowledge() = act { it.acknowledge() }

    /**
     * The big capsule in takeover phase 1 ("Verstanden"): it sets the moment's mark once (even in
     * the first 1.5 s, when it does nothing else), then acknowledges.
     */
    fun takeoverTap() {
        val s = session ?: return
        if (s.view.takeover?.phase != 1) return
        if (!s.markedSinceTrigger()) mark(viaKey = false)
        s.acknowledge()
        publish()
    }

    fun dismissTakeover() = act { it.dismissTakeover() }

    fun reopenTakeover() = act { it.reopenTakeover() }

    fun back(): BackResult {
        val s = session ?: return BackResult.CLOSE
        val r = s.back()
        publish()
        return r
    }

    private inline fun act(block: (CockpitSession) -> Unit) {
        val s = session ?: return
        block(s)
        publish()
    }

    private fun publish() {
        val next = session?.view
        val before = _view.value?.takeover
        val after = next?.takeover
        if (before?.variant != after?.variant || before?.phase != after?.phase) {
            android.util.Log.i(
                "Cockpit",
                "takeover ${before?.variant}/${before?.phase} -> ${after?.variant}/${after?.phase} " +
                    "speed=${graph.bike.state.value.speedKmh} link=${graph.bike.state.value.link}",
            )
        }
        _view.value = next
    }

    private fun lineCount(b: AppViewModel.BikeLog?): Int = b?.text?.lineSequence()?.count { it.isNotBlank() } ?: 0
}
