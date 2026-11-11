package dev.steven.s3control.ui.intro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The opening film as data: every window, every camera key, every cue, and the pure functions of t that the
 * drawing reads. Nothing here touches Android, so the JVM tests can pin the whole schedule down.
 *
 * Times are ms from the start of the film. Lengths are u (world units: the bike is 185 u long). The comedy is
 * mechanical: setup, hit, pause, payoff, and each stomp harder and slower than the last. The return is soft, the call
 * is hard.
 *
 * Every key after the pop is an offset from an anchor ([Film.T_POP], [Film.T_DROP], [Film.T_SILENCE],
 * [Film.T_HOMEFLIGHT], [Film.T_TABLEAU]), never a literal, so moving one beat moves everything behind it.
 */
object Film {
    const val REVEAL_MS = 520

    /** Taps (not the Skip button) are ignored this long, so the opening does not end on the first touch. */
    const val TAP_GRACE_MS = 600

    /** The whole-stage Daylight flip on impact frames, pending a night test on the Find N6. */
    const val FLIP_WHOLE_STAGE = false

    // The ground is drawn from the first frame (the system splash before it is the held breath); the bike rolls in while the
    // hairline is still crossing the window, so no frame of the film is bare asphalt.
    const val T_GROUND_END = 520
    const val T_ROLL_START = 360
    const val T_ROLL_END = 2_140

    /** The open lock has swept into the tube (a tick). */
    const val T_LOCK_IN = 2_660

    // Act I: the boot comes, hovers, stomps the saddle (stomp 1: a wide, flat target) and the bike answers AUW.
    const val T_BOOT_IN = 2_600
    const val T_HOVER = 3_400
    const val T_WIND_1 = 3_400
    const val T_FALL_1 = 3_640
    const val T_S1 = 3_780
    const val HIT_STOP_1 = 100

    /** The matrix is blank for 240 ms after the stomp, then the call. It sweeps out from [T_AUW_OUT] to [T_AUW_GONE]. */
    const val T_AUW = T_S1 + 240
    const val T_AUW_OUT = 4_800
    const val T_AUW_GONE = 4_960

    // Act II: the second stomp (the top tube) and the error. The blink gets faster while the boot hovers and taps its toe.
    const val T_WIND_2 = 4_500
    const val T_FALL_2 = 5_000
    const val T_S2 = 5_100
    const val HIT_STOP_2 = 140

    /** E42 blinks on at [T_E42]; the blink schedule is [BLINK] (ON, OFF, ON, ...), the last ON stays. */
    const val T_E42 = 5_300
    const val T_BLINK_OFF_1 = 5_680
    const val T_BLINK_ON_2 = 5_980
    const val T_BLINK_OFF_2 = 6_300
    const val T_BLINK_ON_3 = 6_520
    const val T_BLINK_OFF_3 = 6_780
    const val T_BLINK_ON_4 = 6_940
    const val T_BLINK_OFF_4 = 7_160
    const val T_STUCK = 7_280

    /**
     * The close-up on the tube while the blink accelerates, and the cut back to the totale (the stuck ON). The totale carries the
     * first blink (680 ms of E42 in the wide shot); the insert starts on the second ON, lasts 1,300 ms and carries all three toe
     * taps and the three fast blinks, so the error is read big and the boot's impatience is the picture.
     */
    const val T_INSERT = T_BLINK_ON_2
    const val T_TOTALE = T_STUCK

    /**
     * The impatient toe: three taps, each one touching the rim of the capsule (the toe tip is on it at these instants), each
     * quicker than the last (540 and 380 ms apart, like the blink). The third is the push-off of the wind-up.
     */
    const val T_TAP_1 = 6_120
    const val T_TAP_2 = 6_660
    const val T_TAP_3 = 7_040

    // Act III: the finishing stomp flattens the bike, the pop.
    const val T_FALL_3 = 7_440
    const val T_S3 = 7_520
    const val HIT_STOP_3 = 220

    /** The bike is squashed over this long (the boot slams down through it in the same 40 ms): the sole never hangs over a flat bike. */
    const val SQUASH_MS = 40
    const val T_POP = T_S3 + HIT_STOP_3
    const val T_HANG = T_POP + 800
    const val T_DROP = T_HANG + 300

    // Dim cuts: the boot's shadow deepens on the blink's ON edges 1, 2 and 4.
    const val T_DIM_1 = T_E42
    const val T_DIM_2 = T_BLINK_ON_2
    const val T_DIM_3 = T_BLINK_ON_4

    // Act IV: silence, light, the dots fly home, the bike rises.
    const val T_SILENCE = T_DROP + 900
    const val T_LIFE_HOP = T_SILENCE + 50
    const val T_LIFE_HOME = T_SILENCE + 320
    const val T_LIGHT_1 = T_SILENCE + 600
    const val T_LIGHT_2 = T_SILENCE + 880
    const val T_LIGHT_3 = T_SILENCE + 1_160
    const val T_HOMEFLIGHT = T_LIGHT_3
    const val T_TABLEAU = T_HOMEFLIGHT + 1_300

    /** The ending is decided once, here: a Welcome tube to land on (handoff) or none (lockup). */
    const val T_DECIDE = T_TABLEAU - 60

    // Act borders.
    const val ACT_II = 2_600
    const val ACT_III = T_POP
    const val ACT_IV = T_SILENCE

    /** The E42 goes out (a cut) when the silence begins; the life sign is then the only light on the road. */
    const val T_LAST_LIGHT_OUT = T_SILENCE

    /** Dots ride the homeflight column by column, [DOT_STAGGER] ms apart, [DOT_FLIGHT] ms each. */
    const val DOT_STAGGER = 20
    const val DOT_FLIGHT = 560
    const val T_DOTS_HOME = T_HOMEFLIGHT + 35 * DOT_STAGGER + DOT_FLIGHT

    /** The open lock lights in the tube (a sweep of 520 ms) while the halo closes: the bike is alive before the cut. */
    const val T_GLYPH = T_TABLEAU + 140

    /** First halo dot, then one every [HALO_STEP] ms, 24 of them. */
    const val T_HALO = T_TABLEAU - 864
    const val HALO_STEP = 36
    const val HALO_DOTS = 24

    const val T_HANDOFF = T_TABLEAU + 900
    const val T_CUT = T_HANDOFF + 900
    const val T_END = T_CUT + REVEAL_MS

    /** The lockup ending: the slogan reveals from [LOCKUP_TEXT_FROM] to [LOCKUP_TEXT_TO], the wipe L to R from [T_LOCKUP_WIPE]. */
    const val LOCKUP_TEXT_FROM = T_TABLEAU + 600
    const val LOCKUP_TEXT_TO = T_TABLEAU + 1_120
    const val T_LOCKUP_WIPE = T_TABLEAU + 2_000
    const val T_LOCKUP_END = T_LOCKUP_WIPE + REVEAL_MS

    /** The error the bike throws (it is the owner's fault code); the dots that fly at the pop are its lit dots. */
    const val ERROR_CODE = "E42"

    /**
     * The blink, ON edge, OFF edge, ON edge ...: hard cuts. The periods are 680, 540, 420 and 340 ms (1.47 to 2.94 Hz): the
     * blink never exceeds 3 Hz, so a small red patch stays safe for photosensitive viewers (WCAG 2.3.1). The last entry is
     * the ON that stays.
     */
    val BLINK: IntArray = intArrayOf(
        T_E42, T_BLINK_OFF_1, T_BLINK_ON_2, T_BLINK_OFF_2, T_BLINK_ON_3, T_BLINK_OFF_3, T_BLINK_ON_4, T_BLINK_OFF_4, T_STUCK,
    )

    /** True while the blink shows the error: from an ON edge to the next OFF edge, and from the stuck ON on. */
    fun blinkOn(ti: Int): Boolean {
        if (ti < BLINK[0]) return false
        if (ti >= BLINK[BLINK.size - 1]) return true
        var i = 0
        while (i + 1 < BLINK.size && ti >= BLINK[i + 1]) i++
        return i % 2 == 0
    }

    /** A named stretch of the film; the tests check that every one has a start before its end. */
    class Window(val name: String, val from: Int, val to: Int)

    val windows: List<Window> = listOf(
        Window("S01 ground", 0, T_GROUND_END),
        Window("S02 roll-in", T_ROLL_START, T_ROLL_END),
        Window("S03 stands", T_ROLL_END, T_BOOT_IN),
        Window("S04 omen", T_BOOT_IN, T_HOVER),
        Window("S05 wind-up 1", T_WIND_1, T_FALL_1),
        Window("S06 stomp 1", T_FALL_1, T_S1 + HIT_STOP_1),
        Window("S07 AUW and pause", T_S1 + HIT_STOP_1, T_WIND_2),
        Window("S08 wind-up 2", T_WIND_2, T_FALL_2),
        Window("S09 stomp 2", T_FALL_2, T_S2 + HIT_STOP_2),
        Window("S10 error, wide", T_S2 + HIT_STOP_2, T_INSERT),
        Window("S11 insert", T_INSERT, T_TOTALE),
        Window("S12 apex", T_TOTALE, T_FALL_3),
        Window("S13 stomp 3", T_FALL_3, T_POP),
        Window("S14 pop", T_POP, T_HANG),
        Window("S15 hang", T_HANG, T_DROP),
        Window("S16 drop", T_DROP, T_SILENCE),
        Window("S17 silence", T_SILENCE, T_LIGHT_1),
        Window("S18 light returns", T_LIGHT_1, T_HOMEFLIGHT),
        Window("S19 homeflight", T_HOMEFLIGHT, T_TABLEAU),
        Window("S20 tableau", T_TABLEAU, T_HANDOFF),
        Window("S21 handoff", T_HANDOFF, T_CUT),
        Window("S22 reveal", T_CUT, T_END),
    )

    /** The deliberate stillness, 4.9 s of 14: nothing but the matrix and a held pose moves, the picture breathes. */
    val stillness: List<Window> = listOf(
        Window("stand", T_ROLL_END, T_BOOT_IN),
        Window("hit-stop 1", T_S1, T_S1 + HIT_STOP_1),
        Window("hit-stop 2", T_S2, T_S2 + HIT_STOP_2),
        Window("blink beat", T_E42, T_STUCK),
        Window("apex", T_STUCK, T_FALL_3),
        Window("hit-stop 3", T_S3, T_POP),
        Window("hang", T_HANG, T_DROP),
        Window("silence", T_SILENCE, T_LIGHT_1),
        Window("tableau", T_TABLEAU, T_HANDOFF),
    )

    enum class Haptic { Tick, Ramp, Thud, Confirm }
    class Cue(val at: Int, val kind: Haptic)

    /**
     * Every haptic cue of the film, sorted. Fired from the clock, never chained: a cue that was missed by more
     * than [CUE_LATE_MS] (a resume, a jump) is not replayed. `reject` is never used here.
     *
     * Stomp 1 is one thud, stomp 2 two (the hit and its rebound), stomp 3 a thud and, 220 ms later, the pop's: the escalation
     * is felt. The three toe taps on the capsule's rim are ticks.
     */
    val cues: List<Cue> = buildList {
        add(Cue(T_LOCK_IN, Haptic.Tick))
        add(Cue(T_S1, Haptic.Thud)); add(Cue(T_AUW, Haptic.Tick))
        add(Cue(T_S2, Haptic.Thud)); add(Cue(T_S2 + HIT_STOP_2, Haptic.Thud))
        add(Cue(T_E42, Haptic.Tick)); add(Cue(T_TAP_1, Haptic.Tick)); add(Cue(T_BLINK_ON_3, Haptic.Tick))
        add(Cue(T_TAP_2, Haptic.Tick)); add(Cue(T_TAP_3, Haptic.Tick)); add(Cue(T_STUCK, Haptic.Tick))
        add(Cue(T_S3, Haptic.Thud))
        add(Cue(T_POP, Haptic.Thud))
        add(Cue(THUD_TUBE_LANDS, Haptic.Thud))
        for (at in intArrayOf(T_DROP + 440, T_DROP + 620, T_DROP + 740, T_DROP + 820)) add(Cue(at, Haptic.Tick))
        add(Cue(T_LIFE_HOME, Haptic.Tick))
        add(Cue(T_LIGHT_1, Haptic.Tick)); add(Cue(T_LIGHT_2, Haptic.Tick)); add(Cue(T_LIGHT_3, Haptic.Tick))
        for (k in 0 until 6) add(Cue(T_HOMEFLIGHT + 70 + 130 * k, Haptic.Ramp))
        add(Cue(T_TABLEAU - 230, Haptic.Tick))
        add(Cue(T_TABLEAU, Haptic.Confirm))
    }.sortedBy { it.at }

    const val CUE_LATE_MS = 120

    /**
     * The big thud of the drop: when the tube first touches the road. With the exploded pose as it hangs (tube 161 u up,
     * gravity 900 u/s2) it lands 0.575 s after the drop begins. [FilmWorld] checks this number against its own simulation.
     */
    const val THUD_TUBE_LANDS = T_DROP + 570
}

// ---------------------------------------------------------------------------------------------------------
// Easing: plain functions, so the schedule can be tested off the device.
// ---------------------------------------------------------------------------------------------------------

object Ease {
    fun clamp01(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x

    /** Progress 0..1 of [t] between [from] and [to]. */
    fun ph(t: Float, from: Float, to: Float) = clamp01((t - from) / (to - from))
    fun ph(t: Float, from: Int, to: Int) = ph(t, from.toFloat(), to.toFloat())

    fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x

    fun outQuad(x: Float) = 1f - (1f - x) * (1f - x)
    fun inQuad(x: Float) = x * x
    fun outCubic(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
    fun inOut(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f

    /**
     * The hard start of an explosion: a damped spring that has covered 95 % of the way after 100 ms, overshoots by
     * about 5 % and is still by [POP_MS]. [ms] is the time since the pop; [a] is the damping, [b] the
     * frequency (rad/s). Exactly 1 from [POP_MS] on, so the hang starts where the pop ends.
     */
    fun pop(ms: Float, a: Float = 13f, b: Float = 14f): Float {
        if (ms <= 0f) return 0f
        if (ms >= POP_MS) return 1f
        val s = ms / 1000f
        return 1f - exp(-a * s) * cos(b * s)
    }

    /** The pop of a dot: lighter, so it overshoots further (about 13 %). */
    fun popDot(ms: Float): Float = pop(ms, 10f, 15.5f)

    const val POP_MS = 800f

    /** The app's FastOutSlowIn: cubic bezier (0.4, 0, 0.2, 1). */
    fun fastOutSlowIn(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // solve bezier x(s) = x for s, then read y(s)
        var lo = 0f
        var hi = 1f
        var s = x
        repeat(22) {
            s = (lo + hi) / 2f
            val bx = bez(s, 0.4f, 0.2f)
            if (bx < x) lo = s else hi = s
        }
        return bez(s, 0f, 1f)
    }

    private fun bez(s: Float, p1: Float, p2: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * p1 + 3f * u * s * s * p2 + s * s * s
    }

    /** Piecewise-linear through [keys] (time, value) pairs; flat outside. */
    fun keyed(t: Float, vararg keys: Float): Float {
        var i = 0
        if (t <= keys[0]) return keys[1]
        while (i + 2 < keys.size) {
            val t0 = keys[i]; val v0 = keys[i + 1]; val t1 = keys[i + 2]; val v1 = keys[i + 3]
            if (t <= t1) return lerp(v0, v1, (t - t0) / (t1 - t0))
            i += 2
        }
        return keys[keys.size - 1]
    }
}

// ---------------------------------------------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------------------------------------------

/**
 * `screenX = centerX + k (wx - cx)`, `screenY = groundY + k wy`. [k] is px per u, [groundY] where `wy = 0` lands.
 */
class Cam(val k: Float, val cx: Float, val groundY: Float, val centerX: Float) {
    fun sx(wx: Float) = centerX + k * (wx - cx)
    fun sy(wy: Float) = groundY + k * wy
}

/** The stage in px: the window on the cover screen, the left pane on the inner one. */
class Stage(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f

    /**
     * px per u at zoom 1. A tall stage (the cover screen) is fitted by width: 215 u wide, the rest of the height is sky
     * for the boot, the burst and the light. A wide or square one (the inner screen) is fitted by height as
     * well: the bike, its lift and the halo need about 250 u of it.
     */
    val k0 get() = minOf(width / 215f, height / 250f)

    /** height / width of the stage. */
    val aspect: Float get() = height / width

    /**
     * 0 on a square or wide stage (the inner screen), 1 on a tall one (the cover screen, 2.3): the film then holds a closer
     * baseline zoom, lifts the road and spreads the exploded view upward, so the bike and its sky fill the height as well as
     * the width.
     */
    val tall: Float get() = Ease.clamp01((aspect - 1.6f) / 0.5f)

    /**
     * Where the road sits, as a fraction of the stage's height: 0.60 on a tall stage (a road band below it is a place of its
     * own), 0.72 on an ordinary one, down to 0.84 on a square one, so the sky above the bike (the halo, the exploded view)
     * never runs out.
     */
    val gf0: Float get() = 0.72f + 0.12f * Ease.clamp01((1.7f - aspect) / 0.7f) - 0.12f * tall

    /** How many u of sky the camera at zoom [z] sees above the road. */
    fun skyU(z: Float): Float = gf0 * height / (k0 * z)

    /** The factor the exploded view is stretched upward by (and gravity with it, so the fall takes as long): 1.35 on a tall stage. */
    val explodeStretch: Float get() = 1f + 0.35f * tall
}

object Camera {
    /** 0.76 shows 283 u: the wreck (262 u, from -141 to 121) has a 10 u margin on each side of a tall stage. */
    const val Z_WIDE = 0.76f

    /** The tall stage's roll-in (the bike overflows the edges), and the stand that follows it (the whole bike again). */
    const val Z_ROLL_TALL = 1.25f
    const val Z_STAND_TALL = 1.08f

    /** The stomps: the camera pulls back to make room for the boot (the bike is 185 u wide, the boot comes from above). */
    const val Z_STOMP = 0.88f
    const val CX_STOMP = 4f

    /** The camera takes this long to go back from the stomp's zoom to [Z_WIDE] after the pop. */
    const val BLAST_PULLBACK_MS = 400
    /** At -10 the wreck (-141 to 121, middle -10) sits in the middle of the frame with the same margin left and right. */
    const val CX_WIDE = -10f

    /**
     * The insert on the error: the capsule (84 u) fills 90 % of the width of a tall stage so that E42 stays legible. It is
     * [INSERT_Z] x the stage's own px per u, the focus ([INSERT_FOCUS_Y] on the stage's middle) halfway up the tube.
     */
    const val INSERT_Z = 2.3f
    const val INSERT_FOCUS_Y = -100f

    /** The road sinks this much (a fraction of the stage's height) after the third stomp, and the tableau sits this much lower than the start. */
    const val GF_DRIFT = 0.05f
    const val GF_TABLEAU_DELTA = 0.03f

    /** Where the road is at the tableau, as a fraction of the stage's height: the lockup ending stays there. */
    fun tableauGf(s: Stage): Float = s.gf0 + GF_TABLEAU_DELTA

    /** True while the hard-cut insert on the blinking tube is on screen. */
    fun inInsert(t: Float) = t >= Film.T_INSERT && t < Film.T_TOTALE

    /** The silence is shot with the insert's lens, on the fallen tube: the life sign's hop and breath are the picture. */
    fun inSilenceShot(t: Float) = t >= Film.T_SILENCE && t < Film.T_LIGHT_1

    /** Where the fallen tube's middle lies: the silence shot is focused on it. */
    val SILENCE_FOCUS_Y: Float = Geo.restY(Part.Tube)

    private val shakeA = floatArrayOf(-1f, 0.85f, -0.55f, 0.25f)
    private val shakeB = floatArrayOf(0.7f, -0.55f, 0.3f, -0.12f)

    /**
     * The jolt of a hit: four steps of 15 ms (60 ms), in u, as (x, y). Each stomp hits harder than the one before (on the
     * cover screen the peaks are 13, 22 and 41 px), the pop does both and hardest. Zero outside these windows (and
     * none in the insert).
     */
    fun shake(t: Float): Pair<Float, Float> {
        fun step(from: Int): Int = if (t < from || t >= from + 60) -1 else ((t - from) / 15f).toInt().coerceIn(0, 3)
        val s1 = step(Film.T_S1)
        if (s1 >= 0) return Pair(1.6f * shakeA[s1], 4.0f * shakeB[s1])
        val s2 = step(Film.T_S2)
        if (s2 >= 0) return Pair(2.8f * shakeA[s2], 6.8f * shakeB[s2])
        val s3 = step(Film.T_S3)
        if (s3 >= 0) return Pair(3.5f * shakeA[s3], 12.5f * shakeB[s3])
        val p = step(Film.T_POP)
        if (p >= 0) return Pair(6.5f * shakeA[p], 12.5f * shakeB[p])
        return Pair(0f, 0f)
    }

    /**
     * The camera at [t]. [slot] (window px) turns on the handoff solve from [Film.T_HANDOFF]; without it the camera stays
     * at the tableau.
     *
     * On a tall stage ([Stage.tall]) the baseline is closer: the roll-in is shot at [Z_ROLL_TALL] (the bike overflows both
     * edges, as it should for a thing that big), the stand at 1.08 (the whole bike again); from the omen on the camera pulls
     * back to [Z_STOMP] and is the same on every stage.
     */
    fun at(t: Float, s: Stage, slot: Stage? = null): Cam {
        val k0 = s.k0
        val g0 = s.gf0
        val tall = s.tall
        val zRoll = 1f + (Z_ROLL_TALL - 1f) * tall
        val zStand = 1f + (Z_STAND_TALL - 1f) * tall
        var z: Float
        var cx: Float
        var gf: Float
        when {
            t < Film.T_ROLL_END -> { z = zRoll; cx = 0f; gf = g0 }
            t < Film.T_LOCK_IN -> { z = Ease.lerp(zRoll, zStand, Ease.inOut(Ease.ph(t, Film.T_ROLL_END, Film.T_LOCK_IN))); cx = 0f; gf = g0 }
            t < Film.T_HOVER -> {
                val e = Ease.inOut(Ease.ph(t, Film.T_LOCK_IN, Film.T_HOVER))
                z = Ease.lerp(zStand, Z_STOMP, e); cx = Ease.lerp(0f, CX_STOMP, e); gf = g0
            }
            t < Film.T_INSERT -> { z = Z_STOMP; cx = CX_STOMP; gf = g0 }
            t < Film.T_TOTALE -> {
                val k = k0 * INSERT_Z
                return Cam(k, Geo.TUBE_CX, s.centerY - k * INSERT_FOCUS_Y, s.centerX)
            }
            // back to the wide shot: the road has sunk a little (the cut hides the jump)
            t < Film.T_POP -> { z = Z_STOMP; cx = CX_STOMP; gf = g0 + GF_DRIFT }
            // the blast pushes the camera back: the exploded view needs 262 u
            t < Film.T_POP + BLAST_PULLBACK_MS -> {
                val e = Ease.outCubic(Ease.ph(t, Film.T_POP, Film.T_POP + BLAST_PULLBACK_MS))
                z = Ease.lerp(Z_STOMP, Z_WIDE, e); cx = Ease.lerp(CX_STOMP, CX_WIDE, e); gf = g0 + GF_DRIFT
            }
            t < Film.T_SILENCE -> { z = Z_WIDE; cx = CX_WIDE; gf = g0 + GF_DRIFT }
            // the silence: the insert's lens on the fallen tube, hard cuts in and out
            t < Film.T_LIGHT_1 -> {
                val k = k0 * INSERT_Z
                return Cam(k, Part.Tube.restX, s.centerY - k * SILENCE_FOCUS_Y, s.centerX)
            }
            t < Film.T_LIGHT_3 -> { z = Z_WIDE; cx = CX_WIDE; gf = g0 + GF_DRIFT }
            t < Film.T_TABLEAU -> {
                val e = Ease.inOut(Ease.ph(t, Film.T_LIGHT_3, Film.T_TABLEAU))
                z = Ease.lerp(Z_WIDE, 1f, e); cx = Ease.lerp(CX_WIDE, 0f, e); gf = Ease.lerp(g0 + GF_DRIFT, g0 + GF_TABLEAU_DELTA, e)
            }
            else -> { z = 1f; cx = 0f; gf = g0 + GF_TABLEAU_DELTA }
        }
        var k = k0 * z
        var groundY = s.top + gf * s.height
        var centerX = s.centerX
        val (shx, shy) = shake(t)
        groundY += shy * k
        centerX += shx * k
        if (slot != null && t > Film.T_HANDOFF) {
            val p = Ease.fastOutSlowIn(Ease.ph(t, Film.T_HANDOFF, Film.T_CUT))
            val kF = slot.width / TUBE_W
            val gYF = slot.centerY + (-Geo.TUBE_CY) * kF
            val cxF = Geo.TUBE_CX - (slot.centerX - s.centerX) / kF
            k = Ease.lerp(k, kF, p)
            groundY = Ease.lerp(groundY, gYF, p)
            cx = Ease.lerp(cx, cxF, p)
        }
        return Cam(k, cx, groundY, centerX)
    }

    const val TUBE_W = Geo.TUBE_W
}

// ---------------------------------------------------------------------------------------------------------
// The tube: what the matrix shows, as a function of t
// ---------------------------------------------------------------------------------------------------------

enum class FrameId { Empty, S12, S8, S5, S3, S1, S0, Unlocked, Locked, Auw, E42, Yaba }

enum class Tone { Led, Signal }

/**
 * One moment of the top tube's display. [shown] is swept in up to [progress] of the way, [prev] still stands to
 * the right of it (progress 1 = [shown] only).
 */
class Face(
    val shown: FrameId,
    val prev: FrameId = FrameId.Empty,
    val progress: Float = 1f,
    val tone: Tone = Tone.Led,
    val dot: Float = 1f,
    val glow: Float = 1f,
    val lit: Float = 1f,
    val shift: Int = 0,
)

object TubeFace {
    private val recoil = intArrayOf(0, -1, 1, -1, 1, 0)
    const val STRIKE_DOT = 1.206f
    const val STRIKE_GLOW = 1.8f

    /** The glow of the open lock at the tableau (the instrument's own is 1). */
    const val GLYPH_GLOW = 1.5f

    /** The lockup ending: the word mark sweeps in here, 0.9 s after the tableau begins and 0.76 s after the lock lit. */
    const val LOCKUP_SWEEP = Film.T_TABLEAU + 900

    /**
     * [ouch] is false when the call is not allowed (switch off, drive lock): the first stomp is then answered without a
     * word, by the closed lock (the stomp has shut the kick lock, the knob in the bike has turned red) on the same beat and
     * with the same recoil as the call. The error E42 is a state, not a call: it shows either way. [lockup] is the ending
     * without a Welcome: the tube boots the word mark on the tableau.
     */
    fun at(t: Float, ouch: Boolean, lockup: Boolean): Face {
        val ti = t.toInt()
        return when {
            ti < Film.T_ROLL_START -> Face(FrameId.Empty)
            ti < Film.T_ROLL_END -> Face(Readout.at(t))
            ti < Film.T_LOCK_IN -> Face(FrameId.Unlocked, prev = FrameId.S0, progress = Ease.fastOutSlowIn(Ease.ph(t, Film.T_ROLL_END, Film.T_LOCK_IN)))
            // the open lock, dimming a little while the boot looms
            ti < Film.T_S1 -> Face(FrameId.Unlocked, lit = Ease.lerp(1f, 0.7f, Ease.ph(t, Film.ACT_II, Film.T_S1)))
            // the blank beat: the bike holds its breath
            ti < Film.T_AUW -> Face(FrameId.Empty)
            ti < Film.T_AUW_OUT -> answer(t, if (ouch) FrameId.Auw else FrameId.Locked)
            ti < Film.T_AUW_GONE ->
                Face(
                    FrameId.Empty, prev = if (ouch) FrameId.Auw else FrameId.Locked,
                    progress = Ease.ph(t, Film.T_AUW_OUT, Film.T_AUW_GONE), glow = auwGlow(t), lit = AUW_LIT_END,
                )
            ti < Film.T_E42 -> Face(FrameId.Empty)
            ti < Film.T_STUCK -> blink(t, ti)
            // the stuck ON; at the third stomp it takes the strike look and holds it to the pop
            ti < Film.T_S3 -> Face(FrameId.E42, tone = Tone.Signal)
            ti < Film.T_POP -> Face(FrameId.E42, tone = Tone.Signal, dot = STRIKE_DOT, glow = STRIKE_GLOW)
            // the lit dots of the E42 have left their sockets (they fly on their own); the capsule keeps the dark ones
            ti < Film.T_GLYPH -> Face(FrameId.Empty)
            // the payoff before the cut: the open lock lights in the tube, led, glowing harder than the instrument does
            ti < Film.T_GLYPH + 520 ->
                Face(FrameId.Unlocked, prev = FrameId.Empty, progress = Ease.fastOutSlowIn(Ease.ph(t, Film.T_GLYPH, Film.T_GLYPH + 520)), glow = GLYPH_GLOW)
            !lockup -> if (ti < Film.T_HANDOFF) Face(FrameId.Unlocked, glow = GLYPH_GLOW) else Face(FrameId.Empty)
            // the lockup ending: the lock holds for a beat, then the word mark sweeps over it
            ti < LOCKUP_SWEEP -> Face(FrameId.Unlocked, glow = GLYPH_GLOW)
            ti < LOCKUP_SWEEP + 520 ->
                Face(FrameId.Yaba, prev = FrameId.Unlocked, progress = Ease.fastOutSlowIn(Ease.ph(t, LOCKUP_SWEEP, LOCKUP_SWEEP + 520)))
            else -> Face(FrameId.Yaba)
        }
    }

    /** The sigh after the call: the brightness goes 1.0 to 0.6 while the call is up. */
    private const val AUW_LIT_END = 0.6f

    private fun auwGlow(t: Float): Float = Ease.lerp(STRIKE_GLOW, 1f, Ease.ph(t, Film.T_AUW + 100, Film.T_AUW + 900))

    /** The recoil of the field in whole columns, 35 ms a step, [since] ms after the strike. */
    private fun recoilShift(since: Float): Int {
        val step = floor(since / 35f).toInt()
        return if (step in recoil.indices) recoil[step] else 0
    }

    /** The strike's dot: 1.206 falling to 1.0 over +90 to +250 ms. */
    private fun strikeDot(t: Float, t0: Int): Float = Ease.lerp(STRIKE_DOT, 1f, Ease.ph(t, t0 + 90, t0 + 250))

    /** The strike: a hard cut to [frame] (the call, or the closed lock), dot r 0.41 p, glow 1.8, a recoil in whole columns, then the sigh. */
    private fun answer(t: Float, frame: FrameId): Face {
        val lit = Ease.lerp(1f, AUW_LIT_END, Ease.ph(t, Film.T_AUW + 100, Film.T_AUW_OUT))
        return Face(frame, dot = strikeDot(t, Film.T_AUW), glow = auwGlow(t), lit = lit, shift = recoilShift(t - Film.T_AUW))
    }

    /**
     * The error blinking in signal red, ON and OFF as hard cuts ([Film.BLINK]). The first ON has the strike look (the call
     * of the second stomp); the others are plain.
     */
    private fun blink(t: Float, ti: Int): Face {
        if (!Film.blinkOn(ti)) return Face(FrameId.Empty)
        if (ti < Film.T_BLINK_OFF_1) {
            val since = t - Film.T_E42
            return Face(
                FrameId.E42, tone = Tone.Signal,
                dot = strikeDot(t, Film.T_E42),
                glow = Ease.lerp(STRIKE_GLOW, 1f, Ease.ph(since, 100f, 380f)),
                shift = recoilShift(since),
            )
        }
        return Face(FrameId.E42, tone = Tone.Signal)
    }

    /**
     * The two head and tail lights of the bike: on from the roll-in, dead with the squash of the third stomp, on again at the
     * tableau. Only the handoff ending turns them off again (so the bars and the spill are off at the cut).
     */
    fun lamp(t: Float, strip: Boolean = false, handoff: Boolean = false): Float = when {
        strip -> 1f
        t < Film.T_ROLL_START.toFloat() -> 0f
        t < Film.T_ROLL_START + 300f -> Ease.ph(t, Film.T_ROLL_START, Film.T_ROLL_START + 300)
        t < Film.T_S3.toFloat() -> 1f
        t < Film.T_TABLEAU.toFloat() -> 0f
        t < Film.T_TABLEAU + 600f -> Ease.ph(t, Film.T_TABLEAU, Film.T_TABLEAU + 600)
        !handoff -> 1f
        t < Film.T_HANDOFF + 200f -> 1f
        t < Film.T_HANDOFF + 800f -> 1f - Ease.ph(t, Film.T_HANDOFF + 200, Film.T_HANDOFF + 800)
        else -> 0f
    }
}

// ---------------------------------------------------------------------------------------------------------
// Light
// ---------------------------------------------------------------------------------------------------------

object StageLight {
    /** How long the stage is in full light when the bike blows apart: the bang is a lit stage, then the dark comes back. */
    const val BLAST_LIT_MS = 400

    /** The hang is only half dark (the E42 hangs in the air and has to be read); the dark of 0.5 is back with the drop. */
    const val HANG_DIM = 0.22f

    /** The silence: the wreck is dark, but the shot is close and the tube is not dimmed; the first light then comes in as 0.34. */
    const val SILENCE_DIM = 0.36f

    /**
     * Black over everything but the tube and the boot: dims in three cuts on the blink (the boot's shadow deepening),
     * returns in three. For [BLAST_LIT_MS] from the pop the parts are at full paint (the bang), then the stage is only
     * [HANG_DIM] dark until the drop, 0.5 for the crash, [SILENCE_DIM] for the silence, and the three light cuts bring it back.
     */
    fun dim(t: Float): Float = when {
        t < Film.T_DIM_1 -> 0f
        t >= Film.T_POP && t < Film.T_POP + BLAST_LIT_MS -> 0f
        t < Film.T_DIM_2 -> 0.18f
        t < Film.T_DIM_3 -> 0.34f
        t < Film.T_POP -> 0.50f
        t < Film.T_DROP -> HANG_DIM
        t < Film.T_SILENCE -> 0.50f
        t < Film.T_LIGHT_1 -> SILENCE_DIM
        t < Film.T_LIGHT_2 -> 0.34f
        t < Film.T_LIGHT_3 -> 0.18f
        else -> 0f
    }

    /**
     * The bike is never darker than this, whatever the dim: the damage of the stomps has to be read on it. The road, the sky
     * and the shafts take the rest of the dark (see [backdropDim]).
     */
    const val BIKE_DIM_MAX = 0.30f

    /** The dim over the bike and its parts: [dim] capped at [BIKE_DIM_MAX]. */
    fun bikeDim(dim: Float): Float = if (dim < BIKE_DIM_MAX) dim else BIKE_DIM_MAX

    /**
     * The dim laid over the road and the sky before the bike is drawn, so that the road, the sky and the bike's dim over it
     * add up to exactly [dim] on the background (1 - (1 - backdrop)(1 - bike) = dim).
     */
    fun backdropDim(dim: Float): Float {
        val bike = bikeDim(dim)
        return if (dim <= bike) 0f else 1f - (1f - dim) / (1f - bike)
    }

    /** 0 = no spot, 1..3 = the nested steps (frame, rib, led at 24 %); never more than three. */
    fun steps(t: Float): Int = when {
        t < Film.T_LIGHT_1 -> 0
        t < Film.T_LIGHT_2 -> 1
        t < Film.T_LIGHT_3 -> 2
        else -> 3
    }

    val shaftHalfWidth = floatArrayOf(150f, 105f, 66f)
    const val SHAFT_TOP_HALF = 35f
    const val SHAFT_ELLIPSE_H = 10f

    /** The third step is a rib that has taken this much led (a flat colour, no alpha over the others). */
    const val LED_STEP_MIX = 0.16f

    /** Led over the top of the third step only: (from, to) as a fraction of the shaft's height from its top, and the alpha. */
    val TOP_BANDS: List<Pair<Pair<Float, Float>, Float>> = listOf(
        Pair(Pair(0f, 0.22f), 0.34f),
        Pair(Pair(0.22f, 0.45f), 0.18f),
    )

    /** The asphalt overlay of the handoff: everything but the tube blends flat to asphalt. */
    fun asphaltVeil(t: Float): Float = Ease.ph(t, Film.T_HANDOFF + 200, Film.T_HANDOFF + 800)

    /** Impact frames: 80 ms of solid paint silhouettes, twice in the whole film (the second and the third stomp, after its squash). */
    fun impact(t: Float): Boolean =
        (t >= Film.T_S2 && t < Film.T_S2 + 80) || (t >= Film.T_S3 + Film.SQUASH_MS && t < Film.T_S3 + Film.SQUASH_MS + 80)
}

// ---------------------------------------------------------------------------------------------------------
// The boot
// ---------------------------------------------------------------------------------------------------------

/**
 * One leg at an instant. The foot: the toe tip at ([toeX], [soleY]) (the sole's underside, the tooth tips) in the boot's own
 * frame, turned [angle] degrees about the ankle (clockwise: the toe goes down), everything scaled by [scale] about the toe.
 * The knee stands at ([kneeX], [kneeY]) in the world. The hip is far above the frame, at x = [hipX]: the drawing puts it
 * there, high enough for the thigh to leave the picture, so the leg always comes from above.
 */
class BootPose(
    val toeX: Float,
    val soleY: Float,
    val angle: Float,
    val scale: Float,
    val kneeX: Float,
    val kneeY: Float,
    val hipX: Float,
    /** The boot's own height factor: 1.05 at the end of a fall, 0.94 for 60 ms at a contact, 0.92 for 100 ms on the squashed bike (the boot gives, too). */
    val squashY: Float = 1f,
) {
    /** Where the toe tip is in the world after the nod: the point that touches the capsule at a tap. */
    fun toeTip(): Pair<Float, Float> {
        val a = Math.toRadians(angle.toDouble())
        val rx = -Boots.ANKLE_X
        val ry = -Boots.ANKLE_Y
        val dx = (rx * cos(a) - ry * sin(a) - rx).toFloat()
        val dy = (rx * sin(a) + ry * cos(a) - ry).toFloat()
        return Pair(toeX + dx * scale, soleY + dy * scale * squashY)
    }
}

/** An easing by name, so that the boot's path can be a table. */
private enum class Ez {
    Lin, OutQ, InQ, OutC, InOut;

    fun at(x: Float): Float = when (this) {
        Lin -> x
        OutQ -> Ease.outQuad(x)
        InQ -> Ease.inQuad(x)
        OutC -> Ease.outCubic(x)
        InOut -> Ease.inOut(x)
    }
}

/** One stretch of the boot's path: the three channels between [t0] and [t1], each with its own easing. */
private class BootSeg(
    val t0: Int, val t1: Int,
    val toe0: Float, val toe1: Float,
    val sole0: Float, val sole1: Float,
    val a0: Float, val a1: Float,
    val soleEz: Ez, val toeEz: Ez, val angEz: Ez,
)

/**
 * The giant boot: no face, no person, only a cartoon boot and its lower leg. It comes from above, stomps three times, each
 * stomp harder and slower than the one before (falls of 140, 100 and 80 ms, hit-stops of 100, 140 and 220 ms), and is
 * blown out through the top at the pop. One channel set (toe, sole, angle) is a table, continuous at every join.
 *
 * It comes at the camera: 1.0 times its size at the top of a wind-up, 1.18 at contact (grown about the toe, so the sole stays
 * on the line it is pressed from), and a little less while it hovers over the capsule. It has weight: it stretches 5 % over the
 * last 50 ms of a fall and squashes at every contact (0.94 for 60 ms at the first two, 0.92 at the third), and the shin leans
 * far forward at the top of each wind-up (24 degrees), so the knee comes into the picture.
 */
object Boots {
    /** The boot's size at the top of a wind-up, at contact, and while it hovers low over the capsule. */
    const val SCALE_FAR = 1.0f
    const val SCALE_NEAR = 1.18f
    const val SCALE_HOVER = 1.08f

    /** The boot's height factor while it stands on the squashed bike (0.92 for 100 ms), on the first two stomps (0.94 for 60 ms), and in the last 50 ms of a fall. */
    const val SQUASH_Y = 0.92f
    const val SQUASH_LIGHT = 0.94f
    const val STRETCH_Y = 1.05f

    /** The boot's own frame: the ankle, and the shin from the ankle to the knee (a giant's shin, and a visible one). */
    const val ANKLE_X = -53f
    const val ANKLE_Y = -46f
    const val SHIN = 154f

    /** The thigh leaves the picture upward from a hip this far left of the knee. */
    const val HIP_LEAN = 80f

    /**
     * Where the soles meet the bike: the top of the saddle (a wide, flat target: the sole is 87 u long) and where it ends up
     * pressing it, the top of the capsule and where it presses that, the crooked saddle's highest corner, and the squashed bike.
     */
    const val CONTACT_1 = -107.6f
    const val PRESS_1 = -98.7f
    const val CONTACT_2 = -96f
    const val PRESS_2 = -86.4f
    const val CONTACT_3 = -93f
    const val SQUASHED_3 = -43.2f

    const val TOE_1 = 20f
    const val TOE_2 = 18f
    const val TOE_3 = 22f

    /** The apex of each wind-up: the sole's height before the fall. */
    const val APEX_1 = -176f
    const val APEX_2 = -192f
    const val APEX_3 = -204f

    /**
     * The hover over the capsule, and the toe taps: each tap drops the sole [TAP_DROP] u (and nods the toe from [HOVER_ANGLE] to
     * [TAP_ANGLE]) until the toe tip is on the capsule's rim, which sits at [RIM_TAP] after the second stomp's sag.
     */
    const val HOVER_SOLE = -122f
    const val TAP_DROP = 14.9f
    const val RIM_TAP = -90.5f
    const val HOVER_ANGLE = 10f
    const val TAP_ANGLE = 20f
    const val TAP_FALL_MS = 80
    const val TAP_RISE_MS = 120

    const val T_FIRST = Film.T_BOOT_IN
    const val T_LAST = 8_120

    private val path: Array<BootSeg> = arrayOf(
        BootSeg(2_600, 3_400, TOE_1, TOE_1, -560f, -164f, 20f, 6f, Ez.OutC, Ez.Lin, Ez.InOut),
        BootSeg(3_400, 3_640, TOE_1, TOE_1, -164f, APEX_1, 6f, 16f, Ez.InOut, Ez.Lin, Ez.InOut),
        BootSeg(3_640, 3_780, TOE_1, TOE_1, APEX_1, CONTACT_1, 16f, 0f, Ez.InQ, Ez.Lin, Ez.Lin),
        // the sole presses the saddle down with the bike's own give (see [Reaction]), and holds
        BootSeg(3_780, 3_840, TOE_1, TOE_1, CONTACT_1, PRESS_1, 0f, 0f, Ez.OutQ, Ez.Lin, Ez.Lin),
        BootSeg(3_840, 3_880, TOE_1, TOE_1, PRESS_1, PRESS_1, 0f, 0f, Ez.Lin, Ez.Lin, Ez.Lin),
        BootSeg(3_880, 4_500, TOE_1, TOE_2, PRESS_1, -172f, 0f, 8f, Ez.InOut, Ez.InOut, Ez.InOut),
        BootSeg(4_500, 5_000, TOE_2, TOE_2, -172f, APEX_2, 8f, 22f, Ez.InOut, Ez.Lin, Ez.InOut),
        BootSeg(5_000, 5_100, TOE_2, TOE_2, APEX_2, CONTACT_2, 22f, 0f, Ez.InQ, Ez.Lin, Ez.Lin),
        BootSeg(5_100, 5_160, TOE_2, TOE_2, CONTACT_2, PRESS_2, 0f, 0f, Ez.OutQ, Ez.Lin, Ez.Lin),
        BootSeg(5_160, 5_240, TOE_2, TOE_2, PRESS_2, PRESS_2, 0f, 0f, Ez.Lin, Ez.Lin, Ez.Lin),
        BootSeg(5_240, 5_900, TOE_2, TOE_2, PRESS_2, HOVER_SOLE, 0f, HOVER_ANGLE, Ez.OutC, Ez.Lin, Ez.OutC),
        // the hover: three toe taps on the capsule's rim (sole and angle are keyed, see [tap]); the third is the push-off
        BootSeg(5_900, Film.T_TAP_3, TOE_2, TOE_2, HOVER_SOLE, HOVER_SOLE + TAP_DROP, HOVER_ANGLE, TAP_ANGLE, Ez.Lin, Ez.Lin, Ez.Lin),
        BootSeg(Film.T_TAP_3, 7_280, TOE_2, TOE_3, HOVER_SOLE + TAP_DROP, APEX_3, TAP_ANGLE, 28f, Ez.InOut, Ez.InOut, Ez.InOut),
        BootSeg(7_280, 7_440, TOE_3, TOE_3, APEX_3, APEX_3, 28f, 28f, Ez.Lin, Ez.Lin, Ez.Lin),
        BootSeg(7_440, 7_520, TOE_3, TOE_3, APEX_3, CONTACT_3, 28f, 0f, Ez.InQ, Ez.Lin, Ez.Lin),
        // the slam into the squash: the sole goes through the place where the capsule was, onto the squashed saddle
        BootSeg(7_520, 7_560, TOE_3, TOE_3, CONTACT_3, SQUASHED_3, 0f, 0f, Ez.OutQ, Ez.Lin, Ez.Lin),
        BootSeg(7_560, 7_740, TOE_3, TOE_3, SQUASHED_3, SQUASHED_3, 0f, 0f, Ez.Lin, Ez.Lin, Ez.Lin),
        // the blast kicks it up and it leaves through the top
        BootSeg(7_740, 7_830, TOE_3, TOE_3, SQUASHED_3, -190f, 0f, -10f, Ez.OutQ, Ez.Lin, Ez.Lin),
        BootSeg(7_830, T_LAST, TOE_3, TOE_3, -190f, -600f, -10f, -35f, Ez.InQ, Ez.Lin, Ez.Lin),
    )

    private val taps = intArrayOf(Film.T_TAP_1, Film.T_TAP_2, Film.T_TAP_3)

    /**
     * The impatient foot of the long pause: 0 while it hovers, 1 when the toe tip is on the rim. It falls over [TAP_FALL_MS]
     * (accelerating, a foot that is dropped) and comes off over [TAP_RISE_MS] (a rebound). No tremor. The last tap is the end of
     * the table: the boot pushes off from it.
     */
    fun tap(t: Float): Float {
        for (at in taps) {
            if (t > at - TAP_FALL_MS && t <= at) return Ease.inQuad(Ease.ph(t, at - TAP_FALL_MS, at))
            if (t > at && t < at + TAP_RISE_MS) return 1f - Ease.outQuad(Ease.ph(t, at, at + TAP_RISE_MS))
        }
        return 0f
    }

    /** The boot's size at [t]: see the object's comment. */
    fun scaleAt(t: Float): Float = when {
        t < 3_640f -> SCALE_FAR
        t < 3_780f -> Ease.lerp(SCALE_FAR, SCALE_NEAR, Ease.inQuad(Ease.ph(t, 3_640, 3_780)))
        t < 3_880f -> SCALE_NEAR
        t < 4_500f -> Ease.lerp(SCALE_NEAR, SCALE_FAR, Ease.inOut(Ease.ph(t, 3_880, 4_500)))
        t < 5_000f -> SCALE_FAR
        t < 5_100f -> Ease.lerp(SCALE_FAR, SCALE_NEAR, Ease.inQuad(Ease.ph(t, 5_000, 5_100)))
        t < 5_240f -> SCALE_NEAR
        t < 5_900f -> Ease.lerp(SCALE_NEAR, SCALE_HOVER, Ease.outCubic(Ease.ph(t, 5_240, 5_900)))
        t < Film.T_TAP_3.toFloat() -> SCALE_HOVER
        t < 7_280f -> Ease.lerp(SCALE_HOVER, SCALE_FAR, Ease.inOut(Ease.ph(t, Film.T_TAP_3, 7_280)))
        t < 7_440f -> SCALE_FAR
        t < 7_520f -> Ease.lerp(SCALE_FAR, SCALE_NEAR, Ease.inQuad(Ease.ph(t, 7_440, 7_520)))
        t < 7_740f -> SCALE_NEAR
        t < 7_830f -> Ease.lerp(SCALE_NEAR, SCALE_FAR, Ease.outQuad(Ease.ph(t, 7_740, 7_830)))
        else -> SCALE_FAR
    }

    /**
     * The boot's height factor: it stretches to [STRETCH_Y] over the last 50 ms of a fall (a foot that is dropped), and gives at the
     * contact: [SQUASH_LIGHT] for 60 ms at the first two stomps, [SQUASH_Y] from 40 ms to 140 ms after the third stomp's hit (on the
     * squashed bike). 1 otherwise.
     */
    fun squashAt(t: Float): Float {
        val s1 = Film.T_S1.toFloat()
        val s2 = Film.T_S2.toFloat()
        val s3 = Film.T_S3.toFloat()
        return when {
            t < s1 - 50f -> 1f
            t < s1 + 140f -> Ease.keyed(t, s1 - 50f, 1f, s1, STRETCH_Y, s1 + 20f, SQUASH_LIGHT, s1 + 80f, SQUASH_LIGHT, s1 + 140f, 1f)
            t < s2 - 50f -> 1f
            t < s2 + 140f -> Ease.keyed(t, s2 - 50f, 1f, s2, STRETCH_Y, s2 + 20f, SQUASH_LIGHT, s2 + 80f, SQUASH_LIGHT, s2 + 140f, 1f)
            t < s3 - 50f -> 1f
            t < s3 + 220f -> Ease.keyed(t, s3 - 50f, 1f, s3, STRETCH_Y, s3 + 40f, SQUASH_Y, s3 + 140f, SQUASH_Y, s3 + 220f, 1f)
            else -> 1f
        }
    }

    /** The shin's lean (degrees, the knee ahead of the foot): 8 on the way in, 24 at the top of each wind-up, 10 to 14 while it hovers. */
    fun lean(t: Float): Float = Ease.keyed(
        t,
        2_600f, 8f, 3_400f, 14f, APEX_TIME_1, 24f, 3_780f, 12f, 4_500f, 12f, APEX_TIME_2, 24f, 5_100f, 12f, 5_900f, 10f, 7_040f, 14f,
        APEX_TIME_3, 24f, 7_440f, 24f, 7_520f, 12f, T_LAST.toFloat(), 12f,
    )

    private const val APEX_TIME_1 = 3_640f
    private const val APEX_TIME_2 = 5_000f
    private const val APEX_TIME_3 = 7_280f

    /**
     * The leg at [t], or null before it enters (2,600 ms) and after it has left through the top (8,120 ms).
     */
    fun pose(t: Float): BootPose? {
        if (t < T_FIRST || t > T_LAST) return null
        var seg = path[path.size - 1]
        for (s in path) if (t < s.t1) { seg = s; break }
        val u = Ease.ph(t, seg.t0, seg.t1)
        val toe = Ease.lerp(seg.toe0, seg.toe1, seg.toeEz.at(u))
        val hovering = seg.t0 == 5_900
        val sole = if (hovering) Ease.lerp(HOVER_SOLE, HOVER_SOLE + TAP_DROP, tap(t)) else Ease.lerp(seg.sole0, seg.sole1, seg.soleEz.at(u))
        val angle = if (hovering) Ease.lerp(HOVER_ANGLE, TAP_ANGLE, tap(t)) else Ease.lerp(seg.a0, seg.a1, seg.angEz.at(u))
        val s = scaleAt(t)
        val sq = squashAt(t)
        val ax = toe + ANKLE_X * s
        val ay = sole + ANKLE_Y * s * sq
        val b = Math.toRadians(lean(t).toDouble())
        val kneeX = ax + SHIN * s * sin(b).toFloat()
        val kneeY = ay - SHIN * s * cos(b).toFloat()
        return BootPose(toe, sole, angle, s, kneeX, kneeY, kneeX - HIP_LEAN, sq)
    }

    /**
     * The shadow of the boot on the road: (centre x, centre y, size), 96 x 8 u at size 1. From the omen to the pop it grows from
     * 0.3 at the apex to 1.0 at contact.
     */
    fun shadow(t: Float): Triple<Float, Float, Float>? {
        if (t < Film.T_BOOT_IN || t >= Film.T_POP) return null
        val b = pose(t) ?: return null
        val size = 0.3f + 0.7f * Ease.ph(-b.soleY, 215f, 96f)
        return Triple(b.toeX - 44f, 4f, size)
    }
}

// ---------------------------------------------------------------------------------------------------------
// The bike's answers to the stomps
// ---------------------------------------------------------------------------------------------------------

/** How much a part is moved away from where the assembled bike has it: bike coordinates (u, degrees, scale). */
class Delta(val dx: Float = 0f, val dy: Float = 0f, val rot: Float = 0f, val sx: Float = 1f, val sy: Float = 1f)

/**
 * What a stomp does to the bike. All pure functions of t, so a scrubber can jump anywhere.
 *
 * Each stomp has a spring (0 at the hit, 1 at +60 ms, held through the hit-stop, then a rebound of -0.35, +0.12, -0.04 and
 * still) and a permanent damage that arrives with it and stays until the pop. The damage is big enough to read from across a
 * room, because the escalation is the joke: stomp 1 (on the saddle) drops the whole bike 5.5 u with its wheels squashed 15 %, and on
 * the rebound the bike hops, both wheels off the road; it leaves the bars bent back 32 degrees (hinged at the stem's foot) and
 * the saddle crooked. Stomp 2 (on the capsule) sags the capsule 6 u and 4 degrees, crooks the saddle to 30 degrees and sinks it 8 u,
 * puts the front wheel out of true and knocks the kickstand folded. The damage is applied to the standing bike, so the pop
 * blends from it; the reassembly brings every part back straight.
 */
object Reaction {
    private const val RISE_MS = 60

    /** The spring of a hit at [tHit] that holds for [hold] ms (the hit-stop). */
    fun spring(t: Float, tHit: Int, hold: Int): Float {
        val h = tHit.toFloat()
        val end = h + RISE_MS + hold
        return when {
            t <= h -> 0f
            t < h + RISE_MS -> Ease.outQuad(Ease.ph(t, h, h + RISE_MS))
            t <= end -> 1f
            else -> Ease.keyed(t, end, 1f, end + 140f, -0.35f, end + 260f, 0.12f, end + 380f, -0.04f, end + 500f, 0f)
        }
    }

    /** The permanent damage of a hit: 0 before it, 1 from 60 ms after it. */
    fun perm(t: Float, tHit: Int): Float = Ease.ph(t, tHit.toFloat(), tHit + RISE_MS.toFloat())

    /** The bars are bent back by this much by the first stomp (about the foot of the stem: the stem stays on the fork). */
    const val BARS_BENT = -32f
    private const val HINGE_X = 37.6f
    private const val HINGE_Y = -96f

    /** The saddle is pressed by the first stomp (the boot stands on it) and left crooked: 10 degrees, 2.5 u down. */
    const val SADDLE_PRESS_1 = 3f
    const val SADDLE_CROOKED_1 = 10f
    const val SADDLE_SUNK_1 = 2.5f

    /** ... and by the second stomp left crooked for good: its nose down by this much in all, and sunk. */
    const val SADDLE_CROOKED = 30f
    const val SADDLE_SUNK = 8f

    /** The capsule is left sagging by the second stomp. */
    const val TUBE_SAG = 6f
    const val TUBE_NOSE = 4f

    /**
     * The first stomp is felt in the whole body: the frame drops this far and the wheels squash by [WHEEL_SQUASH_1] (held), and the
     * rebound throws the bike up [BODY_HOP_1] u per unit of the spring (4.9 u at its peak), the wheels with it: both are off the road
     * for about 125 ms.
     */
    const val BODY_DROP_1 = 5.5f
    const val BODY_HOP_1 = 14f
    const val WHEEL_SQUASH_1 = 0.15f

    /** The second stomp leaves the front wheel out of true: wider by this much, and turned. */
    const val FRONT_OUT_OF_TRUE = 0.06f
    const val FRONT_OUT_OF_TRUE_ROT = 12f

    /** The kickstand is knocked folded back by this many degrees at the second stomp (80 ms), and drops straight again with the reassembly. */
    const val STAND_FOLD = 38f

    /** A part turned by [rot] about the point ([hx], [hy]) of the bike instead of about its own pivot. */
    private fun hinged(p: Part, rot: Float, hx: Float, hy: Float, dy: Float): Delta {
        val px = hx - p.px
        val py = hy - p.py
        val r = Math.toRadians(rot.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return Delta(dx = px - (px * c - py * s), dy = dy + py - (px * s + py * c), rot = rot)
    }

    /** The kickstand's fold in degrees: knocked back at the second stomp, put right again while the rear triangle comes home. */
    fun standFold(t: Float): Float {
        val knocked = STAND_FOLD * Ease.outQuad(Ease.ph(t, Film.T_S2.toFloat(), Film.T_S2 + 80f))
        val back = Ease.inOut(Ease.ph(t, Film.T_HOMEFLIGHT + 360f, Film.T_HOMEFLIGHT + 360f + FilmWorld.RETURN_MS))
        return knocked * (1f - back)
    }

    fun delta(p: Part, t: Float): Delta {
        if (t < Film.T_S1) return NONE
        val s1 = spring(t, Film.T_S1, Film.HIT_STOP_1)
        val s2 = spring(t, Film.T_S2, Film.HIT_STOP_2)
        val p1 = perm(t, Film.T_S1)
        val p2 = perm(t, Film.T_S2)
        // the body drops with the hit and is thrown up by the rebound
        val drop1 = if (s1 >= 0f) BODY_DROP_1 * s1 else BODY_HOP_1 * s1
        return when (p) {
            Part.Cockpit -> hinged(p, BARS_BENT * p1 - 12f * max(0f, s1) + 4f * min(0f, s1) + 5f * s2, HINGE_X, HINGE_Y, drop1)
            Part.Saddle -> Delta(
                dy = drop1 + SADDLE_PRESS_1 * s1 + SADDLE_SUNK_1 * p1 + (SADDLE_SUNK - SADDLE_SUNK_1) * p2 + 4f * s2,
                rot = 3f * s1 + SADDLE_CROOKED_1 * p1 + (SADDLE_CROOKED - SADDLE_CROOKED_1) * p2 + 6f * s2,
            )
            Part.Tube -> Delta(dy = drop1 + 6f * s2 + TUBE_SAG * p2, rot = TUBE_NOSE * p2 + 1.5f * s2)
            Part.RearTri, Part.DownTube, Part.Fork -> Delta(dy = drop1 + 2.5f * s2)
            // the fenders and the lock hang on the frame: they go down with it
            Part.RearFender, Part.FrontFender, Part.Lock -> Delta(dy = drop1)
            Part.RearWheel, Part.FrontWheel -> {
                // the wheels squash and stay on the road; they go with the frame on the rebound (both off the road), the front one is left out of true
                val front = p == Part.FrontWheel
                val sy = 1f - WHEEL_SQUASH_1 * s1 - 0.10f * s2
                val sx = 1f + 0.05f * s1 + 0.04f * s2 + (if (front) FRONT_OUT_OF_TRUE * p2 else 0f)
                val rot = if (front) FRONT_OUT_OF_TRUE_ROT * p2 else 0f
                val hop = (BODY_HOP_1 - Geo.WHEEL_R * WHEEL_SQUASH_1) * max(0f, -s1) + 3f * max(0f, -s2) + frontHop3(p, t)
                // the lowest point of the (turned, squashed) ring stays on the road
                val th = Math.toRadians(rot.toDouble())
                val h = Geo.WHEEL_R * sqrt((sx * sin(th)).let { it * it } + (sy * cos(th)).let { it * it }).toFloat()
                Delta(dy = Geo.WHEEL_R - h - hop, rot = rot, sx = sx, sy = sy)
            }
        }
    }

    /** The squash of the third stomp: the front wheel hops 2 u, the rear one stays on the road. */
    private fun frontHop3(p: Part, t: Float): Float {
        if (p != Part.FrontWheel || t < Film.T_S3) return 0f
        return 2f * sin(PI * Ease.ph(t, Film.T_S3, Film.T_S3 + 120).toDouble()).toFloat()
    }

    private val NONE = Delta()
}

/**
 * The road remembers the stomps: one crack per stomp, each worse than the last (a hairline, a forked one, the burst that runs
 * under the wreck, about 70 u wide). Flat lines, growing from the road's edge over [GROW_MS] from the hit; they stay through the
 * wreck shots and fade in the three cuts of the light's return ([fade], with the dim), so that the tableau stands on a whole road.
 * They are not retracted. The silence shot (a 2.3x lens on the fallen tube) shows only the hairline of each crack: the road
 * stays empty there. Pure data and pure functions of t.
 */
object Cracks {
    const val GROW_MS = 60

    /** A branch of a crack: it starts when the crack has grown to [from] (0..1), and runs along [pts] (x, y pairs, road coordinates in u). */
    class Stroke(val crack: Int, val from: Float, val pts: FloatArray)

    /** Where each crack starts on the kerb (x in u): under the saddle the boot hit, under the capsule, and the burst in the middle. */
    val originX = floatArrayOf(-26f, 14f, -6f)

    /** The line width of each crack in dp: a hairline, a forked one, the burst. */
    val widthDp = floatArrayOf(1.2f, 1.7f, 2.4f)

    /** The crack data is drawn at this multiple of the numbers below: a hairline has to be seen from across a phone. */
    private const val K = 1.3f

    private fun pts(vararg v: Float) = FloatArray(v.size) { v[it] * K }

    val strokes: List<Stroke> = listOf(
        // 1: a hairline
        Stroke(0, 0f, pts(0f, 1f, -1.5f, 5f, 1f, 9f, -0.5f, 14f, 2f, 19f)),
        // 2: forked
        Stroke(1, 0f, pts(0f, 1f, 2.5f, 6f, -1f, 11f, 3f, 17f, 0.5f, 24f)),
        Stroke(1, 0.25f, pts(2.5f, 6f, 8f, 9f, 12f, 15f, 11f, 22f)),
        Stroke(1, 0.45f, pts(-1f, 11f, -7f, 15f, -10f, 22f)),
        // 3: the burst, under the wreck: 54 raw units wide (70 u), a main line and six branches
        Stroke(2, 0f, pts(0f, 1f, -3f, 7f, 2f, 13f, -2f, 20f, 2f, 28f, 0f, 36f)),
        Stroke(2, 0.18f, pts(-3f, 7f, -11f, 9f, -19f, 14f, -26f, 15f)),
        Stroke(2, 0.30f, pts(2f, 13f, 11f, 15f, 19f, 20f, 27f, 21f)),
        Stroke(2, 0.50f, pts(-2f, 20f, -10f, 25f, -18f, 28f, -22f, 35f)),
        Stroke(2, 0.55f, pts(-19f, 14f, -23f, 22f, -22f, 29f)),
        Stroke(2, 0.60f, pts(2f, 28f, 10f, 33f, 17f, 35f)),
        Stroke(2, 0.65f, pts(19f, 20f, 23f, 28f, 22f, 33f)),
    )

    fun hitAt(crack: Int): Int = when (crack) {
        0 -> Film.T_S1
        1 -> Film.T_S2
        else -> Film.T_S3
    }

    /** How far crack [crack] has grown, 0..1: from the hit over [GROW_MS]; it does not go back. */
    fun amount(t: Float, crack: Int): Float = Ease.outQuad(Ease.ph(t, hitAt(crack), hitAt(crack) + GROW_MS))

    /**
     * The cracks' opacity: full until the light's first cut, then fading in the same three cuts as the dim (0.34, 0.18, 0), 0.6,
     * 0.3 and gone: the road is whole at the third light, before the bike comes home.
     */
    fun fade(t: Float): Float = when {
        t < Film.T_LIGHT_1 -> 1f
        t < Film.T_LIGHT_2 -> 0.6f
        t < Film.T_LIGHT_3 -> 0.3f
        else -> 0f
    }

    /** In the silence shot the road stays empty but for the hairline of each crack (a branch is drawn only outside it), [LENS_REACH] of its length. */
    fun drawsBranch(t: Float): Boolean = !Camera.inSilenceShot(t)
    const val LENS_REACH = 0.45f
}

/** The tableau: 36 u up, bobbing 3 u with a 2.4 s period, settling back for the handoff. */
object Lift {
    const val UP = 36f
    const val BOB = 3f
    const val PERIOD_MS = 2_400f

    /** The lift that parts return to (rises from 460 ms before the tableau, then bobs, then comes down with the handoff camera). */
    fun at(t: Float, handoff: Boolean): Float {
        val rise = Ease.inOut(Ease.ph(t, Film.T_TABLEAU - 460, Film.T_TABLEAU))
        val bob = if (t > Film.T_TABLEAU) BOB * sin(2.0 * PI * (t - Film.T_TABLEAU) / PERIOD_MS).toFloat() else 0f
        val settle = if (handoff) Ease.fastOutSlowIn(Ease.ph(t, Film.T_HANDOFF, Film.T_CUT)) else 0f
        return (UP * rise + bob) * (1f - settle)
    }
}

/** The halo: 24 dots, one every 36 ms. Returns how many are on. */
object Halo {
    fun count(t: Float): Int {
        if (t < Film.T_HALO) return 0
        return ((t - Film.T_HALO) / Film.HALO_STEP).toInt().plus(1).coerceAtMost(Film.HALO_DOTS)
    }

    /** Over the middle of the bike (the top tube's centre), 28 u above the handlebar's top (-122.4) plus the lift. */
    val cx = Geo.TUBE_CX
    val cy = -150f
    val rx = 35f
    val ry = 9f
    const val DOT_R = 2.3f
    fun angle(i: Int): Float = (PI + i * 2.0 * PI / Film.HALO_DOTS).toFloat()
}

/** The life sign: one dot (17, 4), the crossbar of the 4, lights, hops home into the fallen tube and breathes. */
object LifeSign {
    const val COL = 17
    const val ROW = 4
    const val HOP_HEIGHT = 10f

    /** 0 before it wakes; 0.35 while it hops; then the breathing level 0.35..1; 0 again from the first light on the road. */
    fun glow(t: Float): Float {
        if (t < Film.T_LIFE_HOP || t >= Film.T_LIGHT_3) return 0f
        if (t < Film.T_LIFE_HOME) return 0.35f
        return (0.675 - 0.325 * cos(2.0 * PI * (t - Film.T_LIFE_HOME) / 1_600.0)).toFloat()
    }

    fun hop(t: Float): Float {
        val u = Ease.ph(t, Film.T_LIFE_HOP, Film.T_LIFE_HOME)
        return 4f * u * (1f - u) * HOP_HEIGHT
    }
}

/**
 * The speed readout during the roll-in: hard cuts through 12, 8, 5, 3, 1, 0. The speed is the slope of the roll-in's ease
 * (12 km/h x (1 - u)^2), so every cut falls where the number crosses the next whole step.
 */
object Readout {
    val frames = listOf(FrameId.S12, FrameId.S8, FrameId.S5, FrameId.S3, FrameId.S1, FrameId.S0)

    /** Progress u of the roll-in at which each frame after the first takes over. */
    val cuts = floatArrayOf(0.18f, 0.357f, 0.5f, 0.714f, 0.889f)

    fun at(t: Float): FrameId {
        val u = Ease.ph(t, Film.T_ROLL_START, Film.T_ROLL_END)
        var i = 0
        while (i < cuts.size && u >= cuts[i]) i++
        return frames[i]
    }
}

/** True when |a - b| is below [eps]. */
internal fun near(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) < eps
