package dev.steven.s3control.ui.intro

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.TubeDefaults
import dev.steven.s3control.ui.components.YabaWordmark
import dev.steven.s3control.ui.components.drawLedDot
import dev.steven.s3control.ui.components.drawMatrixField
import dev.steven.s3control.ui.components.drawTubeBody
import dev.steven.s3control.ui.theme.Daylight
import dev.steven.s3control.ui.theme.S3Palette
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * drawScene: the whole picture at one instant, a pure function of t. Flat steps in brand tokens, no gradients,
 * no soft edges: the only soft thing is the light spill of the tube's own lamps ([drawTubeBody]).
 *
 * The bike is an S3 drawn after a photo of the real one (see [Geo]): long straight top tube with the matrix in it, straight
 * down tube, closed chain guard along the chainstay, kick lock at the rear hub, hub motor in the front wheel, full fenders that
 * hug the tyres, black balloon tyres, side kickstand. Static paths are cached; nothing is allocated per frame but a few small strokes.
 *
 * Draw order: background, light shafts and their ground ellipses, ground and road, the boot's shadow, the dim of the backdrop
 * (see [StageLight.backdropDim]), rear wheel and fender, rear triangle, down tube, lock, front wheel and fender, fork, saddle,
 * cockpit, the dim over the bike (never more than [StageLight.BIKE_DIM_MAX]), the tube with its matrix and lights, the dot swarm,
 * the boot and trouser (foreground), the halo. The handoff veil goes over everything but the tube.
 */

/** What changes between the film, the strip (static panels) and tests. */
internal class SceneOpts(
    /** The call is allowed (switch on, no drive lock): without it the first stomp is answered by the closed lock, not by the word. */
    val ouch: Boolean = true,
    /** The ending without a Welcome tube to land on: the tube boots the word mark on the tableau. */
    val lockup: Boolean = false,
    /** The static strip: no dim, no flashes, lights on, no sweep, no strike look, no blink. */
    val strip: Boolean = false,
    /** Window px: the Welcome's tube. Set only when the ending is the handoff. */
    val slot: Rect? = null,
    val flipStage: Boolean = Film.FLIP_WHOLE_STAGE,
    /** A fixed camera (the strip's panels); null runs the film's own. */
    val camera: Cam? = null,
    /** A fixed face of the tube (the strip's first panel shows the error steady, before the film's blink has started); null runs the film's own. */
    val face: Face? = null,
)

/** The frames the tube shows, built once from the two matrix words (the call and the bike's error code). */
internal class FilmFrames(ouch: String, error: String) {
    private val frames: Map<FrameId, MatrixFrame> = mapOf(
        FrameId.Empty to MatrixFrame.Empty,
        FrameId.S12 to MatrixFrame.text("12"),
        FrameId.S8 to MatrixFrame.text("8"),
        FrameId.S5 to MatrixFrame.text("5"),
        FrameId.S3 to MatrixFrame.text("3"),
        FrameId.S1 to MatrixFrame.text("1"),
        FrameId.S0 to MatrixFrame.text("0"),
        FrameId.Unlocked to MatrixFrame.text(MatrixFont.UNLOCKED.toString()),
        FrameId.Locked to MatrixFrame.text(MatrixFont.LOCKED.toString()),
        FrameId.Auw to MatrixFrame.text(ouch),
        FrameId.E42 to MatrixFrame.text(error),
        FrameId.Yaba to YabaWordmark,
    )

    operator fun get(id: FrameId): MatrixFrame = frames.getValue(id)
}

private fun S3Palette.silhouette(): S3Palette =
    copy(asphalt = paint, frame = paint, rib = paint, led = paint, ledOff = paint, ledGlow = Color.Transparent, signal = paint, lamp = paint, fog = paint)

// ---------------------------------------------------------------------------------------------------------
// Static shapes, built once
// ---------------------------------------------------------------------------------------------------------

/**
 * The foot of the boot in its own frame (the toe tip at 0, 0, the sole's underside y = 0, the heel at -74): a rounded toe box, the vamp
 * up to the ankle at (-53, -46), a heel that stands straight. The shaft is drawn on top of it along the shin, see [drawLeg].
 */
private val bootFoot: Path by lazy {
    Path().apply {
        moveTo(0f, -5f); lineTo(0f, -10f); quadraticBezierTo(0.5f, -17f, -8f, -20f); lineTo(-17f, -23.5f); lineTo(-30f, -32f)
        lineTo(-38f, -46f); lineTo(-68f, -46f); lineTo(-74f, -30f); lineTo(-74f, -5f); close()
    }
}

/** The two lighter panels of the leather (toe cap, heel counter): the boot has form even where the dark takes its edge. */
private val bootToeCap: Path by lazy {
    Path().apply {
        moveTo(0f, -5f); lineTo(0f, -10f); quadraticBezierTo(0.5f, -17f, -8f, -20f); lineTo(-13f, -22f); lineTo(-17f, -5f); close()
    }
}
private val bootHeelCounter: Path by lazy {
    Path().apply {
        moveTo(-74f, -5f); lineTo(-74f, -30f); lineTo(-69f, -41f); lineTo(-61f, -41f); lineTo(-61f, -5f); close()
    }
}

/** The sole: a band 5 u thick, its underside toothed (9 teeth, 4.6 u wide, 8.2 u pitch, 2.4 u deep); the tooth tips are y = 0. */
private val bootSole: Path by lazy {
    Path().apply {
        addRect(Rect(-74f, -5f, 0f, -2.4f))
        for (i in 0 until 9) {
            val x0 = -72f + 8.2f * i
            addRect(Rect(x0, -2.4f, x0 + 4.6f, 0f))
        }
    }
}

/** The heel block under the ankle: 22 x 9 u, standing on the sole. */
private val HEEL_BLOCK = Rect(-66f, -14f, -44f, -5f)

/** The closed chain guard along the chainstay: a black wedge from the rear hub block to the chainring. */
private val chainGuard: Path by lazy {
    Path().apply {
        moveTo(-61.5f, -42.4f); lineTo(-14f, -37.6f); lineTo(-15.8f, -20.2f); lineTo(-55.5f, -27f); lineTo(-62.5f, -29.5f); close()
    }
}

private val saddleShape: Path by lazy {
    Path().apply {
        moveTo(-40f, -103.5f); lineTo(-39f, -107.2f); lineTo(-30f, -107.6f); lineTo(-20f, -106.4f); lineTo(-15.2f, -104.2f)
        lineTo(-15f, -102.6f); lineTo(-19f, -100.6f); lineTo(-34f, -100f); lineTo(-39.5f, -101.2f); close()
    }
}

/** The brake cable from the lever over the bars and down into the fork. */
private val brakeCable: Path by lazy {
    Path().apply {
        moveTo(37.5f, -113.5f)
        quadraticBezierTo(45f, -103f, 40f, -90.5f)
    }
}

private val S_05 = Stroke(width = 0.5f)
private val S_07 = Stroke(width = 0.7f)
private val S_08 = Stroke(width = 0.8f, join = StrokeJoin.Round)
private val S_09 = Stroke(width = 0.9f)
private val S_TYRE = Stroke(width = Geo.TYRE_T)
private val S_FENDER_OUT = Stroke(width = Geo.FENDER_W, cap = StrokeCap.Round)
private val S_FENDER_IN = Stroke(width = Geo.FENDER_W - 1.4f, cap = StrokeCap.Round)
private val S_CABLE = Stroke(width = 0.6f, cap = StrokeCap.Round)
private val S_OUTLINE_BOOT = Stroke(width = 1.6f, join = StrokeJoin.Round)
private val S_TROUSER = Stroke(width = 3.2f, join = StrokeJoin.Round)
private val S_THIN = Stroke(width = 1.1f, cap = StrokeCap.Round)

/** cos and sin of 0, 30, ..., 330 degrees: the twelve spokes and the six slots of the rotor. */
private val COS30 = FloatArray(12) { cos(Math.toRadians(30.0 * it)).toFloat() }
private val SIN30 = FloatArray(12) { sin(Math.toRadians(30.0 * it)).toFloat() }

/** The five bolts of the chainring cover: cos and sin of 0, 72, ..., 288 degrees. */
private val COS72 = FloatArray(5) { cos(Math.toRadians(72.0 * it)).toFloat() }
private val SIN72 = FloatArray(5) { sin(Math.toRadians(72.0 * it)).toFloat() }

/** Draws the film at [t] into this canvas. [stage] is where the world lives (px); everything outside it is asphalt. */
internal fun DrawScope.drawFilm(
    t: Float,
    stage: Stage,
    c: S3Palette,
    world: FilmWorld,
    frames: FilmFrames,
    opts: SceneOpts,
) {
    val strip = opts.strip
    val impact = !strip && StageLight.impact(t)
    val flip = impact && opts.flipStage
    val cc = if (flip) Daylight else c
    val ink = if (impact) cc.silhouette() else cc

    drawRect(cc.asphalt)

    val slotStage = opts.slot?.let { Stage(it.left, it.top, it.right, it.bottom) }
    val cam = opts.camera ?: Camera.at(t, stage, slotStage)
    val k = cam.k
    val d = density
    val handoff = slotStage != null
    val topU = (cam.groundY - stage.top) / k

    // the ground hairline continues across the whole window; it is uncovered L to R in the first beat
    val gp = if (strip) 1f else Ease.fastOutSlowIn(Ease.ph(t, 0, Film.T_GROUND_END))
    if (gp > 0f) {
        clipRect(left = 0f, top = 0f, right = size.width * gp, bottom = size.height) {
            drawLine(cc.rib, Offset(0f, cam.groundY), Offset(size.width, cam.groundY), strokeWidth = d)
        }
    }

    // the road band goes on across the window when the stage is only the left pane (a half-opened fold): the dark and the
    // handoff are the stage's, the road is the whole street's
    if (gp > 0f && stage.right < size.width) {
        clipRect(left = stage.right, top = 0f, right = size.width * gp, bottom = size.height) {
            drawRect(cc.frame, Offset(stage.right, cam.groundY), Size(size.width - stage.right, size.height - cam.groundY))
            drawRect(cc.rib, Offset(stage.right, cam.groundY), Size(size.width - stage.right, KERB_U * k))
        }
    }

    clipRect(stage.left, stage.top, stage.right, stage.bottom) {
        withTransform({
            translate(cam.centerX - k * cam.cx, cam.groundY)
            scale(k, k, Offset.Zero)
        }) {
            val u = d / k
            val dimOn = !strip && !impact
            val lamp = if (impact) 0f else TubeFace.lamp(t, strip, handoff)
            val spinBase = world.spin(t)

            drawShafts(cc, StageLight.steps(t), topU)
            if (gp > 0f) {
                // uncovered L to R with the hairline: the window's edge, seen from the world
                val edgeU = (size.width * gp - (cam.centerX - k * cam.cx)) / k
                clipRect(left = -1000f, top = -1000f, right = edgeU, bottom = 1000f) { drawRoad(cc, u, (size.height - cam.groundY) / k + 20f) }
            }
            drawCracks(cc, t, u)
            if (!impact) drawShadows(t)
            // the technical drawing of the exploded view comes after the blast, like a plate laid over it
            if (!impact && t > Film.T_POP + 250f && t < Film.T_DROP) drawAxes(world, t, handoff, cc, u)

            // the dark: the road and the sky take all of it, the bike at most [StageLight.BIKE_DIM_MAX] (its damage must be read)
            val dim = if (dimOn) StageLight.dim(t) else 0f
            val backdrop = StageLight.backdropDim(dim)
            if (backdrop > 0f) drawRect(Color.Black.copy(alpha = backdrop), Offset(-1000f, -1000f), Size(2000f, 2000f))
            val tyre = if (impact) ink.paint else tyreInk(cc)

            // the bike's parts, back to front
            val pose = { p: Part -> world.pose(p, t, handoff) }
            val frontWheelPose = pose(Part.FrontWheel)
            // the front wheel turns as it rolls back to the fork
            val frontExtra = if (t >= Film.T_HOMEFLIGHT) world.rollDeg(frontWheelPose.x - Part.FrontWheel.restX) else 0f
            withPose(Part.RearWheel, pose(Part.RearWheel)) { drawWheel(ink, tyre, Part.RearWheel.px, Part.RearWheel.py, spinBase, motor = false) }
            withPose(Part.RearFender, pose(Part.RearFender)) { drawFender(ink, Part.RearWheel.px, Part.RearWheel.py, Geo.REAR_FENDER_FROM, Geo.REAR_FENDER_TO) }
            withPose(Part.RearTri, pose(Part.RearTri)) { drawRearTriangle(ink, t) }
            withPose(Part.DownTube, pose(Part.DownTube)) { drawDownTube(ink) }
            withPose(Part.Lock, pose(Part.Lock)) { drawLock(ink, t) }
            withPose(Part.FrontWheel, frontWheelPose) { drawWheel(ink, tyre, Part.FrontWheel.px, Part.FrontWheel.py, spinBase + frontExtra, motor = true) }
            withPose(Part.FrontFender, pose(Part.FrontFender)) { drawFender(ink, Part.FrontWheel.px, Part.FrontWheel.py, Geo.FRONT_FENDER_FROM, Geo.FRONT_FENDER_TO) }
            withPose(Part.Fork, pose(Part.Fork)) { drawFork(ink) }
            withPose(Part.Saddle, pose(Part.Saddle)) { drawSaddle(ink) }
            withPose(Part.Cockpit, pose(Part.Cockpit)) { drawCockpit(ink) }

            val bikeDim = StageLight.bikeDim(dim)
            if (bikeDim > 0f) drawRect(Color.Black.copy(alpha = bikeDim), Offset(-1000f, -1000f), Size(2000f, 2000f))

            // only the handoff ending blends everything but the tube to asphalt; the lockup ending holds the tableau
            val veil = if (strip || !handoff) 0f else StageLight.asphaltVeil(t)
            val tubeFirst = t < Film.T_HANDOFF || slotStage == null
            // the strip is a still: no strike look, no flash, no sweep. The error stays: it is a state, not a call.
            val face = opts.face ?: TubeFace.at(t, opts.ouch, opts.lockup).let { if (strip) it.calm() else it }
            if (tubeFirst) {
                withPose(Part.Tube, pose(Part.Tube)) { drawTube(ink, lamp, face, frames, u, impact) }
            }

            // the 46 lit dots of the error after the pop (the strip draws the empty capsule instead: a still of them reads as noise)
            if (!strip && t >= Film.T_POP && t < Film.T_DOTS_HOME) drawDots(world, t, handoff, ink, impact)

            Boots.pose(t)?.let { drawLeg(ink, it, topU, impact) }

            val halos = Halo.count(t)
            if (halos > 0) drawHalo(ink, t, halos, handoff)

            if (veil > 0f) drawRect(cc.asphalt.copy(alpha = veil), Offset(-1000f, -1000f), Size(2000f, 2000f))
        }
    }

    // the right pane (the inner screen) is dimmed with the stage: one night, not two
    if (!strip && !impact && stage.right < size.width) {
        val dim = StageLight.dim(t)
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim), Offset(stage.right, 0f), Size(size.width - stage.right, size.height))
    }

    // the handoff: the tube alone, in px, lerped onto the Welcome's tube
    if (slotStage != null && t >= Film.T_HANDOFF && opts.slot != null) {
        drawHandoffTube(t, cam, world, opts.slot, cc, frames)
    }
}

/** The face of a still: the look is normalised (dot 1, glow 1, shift 0, lit 1). */
private fun Face.calm(): Face = Face(shown, prev, progress, tone)

// ---------------------------------------------------------------------------------------------------------
// Transform helpers
// ---------------------------------------------------------------------------------------------------------

private inline fun DrawScope.withPose(p: Part, pose: Pose, crossinline block: DrawScope.() -> Unit) {
    withTransform({
        translate(pose.x, pose.y)
        if (pose.rot != 0f) rotate(pose.rot, Offset.Zero)
        if (pose.sx != 1f || pose.sy != 1f) scale(pose.sx, pose.sy, Offset.Zero)
        translate(-p.px, -p.py)
    }) { block() }
}

// ---------------------------------------------------------------------------------------------------------
// Background: light shafts, road, shadows
// ---------------------------------------------------------------------------------------------------------

private fun DrawScope.drawShafts(c: S3Palette, steps: Int, topU: Float) {
    if (steps <= 0) return
    val top = -(topU + 20f)
    for (i in 0 until steps) {
        // three flat steps, each narrower and lighter: frame, rib, and a rib that has taken a little led
        val color = when (i) {
            0 -> c.frame
            1 -> c.rib
            else -> lerp(c.rib, c.led, StageLight.LED_STEP_MIX)
        }
        val half = StageLight.shaftHalfWidth[i]
        val path = Path().apply {
            moveTo(-StageLight.SHAFT_TOP_HALF, top); lineTo(StageLight.SHAFT_TOP_HALF, top)
            lineTo(half, 0f); lineTo(-half, 0f); close()
        }
        drawPath(path, color)
        drawOval(color, Offset(-half, 2f - StageLight.SHAFT_ELLIPSE_H / 2f), Size(2f * half, StageLight.SHAFT_ELLIPSE_H))
        if (i == 2) {
            // the light is warmest where it comes from: two flat bands of led in the top 45 % of the shaft, nowhere else
            for ((band, alpha) in StageLight.TOP_BANDS) {
                val f0 = band.first
                val f1 = band.second
                val w0 = Ease.lerp(StageLight.SHAFT_TOP_HALF, half, f0)
                val w1 = Ease.lerp(StageLight.SHAFT_TOP_HALF, half, f1)
                val y0 = top * (1f - f0)
                val y1 = top * (1f - f1)
                val piece = Path().apply {
                    moveTo(-w0, y0); lineTo(w0, y0); lineTo(w1, y1); lineTo(-w1, y1); close()
                }
                drawPath(piece, c.led.copy(alpha = alpha))
            }
        }
    }
}

/** The kerb along the top edge of the road band, in u. */
private const val KERB_U = 2.4f

/**
 * The road: a band in the frame tone from the ground line down to the bottom of the window ([depthU]) with a rib kerb along its
 * top, so that what lies below the bike is a place and not a void; and dashes, 7 u dash / 15 u gap, 9 u in front of the
 * ground line, wide enough for every camera.
 */
private fun DrawScope.drawRoad(c: S3Palette, u: Float, depthU: Float) {
    drawRect(c.frame, Offset(-1500f, 0f), Size(3000f, depthU))
    drawRect(c.rib, Offset(-1500f, 0f), Size(3000f, KERB_U))
    for (i in 0 until 17) {
        val x = -176f + 22f * i
        drawLine(c.rib, Offset(x, 9f), Offset(x + 7f, 9f), strokeWidth = 2f * u)
    }
    // the lane marking further down: long dashes, wide apart, only where the band is deep enough to carry them
    if (depthU > 80f) {
        for (i in 0 until 9) {
            val x = -330f + 84f * i
            drawLine(c.rib, Offset(x, 66f), Offset(x + 34f, 66f), strokeWidth = 3f * u)
        }
    }
}

/**
 * The cracks the stomps leave in the road ([Cracks]): flat fog lines on the road band, rounded ends, each growing out of the kerb
 * from the hit, branch by branch, fading in the cuts of the returning light. In the silence shot (the lens on the fallen tube)
 * only the hairline of each crack is drawn, 45 % of its length, at 60 %: the road stays empty there. [u] is 1 dp in u.
 */
private fun DrawScope.drawCracks(c: S3Palette, t: Float, u: Float) {
    if (t < Cracks.hitAt(0)) return
    val fade = Cracks.fade(t)
    if (fade <= 0f) return
    val branches = Cracks.drawsBranch(t)
    // the lens sees the road 12 px to the u: a crack is only a stub of 45 % there
    val reach = if (branches) 1f else Cracks.LENS_REACH
    val color = c.fog.copy(alpha = c.fog.alpha * fade * (if (branches) 1f else 0.6f))
    for (n in 0 until 3) {
        val grown = Cracks.amount(t, n)
        if (grown <= 0f) continue
        val w = Cracks.widthDp[n] * u
        val ox = Cracks.originX[n]
        for (stroke in Cracks.strokes) {
            if (stroke.crack != n) continue
            if (stroke.from > 0f && !branches) continue
            val p = Ease.ph(grown, stroke.from, 1f)
            if (p > 0f) drawStroke(color, ox, stroke.pts, p * reach, w)
        }
    }
}

/** The first [p] (0..1) of the way along the polyline [pts] (x, y pairs), shifted by [ox]. */
private fun DrawScope.drawStroke(color: Color, ox: Float, pts: FloatArray, p: Float, w: Float) {
    val n = pts.size / 2
    var total = 0f
    for (i in 1 until n) total += hypot(pts[2 * i] - pts[2 * i - 2], pts[2 * i + 1] - pts[2 * i - 1])
    var left = total * p
    for (i in 1 until n) {
        val ax = pts[2 * i - 2]; val ay = pts[2 * i - 1]
        val bx = pts[2 * i]; val by = pts[2 * i + 1]
        val len = hypot(bx - ax, by - ay)
        val f = if (len <= 0f) 1f else minOf(1f, left / len)
        drawLine(color, Offset(ox + ax, ay), Offset(ox + ax + (bx - ax) * f, ay + (by - ay) * f), strokeWidth = w, cap = StrokeCap.Round)
        left -= len
        if (left <= 0f) break
    }
}

/**
 * The exploded view's axes: a dash-dot line (fog, 1 dp) from where each part was to where it is, plotted as it moves,
 * held through the hang, cut away when the drop begins.
 */
private fun DrawScope.drawAxes(world: FilmWorld, t: Float, handoff: Boolean, c: S3Palette, u: Float) {
    val dashes = PathEffect.dashPathEffect(floatArrayOf(6f * u, 2.5f * u, 1f * u, 2.5f * u))
    for (p in Part.entries) {
        val from = world.pose(p, Film.T_POP.toFloat(), handoff)
        val to = world.pose(p, t, handoff)
        if (from.x == to.x && from.y == to.y) continue
        drawLine(c.fog, Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = u, pathEffect = dashes)
    }
}

/**
 * The boot's shadow on the road: an oval, 96 x 8 u at full size, under the middle of the boot. It grows from 0.3 at the top of
 * a wind-up to 1.0 at contact, so that each stomp is announced on the road before it lands. Gone at the pop.
 */
private fun DrawScope.drawShadows(t: Float) {
    val shadow = Color.Black.copy(alpha = 0.30f)
    Boots.shadow(t)?.let { (x, y, f) -> drawOval(shadow, Offset(x - 48f * f, y - 4f * f), Size(96f * f, 8f * f)) }
}

// ---------------------------------------------------------------------------------------------------------
// The bike, part by part (bike coordinates, one part per function)
// ---------------------------------------------------------------------------------------------------------

private fun DrawScope.line(color: Color, ax: Float, ay: Float, bx: Float, by: Float, w: Float) =
    drawLine(color, Offset(ax, ay), Offset(bx, by), strokeWidth = w, cap = StrokeCap.Round)

/**
 * The tyre's black: darker than the road under it (the photo's Big Ben is black all through), so that it reads as a fat tyre and not as an
 * outlined ring. A light palette takes its paint (dark).
 */
private fun tyreInk(c: S3Palette): Color = if (c.isDark) lerp(c.asphalt, Color.Black, 0.55f) else c.paint

/**
 * A 28-inch balloon wheel: the solid tyre band ([tyre], a fog edge outside and a thin one inside), the rim ring, twelve spokes that
 * turn, and the hub; the front wheel carries the hub motor (a drum, a disc rotor with six slots).
 */
private fun DrawScope.drawWheel(c: S3Palette, tyre: Color, cx: Float, cy: Float, spin: Float, motor: Boolean) {
    val ctr = Offset(cx, cy)
    val r = Geo.WHEEL_R
    // the tyre is a solid black band: one fog edge on the outside, a thinner one on the inside (the rim's edge)
    drawCircle(tyre, r - Geo.TYRE_T / 2f, ctr, style = S_TYRE)
    drawCircle(c.fog, r - 0.35f, ctr, style = S_07)
    drawCircle(lerp(tyre, c.fog, 0.55f), r - Geo.TYRE_T + 0.2f, ctr, style = S_05)
    drawCircle(c.fog, Geo.RIM_R, ctr, style = S_09)
    // twelve spokes (six diameters), from the hub (or the rotor) to the rim, turning
    val r0 = if (motor) 8.2f else 3.2f
    val r1 = Geo.RIM_R - 0.4f
    withTransform({ rotate(spin, ctr) }) {
        for (i in 0 until 12) {
            drawLine(c.fog, Offset(cx + COS30[i] * r0, cy + SIN30[i] * r0), Offset(cx + COS30[i] * r1, cy + SIN30[i] * r1), strokeWidth = 0.55f)
        }
        if (motor) {
            // the disc rotor's six slots (r 3 to 7.6), between the spokes
            for (i in 0 until 6) {
                val a = 2 * i
                val ca = COS30[a] * 0.9659f - SIN30[a] * 0.2588f
                val sa = SIN30[a] * 0.9659f + COS30[a] * 0.2588f
                drawLine(c.fog, Offset(cx + ca * 3f, cy + sa * 3f), Offset(cx + ca * 7.6f, cy + sa * 7.6f), strokeWidth = 0.5f)
            }
        }
    }
    if (motor) {
        drawCircle(c.fog, 8.2f, ctr, style = Stroke(width = 0.8f))
        drawCircle(c.fog, 6.0f, ctr)
        drawCircle(c.rib, 5.3f, ctr)
        drawCircle(c.paint, 2.2f, ctr)
    } else {
        drawCircle(c.fog, 3.2f, ctr)
        drawCircle(c.rib, 2.6f, ctr)
        drawCircle(c.paint, 1.1f, ctr)
    }
}

/** The fender: an arc about the axle (r 38.4, 1.9 u off the tyre) from [a0] to [a1] degrees, a band 3 u wide: fog edge, rib on top. */
private fun DrawScope.drawFender(c: S3Palette, axleX: Float, axleY: Float, a0: Float, a1: Float) {
    val r = Geo.FENDER_R
    val tl = Offset(axleX - r, axleY - r)
    val sz = Size(2f * r, 2f * r)
    drawArc(c.fog, a0, a1 - a0, false, tl, sz, style = S_FENDER_OUT)
    drawArc(c.rib, a0, a1 - a0, false, tl, sz, style = S_FENDER_IN)
}

/**
 * The rear triangle: the chain guard (a closed black wedge along the chainstay) with its chainring cover, the crank and
 * pedals, the white chainstay over the guard, the seat stay, the seat tube, the bottom bracket shell, and the side kickstand
 * (it drops once the bike has stopped rolling).
 */
private fun DrawScope.drawRearTriangle(c: S3Palette, t: Float) {
    drawPath(chainGuard, c.rib)
    drawPath(chainGuard, c.fog, style = S_08)
    val ring = Offset(-9.1f, -28.1f)
    drawCircle(c.fog, 8.4f, ring)
    drawCircle(c.rib, 7.6f, ring)
    for (i in 0 until 5) drawCircle(c.fog, 0.6f, Offset(ring.x + COS72[i] * 5f, ring.y + SIN72[i] * 5f))
    drawCircle(c.fog, 2.2f, ring)
    // crank and pedals (a hub-motor bike: they do not turn)
    line(c.fog, -24.7f, -29.6f, 10f, -23.6f, 1.6f)
    drawRoundRect(c.fog, Offset(-28f, -31f), Size(6f, 1.6f), CornerRadius(0.5f, 0.5f))
    drawRoundRect(c.fog, Offset(7f, -24.6f), Size(6f, 1.6f), CornerRadius(0.5f, 0.5f))
    // the frame, over the guard
    line(c.paint, -56f, -36.5f, -9.5f, -35.5f, 4.4f)
    line(c.paint, -27f, -78f, -56f, -36.5f, 4.0f)
    line(c.paint, -24f, -80f, -9.5f, -36.5f, 4.6f)
    drawCircle(c.paint, 4.8f, Offset(-9.5f, -36f))
    // the kickstand: not there while the bike rolls, then it drops about its top (140 ms); the second stomp knocks it folded back, and
    // bent (its lower half turns on by nine tenths of the fold again, at the kink)
    if (t >= Film.T_ROLL_END) {
        val fold = Reaction.standFold(t)
        val a = -40f * (1f - Ease.outQuad(Ease.ph(t, Film.T_ROLL_END, Film.T_ROLL_END + 140))) + fold
        withTransform({ if (a != 0f) rotate(a, Offset(-10.1f, -20.5f)) }) {
            if (fold <= 0f) {
                line(c.fog, -10.1f, -20.5f, -7.9f, -0.8f, 1.8f)
                drawRoundRect(c.fog, Offset(-9.8f, -1.4f), Size(4.2f, 1.4f), CornerRadius(0.6f, 0.6f))
            } else {
                // the kink at 55 % of the way down: (-9.1, -9.7)
                line(c.fog, -10.1f, -20.5f, -9.1f, -9.7f, 1.8f)
                withTransform({ rotate(fold * 0.9f, Offset(-9.1f, -9.7f)) }) {
                    line(c.fog, -9.1f, -9.7f, -7.9f, -0.8f, 1.8f)
                    drawRoundRect(c.fog, Offset(-9.8f, -1.4f), Size(4.2f, 1.4f), CornerRadius(0.6f, 0.6f))
                }
            }
        }
    }
}

private fun DrawScope.drawDownTube(c: S3Palette) = line(c.paint, 37f, -78f, -9.5f, -36.5f, 8.2f)

/** The fork: the head tube (under the capsule), the raked blade, the axle nut. */
private fun DrawScope.drawFork(c: S3Palette) {
    line(c.paint, 38f, -96f, 38f, -74f, 5.4f)
    line(c.paint, 38f, -74f, 55f, -38.5f, 5.8f)
    drawCircle(c.paint, 2.2f, Offset(Geo.AXLE_X, -Geo.WHEEL_R))
}

/** The cockpit: the stem, the far and the near bar with their grips, the brake cable. */
private fun DrawScope.drawCockpit(c: S3Palette) {
    line(c.paint, 37.6f, -96f, 36.2f, -110f, 3.8f)
    line(c.paint, 36.2f, -109.2f, 24.2f, -122.4f, 3.2f)
    line(c.fog, 28.6f, -118f, 24.2f, -122.4f, 5.0f)
    line(c.rib, 28.6f, -118f, 24.2f, -122.4f, 3.6f)
    line(c.paint, 36.2f, -108.6f, 25f, -104.6f, 3.0f)
    line(c.fog, 28.5f, -105.8f, 25f, -104.6f, 4.8f)
    line(c.rib, 28.5f, -105.8f, 25f, -104.6f, 3.4f)
    drawPath(brakeCable, c.fog, style = S_CABLE)
}

private fun DrawScope.drawSaddle(c: S3Palette) {
    line(c.fog, -27f, -96f, -27.6f, -100f, 4.4f)
    line(c.rib, -27f, -96f, -27.6f, -100f, 3.0f)
    drawPath(saddleShape, c.rib)
    drawPath(saddleShape, c.fog, style = S_08)
}

/** The Kick-Lock: block, slot, a knob that sits at the open end in led and slides to the closed end in signal, the lever. */
private fun DrawScope.drawLock(c: S3Palette, t: Float) {
    // shut by the first stomp, open again as the lock itself comes home in the reassembly (the matrix shows the open lock then)
    val closed = t >= Film.T_S1 && t < Film.T_HOMEFLIGHT + 300
    drawRoundRect(c.rib, Offset(-61f, -42f), Size(13f, 9f), CornerRadius(2f, 2f))
    drawRoundRect(c.fog, Offset(-61f, -42f), Size(13f, 9f), CornerRadius(2f, 2f), style = S_07)
    drawRoundRect(c.asphalt, Offset(-59.5f, -39.4f), Size(9f, 2.4f), CornerRadius(1.2f, 1.2f))
    drawRoundRect(c.rib, Offset(-59.5f, -39.4f), Size(9f, 2.4f), CornerRadius(1.2f, 1.2f), style = Stroke(width = 0.4f))
    drawCircle(if (closed) c.signal else c.led, 1f, Offset(if (closed) -52.4f else -57.6f, -38.2f))
    // the lever, pushed up once the lock is closed
    val flex = if (closed) 14f else 0f
    withTransform({ rotate(flex, Offset(-48f, -34.5f)) }) {
        line(c.fog, -48f, -34.5f, -40f, -31.8f, 2.0f)
    }
}

// ---------------------------------------------------------------------------------------------------------
// The tube
// ---------------------------------------------------------------------------------------------------------

/** The capsule with its lights and matrix in bike coordinates (the caller put it into its pose). [u] is 1 dp in u. */
private fun DrawScope.drawTube(c: S3Palette, lamp: Float, face: Face, frames: FilmFrames, u: Float, sil: Boolean) {
    val rect = Rect(Geo.TUBE_L, Geo.TUBE_T, Geo.TUBE_L + Geo.TUBE_W, Geo.TUBE_T + Geo.TUBE_H)
    drawTubeBody(c, rect, lamp, TubeDefaults.BarWidth.value * u, 1f * u, clip = true)
    if (sil) return
    drawFace(c, face, frames, Offset(rect.left + Geo.PAD_X, rect.top + Geo.PAD_Y), Geo.PITCH)
}

/** The glow of a signal-red dot: the palette's signal at 33 % on a dark palette, none on a light one (as the instrument's own). */
private fun signalGlow(c: S3Palette): Color = if (c.isDark) c.signal.copy(alpha = 0.33f) else Color.Transparent

private fun DrawScope.drawFace(c: S3Palette, face: Face, frames: FilmFrames, origin: Offset, pitch: Float) {
    val lit = if (face.tone == Tone.Signal) c.signal else c.led
    val glow = if (face.tone == Tone.Signal) signalGlow(c) else c.ledGlow
    drawMatrixField(
        shown = frames[face.shown],
        previous = frames[face.prev],
        sweepCol = face.progress * (TubeDefaults.Columns + 2),
        sweeping = face.progress < 1f,
        columns = TubeDefaults.Columns,
        origin = origin,
        pitch = pitch,
        lit = lit,
        unlit = c.ledOff,
        glow = glow,
        dot = face.dot,
        glowScale = face.glow,
        litScale = face.lit,
        shiftCols = face.shift,
    )
}

/**
 * The last frame of the handoff: the capsule in px, from where the camera puts it to exactly where the Welcome's
 * tube is (40 dp / 18 dp padding, pitch (width - 80 dp) / 36), lamps off, ghost dots only.
 */
private fun DrawScope.drawHandoffTube(t: Float, cam: Cam, world: FilmWorld, slot: Rect, c: S3Palette, frames: FilmFrames) {
    val p = Ease.fastOutSlowIn(Ease.ph(t, Film.T_HANDOFF, Film.T_CUT))
    val q = Ease.clamp01((p - 0.6f) / 0.4f)
    val pose = world.pose(Part.Tube, t, handoff = true)
    val k = cam.k
    val wl = cam.sx(pose.x + (Geo.TUBE_L - Part.Tube.px))
    val wt = cam.sy(pose.y + (Geo.TUBE_T - Part.Tube.py))
    val ww = Geo.TUBE_W * k
    val wh = Geo.TUBE_H * k
    // the screen's tube pads in whole pixels (layout rounds): so must the film's, or the dots sit half a pixel off
    val padXpx = TubeDefaults.PadX.roundToPx().toFloat()
    val padYpx = TubeDefaults.PadY.roundToPx().toFloat()
    // at the end the numbers are the screen's own, exactly (a lerp at 1.0 may be an ulp off, and an ulp shows in the dots)
    val padX = if (q >= 1f) padXpx else Ease.lerp(Geo.PAD_X * k, padXpx, q)
    val padY = if (q >= 1f) padYpx else Ease.lerp(Geo.PAD_Y * k, padYpx, q)
    val rect = if (q >= 1f) slot else Rect(
        Ease.lerp(wl, slot.left, q), Ease.lerp(wt, slot.top, q),
        Ease.lerp(wl + ww, slot.right, q), Ease.lerp(wt + wh, slot.bottom, q),
    )
    val pitch = (rect.width - 2f * padX) / TubeDefaults.Columns
    val lamp = TubeFace.lamp(t, handoff = true)
    // drawn in the tube's own coordinates under a translation, the way the screen's tube is: the same numbers
    // reach the rasteriser, so the dots come out the same pixels
    translate(rect.left, rect.top) {
        drawTubeBody(c, Rect(0f, 0f, rect.width, rect.height), lamp, TubeDefaults.BarWidth.toPx(), 1.dp.toPx(), clip = true)
        translate(padX, padY) {
            drawFace(c, Face(FrameId.Empty), frames, Offset.Zero, pitch)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// The swarm, the boot, the halo
// ---------------------------------------------------------------------------------------------------------

/** The flying dots are drawn at this multiple of the lit dot's radius: from across the road they have to read as lights, not specks. */
private const val FLY_SCALE = 2f

/** What a dot homing to its socket is while it flies: as big as an instrument's lit dot, dimming into the ghost's size and tone. */
private const val HOME_SCALE = 1.1f

/**
 * The 46 dots of the error after the pop. While they fly and lie on the road they are signal red with a signal glow (the bike's
 * fault); the life sign is always led. On the homeflight they light up in led and dim into their sockets: the error is cleared.
 */
private fun DrawScope.drawDots(world: FilmWorld, t: Float, handoff: Boolean, c: S3Palette, sil: Boolean) {
    if (sil) return
    val ghost = Geo.PITCH * 0.34f * 0.78f
    val lit = Geo.PITCH * 0.34f * TubeFace.STRIKE_DOT
    val redGlow = signalGlow(c)
    for (i in world.flyers) {
        val dot = world.dot(i, t, handoff)
        val at = Offset(dot.x, dot.y)
        when {
            dot.lit -> {
                // the life sign is led only once it wakes: until then it is the crossbar of the red 4
                val led = dot.life && t >= Film.T_LIFE_HOP
                val color = if (led) c.led else c.signal
                val glow = if (led) c.ledGlow else redGlow
                drawLedDot(at, lit * FLY_SCALE, color.copy(alpha = color.alpha * dot.glow), glow, dot.glow)
            }
            dot.home > 0f -> {
                // back in the light: lit again, and dimming into the socket's own ghost over the last 40 % of the flight
                val k = Ease.clamp01((dot.home - 0.6f) / 0.4f)
                drawLedDot(
                    at, Ease.lerp(lit * HOME_SCALE, ghost, k), lerp(c.led, c.ledOff, k), c.ledGlow, 1f - k,
                )
            }
            else -> drawCircle(c.ledOff, ghost * 1.4f, at)
        }
    }
}

/** A tapered quad along [a]..[b], [wa] wide at a and [wb] at b. */
private fun quad(a: Offset, b: Offset, wa: Float, wb: Float): Path {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    val nx = -dy / len
    val ny = dx / len
    return Path().apply {
        moveTo(a.x + nx * wa / 2f, a.y + ny * wa / 2f)
        lineTo(b.x + nx * wb / 2f, b.y + ny * wb / 2f)
        lineTo(b.x - nx * wb / 2f, b.y - ny * wb / 2f)
        lineTo(a.x - nx * wa / 2f, a.y - ny * wa / 2f)
        close()
    }
}

/**
 * A leg's shape along [a]..[b]: [wa] wide at a, [wm] wide at [tm] of the way (the calf), [wb] wide at b.
 */
private fun legShape(a: Offset, b: Offset, wa: Float, wm: Float, tm: Float, wb: Float): Path {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    val nx = -dy / len
    val ny = dx / len
    val mx = a.x + dx * tm
    val my = a.y + dy * tm
    return Path().apply {
        moveTo(a.x + nx * wa / 2f, a.y + ny * wa / 2f)
        lineTo(mx + nx * wm / 2f, my + ny * wm / 2f)
        lineTo(b.x + nx * wb / 2f, b.y + ny * wb / 2f)
        lineTo(b.x - nx * wb / 2f, b.y - ny * wb / 2f)
        lineTo(mx - nx * wm / 2f, my - ny * wm / 2f)
        lineTo(a.x - nx * wa / 2f, a.y - ny * wa / 2f)
        close()
    }
}

/** The boot's leather: darker than the road and the trouser, so that it is a boot and not a shoe; a light palette takes its paint. */
private fun bootInk(c: S3Palette, sil: Boolean): Color = if (sil) c.paint else if (c.isDark) lerp(c.asphalt, Color.Black, 0.5f) else c.paint

/**
 * The leg of a boot: no face, no person. A heavy dark boot (a rounded toe box, a tall shaft with a lip, a heel block under the ankle,
 * a toothed grey sole) edged in fog light, the trouser going into the shaft, the shin up to a round knee (a visible bend) with a calf,
 * and the thigh on up and off the picture to the hip, [topU] and more above the road. The trouser is one shape (every part stroked
 * wider first, then every fill on top, so only the outline of the union shows), with a crease down the front; so is the boot's
 * (foot and shaft). [sil] is the impact frame: solid paint.
 */
private fun DrawScope.drawLeg(c: S3Palette, b: BootPose, topU: Float, sil: Boolean) {
    val s = b.scale
    val sq = b.squashY
    val ankle = Offset(b.toeX + Boots.ANKLE_X * s, b.soleY + Boots.ANKLE_Y * s * sq)
    val knee = Offset(b.kneeX, b.kneeY)
    val hip = Offset(b.hipX, -(topU + 200f))

    // the trouser
    val wHip = 50f * s
    val wKnee = 32f * s
    val wCalf = 33f * s
    val wAnkle = 24f * s
    val thigh = quad(hip, knee, wHip, wKnee)
    val shin = legShape(knee, ankle, wKnee, wCalf, 0.28f, wAnkle)
    val kneeR = 15.5f * s
    drawPath(thigh, c.fog, style = S_TROUSER)
    drawPath(shin, c.fog, style = S_TROUSER)
    drawCircle(c.fog, kneeR, knee, style = S_TROUSER)
    drawPath(thigh, c.rib)
    drawPath(shin, c.rib)
    drawCircle(c.rib, kneeR, knee)

    // the crease down the front of the shin and the thigh, and the fold across the knee
    fun along(a: Offset, bpt: Offset, side: Float, t0: Float, t1: Float): Pair<Offset, Offset> {
        val dx = bpt.x - a.x
        val dy = bpt.y - a.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / len * side
        val ny = dx / len * side
        return Pair(
            Offset(a.x + dx * t0 + nx, a.y + dy * t0 + ny),
            Offset(a.x + dx * t1 + nx, a.y + dy * t1 + ny),
        )
    }
    val sc = along(ankle, knee, 5.5f * s, 0.16f, 0.86f)
    drawLine(c.fog, sc.first, sc.second, strokeWidth = 1.1f)
    val tc = along(knee, hip, 5.5f * s, 0.05f, 0.9f)
    drawLine(c.fog, tc.first, tc.second, strokeWidth = 1.1f)
    drawArc(c.fog, 160f, 120f, false, Offset(knee.x - kneeR * 0.6f, knee.y - kneeR * 0.6f), Size(kneeR * 1.2f, kneeR * 1.2f), style = S_THIN)

    // the boot: the shaft goes up the shin from the ankle (the trouser disappears in it), the foot is turned about the ankle
    val len = hypot(knee.x - ankle.x, knee.y - ankle.y).coerceAtLeast(0.001f)
    val ux = (knee.x - ankle.x) / len
    val uy = (knee.y - ankle.y) / len
    val shaftFrom = Offset(ankle.x - ux * 8f * s, ankle.y - uy * 8f * s)
    val shaftTop = Offset(ankle.x + ux * 22f * s * sq, ankle.y + uy * 22f * s * sq)
    val shaft = quad(shaftFrom, shaftTop, 31f * s, 34f * s)
    val ink = bootInk(c, sil)
    val edge = c.fog
    val foot: DrawScope.(Boolean) -> Unit = { outline ->
        withTransform({
            translate(b.toeX, b.soleY)
            if (s != 1f || sq != 1f) scale(s, s * sq, Offset.Zero)
            if (b.angle != 0f) rotate(b.angle, Offset(Boots.ANKLE_X, Boots.ANKLE_Y))
        }) {
            if (outline) drawPath(bootFoot, edge, style = S_OUTLINE_BOOT) else drawPath(bootFoot, ink)
        }
    }
    foot(true)
    drawPath(shaft, edge, style = S_OUTLINE_BOOT.scaled(s))
    foot(false)
    drawPath(shaft, ink)
    // the details of the leather and the sole, in the foot's own frame
    withTransform({
        translate(b.toeX, b.soleY)
        if (s != 1f || sq != 1f) scale(s, s * sq, Offset.Zero)
        if (b.angle != 0f) rotate(b.angle, Offset(Boots.ANKLE_X, Boots.ANKLE_Y))
    }) {
        // the toe cap and the heel counter: two lighter panels of the leather
        if (!sil) {
            val panel = lerp(ink, edge, 0.18f)
            drawPath(bootToeCap, panel)
            drawPath(bootHeelCounter, panel)
        }
        // three laces across the vamp, and the seams of the panels in the edge light
        for (x in floatArrayOf(-14f, -20f, -26f)) {
            val ys = if (x >= -17f) -17f + 0.5f * (x + 5f) else -23f + 0.692f * (x + 17f)
            drawLine(edge, Offset(x, ys - 1.5f), Offset(x - 3.5f, ys + 6.5f), strokeWidth = 1.3f)
        }
        drawLine(edge, Offset(-17f, -5f), Offset(-13f, -22f), strokeWidth = 0.9f)
        drawLine(edge, Offset(-61f, -5f), Offset(-61f, -41f), strokeWidth = 0.9f)
        // the heel block: a stack under the ankle, lighter than the leather
        drawRect(if (sil) c.paint else c.rib, HEEL_BLOCK.topLeft, HEEL_BLOCK.size)
        drawRect(edge, HEEL_BLOCK.topLeft, HEEL_BLOCK.size, style = S_THIN)
        drawPath(bootSole, if (sil) c.paint else c.fog)
    }
    // the lip of the shaft: a flared band at its top, over the trouser
    val lipA = Offset(shaftTop.x - ux * 2f * s, shaftTop.y - uy * 2f * s)
    val lipB = Offset(shaftTop.x + ux * 4f * s, shaftTop.y + uy * 4f * s)
    val lip = quad(lipA, lipB, 38f * s, 38f * s)
    drawPath(lip, edge, style = S_OUTLINE_BOOT)
    drawPath(lip, if (sil) c.paint else c.rib)
}

private fun Stroke.scaled(k: Float): Stroke = Stroke(width = width * k, join = join)

private fun DrawScope.drawHalo(c: S3Palette, t: Float, count: Int, handoff: Boolean) {
    val lift = Lift.at(t, handoff)
    val r = Halo.DOT_R
    // the glow breathes with the bob once the ring is closed
    val glow = 1.45f + 0.35f * sin(2.0 * Math.PI * (t - Film.T_HALO) / Lift.PERIOD_MS).toFloat()
    for (i in 0 until count) {
        val a = Halo.angle(i)
        val at = Offset(Halo.cx + Halo.rx * cos(a), Halo.cy - lift + Halo.ry * sin(a))
        drawLedDot(at, r, c.led, c.ledGlow, glow)
    }
}
