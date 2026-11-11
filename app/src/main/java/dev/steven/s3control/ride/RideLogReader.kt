package dev.steven.s3control.ride

import java.io.File
import java.io.Reader
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reads a ride log back exactly as [RideRecorder] writes it, for the viewer in the app:
 *
 * ```
 * time,type,link,status,shipping_reported,lock,kmh,battery,charger,level,reset_button,supply_ok,wheel_moving,faults,faults_raw,battery_raw,message
 * 2026-10-17T07:52:14.031,sample,READY,RIDING,0,UNLOCKED,23,81,NOT_CHARGING,4,0,1,1,42|18,0000…,51,""
 * 2026-10-17T07:52:15.112,event,READY,RIDING,0,LOCKED,23,81,…,"ALERT/KICKLOCK_RIDING: Kick-Lock meldet gesperrt (23 km/h)"
 * 2026-10-17T07:52:17.500,mark,READY,…,"MARK: Marke 1 (…)"
 * ```
 *
 * Time is local wall-clock time of the phone with milliseconds; `message` is always quoted (its own
 * quotes were written as ') and is "KIND/CODE: text (detail)" for event and mark rows, empty for samples.
 * The code ([EventLog.Code]) is there since YABA 1.1; an older log writes "KIND: text (detail)", and both read.
 *
 * Built for files of any size: the input is streamed once, samples are kept up to [MAX_SAMPLES] (a
 * longer log is folded pairwise, keeping the top speed, the lowest battery, a supply drop and every
 * fault). Incidents (alerts and errors) and the rider's marks are kept apart from routine events, up to
 * [MAX_INCIDENTS], so a chatty link never pushes an alert out; routine events up to [MAX_EVENTS] (the
 * newest ones; the end of a log is where a shutdown sits). The event rug, the signal spans of the
 * shipping rule and the stretches without a connection are tracked over every row, and so are the
 * summary numbers (distance, top speed, battery, counts).
 *
 * A last line without its newline is either a complete row (kept) or one cut off while being written
 * (dropped, [RideLog.partialTail]); a message that contains a line break is joined back into its row.
 *
 * Newer logs carry three more columns before `message` ([EXTRA_COLUMNS]): the phone's GPS speed, an S2's error
 * code and its whole status block. A log without them reads exactly as before. Where GPS says the bike rolls
 * faster than [DROPOUT_GPS_KMH] while the bike reports 0 km/h, the log gets a [Dropout] (Signalabriss). An S2's
 * error codes are kept as faults, but they never raise the S3 shipping-rule signals: 8 is a torque sensor there.
 */
object RideLogReader {

    const val MAX_SAMPLES = 20_000
    const val MAX_EVENTS = 5_000
    const val MAX_INCIDENTS = 2_000
    const val MAX_SPANS = 4_000
    const val MAX_LINK_SPANS = 8_000

    /** Time buckets of the event rug, whatever the length of the log. */
    const val RUG_BUCKETS = 1_024

    /** RideRecorder writes a sample every 2 s. */
    const val SAMPLE_MS = 2_000L

    /** Rows further apart than this are not joined (logger or link paused). */
    const val GAP_MS = 12_000L

    /**
     * Rows without a connection say nothing about the signals: a span runs on through them. Only after
     * this long without a single connected row does a span end where it was last seen.
     */
    const val UNKNOWN_MAX_MS = 10 * 60_000L

    /** At this speed the bike counts as rolling, like `BikeState.moving`. */
    const val MOVING_KMH = 3

    /** Below this much rolling time the average speed is not shown: it would be noise. */
    const val MIN_MOVING_MS = 30_000L

    /** GPS faster than this while the bike reports 0 km/h: the bike's speed signal dropped out (a [Dropout]). */
    const val DROPOUT_GPS_KMH = 8.0

    /**
     * GPS lags behind a hard stop, so a stretch that ends with GPS slowing down too counts only from this long on.
     * One that ends with the bike reporting speed again while GPS still rolls counts at any length.
     */
    const val DROPOUT_MIN_MS = 4_000L

    /**
     * A dropout counts as resumed only when the row that ends it comes at most this long after its last holding row,
     * and a stretch with GPS or speed missing for longer ends it where it was last seen. Otherwise a lost link (a
     * harness fault can reset the module) or a GPS outage between a stop and riding on would be counted as one long
     * speed-signal dropout.
     */
    const val DROPOUT_RESUME_MS = 4_000L

    const val MAX_DROPOUTS = 4_000

    private const val MAX_LINE = 16_384
    private const val MAX_JOINED = 40

    /**
     * The columns of the first RideRecorder layout, in its order: a file without a header is read this way.
     * Files are read by header name, so the order may change.
     */
    val COLUMNS = listOf(
        "time", "type", "link", "status", "shipping_reported", "lock", "kmh", "battery", "charger", "level",
        "reset_button", "supply_ok", "wheel_moving", "faults", "faults_raw", "battery_raw", "message",
    )

    /** Columns newer logs add (before `message`), found by header name only. */
    val EXTRA_COLUMNS = listOf("gps_kmh", "s2_error", "raw_frame")
    private const val X_GPS = 0
    private const val X_S2_ERROR = 1
    private const val X_RAW_FRAME = 2

    const val LOCKED = "LOCKED"
    const val UNLOCKED = "UNLOCKED"
    const val PIN_ENTRY = "PIN_ENTRY"
    private const val READY = "READY"

    /** The EventLog kinds RideRecorder writes before the message. */
    private val KINDS = setOf("LINK", "STATE", "COMMAND", "ERROR", "ALERT", "INFO", "MARK")

    /** An [EventLog.Code] as RideRecorder writes it after the kind ("ALERT/SUPPLY_LOW"). */
    private val CODE = Regex("[A-Z][A-Z0-9_]*")

    /** Codes whose events are the shipping rule's signals or a safety matter while rolling: drawn in signal. */
    val SIGNAL_CODES = setOf("KICKLOCK_RIDING", "FAULT", "SHIPPING", "SHUTDOWN_RIDING", "POWER_BUTTON_RIDING")

    /** Codes whose events are about power or the link: drawn in lamp. */
    val LAMP_CODES = setOf("SUPPLY_LOW", "LINK_LOST_RIDING")

    /** What the colour of an event says. Colour follows the meaning, not the kind. */
    enum class Tone { SIGNAL, LAMP, MARK, PLAIN }

    /**
     * The tone of one event: marks are the rider's own; [LAMP_CODES] and [SIGNAL_CODES] by their code; an error
     * without a code (a refused command, a key the bike did not take) is lamp. An alert without a code is signal: an
     * older log has no codes at all, and the alerts the driver raises without one (sleeping or the power button while
     * rolling, the battery not detected, the alarm) are safety matters too. Everything else is plain.
     */
    fun toneOf(kind: String?, code: String?, mark: Boolean): Tone = when {
        mark || kind == "MARK" -> Tone.MARK
        code in LAMP_CODES -> Tone.LAMP
        code in SIGNAL_CODES -> Tone.SIGNAL
        code != null -> Tone.PLAIN
        kind == "ERROR" -> Tone.LAMP
        kind == "ALERT" -> Tone.SIGNAL
        else -> Tone.PLAIN
    }

    /** One message as RideRecorder joined it: kind and code before the colon, the detail in the last "(…)". */
    data class Message(val kind: String?, val code: String?, val text: String, val detail: String?)

    enum class RowType { SAMPLE, EVENT, MARK }

    /** One CSV row, typed. A value the bike had not reported is null. */
    data class Row(
        val atMillis: Long,
        val type: RowType,
        val link: String,
        val status: String?,
        val shippingReported: Boolean,
        val lock: String?,
        val kmh: Int?,
        val battery: Int?,
        val charger: String?,
        val level: Int?,
        val resetButton: Boolean?,
        val supplyOk: Boolean?,
        val wheelMoving: Boolean,
        val faults: List<Int>,
        val faultsRaw: String,
        val batteryRaw: String,
        val message: String,
        /** The phone's GPS speed when the row was written; null without GPS. */
        val gpsKmh: Double? = null,
        /** An S2's error code (0 = none); null for every other bike. */
        val s2Error: Int? = null,
        /** An S2's decrypted status block in hex; empty for every other bike. */
        val rawFrame: String = "",
    ) {
        /** The link was up: only then are the bike values in this row current (they are kept after a drop). */
        val ready: Boolean get() = link == READY
        val locked: Boolean get() = lock == LOCKED
        val moving: Boolean get() = (kmh ?: 0) >= MOVING_KMH || wheelMoving
        val shipping: Boolean get() = shippingReported || status == "SHIPPING"
        /** Written for an S2: its faults are S2 error codes, not S3 fault bits. */
        val s2: Boolean get() = s2Error != null || rawFrame.isNotEmpty()
        /** The faults that count for the S3 shipping rule (none on an S2). */
        val ruleFaults: List<Int> get() = if (s2) emptyList() else faults
        /**
         * The lock as a shipping-rule signal: not on an S2, whose lock bit (byte 3) is not confirmed yet (test steps
         * 9 and 10). The cockpit skips its locked-while-riding alarm on an S2 for the same reason.
         */
        val ruleLocked: Boolean get() = locked && !s2
    }

    /**
     * One stored sample (or, past [MAX_SAMPLES], two or more folded together: the highest speed, the
     * lowest battery, a low supply wins, a locked lock wins, faults are joined).
     */
    data class Sample(
        val atMillis: Long,
        val kmh: Int?,
        val battery: Int?,
        val supplyOk: Boolean?,
        val lock: String?,
        /** Bit n set = fault n (0–63) was reported. */
        val faultMask: Long,
        val ready: Boolean,
        val moving: Boolean,
        val shipping: Boolean,
        /** The phone's GPS speed (folded: the highest). */
        val gpsKmh: Double? = null,
        /** Written for an S2 ([Row.s2]). */
        val s2: Boolean = false,
    ) {
        val locked: Boolean get() = lock == LOCKED
        fun hasFault(code: Int): Boolean = code in 0..63 && (faultMask and (1L shl code)) != 0L
        val faults: List<Int> get() = (0..63).filter { hasFault(it) }

        /** The shipping-rule signals of this sample (none while the link was down; an S2 raises none of them). */
        val signals: Set<Signal>
            get() = if (!ready) emptySet() else signalsOf(locked && !s2, if (s2) NO_FAULT else ::hasFault, shipping, supplyOk)

        internal fun fold(o: Sample): Sample = Sample(
            atMillis = atMillis,
            kmh = maxOrNull(kmh, o.kmh),
            battery = minOrNull(battery, o.battery),
            supplyOk = when {
                supplyOk == false || o.supplyOk == false -> false
                supplyOk == null && o.supplyOk == null -> null
                else -> true
            },
            lock = if (locked || o.locked) LOCKED else lock ?: o.lock,
            faultMask = faultMask or o.faultMask,
            ready = ready || o.ready,
            moving = moving || o.moving,
            shipping = shipping || o.shipping,
            gpsKmh = maxOrNull(gpsKmh, o.gpsKmh),
            s2 = s2 || o.s2,
        )
    }

    /** An event or mark row, with the message split the way RideRecorder joined it. */
    data class Event(
        val atMillis: Long,
        val mark: Boolean,
        /** The EventLog kind ("ALERT", "MARK", …); null when the message had none. */
        val kind: String?,
        val text: String,
        val detail: String?,
        val kmh: Int?,
        val battery: Int?,
        val lock: String?,
        val faults: List<Int>,
        val ready: Boolean,
        /** 1-based line number in the file. */
        val line: Int,
        val supplyOk: Boolean? = null,
        val shipping: Boolean = false,
        val moving: Boolean = false,
        /** The phone's GPS speed at this row; null without GPS. */
        val gpsKmh: Double? = null,
        /** Written for an S2 ([Row.s2]). */
        val s2: Boolean = false,
        /** The [EventLog.Code] written after the kind ("SUPPLY_LOW"); null in an older log or for an event without one. */
        val code: String? = null,
    ) {
        /** An incident, as everywhere in YABA: an alert or an error. The rider's marks are counted on their own. */
        val incident: Boolean get() = !mark && (kind == "ALERT" || kind == "ERROR")

        /** Kept apart from the routine events, past their cap: incidents and marks. */
        val kept: Boolean get() = incident || mark
        val tone: Tone get() = toneOf(kind, code, mark)
        val locked: Boolean get() = lock == LOCKED

        /** The shipping-rule signals this row showed (none while the link was down; an S2 raises none of them). */
        val signals: Set<Signal>
            get() = if (!ready) emptySet() else signalsOf(locked && !s2, if (s2) NO_FAULT else faults::contains, shipping, supplyOk)
    }

    /** The signals of the bike's shipping rule (the shipping-mode notes), plus the supply drop. */
    enum class Signal { LOCKED, FAULT_42, FAULT_18, FAULT_8, SHIPPING, SUPPLY_LOW }

    /**
     * A stretch in which [signal] held, from the first connected row that showed it to the next connected
     * row that did not ([closedByRow]: that end is the first moment it no longer held, so it is not part
     * of the span), or to the last row that showed it (before a gap, a long time without connection, or
     * the end). Rows without a connection neither start nor end a span. [moving]: the bike rolled at
     * some point in it.
     */
    data class Span(
        val signal: Signal,
        val startMillis: Long,
        val endMillis: Long,
        val moving: Boolean,
        val closedByRow: Boolean = false,
    ) {
        fun holdsAt(t: Long): Boolean = t >= startMillis && (if (closedByRow) t < endMillis else t <= endMillis)
    }

    /** A stretch of rows without a connection to the bike, from the first such row to the next connected one. */
    data class Stretch(val startMillis: Long, val endMillis: Long)

    /**
     * A signal dropout (Signalabriss): connected rows in which GPS measured more than [DROPOUT_GPS_KMH] while the
     * bike reported 0 km/h, from the first such row to the row that ended it ([resumed]: the bike reported speed
     * again while GPS still rolled, right after), or to the last such row before a lost link, a few seconds without
     * GPS or speed, a gap or the end ([DropoutTracker]). Such rows never start one.
     */
    data class Dropout(
        val startMillis: Long,
        val endMillis: Long,
        /** The highest GPS speed in it, km/h. */
        val peakGpsKmh: Double,
        val rows: Int,
        val resumed: Boolean,
    ) {
        val durationMillis: Long get() = endMillis - startMillis
        fun holdsAt(t: Long): Boolean = t >= startMillis && (if (resumed) t < endMillis else t <= endMillis)
    }

    /**
     * The event rug: [buckets] time slots of [bucketMillis] each from [anchorMillis], each holding the
     * most serious kind of event in it ([RANK_ROUTINE] … [RANK_ALERT], 0 = none). Built from every row,
     * so it shows the whole log even when the event list is capped.
     */
    class Rug(val anchorMillis: Long, val bucketMillis: Long, private val ranks: ByteArray) {
        val buckets: Int get() = ranks.size
        fun rank(i: Int): Int = ranks[i].toInt()
        fun startOf(i: Int): Long = anchorMillis + i * bucketMillis

        companion object {
            const val RANK_ROUTINE = 1
            const val RANK_MARK = 2
            const val RANK_ERROR = 3
            const val RANK_ALERT = 4
            val EMPTY = Rug(0L, 1L, ByteArray(0))

            /** By the event's [Tone], so the rug and the list say the same: signal, lamp, mark, the rest. */
            fun rankOf(e: Event): Int = when (e.tone) {
                Tone.SIGNAL -> RANK_ALERT
                Tone.LAMP -> RANK_ERROR
                Tone.MARK -> RANK_MARK
                Tone.PLAIN -> RANK_ROUTINE
            }
        }
    }

    data class RideLog(
        val startMillis: Long?,
        val endMillis: Long?,
        val sampleCount: Int,
        /** Event rows (marks not counted). */
        val eventCount: Int,
        val markCount: Int,
        /** Every alert and error row in the file (marks are [markCount]). */
        val incidentCount: Int,
        /** Integrated from the speed of consecutive samples (trapezoid, link up, at most [GAP_MS] apart). */
        val distanceKm: Double,
        val movingMillis: Long,
        val topKmh: Int?,
        val batteryStart: Int?,
        val batteryEnd: Int?,
        val samples: List<Sample>,
        /** How many rows one stored sample stands for: 1, or 2, 4, … for a very long log. */
        val sampleStride: Int,
        /** Events and marks, oldest first: every incident and mark (up to [MAX_INCIDENTS]) and the newest routine events. */
        val events: List<Event>,
        /** Event rows (not marks) left out of [events]: the oldest routine ones, or incidents past their cap. */
        val eventsLeftOut: Int,
        /** Marks left out of [events] (only past [MAX_INCIDENTS] incidents). */
        val marksLeftOut: Int,
        val rug: Rug,
        val spans: List<Span>,
        /** Stretches without a connection, oldest first. */
        val linkDown: List<Stretch>,
        /** Rows written while the link was up. 0: the bike was never connected in this recording. */
        val readyRows: Int,
        /** Every fault code reported while the link was up, ascending. */
        val faultsSeen: List<Int>,
        /**
         * The faults of the last fault episode, ascending: those of the first row of shipping mode that had
         * any ([tubeFromShipping]), else those of the last connected row with faults. Empty: none.
         */
        val tubeFaults: List<Int>,
        val tubeFaultAtMillis: Long?,
        val tubeFromShipping: Boolean,
        val lines: Int,
        /** Lines that are no row of a ride log (garbage, a broken row); the header is not counted. */
        val skippedLines: Int,
        /** The last line was cut off while being written and is left out. */
        val partialTail: Boolean,
        val hasHeader: Boolean,
        /** Connected rows with a GPS speed. 0: no GPS comparison in this recording. */
        val gpsRows: Int = 0,
        val gpsTopKmh: Double? = null,
        /** Rows written for an S2 ([Row.s2]). */
        val s2Rows: Int = 0,
        /** The signal dropouts, oldest first (only with GPS). */
        val dropouts: List<Dropout> = emptyList(),
    ) {
        val hasGps: Boolean get() = gpsRows > 0
        val isS2: Boolean get() = s2Rows > 0
        val dropoutMillis: Long get() = dropouts.sumOf { it.durationMillis }

        /** True when [atMillis] lies in a signal dropout. */
        fun dropoutAt(atMillis: Long): Boolean = dropouts.any { it.holdsAt(atMillis) }

        val durationMillis: Long get() = if (startMillis != null && endMillis != null) endMillis - startMillis else 0L
        val avgMovingKmh: Double? get() = if (movingMillis >= MIN_MOVING_MS) distanceKm / (movingMillis / 3_600_000.0) else null
        /** The lowest fault code of the whole log (the signals section lists them all). */
        val lowestFault: Int? get() = faultsSeen.firstOrNull()
        /** The code the top tube showed last: the lowest one of the last fault episode, as the firmware does. */
        val tubeFault: Int? get() = tubeFaults.firstOrNull()
        val everConnected: Boolean get() = readyRows > 0
        val isEmpty: Boolean get() = sampleCount + eventCount + markCount == 0
        fun spans(signal: Signal): List<Span> = spans.filter { it.signal == signal }

        /** The stored sample closest to [atMillis], or null when none lies within [GAP_MS]. */
        fun sampleAt(atMillis: Long): Sample? {
            if (samples.isEmpty()) return null
            var lo = 0
            var hi = samples.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (samples[mid].atMillis < atMillis) lo = mid + 1 else hi = mid
            }
            val best = listOfNotNull(samples.getOrNull(lo - 1), samples[lo]).minByOrNull { kotlin.math.abs(it.atMillis - atMillis) }
            return best?.takeIf { kotlin.math.abs(it.atMillis - atMillis) <= GAP_MS * sampleStride }
        }

        /** Index in [events] of the event closest to [atMillis]. */
        fun eventIndexAt(atMillis: Long): Int? {
            if (events.isEmpty()) return null
            return events.indices.minByOrNull { kotlin.math.abs(events[it].atMillis - atMillis) }
        }

        /** The spans that hold at [atMillis] (a span closed by a row no longer holds at that row). */
        fun signalsAt(atMillis: Long): List<Signal> =
            spans.filter { it.holdsAt(atMillis) }.map { it.signal }.distinct()

        /** True when [atMillis] lies in a stretch without a connection. */
        fun linkDownAt(atMillis: Long): Boolean = linkDown.any { atMillis >= it.startMillis && atMillis < it.endMillis }

        companion object {
            val EMPTY = RideLog(
                startMillis = null, endMillis = null, sampleCount = 0, eventCount = 0, markCount = 0, incidentCount = 0,
                distanceKm = 0.0, movingMillis = 0L, topKmh = null, batteryStart = null, batteryEnd = null,
                samples = emptyList(), sampleStride = 1, events = emptyList(), eventsLeftOut = 0, marksLeftOut = 0,
                rug = Rug.EMPTY, spans = emptyList(), linkDown = emptyList(), readyRows = 0, faultsSeen = emptyList(),
                tubeFaults = emptyList(), tubeFaultAtMillis = null, tubeFromShipping = false,
                lines = 0, skippedLines = 0, partialTail = false, hasHeader = false,
            )
        }
    }

    private val NO_FAULT: (Int) -> Boolean = { false }

    internal fun signalsOf(locked: Boolean, hasFault: (Int) -> Boolean, shipping: Boolean, supplyOk: Boolean?): Set<Signal> = buildSet {
        if (locked) add(Signal.LOCKED)
        if (hasFault(42)) add(Signal.FAULT_42)
        if (hasFault(18)) add(Signal.FAULT_18)
        if (hasFault(8)) add(Signal.FAULT_8)
        if (shipping) add(Signal.SHIPPING)
        if (supplyOk == false) add(Signal.SUPPLY_LOW)
    }

    // --- Entry points ---------------------------------------------------------------------------

    fun read(file: File, zone: ZoneId = ZoneId.systemDefault()): RideLog =
        file.inputStream().reader(Charsets.UTF_8).use { read(it, zone) }

    fun read(text: String, zone: ZoneId = ZoneId.systemDefault()): RideLog = read(text.reader(), zone)

    /** Streams [input] once. Never throws on content; an I/O error propagates. */
    fun read(
        input: Reader,
        zone: ZoneId = ZoneId.systemDefault(),
        maxSamples: Int = MAX_SAMPLES,
        maxEvents: Int = MAX_EVENTS,
        maxIncidents: Int = MAX_INCIDENTS,
    ): RideLog {
        val acc = Accumulator(zone, maxSamples.coerceAtLeast(2), maxEvents.coerceAtLeast(1), maxIncidents.coerceAtLeast(1))
        val buf = CharArray(16_384)
        val line = StringBuilder()
        var overlong = false
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val ch = buf[i]
                if (ch == '\n') {
                    acc.physical(if (overlong) null else line.toString(), terminated = true)
                    line.setLength(0)
                    overlong = false
                } else if (!overlong) {
                    if (line.length < MAX_LINE) line.append(ch) else overlong = true
                }
            }
        }
        if (line.isNotEmpty() || overlong) acc.physical(if (overlong) null else line.toString(), terminated = false)
        return acc.finish()
    }

    // --- Parsing pieces (internal for the tests) ---------------------------------------------------

    /** Splits one CSV row. Quoted fields are unwrapped; "" inside quotes is one quote. */
    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(COLUMNS.size)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' -> if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else quoted = false
                quoted -> sb.append(ch)
                ch == '"' -> quoted = true
                ch == ',' -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** "2026-10-17T07:52:14.031" in [zone] to epoch milliseconds; null if it is not that shape. */
    internal fun parseTime(s: String, zone: ZoneId): Long? {
        if (s.length < 19 || s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':') return null
        fun num(from: Int, to: Int): Int {
            var v = 0
            for (i in from until to) {
                val d = s[i] - '0'
                if (d !in 0..9) return -1
                v = v * 10 + d
            }
            return v
        }
        val y = num(0, 4); val mo = num(5, 7); val d = num(8, 10)
        val h = num(11, 13); val mi = num(14, 16); val se = num(17, 19)
        if (y < 0 || mo < 0 || d < 0 || h < 0 || mi < 0 || se < 0) return null
        var ms = 0
        if (s.length > 19) {
            if (s[19] != '.' || s.length > 23 || s.length == 20) return null
            val frac = num(20, s.length)
            if (frac < 0) return null
            ms = when (s.length) { 21 -> frac * 100; 22 -> frac * 10; else -> frac }
        }
        return try {
            LocalDateTime.of(y, mo, d, h, mi, se).atZone(zone).toInstant().toEpochMilli() + ms
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * "ALERT/KICKLOCK_RIDING: Kick-Lock meldet gesperrt (23 km/h)" to (ALERT, KICKLOCK_RIDING, text, detail); the
     * older "ALERT: …" without a code reads the same with a null code. The detail is the last balanced "(…)" after a
     * space at the very end, as RideRecorder appends it.
     */
    internal fun splitMessage(message: String): Message {
        var kind: String? = null
        var code: String? = null
        var rest = message.trim()
        val colon = rest.indexOf(": ")
        if (colon > 0) {
            val head = rest.substring(0, colon)
            val slash = head.indexOf('/')
            val k = if (slash > 0) head.substring(0, slash) else head
            val c = if (slash > 0) head.substring(slash + 1) else null
            if (k in KINDS && (c == null || CODE.matches(c))) {
                kind = k
                code = c
                rest = rest.substring(colon + 2)
            }
        }
        if (rest.endsWith(")")) {
            var depth = 0
            for (i in rest.indices.reversed()) {
                when (rest[i]) {
                    ')' -> depth++
                    '(' -> {
                        depth--
                        if (depth == 0) {
                            if (i >= 2 && rest[i - 1] == ' ') {
                                return Message(kind, code, rest.substring(0, i - 1).trimEnd(), rest.substring(i + 1, rest.length - 1))
                            }
                            return Message(kind, code, rest, null)
                        }
                    }
                }
            }
        }
        return Message(kind, code, rest, null)
    }

    private fun maxOrNull(a: Int?, b: Int?): Int? = if (a == null) b else if (b == null) a else maxOf(a, b)
    private fun maxOrNull(a: Double?, b: Double?): Double? = if (a == null) b else if (b == null) a else maxOf(a, b)
    private fun minOrNull(a: Int?, b: Int?): Int? = if (a == null) b else if (b == null) a else minOf(a, b)

    private fun bool(s: String?): Boolean? = when (s) { "1" -> true; "0" -> false; else -> null }

    private fun lockName(s: String?): String? = when (s) {
        LOCKED -> LOCKED
        UNLOCKED -> UNLOCKED
        PIN_ENTRY -> PIN_ENTRY
        null, "" -> null
        else -> s
    }

    private fun mask(faults: List<Int>): Long {
        var m = 0L
        for (f in faults) if (f in 0..63) m = m or (1L shl f)
        return m
    }

    /** Column positions from a header, -1 where a column is missing. */
    private class Columns(names: List<String>) {
        val idx = IntArray(COLUMNS.size) { i -> names.indexOf(COLUMNS[i]) }
        val extra = IntArray(EXTRA_COLUMNS.size) { i -> names.indexOf(EXTRA_COLUMNS[i]) }
        val count = names.size
        fun of(fields: List<String>, col: Int): String? = idx[col].takeIf { it >= 0 }?.let { fields.getOrNull(it) }
        fun extra(fields: List<String>, col: Int): String? = extra[col].takeIf { it >= 0 }?.let { fields.getOrNull(it) }

        companion object {
            val DEFAULT = Columns(COLUMNS)
        }
    }

    // --- The one pass ------------------------------------------------------------------------------

    private class Accumulator(val zone: ZoneId, val maxSamples: Int, val maxEvents: Int, val maxIncidents: Int) {
        var columns = Columns.DEFAULT
        var hasHeader = false
        var lines = 0
        var skipped = 0
        var partialTail = false
        var sawContent = false

        // A row whose quoted message continues on the next physical line.
        var pending: StringBuilder? = null
        var pendingStart = 0
        var pendingLines = 0

        var start: Long? = null
        var end: Long? = null
        var sampleCount = 0
        var eventCount = 0
        var markCount = 0
        var incidentCount = 0
        var readyRows = 0
        var distanceKm = 0.0
        var movingMillis = 0L
        var top: Int? = null
        var batteryStart: Int? = null
        var batteryEnd: Int? = null
        var faultsSeen = 0L
        var prevSpeed: Row? = null

        // The last fault episode, for the top tube.
        var lastFaultAt: Long? = null
        var lastFaultMask = 0L
        var shipFaultAt: Long? = null
        var shipFaultMask = 0L

        var samples = ArrayList<Sample>()
        var stride = 1
        var carry: Sample? = null
        var carryCount = 0

        /** INFO, LINK, STATE, COMMAND: the newest [maxEvents]. */
        val routine = ArrayDeque<Event>()
        /** ALERT, ERROR and the marks: the newest [maxIncidents], never pushed out by routine rows. */
        val incidents = ArrayDeque<Event>()
        var eventsLeftOut = 0
        var marksLeftOut = 0
        val rug = RugBuilder(RUG_BUCKETS)

        val spans = SpanTracker()
        val link = LinkTracker()
        val dropouts = DropoutTracker()
        var gpsRows = 0
        var gpsTop: Double? = null
        var s2Rows = 0

        fun physical(text: String?, terminated: Boolean) {
            lines++
            val lineNo = lines
            if (text == null) {
                // Longer than any row RideRecorder writes.
                pending = null
                skipped++
                return
            }
            var t = text.trimEnd('\r')
            if (lineNo == 1 && t.startsWith('﻿')) t = t.substring(1)
            val p = pending
            val joined = if (p != null) p.append(' ').append(t).toString() else t
            val startLine = if (p != null) pendingStart else lineNo
            if (oddQuotes(joined)) {
                if (!terminated) {
                    // Cut off inside the message: the row was still being written.
                    pending = null
                    partialTail = true
                    return
                }
                val joinedLines = if (p != null) pendingLines + 1 else 1
                if (joinedLines < MAX_JOINED) {
                    pending = StringBuilder(joined)
                    pendingStart = startLine
                    pendingLines = joinedLines
                    return
                }
            }
            pending = null
            logical(joined, startLine, terminated)
        }

        fun oddQuotes(s: String): Boolean {
            var n = 0
            for (ch in s) if (ch == '"') n++
            return n % 2 == 1
        }

        fun logical(text: String, lineNo: Int, terminated: Boolean) {
            if (text.isBlank()) return
            if (text.startsWith("time,")) {
                val names = text.split(',').map { it.trim() }
                if (!sawContent && !hasHeader) {
                    columns = Columns(names)
                    hasHeader = true
                }
                // A second header: the logger was started twice within one second and appended.
                return
            }
            sawContent = true
            val fields = splitCsv(text)
            if (!terminated) {
                // The last line has no newline: keep it only if it is visibly whole.
                val expected = if (hasHeader) columns.count else COLUMNS.size
                if (fields.size < expected || !text.endsWith('"')) {
                    partialTail = true
                    return
                }
            }
            val row = parseRow(fields)
            if (row == null) {
                skipped++
                return
            }
            add(row, lineNo)
        }

        fun parseRow(f: List<String>): Row? {
            val c = columns
            val at = c.of(f, 0)?.let { parseTime(it.trim(), zone) } ?: return null
            val type = when (c.of(f, 1)?.trim()) {
                "sample" -> RowType.SAMPLE
                "event" -> RowType.EVENT
                "mark" -> RowType.MARK
                else -> return null
            }
            val faults = c.of(f, 13)?.split('|')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            return Row(
                atMillis = at,
                type = type,
                link = c.of(f, 2)?.trim().orEmpty(),
                status = c.of(f, 3)?.trim()?.takeIf { it.isNotEmpty() },
                shippingReported = c.of(f, 4)?.trim() == "1",
                lock = lockName(c.of(f, 5)?.trim()),
                kmh = c.of(f, 6)?.trim()?.toIntOrNull(),
                battery = c.of(f, 7)?.trim()?.toIntOrNull(),
                charger = c.of(f, 8)?.trim()?.takeIf { it.isNotEmpty() },
                level = c.of(f, 9)?.trim()?.toIntOrNull(),
                resetButton = bool(c.of(f, 10)?.trim()),
                supplyOk = bool(c.of(f, 11)?.trim()),
                wheelMoving = c.of(f, 12)?.trim() == "1",
                faults = faults,
                faultsRaw = c.of(f, 14)?.trim().orEmpty(),
                batteryRaw = c.of(f, 15)?.trim().orEmpty(),
                message = c.of(f, 16).orEmpty(),
                gpsKmh = c.extra(f, X_GPS)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 },
                s2Error = c.extra(f, X_S2_ERROR)?.trim()?.toIntOrNull(),
                rawFrame = c.extra(f, X_RAW_FRAME)?.trim().orEmpty(),
            )
        }

        fun add(r: Row, lineNo: Int) {
            val t = r.atMillis
            if (start == null || t < start!!) start = t
            if (end == null || t > end!!) end = t
            if (r.ready) {
                readyRows++
                r.kmh?.let { k -> if (top == null || k > top!!) top = k }
                r.battery?.let { b ->
                    if (batteryStart == null) batteryStart = b
                    batteryEnd = b
                }
                val m = mask(r.faults)
                faultsSeen = faultsSeen or m
                r.gpsKmh?.let { g ->
                    gpsRows++
                    if (gpsTop.let { it == null || g > it }) gpsTop = g
                }
                if (m != 0L) {
                    lastFaultAt = t
                    lastFaultMask = m
                    if (r.shipping && shipFaultAt == null) {
                        shipFaultAt = t
                        shipFaultMask = m
                    }
                }
            }
            if (r.s2) s2Rows++
            spans.row(r)
            link.row(r)
            dropouts.row(r)
            when (r.type) {
                RowType.SAMPLE -> sample(r)
                RowType.EVENT, RowType.MARK -> event(r, lineNo)
            }
        }

        fun sample(r: Row) {
            sampleCount++
            // Distance and rolling time from consecutive samples with the link up and a speed.
            val prev = prevSpeed
            if (r.ready && r.kmh != null) {
                if (prev != null) {
                    val dt = r.atMillis - prev.atMillis
                    if (dt in 1..GAP_MS) {
                        val mean = (prev.kmh!! + r.kmh) / 2.0
                        distanceKm += mean * dt / 3_600_000.0
                        if (mean >= MOVING_KMH / 2.0 || r.wheelMoving) movingMillis += dt
                    }
                }
                prevSpeed = r
            } else {
                prevSpeed = null
            }
            val s = Sample(
                atMillis = r.atMillis,
                kmh = r.kmh.takeIf { r.ready },
                battery = r.battery.takeIf { r.ready },
                supplyOk = r.supplyOk.takeIf { r.ready },
                lock = r.lock.takeIf { r.ready },
                faultMask = if (r.ready) mask(r.faults) else 0L,
                ready = r.ready,
                moving = r.ready && r.moving,
                shipping = r.ready && r.shipping,
                gpsKmh = r.gpsKmh.takeIf { r.ready },
                s2 = r.s2,
            )
            // Past the cap every stored sample stands for [stride] rows: fold them as they come.
            val c = carry
            val folded = if (c == null) s else c.fold(s)
            carryCount++
            if (carryCount < stride) {
                carry = folded
                return
            }
            carry = null
            carryCount = 0
            samples.add(folded)
            if (samples.size >= maxSamples) halve()
        }

        fun halve() {
            val out = ArrayList<Sample>(samples.size / 2 + 1)
            var i = 0
            while (i < samples.size) {
                val a = samples[i]
                val b = samples.getOrNull(i + 1)
                out.add(if (b != null) a.fold(b) else a)
                i += 2
            }
            samples = out
            stride *= 2
        }

        fun event(r: Row, lineNo: Int) {
            val mark = r.type == RowType.MARK
            if (mark) markCount++ else eventCount++
            val msg = splitMessage(r.message)
            val e = Event(
                atMillis = r.atMillis,
                mark = mark,
                kind = msg.kind,
                text = msg.text,
                detail = msg.detail?.takeIf { it.isNotBlank() },
                kmh = r.kmh.takeIf { r.ready },
                battery = r.battery.takeIf { r.ready },
                lock = r.lock.takeIf { r.ready },
                faults = if (r.ready) r.faults else emptyList(),
                ready = r.ready,
                line = lineNo,
                supplyOk = r.supplyOk.takeIf { r.ready },
                shipping = r.ready && r.shipping,
                moving = r.ready && r.moving,
                gpsKmh = r.gpsKmh.takeIf { r.ready },
                s2 = r.s2,
                code = msg.code,
            )
            rug.add(e.atMillis, Rug.rankOf(e))
            val list = if (e.kept) incidents else routine
            if (e.incident) incidentCount++
            list.addLast(e)
            if (list.size > (if (e.kept) maxIncidents else maxEvents)) {
                val gone = list.removeFirst()
                if (gone.mark) marksLeftOut++ else eventsLeftOut++
            }
        }

        fun finish(): RideLog {
            pending?.let {
                // The file ends inside a quoted message.
                partialTail = true
                pending = null
            }
            carry?.let { samples.add(it); carry = null }
            spans.finish()
            link.finish()
            dropouts.finish()
            if (!sawContent && start == null) {
                return RideLog.EMPTY.copy(lines = lines, skippedLines = skipped, partialTail = partialTail, hasHeader = hasHeader)
            }
            // Stable: rows with the same time stay in file order.
            val allEvents = (routine + incidents).sortedWith(compareBy({ it.atMillis }, { it.line }))
            fun codes(m: Long) = (0..63).filter { (m and (1L shl it)) != 0L }
            val tubeAt = shipFaultAt ?: lastFaultAt
            val tubeMask = if (shipFaultAt != null) shipFaultMask else lastFaultMask
            return RideLog(
                startMillis = start,
                endMillis = end,
                sampleCount = sampleCount,
                eventCount = eventCount,
                markCount = markCount,
                incidentCount = incidentCount,
                distanceKm = distanceKm,
                movingMillis = movingMillis,
                topKmh = top,
                batteryStart = batteryStart,
                batteryEnd = batteryEnd,
                samples = samples,
                sampleStride = stride,
                events = allEvents,
                eventsLeftOut = eventsLeftOut,
                marksLeftOut = marksLeftOut,
                rug = rug.build(),
                spans = spans.result(),
                linkDown = link.result(),
                readyRows = readyRows,
                faultsSeen = codes(faultsSeen),
                tubeFaults = if (tubeAt != null) codes(tubeMask) else emptyList(),
                tubeFaultAtMillis = tubeAt,
                tubeFromShipping = shipFaultAt != null,
                lines = lines,
                skippedLines = skipped,
                partialTail = partialTail,
                hasHeader = hasHeader,
                gpsRows = gpsRows,
                gpsTopKmh = gpsTop,
                s2Rows = s2Rows,
                dropouts = dropouts.result(),
            )
        }
    }

    /**
     * Tracks when each [Signal] holds, over every row in file order. Rows without a connection are
     * unknown: they neither open nor close a span (a flaky link next to a parked bike is one span, not
     * thousands). A span ends at the first connected row that no longer shows it, or where it was last
     * seen before a gap in the rows or [UNKNOWN_MAX_MS] without a connected row.
     */
    private class SpanTracker {
        private class Open(val start: Long, var lastSeen: Long, var moving: Boolean)

        private val open = HashMap<Signal, Open>()
        private val out = ArrayList<Span>()
        private var lastAt: Long? = null
        private var lastReady: Long? = null

        fun row(r: Row) {
            val t = r.atMillis
            val prev = lastAt
            // A gap: what held before ends where it was last seen.
            if (prev != null && t - prev > GAP_MS) closeAll()
            lastAt = if (prev == null) t else maxOf(prev, t)
            if (!r.ready) return
            val seen = lastReady
            if (seen != null && t - seen > UNKNOWN_MAX_MS) closeAll()
            lastReady = if (seen == null) t else maxOf(seen, t)
            val active = signalsOf(r.ruleLocked, r.ruleFaults::contains, r.shipping, r.supplyOk)
            for (sig in Signal.entries) {
                val o = open[sig]
                if (sig in active) {
                    if (o == null) open[sig] = Open(t, t, r.moving) else {
                        o.lastSeen = maxOf(o.lastSeen, t)
                        o.moving = o.moving || r.moving
                    }
                } else if (o != null) {
                    open.remove(sig)
                    emit(sig, o, maxOf(t, o.start), closedByRow = true)
                }
            }
        }

        fun finish() = closeAll()

        fun result(): List<Span> = out.sortedBy { it.startMillis }

        private fun closeAll() {
            for ((sig, o) in open) emit(sig, o, o.lastSeen, closedByRow = false)
            open.clear()
        }

        private fun emit(sig: Signal, o: Open, end: Long, closedByRow: Boolean) {
            if (out.size >= MAX_SPANS) return
            out.add(Span(sig, o.start, maxOf(end, o.start), o.moving, closedByRow && end > o.start))
        }
    }

    /**
     * The signal dropouts ([Dropout]) over every row in file order. A row holds when it is connected, has a GPS
     * speed above [DROPOUT_GPS_KMH] and a bike speed of exactly 0; it clears when it is connected with both speeds
     * and does not hold. A row without a connection ends an open stretch where it was last seen, and so does a row
     * without GPS or speed more than [DROPOUT_RESUME_MS] after it: neither says what the speed signal did. A stretch
     * that clears with the bike rolling again while GPS still says so, within [DROPOUT_RESUME_MS] of its last
     * holding row, is kept at any length ([Dropout.resumed]); one that ends otherwise (GPS slowing down too, a lost
     * link, missing GPS, a gap, the end) only from [DROPOUT_MIN_MS] on, since GPS trails a hard stop by a second or two.
     */
    internal class DropoutTracker {
        private class Open(val start: Long, var lastSeen: Long, var peak: Double, var rows: Int)

        private var open: Open? = null
        private var lastAt: Long? = null
        private val out = ArrayList<Dropout>()

        fun row(r: Row) {
            val t = r.atMillis
            val prev = lastAt
            // A gap in the rows: what held ends where it was last seen.
            if (prev != null && t - prev > GAP_MS) close(null)
            lastAt = if (prev == null) t else maxOf(prev, t)
            val gps = r.gpsKmh
            val kmh = r.kmh
            if (!r.ready) {
                // The link went: what the bike reports from here on is not its speed signal.
                close(null)
                return
            }
            if (gps == null || kmh == null) {
                val o = open
                if (o != null && t - o.lastSeen > DROPOUT_RESUME_MS) close(null)
                return
            }
            if (gps > DROPOUT_GPS_KMH && kmh == 0) {
                val o = open
                if (o == null) {
                    open = Open(t, t, gps, 1)
                } else {
                    o.lastSeen = maxOf(o.lastSeen, t)
                    o.peak = maxOf(o.peak, gps)
                    o.rows++
                }
            } else if (open != null) {
                close(r)
            }
        }

        fun finish() = close(null)

        fun result(): List<Dropout> = out

        private fun close(by: Row?) {
            val o = open ?: return
            open = null
            // Not holding with GPS still above the threshold means the bike reports speed again, if it comes right after.
            val resumed = by != null && (by.gpsKmh ?: 0.0) > DROPOUT_GPS_KMH && by.atMillis - o.lastSeen <= DROPOUT_RESUME_MS
            if (!resumed && o.lastSeen - o.start < DROPOUT_MIN_MS) return
            if (out.size >= MAX_DROPOUTS) return
            val end = if (resumed) maxOf(by!!.atMillis, o.start) else o.lastSeen
            out.add(Dropout(o.start, end, o.peak, o.rows, resumed))
        }
    }

    /** The stretches without a connection: from the first row with the link not up to the next connected row. */
    private class LinkTracker {
        private val out = ArrayList<Stretch>()
        private var downFrom: Long? = null
        private var lastAt: Long? = null

        fun row(r: Row) {
            val t = r.atMillis
            val prev = lastAt
            // A gap in the rows is no record at all, not a lost connection.
            if (prev != null && t - prev > GAP_MS) {
                downFrom?.let { emit(it, prev) }
                downFrom = null
            }
            lastAt = if (prev == null) t else maxOf(prev, t)
            if (!r.ready) {
                if (downFrom == null) downFrom = t
            } else {
                downFrom?.let { emit(it, t) }
                downFrom = null
            }
        }

        fun finish() {
            val from = downFrom ?: return
            emit(from, lastAt ?: from)
            downFrom = null
        }

        fun result(): List<Stretch> = out

        private fun emit(from: Long, to: Long) {
            if (out.size >= MAX_LINK_SPANS) return
            out.add(Stretch(from, maxOf(from, to)))
        }
    }

    /**
     * The rug in a fixed number of buckets: they start one second wide at the first event and double
     * (pairwise, the more serious kind wins) whenever an event falls past the last one.
     */
    private class RugBuilder(val n: Int) {
        private var anchor: Long? = null
        private var width = 1_000L
        private val ranks = ByteArray(n)

        fun add(t: Long, rank: Int) {
            val a = anchor ?: t.also { anchor = it }
            var i = ((t - a) / width).coerceAtLeast(0L)
            while (i >= n) {
                for (j in 0 until n / 2) ranks[j] = maxOf(ranks[2 * j], ranks[2 * j + 1])
                for (j in n / 2 until n) ranks[j] = 0
                width *= 2
                i = (t - a) / width
            }
            val k = i.toInt()
            if (rank > ranks[k]) ranks[k] = rank.toByte()
        }

        fun build(): Rug = anchor?.let { Rug(it, width, ranks.copyOf()) } ?: Rug.EMPTY
    }
}
