package dev.steven.s3control.ui.intro

import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The world of the film: the bike's eleven rigid parts, where each one stands, hangs, falls and lies, and the 252 dots of
 * the top-tube matrix (the 46 lit ones of the error code E42 fly on their own, the rest stay in the capsule). Everything
 * is computed once in [FilmWorld.build] (deterministic; only the vertical stretch of the exploded view depends on the
 * stage) and read as pure functions of t, so a scrubber can jump anywhere and the tests can pin it down.
 *
 * Units: 1 u. The bike is 185 u long, 123 u high (drawn after a photo of the real S3, 28-inch wheels); the road is y = 0,
 * up is negative. The bike never leaves the road: it rolls in, stands, is stomped three times (the damage of the first
 * two stays on it, see [Reaction]), is squashed flat by the third and bursts.
 */

/** A rigid body's pose: pivot at ([x], [y]), turned [rot] degrees about it, scaled [sx], [sy] about it (squash, coin). */
class Pose(val x: Float, val y: Float, val rot: Float, val sx: Float = 1f, val sy: Float = 1f)

/** The eleven parts. [px], [py] is the pivot in bike coordinates (the part's centroid). */
enum class Part(
    val px: Float,
    val py: Float,
    /** Fixed rotation in the exploded view. */
    val explodedRot: Float,
    /** Where it lies at the end of the drop: centroid x and rotation. */
    val restX: Float,
    val restRot: Float,
    /** Delay of the fall after the hang, 0..120 ms. */
    val stagger: Int,
    val restitution: Float,
) {
    Tube(Geo.TUBE_CX, Geo.TUBE_CY, 4f, 12f, 3f, 0, 0.10f),
    RearWheel(-56f, -36.5f, -35f, -98f, 0f, 0, 0.38f),
    FrontWheel(56f, -36.5f, 40f, 84f, 0f, 30, 0.38f),
    RearTri(-26f, -42f, -14f, -48f, 68f, 40, 0.20f),
    DownTube(14f, -57f, 18f, 46f, -12f, 20, 0.20f),
    Fork(46f, -66f, 22f, 70f, 82f, 100, 0.20f),
    Cockpit(31f, -110f, -28f, 100f, -20f, 120, 0.20f),
    Saddle(-27.5f, -102f, -32f, -20f, 14f, 110, 0.20f),
    RearFender(-58.6f, -65.9f, 20f, -70f, 12f, 60, 0.20f),
    FrontFender(42.0f, -61.3f, -18f, 16f, -10f, 70, 0.20f),
    Lock(-54.5f, -37.5f, 45f, -78f, 35f, 90, 0.20f),
    ;

    val isWheel: Boolean get() = this == RearWheel || this == FrontWheel
}

/** The bike's assembled motion at one instant: translation, rotation about [Geo.CX], CY (always 0 now), squash about the road. */
class BikeMotion(val x: Float, val y: Float, val rot: Float, val squash: Float)

class FilmWorld private constructor(
    /** Gravity in u/s2: [Geo.G], times the stretch of the exploded view so that a taller fall takes as long. */
    private val g: Float,
    /** Rest pivot y of every part, so that its lowest hull point touches the road. */
    private val restY: FloatArray,
    private val hangX: FloatArray,
    private val hangY: FloatArray,
    // dots
    val dotCol: IntArray,
    val dotRow: IntArray,
    val dotLit: BooleanArray,
    private val dotHangX: FloatArray,
    private val dotHangY: FloatArray,
    private val dotRestX: FloatArray,
    private val dotRestY: FloatArray,
    private val dotStagger: IntArray,
    private val dotCellX: FloatArray,
    private val dotCellY: FloatArray,
) {
    val dotCount: Int get() = dotCol.size

    /** The dots that fly: the lit ones of the error code (E42). The others never leave the capsule. */
    val flyers: IntArray = (0 until dotCol.size).filter { dotLit[it] }.toIntArray()
    fun flies(i: Int): Boolean = dotLit[i]
    val partCount: Int get() = Part.entries.size

    fun restY(p: Part) = restY[p.ordinal]
    fun hangX(p: Part) = hangX[p.ordinal]
    fun hangY(p: Part) = hangY[p.ordinal]
    fun restPose(p: Part): Pose = if (p == Part.RearWheel) Pose(p.restX, -Geo.WHEEL_R * Geo.COIN_MIN, 0f, 1f, Geo.COIN_MIN) else Pose(p.restX, restY(p), p.restRot)

    /** A number that changes when anything in the world changes: build() twice must give the same one. */
    fun fingerprint(): Int {
        var h = 17
        fun mix(a: FloatArray) { for (v in a) h = h * 31 + v.toBits() }
        fun mix(a: IntArray) { for (v in a) h = h * 31 + v }
        h = h * 31 + g.toBits()
        mix(restY); mix(hangX); mix(hangY); mix(dotCol); mix(dotRow)
        mix(dotHangX); mix(dotHangY); mix(dotRestX); mix(dotRestY); mix(dotStagger); mix(dotCellX); mix(dotCellY)
        for (b in dotLit) h = h * 31 + (if (b) 1 else 0)
        return h
    }

    fun isSane(): Boolean {
        val all = listOf(restY, hangX, hangY, dotHangX, dotHangY, dotRestX, dotRestY, dotCellX, dotCellY)
        return all.all { a -> a.all { it.isFinite() } }
    }

    // -------------------------------------------------------------------------------------------------
    // The assembled bike
    // -------------------------------------------------------------------------------------------------

    /**
     * The bike as one piece: rolling in from the left, standing, and squashed flat (about the road) in 40 ms at the third
     * stomp. It never leaves the road; the damage of the stomps is per part, see [Reaction].
     */
    fun bike(t: Float): BikeMotion = when {
        t < Film.T_ROLL_START -> BikeMotion(ROLL_FROM, 0f, 0f, 1f)
        t < Film.T_ROLL_END -> BikeMotion(ROLL_FROM * (1f - Ease.outCubic(Ease.ph(t, Film.T_ROLL_START, Film.T_ROLL_END))), 0f, 0f, 1f)
        t < Film.T_S3 -> BikeMotion(0f, 0f, 0f, 1f)
        // the third stomp flattens it in [Film.SQUASH_MS] (the boot slams down through the bike in the same time), then it stays flat
        else -> BikeMotion(0f, 0f, 0f, Ease.lerp(1f, SQUASH, Ease.outQuad(Ease.ph(t, Film.T_S3, Film.T_S3 + Film.SQUASH_MS))))
    }

    /** A part of the assembled bike moved by [m] and bent by [d] (the damage lives in bike coordinates, so the squash scales it too). */
    private fun assembled(p: Part, m: BikeMotion, d: Delta): Pose {
        val dx = p.px - Geo.CX
        val dy = p.py - Geo.CY
        val r = Math.toRadians(m.rot.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val wx = Geo.CX + dx * c - dy * s + m.x + d.dx
        val wy = Geo.CY + dx * s + dy * c + m.y + d.dy
        return Pose(wx, wy * m.squash, (m.rot % 360f) + d.rot, d.sx, d.sy * m.squash)
    }

    /** Where a part sits when the bike is whole and back home (x = 0), lifted. */
    private fun home(p: Part, t: Float, handoff: Boolean): Pose = Pose(p.px, p.py - Lift.at(t, handoff), 0f)

    // -------------------------------------------------------------------------------------------------
    // The parts
    // -------------------------------------------------------------------------------------------------

    private fun exploded(p: Part): Pose = Pose(hangX(p), hangY(p), p.explodedRot)

    /** Return window of a part, ms: [Film.T_HOMEFLIGHT] + n, so that the last one is home exactly at [Film.T_TABLEAU]. */
    private fun returnFrom(p: Part): Int = Film.T_HOMEFLIGHT + when (p) {
        Part.RearWheel -> 240
        Part.Lock -> 300
        Part.RearTri -> 360
        Part.DownTube -> 420
        Part.RearFender, Part.FrontFender, Part.FrontWheel -> 440
        Part.Tube -> 500
        Part.Fork -> 540
        Part.Saddle -> 580
        Part.Cockpit -> 600
    }

    /** First instant a part touches the road, in ms (the tube's is the big thud). */
    fun firstContact(p: Part): Float {
        if (p == Part.RearWheel) return REAR_WHEEL_TOUCH
        val h = restY(p) - hangY(p)
        return Film.T_DROP + p.stagger + 1000f * sqrt(2f * max(h, 0f) / g)
    }

    private fun dropStart(p: Part): Float {
        if (p != Part.RearWheel) return Film.T_DROP + p.stagger.toFloat()
        // the rear wheel's first touch is pinned to the coin-settle; it hangs until it has to fall
        val h0 = -Geo.WHEEL_R - hangY(p)
        return REAR_WHEEL_TOUCH - 1000f * sqrt(2f * max(h0, 0f) / g)
    }

    /** The pose of [p] at [t]. [handoff] as in [Lift]. */
    fun pose(p: Part, t: Float, handoff: Boolean = false): Pose {
        if (t < Film.T_POP) return assembled(p, bike(t), Reaction.delta(p, t))
        if (t < Film.T_HANG) {
            // the pop: 95 % of the way in 100 ms, a little past the hang pose, back to it by the end of the hang's first beat.
            // It starts from the damaged, squashed pose.
            val a = assembled(p, bike(Film.T_POP - 1f), Reaction.delta(p, Film.T_POP - 1f))
            return blend(a, exploded(p), Ease.pop(t - Film.T_POP))
        }
        val back = returnFrom(p)
        if (t >= back + RETURN_MS) return home(p, t, handoff)
        val rest = if (t >= back) restPose(p) else dropPose(p, t)
        if (t < back) return rest
        return blend(rest, home(p, t, handoff), Ease.inOut(Ease.ph(t, back, back + RETURN_MS)))
    }

    /** Hang, then the fall: x and rotation settle by the first contact, y bounces with the part's restitution. */
    private fun dropPose(p: Part, t: Float): Pose {
        val t0 = dropStart(p)
        if (t <= t0) return exploded(p)
        val rest = restPose(p)
        val tau = (t - t0) / 1000f
        if (p == Part.RearWheel) {
            val touch = REAR_WHEEL_TOUCH
            val h0 = (-Geo.WHEEL_R) - hangY(p)
            val fallT = sqrt(2f * max(h0, 0f) / g)
            val e = Ease.ph(t, touch - fallT * 1000f, touch)
            val phi = coinAngle(t)
            val sy = max(sin(Math.toRadians(phi.toDouble())).toFloat(), Geo.COIN_MIN)
            val x = Ease.lerp(hangX(p), p.restX, Ease.inOut(e))
            val air = if (t < touch) 0.5f * g * ((touch - t) / 1000f) * ((touch - t) / 1000f) else 0f
            return Pose(x, -Geo.WHEEL_R * sy - air, Ease.lerp(p.explodedRot, 0f, Ease.inOut(e)), 1f, sy)
        }
        val h = rest.y - hangY(p)
        val t1 = sqrt(2f * max(h, 0.001f) / g)
        val k = Ease.inOut((tau / t1).coerceIn(0f, 1f))
        return Pose(
            Ease.lerp(hangX(p), rest.x, k),
            fallY(hangY(p), rest.y, p.restitution, tau, g),
            Ease.lerp(p.explodedRot, rest.rot, k),
        )
    }

    /** The ring tilts like a coin settling: 90 to 0 at the touch, then 28, 14, 6 degrees of rocking, each a touch. */
    fun coinAngle(t: Float): Float {
        val d = Film.T_DROP.toFloat()
        return Ease.keyed(
            t,
            d + 280f, 90f, d + 440f, 0f, d + 530f, 28f, d + 620f, 0f, d + 680f, 14f, d + 740f, 0f, d + 780f, 6f, d + 820f, 0f,
        )
    }

    private fun blend(a: Pose, b: Pose, e: Float) = Pose(
        Ease.lerp(a.x, b.x, e), Ease.lerp(a.y, b.y, e), Ease.lerp(a.rot, b.rot, e),
        Ease.lerp(a.sx, b.sx, e), Ease.lerp(a.sy, b.sy, e),
    )

    /** Wheel turn in degrees: the roll-in, by distance. */
    fun spin(t: Float): Float {
        val x = ROLL_FROM * (1f - Ease.outCubic(Ease.ph(t, Film.T_ROLL_START, Film.T_ROLL_END)))
        return (x - ROLL_FROM) / Geo.WHEEL_R * RAD_TO_DEG
    }

    /** Degrees a wheel turns when it rolls [dx] along the road. */
    fun rollDeg(dx: Float): Float = dx / Geo.WHEEL_R * RAD_TO_DEG

    // -------------------------------------------------------------------------------------------------
    // The dots
    // -------------------------------------------------------------------------------------------------

    /**
     * [home] is the progress 0..1 of a dot's flight back into the capsule (0 before it, while it lies or flies the other way),
     * so the drawing can shrink and dim it into the socket it is going to. [life] marks the life-sign dot: it is always led.
     */
    class Dot(
        val x: Float,
        val y: Float,
        val lit: Boolean,
        val glow: Float,
        val visible: Boolean,
        val home: Float = 0f,
        val life: Boolean = false,
    )

    /** The world position of cell ([col], [row]) of the tube lying in [tube]'s pose (scaled, then turned, then moved). */
    fun cellWorld(col: Int, row: Int, tube: Pose): Pair<Float, Float> {
        val lx = (Geo.cellX(col) - Part.Tube.px) * tube.sx
        val ly = (Geo.cellY(row) - Part.Tube.py) * tube.sy
        val r = Math.toRadians(tube.rot.toDouble())
        val c = cos(r).toFloat()
        val sn = sin(r).toFloat()
        return Pair(tube.x + lx * c - ly * sn, tube.y + lx * sn + ly * c)
    }

    /** The tube in its dented, squashed pose at the moment of the pop: where the flying dots start. */
    private val squashedTube = pose(Part.Tube, Film.T_POP - 1f)

    /**
     * One dot at [t]. A dot that does not fly (a dark socket of the capsule) rides in its cell, whatever the capsule does: it
     * drops straight down with it. A flying dot (a lit one of the E42) leaves its socket at the pop, radially, hangs, falls to
     * the road, lies there glowing red until the silence, and flies home column by column (now in led: the error is cleared)
     * once the light is back.
     */
    fun dot(i: Int, t: Float, handoff: Boolean = false): Dot {
        val col = dotCol[i]
        val row = dotRow[i]
        if (!dotLit[i]) {
            val cell = cellWorld(col, row, pose(Part.Tube, t, handoff))
            return Dot(cell.first, cell.second, false, 1f, true)
        }
        val isLife = col == LifeSign.COL && row == LifeSign.ROW
        val dep = Film.T_HOMEFLIGHT + col * Film.DOT_STAGGER
        if (t < Film.T_HANG) {
            // the pop: out of the squashed capsule, radially, to the hang
            val (sx, sy) = cellWorld(col, row, squashedTube)
            // lighter than the parts, and each at its own speed (the stagger, 0.85..1.35): they overshoot their place and settle
            val e = Ease.popDot((t - Film.T_POP) * (0.85f + dotStagger[i] / 240f))
            return Dot(Ease.lerp(sx, dotHangX[i], e), Ease.lerp(sy, dotHangY[i], e), true, 1f, true, life = isLife)
        }
        // the drop: hang, fall, bounce, lie on the road
        val t0 = Film.T_DROP + dotStagger[i].toFloat()
        val tau = (t - t0) / 1000f
        val fall = Ease.inOut((tau / fallTime(i)).coerceIn(0f, 1f))
        // the scatter exists only on the way down: a swerve by the dot's own stagger, nothing at the hang and none on the road
        val swerve = if (t <= t0) 0f else (dotStagger[i] - 60) / 60f * DROP_SCATTER * sin(PI * fall.toDouble()).toFloat()
        var x = if (t <= t0) dotHangX[i] else Ease.lerp(dotHangX[i], dotRestX[i], fall) + swerve
        var y = if (t <= t0) dotHangY[i] else fallY(dotHangY[i], dotRestY[i], 0.35f, tau, g)
        // the E42 glows on the road until the silence, then goes out in a cut
        var lit = t < Film.T_LAST_LIGHT_OUT
        var glow = 1f
        var home = 0f
        var fromX = dotRestX[i]
        var fromY = dotRestY[i]
        if (isLife) {
            // the life sign: lights, hops home into the fallen tube and breathes, goes out (a cut) as the light returns
            val lying = cellWorld(col, row, restPose(Part.Tube))
            if (t >= Film.T_LIFE_HOP) {
                val u = Ease.ph(t, Film.T_LIFE_HOP, Film.T_LIFE_HOME)
                x = Ease.lerp(dotRestX[i], lying.first, u)
                y = Ease.lerp(dotRestY[i], lying.second, u) - LifeSign.hop(t)
                glow = LifeSign.glow(t)
                lit = glow > 0f
                fromX = lying.first
                fromY = lying.second
            }
        }
        if (t >= dep) {
            // the homeflight: to the cell in the tube's pose of this very moment, lit again and dimming into its socket
            val target = cellWorld(col, row, pose(Part.Tube, t, handoff))
            val e = Ease.inOut(Ease.ph(t, dep, dep + Film.DOT_FLIGHT))
            x = Ease.lerp(fromX, target.first, e)
            y = Ease.lerp(fromY, target.second, e)
            lit = false
            glow = 1f
            home = Ease.ph(t, dep, dep + Film.DOT_FLIGHT)
        }
        return Dot(x, y, lit, glow, true, home, life = isLife)
    }

    private fun fallTime(i: Int): Float = sqrt(2f * max(dotRestY[i] - dotHangY[i], 0.001f) / g)

    companion object {
        /** The bike rolls in from this far left: half of the widest stage (a square one is 227 u wide), plus the bike's own 111 u. */
        const val ROLL_FROM = -(113.5f + 111.5f)

        /** Where the exploded view is centred: the wide camera's middle. */
        const val HANG_CX = -4f
        const val SQUASH = 0.38f
        const val RETURN_MS = 700
        const val REAR_WHEEL_TOUCH = Film.T_DROP + 440f
        const val DOTS = 252
        private const val RAD_TO_DEG = (180.0 / PI).toFloat()

        /** Free fall onto [yr] from [y0] with bounces (restitution [e]), tau seconds after release. y grows downward. */
        fun fallY(y0: Float, yr: Float, e: Float, tau: Float, g: Float = Geo.G): Float {
            if (tau <= 0f) return y0
            val h = yr - y0
            if (h <= 0f) return yr
            val t1 = sqrt(2f * h / g)
            if (tau <= t1) return y0 + 0.5f * g * tau * tau
            var v = e * g * t1
            var rem = tau - t1
            repeat(4) {
                val air = 2f * v / g
                if (rem <= air) return yr - (v * rem - 0.5f * g * rem * rem)
                rem -= air
                v *= e
            }
            return yr
        }

        private val byStretch = java.util.concurrent.ConcurrentHashMap<Int, FilmWorld>()

        /** The world for a vertical stretch, built once per step of 0.05 and shared (a world is immutable). */
        fun forStretch(vStretch: Float): FilmWorld {
            val key = (vStretch * 20f).roundToInt()
            return byStretch.getOrPut(key) { build(key / 20f) }
        }

        /** The world for [stage]: its exploded view is as tall as the stage's sky allows. */
        internal fun forStage(stage: Stage): FilmWorld = forStretch(stage.explodeStretch)

        /**
         * How big the E42 hangs in the air: the cells' own layout scaled by this, the same in x and y, so that it stays a clean,
         * readable E42 (no push, no jitter). The scatter comes with the drop: see [DROP_SCATTER].
         */
        private const val HANG_SCALE = 2.0f

        /** How far (u) a dot swerves sideways, at most, on its way down: the heap on the road is not a copy of the code. */
        private const val DROP_SCATTER = 6f

        /**
         * Builds the world. Deterministic and well under a millisecond. [vStretch] (1 on an ordinary stage, 1.35 on a tall one:
         * [Stage.explodeStretch]) spreads the exploded view upward and raises gravity by the same factor, so every fall takes
         * exactly as long as it does on the square stage and the haptic cues still land on the thud.
         */
        fun build(vStretch: Float = 1f): FilmWorld {
            val parts = Part.entries
            val hx = FloatArray(parts.size)
            val hy = FloatArray(parts.size)
            val ry = FloatArray(parts.size)
            for (p in parts) {
                hx[p.ordinal] = HANG_CX + Geo.CX + (p.px - Geo.CX) * 1.4f
                // the view is taller than it is wide: the parts go up (1.8x, 52 u higher), the picture's sky is where they fly;
                // a tall stage has more of it, and the whole view goes up by [vStretch]
                hy[p.ordinal] = (Geo.CY + (p.py - Geo.CY) * 1.8f - 52f) * vStretch
                ry[p.ordinal] = Geo.restY(p)
            }
            val rnd = Random(0x59414241)
            val frame = MatrixFrame.text(Film.ERROR_CODE)
            val start = (36 - frame.width) / 2
            val n = 36 * 7
            val col = IntArray(n); val row = IntArray(n); val lit = BooleanArray(n)
            val hangX = FloatArray(n); val hangY = FloatArray(n)
            val restX = FloatArray(n); val restYd = FloatArray(n)
            val stagger = IntArray(n)
            val cellX = FloatArray(n); val cellY = FloatArray(n)
            val tubeHangX = hx[Part.Tube.ordinal]
            val tubeHangY = hy[Part.Tube.ordinal]
            val tubeRestX = Part.Tube.restX
            var i = 0
            for (c in 0 until 36) for (r in 0 until MatrixFont.ROWS) {
                col[i] = c; row[i] = r
                val mask = frame.columns.getOrElse(c - start) { 0 }
                lit[i] = mask and (1 shl r) != 0
                cellX[i] = Geo.cellX(c); cellY[i] = Geo.cellY(r)
                // out of the capsule to the hang, radially from its middle: the cell's own layout, bigger, clean
                val dx = cellX[i] - Part.Tube.px
                val dy = cellY[i] - Part.Tube.py
                hangX[i] = tubeHangX + dx * HANG_SCALE
                hangY[i] = tubeHangY + dy * HANG_SCALE
                restX[i] = tubeRestX + (cellX[i] - Part.Tube.px) * 1.25f + (rnd.nextFloat() * 2f - 1f) * 5f
                val q = rnd.nextFloat()
                restYd[i] = -(0.8f + 2.2f * q * q)
                stagger[i] = rnd.nextInt(121)
                i++
            }
            return FilmWorld(Geo.G * vStretch, ry, hx, hy, col, row, lit, hangX, hangY, restX, restYd, stagger, cellX, cellY)
        }
    }
}

/** The numbers of the bike's drawing that more than one file needs. */
object Geo {
    /** The tyre's outer radius (a 28-inch wheel on the photo's scale), and the numbers of the wheel's drawing. */
    const val WHEEL_R = 36.5f
    const val TYRE_T = 5.0f
    const val RIM_R = 30.6f
    const val FENDER_R = 38.4f
    const val FENDER_W = 3.0f

    /** The fenders' arcs, degrees clockwise from 3 o'clock about the axle (they hug the tyre, 1.9 u off it): the front one wraps over it to about 2:30, as in the photo; the rear one's tail is low. */
    const val FRONT_FENDER_FROM = 166f
    const val FRONT_FENDER_TO = 315f
    const val REAR_FENDER_FROM = 195f
    const val REAR_FENDER_TO = 335f
    const val G = 900f

    /** The thinnest the coin-flat rear wheel gets, so it never vanishes. */
    const val COIN_MIN = 0.10f

    /** The exploded view's centre (the bike's centre of mass lies on it). */
    const val CX = 0f
    const val CY = -55f

    /** The wheels' axles: x = +-56, y = -WHEEL_R. */
    const val AXLE_X = 56f

    // The top tube capsule: x -38..46, y -96..-74.2, field 36 x 7. Its middle is (TUBE_CX, TUBE_CY).
    const val TUBE_L = -38f
    const val TUBE_T = -96f
    const val TUBE_W = 84f
    const val TUBE_H = 21.8f
    const val PAD_X = 9.0f
    const val PAD_Y = 4.48f
    const val PITCH = (TUBE_W - 2f * PAD_X) / 36f
    const val TUBE_CX = 4f
    const val TUBE_CY = -85.1f

    fun cellX(col: Int) = TUBE_L + PAD_X + (col + 0.5f) * PITCH
    fun cellY(row: Int) = TUBE_T + PAD_Y + (row + 0.5f) * PITCH

    /** A hull disc: centre in bike coordinates and radius. The lowest point of a pose rests on the road. */
    class Disc(val x: Float, val y: Float, val r: Float)

    private fun seg(ax: Float, ay: Float, bx: Float, by: Float, w: Float) = listOf(Disc(ax, ay, w / 2f), Disc(bx, by, w / 2f))

    /** Five discs of r 1.5 along the arc about ([cx], [cy]) from [a0] to [a1] degrees (clockwise from 3 o'clock, y down). */
    private fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float): List<Disc> =
        (0..4).map {
            val a = Math.toRadians((a0 + (a1 - a0) * it / 4f).toDouble())
            Disc(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), 1.5f)
        }

    fun hull(p: Part): List<Disc> = when (p) {
        Part.Tube -> listOf(Disc(-27.1f, TUBE_CY, 10.9f), Disc(35.1f, TUBE_CY, 10.9f))
        Part.RearWheel -> listOf(Disc(-AXLE_X, -WHEEL_R, WHEEL_R))
        Part.FrontWheel -> listOf(Disc(AXLE_X, -WHEEL_R, WHEEL_R))
        Part.RearTri ->
            seg(-24f, -80f, -9.5f, -36.5f, 4.6f) + seg(-27f, -78f, -56f, -36.5f, 4.0f) + seg(-56f, -36.5f, -9.5f, -35.5f, 4.4f) +
                listOf(Disc(-9.1f, -28.1f, 8.4f), Disc(-57f, -35.5f, 6f), Disc(-7.9f, -1f, 1.5f))
        Part.DownTube -> seg(37f, -78f, -9.5f, -36.5f, 8.2f) + Disc(-9.5f, -36.5f, 5f)
        Part.Fork -> seg(38f, -96f, 38f, -74f, 5.4f) + seg(38f, -74f, 55f, -38.5f, 5.8f)
        Part.Cockpit -> seg(37.6f, -96f, 36.2f, -110f, 3.8f) + seg(36.2f, -109.2f, 24.2f, -122.4f, 3.2f) + seg(36.2f, -108.6f, 25f, -104.6f, 3.0f)
        Part.Saddle ->
            seg(-27f, -96f, -27.6f, -100f, 3.0f) +
                listOf(Disc(-37f, -104f, 3.5f), Disc(-17.5f, -104f, 3f), Disc(-37f, -101.5f, 2.5f), Disc(-17.5f, -101.5f, 2f))
        Part.RearFender -> arc(-AXLE_X, -WHEEL_R, FENDER_R, REAR_FENDER_FROM, REAR_FENDER_TO)
        Part.FrontFender -> arc(AXLE_X, -WHEEL_R, FENDER_R, FRONT_FENDER_FROM, FRONT_FENDER_TO)
        Part.Lock -> listOf(Disc(-59f, -40f, 2f), Disc(-50f, -40f, 2f), Disc(-59f, -35f, 2f), Disc(-50f, -35f, 2f), Disc(-40f, -31.8f, 1.1f))
    }

    /** Rest pivot y of [p]: its lowest hull point, after the rest rotation about the pivot, on the road. */
    fun restY(p: Part): Float {
        if (p.isWheel) return -WHEEL_R
        val r = Math.toRadians(p.restRot.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        var low = -1e9f
        for (d in hull(p)) {
            val lx = d.x - p.px
            val ly = d.y - p.py
            low = max(low, lx * s + ly * c + d.r)
        }
        return -low
    }
}
