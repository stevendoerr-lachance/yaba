package dev.steven.s3control.ui.s2

import androidx.annotation.StringRes
import dev.steven.s3control.R
import dev.steven.s3control.ble.S2Faults
import dev.steven.s3control.ble.S2Frame

/*
 * The S2 drive diagnosis without Android: what the error catcher keeps, how the wiggle test spots a dropped speed
 * signal, and which cable or part an S2 error code points to. JVM-tested (S2DriveLogicTest).
 *
 * The S2 status block carries no motor current, no temperature and no raw hall data. Everything here infers from
 * the speed byte and the 5-bit error code, block by block.
 */

/**
 * The error catcher: every decoded status block goes through [offer]; each block with a non-zero error code lands
 * in [hits], a single one too. Blocks in a row with the same code from the same bike (no other code, no 0, at most
 * [EPISODE_GAP_MS] apart) are one [Hit], so a code the bike holds for minutes is one line, and a code that flickers
 * up for one block between two others is a line of its own. Implausible blocks (a wrong key decrypts to noise) are
 * counted, not caught. It only sees the blocks YABA receives: a code shorter than the read interval is caught only
 * when the bike pushes that block itself (the screen says which happens).
 */
class S2ErrorCatcher(private val capacity: Int = CAPACITY) {

    /** One error episode, with the bike's values at its first block. */
    data class Hit(
        val code: Int,
        val firstAtMillis: Long,
        val lastAtMillis: Long,
        val blocks: Int,
        /** Speed byte at the first block, km/h (or mph under imperial, open question A6.12). */
        val kmh: Int,
        val topKmh: Int,
        val motorBattery: Int,
        val moduleBattery: Int,
        /** The first block of the episode, hex. */
        val frameHex: String,
        /** The bike it came from (normalised MAC); null in lists stored before the column existed. */
        val mac: String? = null,
    ) {
        val durationMillis: Long get() = lastAtMillis - firstAtMillis
    }

    private val list = ArrayList<Hit>()
    private var open = false

    /** Episodes, oldest first. */
    val hits: List<Hit> get() = list.toList()

    /** Plausible blocks checked since [sinceMillis]. */
    var blocks: Long = 0
        private set

    /** Blocks skipped because their values cannot come from a real S2. */
    var implausible: Int = 0
        private set

    /** Phone time of the first block checked (null before it). */
    var sinceMillis: Long? = null
        private set

    /** Takes one block from the bike [mac]; true when [hits] changed. */
    fun offer(atMillis: Long, frame: S2Frame, mac: String? = null): Boolean {
        if (!frame.plausible) {
            implausible++
            return false
        }
        blocks++
        if (sinceMillis == null) sinceMillis = atMillis
        val code = frame.errorCode
        if (code == S2Faults.NO_ERROR) {
            open = false
            return false
        }
        val last = list.lastOrNull()
        if (open && last != null && last.code == code && last.mac == mac && atMillis - last.lastAtMillis in 0..EPISODE_GAP_MS) {
            list[list.lastIndex] = last.copy(
                lastAtMillis = atMillis,
                blocks = last.blocks + 1,
                topKmh = maxOf(last.topKmh, frame.speed),
            )
            return true
        }
        list.add(
            Hit(
                code = code,
                firstAtMillis = atMillis,
                lastAtMillis = atMillis,
                blocks = 1,
                kmh = frame.speed,
                topKmh = frame.speed,
                motorBattery = frame.motorBattery,
                moduleBattery = frame.moduleBattery,
                frameHex = frame.hex,
                mac = mac,
            ),
        )
        open = true
        if (list.size > capacity) list.subList(0, list.size - capacity).clear()
        return true
    }

    /** Puts back episodes from storage (oldest first), before any block arrives. */
    fun restore(saved: List<Hit>) {
        list.clear()
        list.addAll(saved.takeLast(capacity))
        open = false
    }

    fun clear() {
        list.clear()
        open = false
        blocks = 0
        implausible = 0
        sinceMillis = null
    }

    /** Drops the episodes [which] matches (one bike's, the debug demo's); the counters stay. */
    fun remove(which: (Hit) -> Boolean) {
        list.removeAll(which)
        open = false
    }

    companion object {
        const val CAPACITY = 200

        /** Blocks of one code further apart than this (a lost link, a parked bike) start a new episode. */
        const val EPISODE_GAP_MS = 15_000L

        /**
         * The stored form: stable English column names, never translated (like the ride log CSV). `mac` came last
         * and later: a line without it (9 fields) still reads, as a hit of no known bike.
         */
        const val CSV_HEADER = "first_at,last_at,code,blocks,kmh,top_kmh,battery,module_battery,frame,mac"

        fun toCsv(h: Hit): String = listOf(
            h.firstAtMillis, h.lastAtMillis, h.code, h.blocks, h.kmh, h.topKmh, h.motorBattery, h.moduleBattery, h.frameHex, h.mac ?: "",
        ).joinToString(",")

        /** One stored line back to a [Hit]; null when it is not one. */
        fun fromCsv(line: String): Hit? {
            val f = line.trim().split(',')
            if (f.size != 9 && f.size != 10) return null
            val n = f.take(8).map { it.toLongOrNull() ?: return null }
            val hex = f[8].takeIf { it.length == S2Frame.SIZE * 2 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
                ?: return null
            if (n[2] !in 1L..31L || n[3] < 1) return null
            val mac = f.getOrNull(9)?.trim()?.takeIf { it.isNotEmpty() }
            return Hit(n[2].toInt(), n[0], n[1], n[3].toInt(), n[4].toInt(), n[5].toInt(), n[6].toInt(), n[7].toInt(), hex, mac)
        }
    }
}

/**
 * The catcher's episodes of one code: how often it came, when last, and the single moments (newest first). The list
 * names the likely cause once per code instead of once per moment.
 */
data class HitGroup(val code: Int, val hits: List<S2ErrorCatcher.Hit>) {
    val count: Int get() = hits.size

    /** When the code came up last: the start of its newest episode. */
    val lastAtMillis: Long get() = hits.maxOf { it.firstAtMillis }

    companion object {
        /** Groups [hits] (oldest first, as the catcher keeps them) by code; the code seen most recently comes first. */
        fun of(hits: List<S2ErrorCatcher.Hit>): List<HitGroup> =
            hits.groupBy { it.code }
                .map { (code, list) -> HitGroup(code, list.sortedByDescending { it.firstAtMillis }) }
                .sortedByDescending { it.lastAtMillis }
    }
}

/**
 * The wiggle test: the front wheel spins by hand while the rider flexes the motor cable. A wheel that slows down on
 * its own passes through the speeds in between; a broken signal drops from riding speed to 0 from one block to the
 * next. [offer] says when that happens: the two blocks before both showed at least [spinKmh], the wheel was not
 * being slowed down between them (at most [maxSlowingKmh] less), and each came at most [maxGapMs] after the one
 * before (blocks arrive about every 0.7 s during the test). The threshold sits above the few km/h at which many
 * controllers report 0 anyway, and the steadiness check skips a hand braking the tyre to spin it again.
 */
class WiggleDetector(
    private val spinKmh: Int = SPIN_KMH,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val maxSlowingKmh: Int = MAX_SLOWING_KMH,
) {
    private var lastAt: Long? = null
    private var lastKmh = 0
    private var beforeAt: Long? = null
    private var beforeKmh = 0

    /** One block's speed; returns the speed it fell from when the signal dropped to 0 in one step, else null. */
    fun offer(atMillis: Long, kmh: Int): Int? {
        val prevAt = lastAt
        val from = lastKmh
        val earlierAt = beforeAt
        val earlier = beforeKmh
        beforeAt = prevAt
        beforeKmh = from
        lastAt = atMillis
        lastKmh = kmh
        if (prevAt == null || earlierAt == null || kmh != 0) return null
        if (from < spinKmh || earlier < spinKmh || earlier - from > maxSlowingKmh) return null
        if (atMillis - prevAt !in 0..maxGapMs || prevAt - earlierAt !in 0..maxGapMs) return null
        return from
    }

    fun reset() {
        lastAt = null
        lastKmh = 0
        beforeAt = null
        beforeKmh = 0
    }

    companion object {
        /** The wheel counts as spinning from here (the steps ask for at least 10 km/h). */
        const val SPIN_KMH = 10

        /** About two missed blocks at the test's 0.7 s reading. */
        const val MAX_GAP_MS = 2_500L

        /** More lost between two blocks than this and a hand is braking the wheel. */
        const val MAX_SLOWING_KMH = 3
    }
}

/** Where on the motor cable the rider is flexing (the wiggle test's marks carry it). */
enum class CableSpot(@StringRes val label: Int) {
    FORK(R.string.s2diag_spot_fork),
    HEAD_TUBE(R.string.s2diag_spot_head),
    FRAME(R.string.s2diag_spot_frame),
}

/** A mark of the wiggle test: set by a drop ([fromKmh] not null) or by hand at [kmh]. */
data class WiggleMark(val atMillis: Long, val spot: CableSpot?, val kmh: Int, val fromKmh: Int?) {
    val auto: Boolean get() = fromKmh != null
}

/** What an S2 error code points to on the bike. The codes and their meaning: [S2Faults] (OldMoofKit ErrorCode.swift). */
object S2Parts {
    /** The disputed "motor too fast" pair (plan B4): the S2 test mode logs every changed block, so it can be told apart. */
    val disputed: Set<Int> = setOf(S2Faults.MOTOR_FAST_ALT_CODE, S2Faults.MOTOR_FAST_CODE)

    /** Codes that are not about the drive (modem, the module's own bus); they stay quiet in the list. */
    private val notDrive = setOf(25, 26, 28)

    @StringRes
    fun likely(code: Int): Int = when (code) {
        1 -> R.string.s2diag_part_stall
        2 -> R.string.s2diag_part_voltage_high
        3, 29 -> R.string.s2diag_part_supply
        S2Faults.MOTOR_FAST_ALT_CODE, S2Faults.MOTOR_FAST_CODE -> R.string.s2diag_part_speed_signal
        6 -> R.string.s2diag_part_phase_short
        7, 8 -> R.string.s2diag_part_torque
        9 -> R.string.s2diag_part_heat
        16 -> R.string.s2diag_part_hall_cable
        25 -> R.string.s2diag_part_module
        26, 28 -> R.string.s2diag_part_gsm
        27 -> R.string.s2diag_part_controller_line
        else -> R.string.s2diag_part_unknown
    }

    /** True for a code about the motor, its cables, sensors or supply. */
    fun drive(code: Int): Boolean = code !in notDrive
}
