package dev.steven.s3control.ride

import dev.steven.s3control.ble.DerateTable
import dev.steven.s3control.ride.BikeLogParser.DerateEvent
import dev.steven.s3control.ride.BikeLogParser.MotorErrorEvent
import dev.steven.s3control.ride.BikeLogParser.ParsedLog
import dev.steven.s3control.ride.BikeLogParser.ShifterEvent
import dev.steven.s3control.ride.BikeLogParser.ShifterEventKind
import dev.steven.s3control.ride.BikeLogParser.Telemetry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the "Fahrtenschreiber" screen reads out of the bike's own log: rides cut from the 30-second
 * telemetry, the wake timeline, the shifter's health, the firmware's current limit explained, and the
 * guard for the irreversible log seek. Pure and offline (no Android), unit-tested directly.
 *
 * Column meaning (decomp mainware states.c:2922): BMSSOC is the main battery (the "SOC" the derate lines
 * print, −1 = unknown); LiPOSOC is the module's own small battery. Charts and the derate explainer use
 * BMSSOC.
 */
object LogInsights {

    const val ROW_SECONDS = 30L
    /** A gap longer than three rows ends a wake session (the bike slept in between). */
    const val SESSION_GAP_SECONDS = 95L
    /** Idle rows a ride may contain (a stop at a light): 4 rows = 2 minutes. */
    const val STOP_ROWS = 4
    const val MOVING_KMH = 1.0
    /** An odometer step this big between two rows counts as moving even if both speed samples are 0. */
    const val ODO_STEP_KM = 0.05
    /** A shift line is matched to the telemetry speed only if a row lies this close. */
    const val SPEED_MATCH_SECONDS = 45L
    /** A wake "led to a ride" when a ride starts within this window after it. */
    const val WAKE_RIDE_WINDOW_SECONDS = 600L
    /** Earliest epoch treated as a set clock (2017-07-14); below it the bike clock was never set. */
    const val PLAUSIBLE_EPOCH = 1_500_000_000L
    /** The seek is offered only this long after the download it relies on. */
    const val SEEK_MAX_AGE_SECONDS = 600L

    // --- Rides -------------------------------------------------------------------------------------

    data class Ride(
        val startEpoch: Long,
        val endEpoch: Long,
        /** The ride's rows plus one idle row on each side for context, oldest first. */
        val rows: List<Telemetry>,
        val movingRows: Int,
        val distanceKm: Double?,
        val maxSpeedKmh: Double?,
        val avgSpeedKmh: Double?,
        /** Main battery (BMSSOC) at the first and last row; null when the BMS reported −1. */
        val socStart: Int?,
        val socEnd: Int?,
        val maxMotorTempC: Int?,
        val maxDriverTempC: Int?,
        val maxBatTempC: Double?,
        val minBatTempC: Double?,
        val maxMotorCurrentA: Double?,
        /** Share of rows with Boost held, 0..1. */
        val boostShare: Double?,
        /** The current-limit line in force when the ride started (may be from long before). */
        val limitBefore: DerateEvent?,
        /** Current-limit changes during the ride. */
        val limits: List<DerateEvent>,
        val shifter: List<ShifterEvent>,
        val motorErrors: List<MotorErrorEvent>,
    ) {
        val durationSeconds: Long get() = (endEpoch - startEpoch).coerceAtLeast(ROW_SECONDS)
        val socUsed: Int? get() = if (socStart != null && socEnd != null && socStart >= socEnd) socStart - socEnd else null

        /** The firmware's current limit (A) at [epoch] as logged, null if no limit line is known. */
        fun limitAt(epoch: Long): Double? =
            (limits.lastOrNull { it.epoch <= epoch } ?: limitBefore)?.currentLimitA

        /** The row closest in time to [epoch]. */
        fun rowAt(epoch: Long): Telemetry? = rows.minByOrNull { abs(it.epoch - epoch) }
    }

    /** Main-battery SoC of a row, null for the BMS's "unknown" (−1). */
    fun soc(t: Telemetry): Int? = t.bmsSoc?.takeIf { it in 0..100 }

    /** Motor temperature of a row, null when the motor has no sensor (the firmware writes 0). */
    fun motorTemp(t: Telemetry): Int? = t.motorTempC?.takeIf { it != 0 }

    fun rides(log: ParsedLog): List<Ride> {
        val rows = dedupe(log.validTelemetry)
        if (rows.isEmpty()) return emptyList()
        val boots = log.boots.sorted()
        val out = ArrayList<Ride>()
        var session = ArrayList<Telemetry>()
        for (r in rows) {
            val prev = session.lastOrNull()
            if (prev != null && (r.epoch - prev.epoch > SESSION_GAP_SECONDS || r.epoch < prev.epoch || bootBetween(boots, prev.epoch, r.epoch))) {
                ridesIn(session, log, out)
                session = ArrayList()
            }
            session.add(r)
        }
        ridesIn(session, log, out)
        return out
    }

    private fun dedupe(rows: List<Telemetry>): List<Telemetry> {
        val seen = HashSet<Long>()
        return rows.filter { seen.add(it.epoch) }
    }

    private fun bootBetween(boots: List<Long>, from: Long, to: Long): Boolean {
        if (boots.isEmpty()) return false
        var i = boots.binarySearch(from + 1)
        if (i < 0) i = -i - 1
        return i < boots.size && boots[i] <= to
    }

    private fun ridesIn(rows: List<Telemetry>, log: ParsedLog, out: MutableList<Ride>) {
        val n = rows.size
        if (n == 0) return
        val moving = BooleanArray(n) { i ->
            val speed = rows[i].speedKmh ?: 0.0
            val step = if (i > 0) {
                val a = rows[i - 1].odoKm
                val b = rows[i].odoKm
                if (a != null && b != null) b - a else null
            } else null
            speed >= MOVING_KMH || (step != null && step >= ODO_STEP_KM && step < 5.0)
        }
        var i = 0
        while (i < n) {
            if (!moving[i]) { i++; continue }
            val first = i
            var last = i
            var idle = 0
            var j = i + 1
            while (j < n) {
                if (moving[j]) { last = j; idle = 0 } else if (++idle > STOP_ROWS) break
                j++
            }
            val from = maxOf(0, first - 1)
            val to = minOf(n - 1, last + 1)
            val count = (first..last).count { moving[it] }
            val ride = buildRide(rows.subList(from, to + 1), count, log)
            if (count >= 2 || (ride.distanceKm ?: 0.0) >= 0.2) out.add(ride)
            i = last + 1
        }
    }

    private fun buildRide(rows: List<Telemetry>, movingRows: Int, log: ParsedLog): Ride {
        val start = rows.first().epoch
        val end = rows.last().epoch
        val odoA = rows.firstNotNullOfOrNull { it.odoKm }
        val odoB = rows.lastOrNull { it.odoKm != null }?.odoKm
        val distance = if (odoA != null && odoB != null && odoB - odoA in 0.0..300.0) odoB - odoA else null
        val duration = (end - start).coerceAtLeast(ROW_SECONDS)
        val boosts = rows.mapNotNull { it.boost }
        return Ride(
            startEpoch = start,
            endEpoch = end,
            rows = rows,
            movingRows = movingRows,
            distanceKm = distance,
            maxSpeedKmh = rows.mapNotNull { it.speedKmh }.maxOrNull(),
            avgSpeedKmh = distance?.let { it / (duration / 3600.0) },
            socStart = rows.firstNotNullOfOrNull { soc(it) },
            socEnd = rows.lastOrNull { soc(it) != null }?.let { soc(it) },
            maxMotorTempC = rows.mapNotNull { motorTemp(it) }.maxOrNull(),
            maxDriverTempC = rows.mapNotNull { it.driverTempC }.maxOrNull(),
            maxBatTempC = rows.mapNotNull { it.batTempC }.maxOrNull(),
            minBatTempC = rows.mapNotNull { it.batTempC }.minOrNull(),
            maxMotorCurrentA = rows.mapNotNull { it.motorCurrent }.maxOrNull(),
            boostShare = if (boosts.isEmpty()) null else boosts.count { it } / boosts.size.toDouble(),
            limitBefore = log.derate.lastOrNull { it.epoch <= start },
            limits = log.derate.filter { it.epoch in (start + 1)..end },
            shifter = log.shifter.filter { it.epoch in (start - ROW_SECONDS)..(end + ROW_SECONDS) },
            motorErrors = log.motorErrors.filter { it.epoch in (start - ROW_SECONDS)..(end + ROW_SECONDS) },
        )
    }

    // --- Wake timeline -----------------------------------------------------------------------------

    enum class WakeSource { NONE, RTC, BUTTON_1, BUTTON_2, BLE, MEMS, CHARGER, WHEEL, KEY_IN, KICKLOCK, COLD_BOOT, COUNTER, OTHER }

    data class Wake(
        val epoch: Long,
        val source: WakeSource,
        /** The firmware's own token ("WAKE_SRC_MEMS"), shown beside the label so it can be checked. */
        val token: String,
        val counter: Int? = null,
        val counterOf: Int? = null,
        val type: Int? = null,
        /** The ride that started within [WAKE_RIDE_WINDOW_SECONDS] after this wake, if any. */
        val ride: Ride? = null,
    ) {
        /** Shaken or wheel turned, and nobody rode: the "was my bike moved?" moment. */
        val movedWithoutRide: Boolean
            get() = (source == WakeSource.MEMS || source == WakeSource.WHEEL) && ride == null
    }

    /** "BleDeepSleep soc S wakeups W" (1.9.x): low battery after many wakes sends Bluetooth into a deeper sleep. */
    data class DeepSleep(val epoch: Long, val soc: Int?, val wakeups: Int?)

    data class WakeTimeline(val wakes: List<Wake>, val deepSleeps: List<DeepSleep>)

    private val EPOCH_PREFIX = Regex("""^(\d{1,10}) """)
    private val WAKE_REASON = Regex("""Wake Reason:\s*(\S+)""")
    private val WAKE_SHORT = Regex("""^Wake:\s*(\S+)""")
    private val WAKE_COUNTER = Regex("""Wake counter (\d+) of (\d+), type (\d+)""")
    private val DEEP_SLEEP = Regex("""BleDeepSleep soc (-?\d+) wakeups (\d+)""")

    private sealed interface WakeHit
    private data class ReasonHit(val source: WakeSource, val token: String) : WakeHit
    private data class CounterHit(val n: Int, val of: Int, val type: Int, val token: String) : WakeHit
    private data class SleepHit(val soc: Int?, val wakeups: Int?) : WakeHit

    fun sourceOf(token: String): WakeSource {
        val t = token.uppercase().removePrefix("WAKE_").removePrefix("SRC_")
        return when (t) {
            "NONE" -> WakeSource.NONE
            "RTC" -> WakeSource.RTC
            "BUTTON_1" -> WakeSource.BUTTON_1
            "BUTTON_2" -> WakeSource.BUTTON_2
            "BLE" -> WakeSource.BLE
            "MEMS" -> WakeSource.MEMS
            "CHG" -> WakeSource.CHARGER
            "WHEEL" -> WakeSource.WHEEL
            "KEY_IN" -> WakeSource.KEY_IN
            "KICKLOCK" -> WakeSource.KICKLOCK
            else -> WakeSource.OTHER
        }
    }

    private fun classify(msg: String): WakeHit? {
        WAKE_REASON.find(msg)?.let { return ReasonHit(sourceOf(it.groupValues[1]), it.groupValues[1]) }
        if (msg.startsWith("Cold boot clear log")) return ReasonHit(WakeSource.COLD_BOOT, "Cold boot")
        WAKE_COUNTER.find(msg)?.let {
            return CounterHit(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), "Wake counter")
        }
        DEEP_SLEEP.find(msg)?.let { return SleepHit(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) }
        WAKE_SHORT.find(msg)?.let { return ReasonHit(sourceOf(it.groupValues[1]), it.groupValues[1]) }
        return null
    }

    /**
     * Wake lines from the raw text. The boot-time "Wake Reason: …" is printed without the timestamp
     * prefix (decomp log.c:316, no log_print_timestamp_prefix), so it takes the time of the next
     * stamped line (the boot continues within a second); at the end of the text, the previous one.
     */
    fun wakeTimeline(text: String, rides: List<Ride>): WakeTimeline {
        val hits = ArrayList<Pair<Long, WakeHit>>()
        val pending = ArrayList<WakeHit>()
        var lastEpoch: Long? = null
        for (raw in text.split('\n')) {
            val line = raw.trim('\r', ' ', '\u0000')
            if (line.isEmpty()) continue
            val m = EPOCH_PREFIX.find(line)
            val epoch = m?.groupValues?.get(1)?.toLongOrNull()
            val msg = if (m != null) line.substring(m.range.last + 1) else line
            if (epoch != null) {
                pending.forEach { hits.add(epoch to it) }
                pending.clear()
                lastEpoch = epoch
            }
            val hit = classify(msg) ?: continue
            if (epoch != null) hits.add(epoch to hit) else pending.add(hit)
        }
        lastEpoch?.let { e -> pending.forEach { hits.add(e to it) } }

        val wakes = ArrayList<Wake>()
        val sleeps = ArrayList<DeepSleep>()
        for ((epoch, hit) in hits) {
            when (hit) {
                is ReasonHit -> wakes.add(Wake(epoch, hit.source, hit.token))
                is CounterHit -> {
                    // 1.9.x prints the counter in the main loop shortly after the boot reason: attach it.
                    val prev = wakes.lastOrNull()
                    if (prev != null && prev.counter == null && prev.source != WakeSource.COUNTER && epoch - prev.epoch in 0..90) {
                        wakes[wakes.lastIndex] = prev.copy(counter = hit.n, counterOf = hit.of, type = hit.type)
                    } else {
                        wakes.add(Wake(epoch, WakeSource.COUNTER, hit.token, hit.n, hit.of, hit.type))
                    }
                }
                is SleepHit -> sleeps.add(DeepSleep(epoch, hit.soc, hit.wakeups))
            }
        }
        val withRides = wakes.map { w ->
            w.copy(ride = rides.firstOrNull { it.startEpoch in (w.epoch - 60)..(w.epoch + WAKE_RIDE_WINDOW_SECONDS) })
        }
        return WakeTimeline(withRides, sleeps)
    }

    // --- Shifter health ----------------------------------------------------------------------------

    enum class Verdict { OK, ATTENTION, PROBLEM }

    data class ShiftEntry(val event: ShifterEvent, val speedKmh: Double?)

    data class ShifterHealth(
        /** Oldest first. */
        val entries: List<ShiftEntry>,
        val retries: Int,
        val positionErrors: Int,
        val shifterOff: Int,
        val zeroCal: Int,
        /** Retries per requested gear, index 0..3 = gear 1..4. */
        val retriesByGear: List<Int>,
        val rideSeconds: Long,
    ) {
        val verdict: Verdict
            get() = when {
                positionErrors > 0 -> Verdict.PROBLEM
                retries > 0 -> Verdict.ATTENTION
                else -> Verdict.OK
            }

        /** Retries per riding hour; null below 10 minutes of riding (too little to say). */
        val retriesPerHour: Double? get() = if (rideSeconds >= 600) retries / (rideSeconds / 3600.0) else null
    }

    fun shifterHealth(log: ParsedLog, rides: List<Ride>): ShifterHealth {
        val rows = dedupe(log.validTelemetry).sortedBy { it.epoch }
        val epochs = rows.map { it.epoch }
        fun speedNear(epoch: Long): Double? {
            if (epochs.isEmpty()) return null
            var i = epochs.binarySearch(epoch)
            if (i < 0) i = -i - 1
            val best = listOfNotNull(rows.getOrNull(i - 1), rows.getOrNull(i)).minByOrNull { abs(it.epoch - epoch) }
            return best?.takeIf { abs(it.epoch - epoch) <= SPEED_MATCH_SECONDS }?.speedKmh
        }
        val byGear = IntArray(4)
        log.shifter.filter { it.kind == ShifterEventKind.GEAR_RETRY }.forEach { e ->
            e.gear?.takeIf { it in 1..4 }?.let { byGear[it - 1]++ }
        }
        return ShifterHealth(
            entries = log.shifter.map { ShiftEntry(it, speedNear(it.epoch)) },
            retries = log.shifter.count { it.kind == ShifterEventKind.GEAR_RETRY },
            positionErrors = log.shifter.count { it.kind == ShifterEventKind.POSITION_ERROR },
            shifterOff = log.shifter.count { it.kind == ShifterEventKind.SHIFTER_OFF },
            zeroCal = log.shifter.count { it.kind == ShifterEventKind.ZERO_CAL },
            retriesByGear = byGear.toList(),
            rideSeconds = rides.sumOf { it.durationSeconds },
        )
    }

    // --- Derate explainer --------------------------------------------------------------------------

    enum class DerateCause { FULL, EMPTY, BATTERY, COLD, HEAT, BOTH, OUTSIDE }

    /** Battery-temperature rows of the firmware table (representative value per row). */
    val DERATE_ROW_TEMPS = listOf(-5.0, 3.0, 20.0, 65.0)
    /** Upper SoC bound of each column (the last column is "over 22 %"). */
    val DERATE_COL_SOC = listOf(5, 13, 18, 22, 100)

    data class DerateExplanation(
        val socPercent: Int,
        val tempC: Double,
        val limitAmps: Int?,
        val cause: DerateCause,
        /** The next higher SoC that frees more current, and how much (battery-limited cases). */
        val nextSocPercent: Int?,
        val nextLimitAmps: Int?,
        /** The temperature edge of the current row and the limit just beyond it (temperature-limited cases). */
        val tempEdgeC: Double?,
        val tempEdgeLimitAmps: Int?,
        /** Cell of [DERATE_ROW_TEMPS] × [DERATE_COL_SOC] this state falls in. */
        val row: Int,
        val column: Int,
    )

    fun derateRow(tempC: Double): Int {
        val deci = Math.round(tempC * 10)
        return when {
            deci <= 0 -> 0
            deci <= 50 -> 1
            deci <= 600 -> 2
            else -> 3
        }
    }

    fun derateColumn(soc: Int): Int = DERATE_COL_SOC.indexOfFirst { soc <= it }.let { if (it < 0) DERATE_COL_SOC.lastIndex else it }

    /** The firmware's limit for one table cell (faithful: asks [DerateTable] with a value inside the cell). */
    fun derateCell(row: Int, column: Int): Int? = DerateTable.limitAmps(DERATE_COL_SOC[column], DERATE_ROW_TEMPS[row])

    fun explainDerate(socPercent: Int, tempC: Double): DerateExplanation {
        val soc = socPercent.coerceIn(0, 100)
        val limit = DerateTable.limitAmps(soc, tempC)
        val socOnly = DerateTable.limitAmps(soc, 20.0) ?: 30
        val tempOnly = DerateTable.limitAmps(100, tempC) ?: 30
        val cause = when {
            limit == null -> DerateCause.OUTSIDE
            limit == 0 -> DerateCause.EMPTY
            limit >= 30 -> DerateCause.FULL
            socOnly < 30 && tempOnly < 30 -> DerateCause.BOTH
            socOnly < 30 -> DerateCause.BATTERY
            Math.round(tempC * 10) > 600 -> DerateCause.HEAT
            else -> DerateCause.COLD
        }
        var nextSoc: Int? = null
        var nextLimit: Int? = null
        if (limit != null && cause in setOf(DerateCause.EMPTY, DerateCause.BATTERY, DerateCause.BOTH)) {
            for (s in soc + 1..100) {
                val l = DerateTable.limitAmps(s, tempC) ?: continue
                if (l > limit) { nextSoc = s; nextLimit = l; break }
            }
        }
        val deci = Math.round(tempC * 10)
        val edge: Double? = when {
            cause !in setOf(DerateCause.COLD, DerateCause.HEAT, DerateCause.BOTH) -> null
            deci <= 0 -> 0.0
            deci <= 50 -> 5.0
            deci > 600 -> 60.0
            else -> null
        }
        val edgeLimit = edge?.let { e -> DerateTable.limitAmps(soc, if (e >= 60.0) 59.9 else e + 0.1) }
        return DerateExplanation(
            socPercent = soc,
            tempC = tempC,
            limitAmps = limit,
            cause = cause,
            nextSocPercent = nextSoc,
            nextLimitAmps = nextLimit,
            tempEdgeC = edge,
            tempEdgeLimitAmps = edgeLimit,
            row = derateRow(tempC),
            column = derateColumn(soc),
        )
    }

    // --- Log seek guard ----------------------------------------------------------------------------

    /** Epoch of the newest stamped line, in text order (not the maximum: a clock jump must not move it). */
    fun newestEpoch(text: String): Long? {
        var newest: Long? = null
        for (raw in text.split('\n')) {
            val line = raw.trim('\r', ' ', '\u0000')
            if (!BikeLogArchive.isStamped(line)) continue
            newest = line.substring(0, line.indexOf(' ')).toLongOrNull() ?: newest
        }
        return newest
    }

    /**
     * Whether 55C1 = [2, T] may be written. The bike walks its tail forward past every line whose
     * successor is stamped ≤ T and keeps the cursor (decomp bleware log_gatt.c:286). Irreversible, so:
     * the latest download reached the end of the ring, its file is on the phone with identical content,
     * it is at most [SEEK_MAX_AGE_SECONDS] old and from the current connection (no line written since is
     * older than its newest line), and T comes from the bike's clock, never from the phone.
     */
    data class SeekGuard(
        val linkReady: Boolean,
        val complete: Boolean,
        val saved: Boolean,
        val ageSeconds: Long?,
        val sameConnection: Boolean,
        val newestEpoch: Long?,
    ) {
        val fresh: Boolean get() = ageSeconds != null && ageSeconds in 0..SEEK_MAX_AGE_SECONDS && sameConnection
        val clockSet: Boolean get() = newestEpoch != null && newestEpoch >= PLAUSIBLE_EPOCH
        val allowed: Boolean get() = linkReady && complete && saved && fresh && clockSet

        /** One second before the newest saved line: that line and everything in its second stay on the bike. */
        val target: Long? get() = if (allowed) newestEpoch!! - 1 else null
    }

    // --- Everything at once ------------------------------------------------------------------------

    data class Insights(
        val log: ParsedLog,
        /** Oldest first. */
        val rides: List<Ride>,
        val wakes: WakeTimeline,
        val shifter: ShifterHealth,
        val lines: Int,
        val firstEpoch: Long?,
        val lastEpoch: Long?,
    ) {
        /** The newest plausible battery temperature in the log, with its time. */
        val latestBatTemp: Pair<Long, Double>?
            get() = log.validTelemetry.lastOrNull { it.batTempC != null }?.let { it.epoch to it.batTempC!! }

        /** The newest main-battery SoC in the log, with its time. */
        val latestSoc: Pair<Long, Int>?
            get() = log.validTelemetry.lastOrNull { soc(it) != null }?.let { it.epoch to soc(it)!! }

        val totalDistanceKm: Double get() = rides.sumOf { it.distanceKm ?: 0.0 }
    }

    fun analyze(text: String): Insights {
        val log = BikeLogParser.parse(text)
        val rides = rides(log)
        val stamped = log.validTelemetry.map { it.epoch } + log.derate.map { it.epoch }
        return Insights(
            log = log,
            rides = rides,
            wakes = wakeTimeline(text, rides),
            shifter = shifterHealth(log, rides),
            lines = text.count { it == '\n' } + if (text.isNotEmpty() && !text.endsWith('\n')) 1 else 0,
            firstEpoch = stamped.minOrNull(),
            lastEpoch = stamped.maxOrNull(),
        )
    }

    /** Rounds a share 0..1 to whole percent. */
    fun percent(share: Double): Int = (share * 100).roundToInt()
}
