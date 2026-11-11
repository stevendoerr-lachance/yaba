package dev.steven.s3control.ui.s2

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.steven.s3control.AppGraph
import dev.steven.s3control.S3App
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.S2Block
import dev.steven.s3control.ble.S2Frame
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ride.RideRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The S2 drive diagnosis behind the screen, for the whole app process: it reads every status block the S2 driver
 * decodes ([BikeController.s2Blocks]), feeds the [S2ErrorCatcher], counts how often each byte of the block changes,
 * and keeps the wiggle test's marks. Attached once (from AppGraph, and again harmlessly from the screen), so codes
 * are caught while nobody looks. The caught codes survive a restart in `filesDir/s2/errors.csv`, each with its bike.
 *
 * It only sees the blocks YABA receives: read every few seconds, or pushed by the bike. So it counts both kinds, and
 * while a connected S2 rolls (and for two minutes after, a dropout reads as standing) or the ride logger runs, it
 * claims a 1 s read ([RIDE_POLL_MS]), so a short code has fewer chances to slip between two reads.
 */
object S2DriveWatch {

    /** What the screen shows; one value per status block. */
    @Immutable
    data class State(
        val hits: List<S2ErrorCatcher.Hit> = emptyList(),
        val blocks: Long = 0,
        val implausible: Int = 0,
        val sinceMillis: Long? = null,
        /** The latest block, plausible or not. */
        val last: S2Block? = null,
        /** Bytes that differ from the block before [last]. */
        val changed: Set<Int> = emptySet(),
        /** How often each byte changed from one block to the next since the process started. */
        val changes: List<Int> = List(S2Frame.SIZE) { 0 },
        /** Blocks the bike sent on its own (notifications) since the process started. */
        val pushed: Long = 0,
        /** Blocks YABA read itself (after connecting, polling, after a command). */
        val polled: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _marks = MutableStateFlow<List<WiggleMark>>(emptyList())
    val marks: StateFlow<List<WiggleMark>> = _marks.asStateFlow()

    private val lock = Any()
    private val catcher = S2ErrorCatcher()
    private val changes = IntArray(S2Frame.SIZE)
    private var previous: S2Frame? = null
    private var pushed = 0L
    private var polled = 0L
    @Volatile private var attached = false
    @Volatile private var log: EventLog? = null
    private var store: File? = null
    /** False until the stored list is loaded; a removal before that is applied to the stored list too. */
    private var loaded = false
    private val pendingRemovals = ArrayList<(S2ErrorCatcher.Hit) -> Boolean>()
    private val toSave = MutableStateFlow<List<S2ErrorCatcher.Hit>?>(null)

    /** Starts watching [graph]'s bike. Idempotent; the first call wins. */
    fun attach(context: Context, graph: AppGraph) = attach(context, graph.bike, graph.log, graph.scope, graph.recorder)

    /** Starts watching from a screen: the app's graph, if this process has one. */
    fun attach(context: Context) {
        val graph = (context.applicationContext as? S3App)?.graph ?: return
        attach(context, graph)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun attach(context: Context, bike: BikeController, log: EventLog, scope: CoroutineScope, recorder: RideRecorder) {
        synchronized(lock) {
            if (attached) return
            attached = true
        }
        // The 1 s read while a connected S2 rolls or the logger runs; the claim outlives a stop by RIDE_HOLD_MS,
        // because a speed signal that drops out reads as a standing bike. Nothing changes for an S3.
        scope.launch {
            combine(
                bike.state.map { it.family == BikeFamily.S2 && it.isReady }.distinctUntilChanged(),
                bike.state.map { it.moving }.distinctUntilChanged(),
                recorder.status.map { it.active }.distinctUntilChanged(),
            ) { s2, moving, logging -> Triple(s2, moving, logging) }
                .collectLatest { (s2, moving, logging) ->
                    when {
                        !s2 -> bike.requestPoll(RIDE_POLL_OWNER, null)
                        moving || logging -> bike.requestPoll(RIDE_POLL_OWNER, RIDE_POLL_MS)
                        else -> {
                            delay(RIDE_HOLD_MS)
                            bike.requestPoll(RIDE_POLL_OWNER, null)
                        }
                    }
                }
        }
        this.log = log
        val file = File(context.applicationContext.filesDir, "s2/errors.csv")
        store = file
        // Collect first, so no block is missed while the stored list loads; the stored episodes go in front.
        scope.launch { bike.s2Blocks.collect { offer(it) } }
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { load(file) }.getOrDefault(emptyList())
            val changed = synchronized(lock) {
                loaded = true
                val keep = saved.filterNot { h -> pendingRemovals.any { it(h) } }
                pendingRemovals.clear()
                if (keep.isNotEmpty()) catcher.restore(keep + catcher.hits)
                keep.size != saved.size || keep.isNotEmpty()
            }
            if (changed) {
                publish()
                toSave.value = synchronized(lock) { catcher.hits }
            }
            toSave.collect { hits ->
                if (hits != null) runCatching { save(file, hits) }
                delay(SAVE_EVERY_MS)
            }
        }
    }

    /** One status block (also the debug demo's). */
    internal fun offer(block: S2Block) {
        val caught: Boolean
        synchronized(lock) {
            caught = catcher.offer(block.atMillis, block.frame, block.mac)
            if (block.pushed) pushed++ else polled++
            val prev = previous
            val changed = if (prev == null) emptySet() else block.frame.changedBytes(prev).toSet()
            changed.forEach { changes[it]++ }
            previous = block.frame
            _state.value = snapshot(block, changed)
        }
        if (caught) toSave.value = catcher.hits
    }

    private fun snapshot(last: S2Block?, changed: Set<Int>): State = State(
        hits = catcher.hits,
        blocks = catcher.blocks,
        implausible = catcher.implausible,
        sinceMillis = catcher.sinceMillis,
        last = last,
        changed = changed,
        changes = changes.toList(),
        pushed = pushed,
        polled = polled,
    )

    private fun publish() {
        synchronized(lock) {
            val cur = _state.value
            _state.value = snapshot(cur.last, cur.changed)
        }
    }

    /** Empties the caught list (and the file); the live block stays. */
    fun clearHits() {
        synchronized(lock) {
            if (!loaded) pendingRemovals += { true }
            catcher.clear()
        }
        publish()
        toSave.value = emptyList()
    }

    /** Drops the episodes [which] matches (one bike's, or the debug demo's) and keeps the rest and the counters. */
    fun clearHits(which: (S2ErrorCatcher.Hit) -> Boolean) {
        val left = synchronized(lock) {
            if (!loaded) pendingRemovals += which
            catcher.remove(which)
            catcher.hits
        }
        publish()
        toSave.value = left
    }

    /** The hits of the bike [mac] (with those stored before hits carried a bike); all of them without a bike. */
    fun hitsFor(hits: List<S2ErrorCatcher.Hit>, mac: String?): List<S2ErrorCatcher.Hit> =
        if (mac == null) hits else hits.filter { it.mac == null || it.mac == mac }

    /** Adds a wiggle-test mark and writes it into the event log, so a running ride log has it too. */
    fun addMark(mark: WiggleMark, message: String, detail: String?) {
        _marks.update { it + mark }
        log?.add(EventLog.Kind.MARK, message, detail, EventLog.Code.MARK)
    }

    fun clearMarks() {
        _marks.value = emptyList()
    }

    /**
     * The caught list as text to paste into a message: CSV with ISO times, under one comment line that says how the
     * blocks came in (pushed by the bike or read by YABA, and the read interval now), so whoever reads it knows
     * whether codes shorter than that interval could have slipped through.
     */
    fun copyText(hits: List<S2ErrorCatcher.Hit>, state: State, pollMs: Long): String = buildString {
        append("# blocks pushed by the bike: ").append(state.pushed)
        append(", read by YABA: ").append(state.polled)
        append(", read interval now: ").append(String.format(Locale.ROOT, "%.1f", pollMs / 1000.0)).appendLine(" s")
        appendLine("time,code,blocks,seconds,kmh,top_kmh,battery,module_battery,frame")
        hits.forEach { h ->
            append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(h.firstAtMillis), ZoneId.systemDefault())).append(',')
            append(h.code).append(',').append(h.blocks).append(',').append(h.durationMillis / 1000).append(',')
            append(h.kmh).append(',').append(h.topKmh).append(',').append(h.motorBattery).append(',')
            append(h.moduleBattery).append(',').appendLine(h.frameHex)
        }
    }

    private fun load(file: File): List<S2ErrorCatcher.Hit> {
        if (!file.isFile) return emptyList()
        return file.readLines().drop(1).mapNotNull(S2ErrorCatcher::fromCsv)
    }

    private fun save(file: File, hits: List<S2ErrorCatcher.Hit>) {
        if (hits.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(buildString {
            appendLine(S2ErrorCatcher.CSV_HEADER)
            hits.forEach { appendLine(S2ErrorCatcher.toCsv(it)) }
        })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private const val SAVE_EVERY_MS = 3_000L

    /** The S2 ride claim on the poll ([BikeController.requestPoll]). */
    private const val RIDE_POLL_OWNER = "s2-ride"
    const val RIDE_POLL_MS = 1_000L

    /** How long the ride claim stays after the S2 stopped rolling. */
    private const val RIDE_HOLD_MS = 120_000L
}
