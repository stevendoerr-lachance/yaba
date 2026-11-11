package dev.steven.s3control.ble

import android.content.Context
import androidx.annotation.StringRes
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * E-shifter configuration (MOVEMENT service), read from the mainware 1.07.06 decompilation. There is no
 * 1.08/1.09 source for these handlers, so on any firmware YABA checks the bike's own answers before every
 * write ([ShiftCheck]) and checks again on the next connection that a write was kept ([ShiftJournal]).
 * - 5538 mode: 0 = automatic, 1 = manual; anything >= 2 is refused (ble.c case 0x5538).
 * - 5537 points: 6 bytes in km/h — up 1→2, 2→3, 3→4, then down 2→1, 3→2, 4→3. Stored ×10 in the
 *   current region's slot and persisted to flash with the same dual-bank write as a region change.
 *   The firmware echoes the raw bytes before storing them and checks neither length, range nor order,
 *   and it ignores whether the flash write worked: a matching read only proves the value reached RAM.
 * - 5536 gear: the commanded gear. YABA never writes it (see docs: it skips every firmware guard).
 * Auto logic (shifter.c state 9): speed in 0.1 km/h, strict comparisons, one upshift per pass,
 * downshifts may skip gears, gear 2's up check runs last and wins, 600 ms pause after every shift.
 * A mode or points change applies on the state machine's next pass, riding or not.
 */

enum class ShiftMode(val code: Int, @StringRes val labelRes: Int) {
    AUTO(0, R.string.shift_mode_auto), MANUAL(1, R.string.shift_mode_manual);

    val label: String get() = I18n.get(labelRes)

    companion object { fun from(code: Int?) = entries.firstOrNull { it.code == code } }
}

/** One of the six shift points. The ordinal is the byte index on the wire. */
enum class Handle(val up: Boolean, val from: Int, val to: Int) {
    UP12(true, 1, 2), UP23(true, 2, 3), UP34(true, 3, 4),
    DOWN21(false, 2, 1), DOWN32(false, 3, 2), DOWN43(false, 4, 3);

    /** "Shift up from 1 to 2" — also the handle's name for TalkBack and "Limited by". */
    val label: String
        get() = I18n.get(if (up) R.string.shift_handle_up else R.string.shift_handle_down, from, to)
}

/** E_SHIFTER_GEAR byte 0: 1..4 is a gear; 0 (or anything else) means unknown. */
fun decodeGear(b0: Int?): Int? = b0?.takeIf { it in 1..4 }

/** All six points in whole km/h, as the bike stores them for the current region. */
data class ShiftPoints(
    val up12: Int,
    val up23: Int,
    val up34: Int,
    val down21: Int,
    val down32: Int,
    val down43: Int,
) {
    operator fun get(h: Handle): Int = when (h) {
        Handle.UP12 -> up12
        Handle.UP23 -> up23
        Handle.UP34 -> up34
        Handle.DOWN21 -> down21
        Handle.DOWN32 -> down32
        Handle.DOWN43 -> down43
    }

    fun with(h: Handle, kmh: Int): ShiftPoints = when (h) {
        Handle.UP12 -> copy(up12 = kmh)
        Handle.UP23 -> copy(up23 = kmh)
        Handle.UP34 -> copy(up34 = kmh)
        Handle.DOWN21 -> copy(down21 = kmh)
        Handle.DOWN32 -> copy(down32 = kmh)
        Handle.DOWN43 -> copy(down43 = kmh)
    }

    fun toList(): List<Int> = Handle.entries.map { get(it) }

    /** 6 bytes, km/h, unsigned. sealWrite adds the nonce and pads to 16; no trailing 1. */
    fun encode(): ByteArray = ByteArray(6) { toList()[it].coerceIn(0, 255).toByte() }

    /** "up 10, 19, 24, down 8, 17, 22 km/h" in the app language. */
    fun short(): String = I18n.get(R.string.shift_points_short, up12, up23, up34, down21, down32, down43)

    companion object {
        /**
         * First 6 bytes of a decrypted value (reads come back zero-padded to 16), unsigned. Null only
         * when shorter than 6. Not validated: invalid bike values must still show — six zeros included,
         * the worst set a bike can hold (it shifts up to gear 4 as soon as it rolls and starts off in 4).
         */
        fun decode(v: ByteArray): ShiftPoints? {
            if (v.size < 6) return null
            val b = IntArray(6) { v[it].toInt() and 0xFF }
            return ShiftPoints(b[0], b[1], b[2], b[3], b[4], b[5])
        }

        val ZERO = ShiftPoints(0, 0, 0, 0, 0, 0)

        fun fromList(values: List<Int>): ShiftPoints? =
            if (values.size != 6) null else ShiftPoints(values[0], values[1], values[2], values[3], values[4], values[5])
    }
}

/**
 * What YABA lets anyone write. With these rules the firmware's auto logic is monotone at any
 * constant speed, so it cannot hunt (proven by ShiftingTest against a port of shifter.c).
 */
object ShiftRules {
    /** A down point sits at least this far below its up point. */
    const val GAP = 2
    /** Points of the same direction sit at least this far apart. */
    const val SPACING = 3
    const val MIN_DOWN21 = 5
    const val MIN_UP12 = 7
    const val MAX_UP12 = 20
    const val MAX_KMH = 40

    /** Each rule names the points it ties together: the first is the one that sits wrong. */
    enum class Rule(vararg val handles: Handle) {
        GAP_12(Handle.DOWN21, Handle.UP12), GAP_23(Handle.DOWN32, Handle.UP23), GAP_34(Handle.DOWN43, Handle.UP34),
        UP_SPACING_LOW(Handle.UP23, Handle.UP12), UP_SPACING_HIGH(Handle.UP34, Handle.UP23),
        DOWN_SPACING_LOW(Handle.DOWN32, Handle.DOWN21), DOWN_SPACING_HIGH(Handle.DOWN43, Handle.DOWN32),
        DOWN21_MIN(Handle.DOWN21), UP12_RANGE(Handle.UP12), UP34_MAX(Handle.UP34),
    }

    /**
     * Where a broken rule sits on the axis (km/h): between its two points, or from a lone point to the
     * limit it crosses. For drawing; never empty (from <= to).
     */
    fun span(rule: Rule, p: ShiftPoints): Pair<Int, Int> {
        val a = p[rule.handles[0]]
        val b = when (rule) {
            Rule.DOWN21_MIN -> MIN_DOWN21
            Rule.UP12_RANGE -> if (a < MIN_UP12) MIN_UP12 else MAX_UP12
            Rule.UP34_MAX -> MAX_KMH
            else -> p[rule.handles[1]]
        }
        return minOf(a, b) to maxOf(a, b)
    }

    fun violations(p: ShiftPoints): Set<Rule> = buildSet {
        if (p.down21 > p.up12 - GAP) add(Rule.GAP_12)
        if (p.down32 > p.up23 - GAP) add(Rule.GAP_23)
        if (p.down43 > p.up34 - GAP) add(Rule.GAP_34)
        if (p.up23 < p.up12 + SPACING) add(Rule.UP_SPACING_LOW)
        if (p.up34 < p.up23 + SPACING) add(Rule.UP_SPACING_HIGH)
        if (p.down32 < p.down21 + SPACING) add(Rule.DOWN_SPACING_LOW)
        if (p.down43 < p.down32 + SPACING) add(Rule.DOWN_SPACING_HIGH)
        if (p.down21 < MIN_DOWN21) add(Rule.DOWN21_MIN)
        if (p.up12 !in MIN_UP12..MAX_UP12) add(Rule.UP12_RANGE)
        if (p.up34 > MAX_KMH) add(Rule.UP34_MAX)
    }

    fun isValid(p: ShiftPoints): Boolean = violations(p).isEmpty()

    /** One bound on a handle: its value and the handle that sets it (null = an absolute limit). */
    private class Bound(val kmh: Int, val by: Handle?)

    private fun lower(h: Handle, p: ShiftPoints): List<Bound> = when (h) {
        Handle.UP12 -> listOf(Bound(MIN_UP12, null), Bound(p.down21 + GAP, Handle.DOWN21))
        Handle.UP23 -> listOf(Bound(p.up12 + SPACING, Handle.UP12), Bound(p.down32 + GAP, Handle.DOWN32))
        Handle.UP34 -> listOf(Bound(p.up23 + SPACING, Handle.UP23), Bound(p.down43 + GAP, Handle.DOWN43))
        Handle.DOWN21 -> listOf(Bound(MIN_DOWN21, null))
        Handle.DOWN32 -> listOf(Bound(p.down21 + SPACING, Handle.DOWN21))
        Handle.DOWN43 -> listOf(Bound(p.down32 + SPACING, Handle.DOWN32))
    }

    private fun upper(h: Handle, p: ShiftPoints): List<Bound> = when (h) {
        Handle.UP12 -> listOf(Bound(MAX_UP12, null), Bound(p.up23 - SPACING, Handle.UP23))
        Handle.UP23 -> listOf(Bound(MAX_KMH - SPACING, null), Bound(p.up34 - SPACING, Handle.UP34))
        Handle.UP34 -> listOf(Bound(MAX_KMH, null))
        Handle.DOWN21 -> listOf(Bound(p.up12 - GAP, Handle.UP12), Bound(p.down32 - SPACING, Handle.DOWN32))
        Handle.DOWN32 -> listOf(Bound(p.up23 - GAP, Handle.UP23), Bound(p.down43 - SPACING, Handle.DOWN43))
        Handle.DOWN43 -> listOf(Bound(p.up34 - GAP, Handle.UP34))
    }

    /** Where [h] may go while the other five stay put. Empty when the others already conflict. */
    fun range(h: Handle, p: ShiftPoints): IntRange =
        lower(h, p).maxOf { it.kmh }..upper(h, p).minOf { it.kmh }

    /** [p] with [h] moved as close to [kmh] as the rules allow; unchanged if [h] has no room. */
    fun clamp(h: Handle, p: ShiftPoints, kmh: Int): ShiftPoints {
        val r = range(h, p)
        if (r.isEmpty()) return p
        return p.with(h, kmh.coerceIn(r.first, r.last))
    }

    /**
     * The handle whose position sets the bound [h] runs into when moved [upward] (or down).
     * Null when that bound is an absolute limit (an absolute limit wins a tie: it holds anyway).
     */
    fun blocker(h: Handle, p: ShiftPoints, upward: Boolean): Handle? {
        val bounds = if (upward) upper(h, p) else lower(h, p)
        val limit = if (upward) bounds.minOf { it.kmh } else bounds.maxOf { it.kmh }
        val hit = bounds.filter { it.kmh == limit }
        return if (hit.any { it.by == null }) null else hit.first().by
    }
}

enum class ShiftProfile(@StringRes val labelRes: Int, @StringRes val reasonRes: Int) {
    STANDARD(R.string.shift_profile_standard, R.string.shift_reason_standard),
    FLAT(R.string.shift_profile_flat, R.string.shift_reason_flat),
    HILLY(R.string.shift_profile_hilly, R.string.shift_reason_hilly);

    val label: String get() = I18n.get(labelRes)
}

/**
 * The bike stores no profile id: a profile is recognised by its six values. Standard is the
 * factory set of the region slot in use (slot 2 = SpeedLimit.JP carries 28/25 for the top gear).
 * Flach and Hügelig are YABA proposals, the same for every region, not yet ride-tested.
 */
object ShiftProfiles {
    val FACTORY = ShiftPoints(10, 19, 24, 8, 17, 22)
    val FACTORY_SLOT2 = ShiftPoints(10, 19, 28, 8, 17, 25)
    val FLAT = ShiftPoints(10, 17, 27, 7, 14, 24)
    val HILLY = ShiftPoints(12, 21, 27, 9, 18, 24)

    fun points(p: ShiftProfile, region: SpeedLimit?): ShiftPoints = when (p) {
        ShiftProfile.STANDARD -> if (region == SpeedLimit.JP) FACTORY_SLOT2 else FACTORY
        ShiftProfile.FLAT -> FLAT
        ShiftProfile.HILLY -> HILLY
    }

    /** Exact match only; anything else is "Eigene" (null). */
    fun match(points: ShiftPoints, region: SpeedLimit?): ShiftProfile? =
        ShiftProfile.entries.firstOrNull { points(it, region) == points }
}

// --- Plausibility of what the bike reports --------------------------------------------------------

/**
 * Whether the bike's shifting values have the layout YABA was built for (checked against 1.07.06; the
 * owner's bike runs 1.9.3, for which no source exists). Run on every connect and before every write:
 * mode 0 or 1, six points that read as km/h. Six zeros pass on purpose: that set is broken, and
 * writing Standard over it is exactly what should stay possible.
 */
enum class ShiftCheck {
    OK, UNREADABLE, ODD_MODE, ODD_POINTS;

    companion object {
        /** No S3 assists beyond 37 km/h; a shift point above this means the bytes mean something else. */
        const val PLAUSIBLE_MAX_KMH = 60

        fun of(mode: ByteArray?, points: ByteArray?): ShiftCheck = when {
            mode == null || mode.isEmpty() || points == null || points.size < 6 -> UNREADABLE
            ShiftMode.from(mode[0].toInt() and 0xFF) == null -> ODD_MODE
            (0 until 6).any { (points[it].toInt() and 0xFF) > PLAUSIBLE_MAX_KMH } -> ODD_POINTS
            else -> OK
        }

        /** "5538 = 2 · 5537 = 100 190 240 80 170 220": the raw answer, the same in every language. */
        fun detail(mode: ByteArray?, points: ByteArray?): String {
            val m = mode?.firstOrNull()?.let { "${it.toInt() and 0xFF}" } ?: "–"
            val p = points?.takeIf { it.size >= 6 }?.let { b -> (0 until 6).joinToString(" ") { "${b[it].toInt() and 0xFF}" } } ?: "–"
            return "5538 = $m · 5537 = $p"
        }
    }
}

/**
 * The shifting characteristics into [st]; null for any other characteristic. 5537 belongs to the
 * region slot in use, so a decoded set is tagged with the current region — a short read changes
 * nothing, so an old set never gets a new tag.
 */
fun decodeShifting(st: BikeState, ch: Gatt.Char, v: ByteArray): BikeState? = when (ch) {
    Gatt.E_SHIFTER_GEAR -> st.copy(gear = decodeGear(v.u8()))
    Gatt.E_SHIFTER_MODE -> st.copy(shiftMode = ShiftMode.from(v.u8()) ?: st.shiftMode)
    Gatt.E_SHIFT_POINTS -> ShiftPoints.decode(v)?.let { st.copy(shiftPoints = it, shiftPointsRegion = st.speedLimit) } ?: st
    else -> null
}

// --- Did the bike keep it? ------------------------------------------------------------------------

/** What YABA last wrote to one bike and has not yet seen again on a later connection. */
data class ShiftWritten(val mode: ShiftMode? = null, val region: SpeedLimit? = null, val points: ShiftPoints? = null) {
    val isEmpty: Boolean get() = mode == null && points == null
}

/** "MAC|m=1|r=EU|p=10,19,24,8,17,22;MAC2|m=0". MACs contain ':' only; the separators are ';', '|', '=' and ','. */
fun encodeShiftJournal(map: Map<String, ShiftWritten>): String =
    map.entries.filter { !it.value.isEmpty }.joinToString(";") { (mac, w) ->
        buildList {
            add(mac)
            w.mode?.let { add("m=${it.code}") }
            if (w.points != null && w.region != null) {
                add("r=${w.region.name}")
                add("p=" + w.points.toList().joinToString(","))
            }
        }.joinToString("|")
    }

/** Inverse of [encodeShiftJournal]; skips anything malformed. */
fun decodeShiftJournal(text: String?): Map<String, ShiftWritten> {
    if (text.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, ShiftWritten>()
    for (entry in text.split(';')) {
        val parts = entry.split('|')
        val mac = parts.first().trim()
        if (mac.isEmpty() || parts.size < 2) continue
        var w = ShiftWritten()
        var region: SpeedLimit? = null
        var points: ShiftPoints? = null
        for (part in parts.drop(1)) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val value = part.substring(eq + 1)
            when (part.substring(0, eq)) {
                "m" -> w = w.copy(mode = ShiftMode.from(value.toIntOrNull()))
                "r" -> region = SpeedLimit.entries.firstOrNull { it.name == value }
                "p" -> points = value.split(',').map { it.trim().toIntOrNull() }
                    .takeIf { v -> v.all { it != null && it in 0..255 } }
                    ?.let { ShiftPoints.fromList(it.filterNotNull()) }
            }
        }
        if (region != null && points != null) w = w.copy(region = region, points = points)
        if (!w.isEmpty) out[mac] = w
    }
    return out
}

/** Per value: true = the bike still reports what YABA wrote, false = it does not, null = this read cannot tell. */
data class ShiftKept(val mode: Boolean?, val points: Boolean?)

/**
 * Compares a fresh read on connect with what YABA wrote before. Points can only be judged in the
 * region slot they were written to; another region (or no read) leaves them undecided.
 */
fun checkKept(written: ShiftWritten, mode: ShiftMode?, region: SpeedLimit?, points: ShiftPoints?): ShiftKept = ShiftKept(
    mode = written.mode?.let { w -> mode?.let { it == w } },
    points = written.points?.let { w ->
        if (points == null || region == null || region != written.region) null else points == w
    },
)

/**
 * The last mode and points YABA wrote to each bike (normalised MAC), kept across app restarts until a
 * later connection reads them back. A match there is the closest YABA gets to proof that the flash
 * write worked; the firmware itself never reports a failed one.
 */
class ShiftJournal private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shifting", Context.MODE_PRIVATE)
    private var map = decodeShiftJournal(prefs.getString(KEY, null))

    @Synchronized fun get(mac: String): ShiftWritten? = map[mac]

    @Synchronized fun recordMode(mac: String, mode: ShiftMode) =
        put(mac, (map[mac] ?: ShiftWritten()).copy(mode = mode))

    @Synchronized fun recordPoints(mac: String, region: SpeedLimit, points: ShiftPoints) =
        put(mac, (map[mac] ?: ShiftWritten()).copy(region = region, points = points))

    /** Drops what a connection has decided (kept or not), keeps what it could not tell. */
    @Synchronized fun settle(mac: String, kept: ShiftKept) {
        val w = map[mac] ?: return
        put(mac, w.copy(
            mode = if (kept.mode != null) null else w.mode,
            region = if (kept.points != null) null else w.region,
            points = if (kept.points != null) null else w.points,
        ))
    }

    private fun put(mac: String, w: ShiftWritten) {
        map = if (w.isEmpty) map - mac else map + (mac to w)
        prefs.edit().putString(KEY, encodeShiftJournal(map)).apply()
    }

    companion object {
        private const val KEY = "lastWrite"
        @Volatile private var instance: ShiftJournal? = null

        fun get(context: Context): ShiftJournal =
            instance ?: synchronized(this) { instance ?: ShiftJournal(context).also { instance = it } }
    }
}

// --- The rider's own set, remembered per bike ---------------------------------------------------

/** "MAC=10,19,24,8,17,22;MAC2=…". MACs may contain ':'; the separators are '=', ';' and ','. */
fun encodeCustomShift(map: Map<String, ShiftPoints>): String =
    map.entries.joinToString(";") { (mac, p) -> "$mac=" + p.toList().joinToString(",") }

/** Inverse of [encodeCustomShift]; skips anything malformed instead of failing. */
fun decodeCustomShift(text: String?): Map<String, ShiftPoints> {
    if (text.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, ShiftPoints>()
    for (entry in text.split(';')) {
        val eq = entry.lastIndexOf('=')
        if (eq <= 0) continue
        val mac = entry.substring(0, eq).trim()
        val values = entry.substring(eq + 1).split(',').map { it.trim().toIntOrNull() }
        if (mac.isEmpty() || values.any { it == null || it !in 0..255 }) continue
        ShiftPoints.fromList(values.filterNotNull())?.let { out[mac] = it }
    }
    return out
}

/**
 * The last custom set each bike took ("Eigene"), keyed by normalised MAC, so the editor can
 * start from it after a profile was chosen in between. Only sets the bike confirmed are saved.
 */
class CustomShiftStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shifting", Context.MODE_PRIVATE)
    private val _sets = MutableStateFlow(decodeCustomShift(prefs.getString(KEY, null)))
    val sets: StateFlow<Map<String, ShiftPoints>> = _sets.asStateFlow()

    fun save(mac: String, points: ShiftPoints) {
        _sets.update { it + (mac to points) }
        prefs.edit().putString(KEY, encodeCustomShift(_sets.value)).apply()
    }

    companion object {
        private const val KEY = "customShift"
        @Volatile private var instance: CustomShiftStore? = null

        fun get(context: Context): CustomShiftStore =
            instance ?: synchronized(this) { instance ?: CustomShiftStore(context).also { instance = it } }
    }
}
