package dev.steven.s3control.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.steven.s3control.ride.RideLogReader
import dev.steven.s3control.ride.RideLogReader.Signal
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Palette
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/*
 * The timeline of one ride log (ui/screens/RideLogViewer.kt): speed, battery, supply, the signals of
 * the shipping rule and the Bluetooth link stacked on one time axis, read like a logic analyser on the
 * bike's lines. One cursor runs through every lane. Colours keep their jobs: led = speed and the rider's
 * marks, lamp = power (battery, supply), signal = locked while riding, faults, shipping; fog = what is
 * normal or unknown (a lock that is shut while parked, no connection). Grid in rib, labels in fog.
 *
 * The visible window can be narrowed down to [MIN_WINDOW_MS] (double tap, two fingers, or the zoom
 * buttons), so the seconds before a shutdown can be read in order.
 *
 * Drawing is split in two layers: the lanes (cached per size and window, own render layer) and the
 * cursor, so dragging the cursor never rebuilds a path.
 *
 * A log with the phone's GPS speed (S2 drive diagnosis) also draws it as a dashed paint line in the speed lane,
 * and each signal dropout (the bike reported 0 km/h while GPS rolled faster than 8 km/h) as a signal-red wash over
 * that lane with a red bar on its zero line. A log without GPS draws exactly as before.
 *
 * [compact]: the short form for a chart that sticks over the event list on the cover screen, about 120 dp: the speed
 * lane, one strip with every signal of the shipping rule in signal and the supply drop in lamp, and the axis. A tap
 * on the strip asks for the full lanes ([RideTimeline]'s onExpand). In the full form the gutter labels name their
 * lanes in words, and a tap on one hands its lane to onLane.
 */

/** Gutter labels the screen resolves in the app language: one per lane, and [signals] for the compact strip. */
@Immutable
data class TimelineText(
    val battery: String,
    val supply: String,
    val shipping: String,
    val lock: String,
    val fault42: String,
    val fault18: String,
    val fault8: String,
    val link: String,
    val signals: String,
)

/**
 * Time labels in the app language. [zone] places the day boundaries; [dayTime] and [day] are used once
 * the visible window crosses midnight.
 */
@Immutable
class TimelineClock(
    val zone: ZoneId,
    val time: (Long) -> String,
    val timeShort: (Long) -> String,
    val dayTime: (Long) -> String,
    val day: (Long) -> String,
)

/** The narrowest window the timeline zooms to. */
const val MIN_WINDOW_MS = 60_000L

/** The signal lanes, top to bottom. The supply drop has its own lamp lane above them, the link one below. */
val TIMELINE_SIGNALS: List<Signal> = listOf(Signal.LOCKED, Signal.FAULT_42, Signal.FAULT_18, Signal.FAULT_8, Signal.SHIPPING)

private val TOP = 10.dp
private val RUG_GAP = 5.dp
private val RUG = 6.dp
private val LANE_GAP = 14.dp
private val BATTERY = 36.dp
private val SUPPLY_GAP = 10.dp
private val SUPPLY = 12.dp
private val SIGNAL_GAP = 12.dp
private val ROW = 15.dp
private val AXIS = 22.dp
private val STRIP_GAP = 8.dp
private val STRIP = 10.dp

/** The full height of a [RideTimeline] with a speed plot of [speed]; [compact] is the short form (speed, strip, axis). */
fun rideTimelineHeight(speed: Dp, compact: Boolean = false): Dp =
    if (compact) TOP + speed + RUG_GAP + RUG + STRIP_GAP + STRIP + AXIS
    else TOP + speed + RUG_GAP + RUG + LANE_GAP + BATTERY + SUPPLY_GAP + SUPPLY + SIGNAL_GAP + ROW * (TIMELINE_SIGNALS.size + 1) + AXIS

/** Where every lane sits, in px, for one width and the visible window [start]..[end]. */
private class TimelineGeo(d: Density, width: Float, val left: Float, speed: Dp, val start: Long, val end: Long, val compact: Boolean = false) {
    val right = width - with(d) { 2.dp.toPx() }
    val span = (end - start).coerceAtLeast(1L)
    val speedTop = with(d) { TOP.toPx() }
    val speedBottom = speedTop + with(d) { speed.toPx() }
    val rugTop = speedBottom + with(d) { RUG_GAP.toPx() }
    val rugBottom = rugTop + with(d) { RUG.toPx() }
    val batTop = rugBottom + with(d) { LANE_GAP.toPx() }
    val batBottom = batTop + with(d) { BATTERY.toPx() }
    val supTop = batBottom + with(d) { SUPPLY_GAP.toPx() }
    val supBottom = supTop + with(d) { SUPPLY.toPx() }
    val sigTop = supBottom + with(d) { SIGNAL_GAP.toPx() }
    val row = with(d) { ROW.toPx() }
    val sigBottom = sigTop + row * TIMELINE_SIGNALS.size
    val linkCenter = sigBottom + row / 2
    val linkBottom = sigBottom + row
    /** The compact form's one strip of signals, right under the rug. */
    val stripTop = rugBottom + with(d) { STRIP_GAP.toPx() }
    val stripBottom = stripTop + with(d) { STRIP.toPx() }
    /** Where the cursor line ends. */
    val cursorBottom = if (compact) stripBottom else linkBottom
    val axisTop = (if (compact) stripBottom else linkBottom) + with(d) { 5.dp.toPx() }
    val pxPerMs: Float get() = (right - left) / span
    fun x(t: Long): Float = left + (right - left) * ((t - start).toFloat() / span)
    fun rowCenter(i: Int): Float = sigTop + row * i + row / 2
}

/** The top of the speed axis: the bike's top speed, or GPS's when it was faster. */
private fun speedAxisTop(log: RideLogReader.RideLog): Int? {
    val gps = log.gpsTopKmh ?: return log.topKmh
    return maxOf(log.topKmh ?: 0, ceil(gps).toInt())
}

/** The speed axis: 0 to a clean step above the top speed. */
private class SpeedAxis(top: Int?) {
    val step: Double = niceStep(maxOf(top ?: 0, 10).toDouble())
    val high: Double = maxOf(ceil((top ?: 0) / step) * step, step)
    val ticks: List<Int> = generateSequence(0.0) { it + step }.takeWhile { it <= high + step / 1000 }.map { it.toInt() }.toList()
}

/**
 * The window of [factor] times the width of [viewStart]..[viewEnd] (at least [MIN_WINDOW_MS], at most the
 * whole log [start]..[end]) that keeps [anchor] at [anchorFrac] of the width.
 */
fun zoomWindow(start: Long, end: Long, viewStart: Long, viewEnd: Long, anchor: Long, anchorFrac: Float, factor: Double): Pair<Long, Long> {
    val full = (end - start).coerceAtLeast(1L)
    if (full <= MIN_WINDOW_MS) return start to end
    val width = ((viewEnd - viewStart) * factor).toLong().coerceIn(MIN_WINDOW_MS, full)
    val from = (anchor - (anchorFrac.coerceIn(0f, 1f) * width).toLong()).coerceIn(start, end - width)
    return from to from + width
}

/** [viewStart]..[viewEnd] moved (not resized) so that [t] lies inside with some room, within [start]..[end]. */
fun windowShowing(start: Long, end: Long, viewStart: Long, viewEnd: Long, t: Long): Pair<Long, Long> {
    val width = viewEnd - viewStart
    if (width >= end - start) return start to end
    val margin = width / 10
    if (t >= viewStart + margin && t <= viewEnd - margin) return viewStart to viewEnd
    val from = (t - width / 2).coerceIn(start, end - width)
    return from to from + width
}

/**
 * The timeline. [cursor] is the chosen moment (epoch ms) or null; a tap or a sideways drag sets it
 * through [onCursor]. [viewStart]..[viewEnd] is the visible window: a double tap zooms in around the tap,
 * two fingers zoom and move it ([onView]). [description] and [state] are what TalkBack reads; [actions]
 * step the cursor and zoom.
 */
@Composable
fun RideTimeline(
    log: RideLogReader.RideLog,
    cursor: Long?,
    onCursor: (Long) -> Unit,
    viewStart: Long,
    viewEnd: Long,
    onView: (Long, Long) -> Unit,
    text: TimelineText,
    clock: TimelineClock,
    description: String,
    state: String,
    actions: List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
    speedHeight: Dp = 112.dp,
    compact: Boolean = false,
    onExpand: (() -> Unit)? = null,
    onLane: ((Signal?) -> Unit)? = null,
) {
    val start = log.startMillis ?: return
    val end = log.endMillis ?: return
    val c = S3.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = S3.type.data.copy(fontSize = 13.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum, zero", color = c.fog)
    val axis = remember(log.topKmh, log.gpsTopKmh) { SpeedAxis(speedAxisTop(log)) }
    // The gutter fits the widest label, so the plot starts at the same x in every lane.
    val gutterPx = remember(text, axis, labelStyle, measurer, density, compact) {
        val labels = axis.ticks.map { it.toString() } + if (compact) {
            listOf(text.signals)
        } else {
            listOf(text.battery, text.supply, text.shipping, text.lock, text.fault42, text.fault18, text.fault8, text.link)
        }
        val widest = labels.maxOf { measurer.measure(AnnotatedString(it), labelStyle).size.width }
        with(density) { (widest + 8.dp.toPx()).coerceIn(28.dp.toPx(), 72.dp.toPx()) }
    }
    val scrubTo by rememberUpdatedState(onCursor)
    val viewTo by rememberUpdatedState(onView)
    val expand by rememberUpdatedState(onExpand)
    val lane by rememberUpdatedState(onLane)
    val window by rememberUpdatedState(viewStart to viewEnd)
    val height = rideTimelineHeight(speedHeight, compact)

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = description
                stateDescription = state
                customActions = actions
            }
            .pointerInput(start, end, gutterPx, compact, speedHeight) {
                val slop = viewConfiguration.touchSlop
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                val near = 48.dp.toPx()
                var lastTapAt = 0L
                var lastTapX = 0f
                fun right() = size.width - 2.dp.toPx()
                fun frac(x: Float) = ((x - gutterPx) / (right() - gutterPx)).coerceIn(0f, 1f)
                fun timeAt(x: Float): Long {
                    val (a, b) = window
                    return a + (frac(x) * (b - a).coerceAtLeast(1L)).toLong()
                }
                val geo = TimelineGeo(this, size.width.toFloat(), gutterPx, speedHeight, start, end, compact)
                val reach = 4.dp.toPx()
                /** A tap that a lane label or the compact strip takes, instead of setting the cursor. */
                fun takeTap(x: Float, y: Float): Boolean {
                    if (compact) {
                        if (y < geo.stripTop - reach) return false
                        expand?.invoke() ?: return false
                        return true
                    }
                    if (x >= gutterPx) return false
                    val to = lane ?: return true
                    val half = geo.row / 2 + reach / 2
                    TIMELINE_SIGNALS.forEachIndexed { i, sig -> if (abs(y - geo.rowCenter(i)) <= half) { to(sig); return true } }
                    if (y >= geo.supTop - reach && y <= geo.supBottom + reach) { to(Signal.SUPPLY_LOW); return true }
                    if (abs(y - geo.linkCenter) <= half) { to(null); return true }
                    // The gutter beside the speed or battery lane: no lane of its own, and no moment either.
                    return true
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 0 undecided, 1 scrubbing, 2 two fingers, 3 left to the list (a vertical scroll)
                    var mode = 0
                    var moved = Offset.Zero
                    var prevDist = 0f
                    var prevX = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (mode == 0 && takeTap(down.position.x, down.position.y)) {
                                lastTapAt = 0L
                            } else if (mode == 0) {
                                val now = ev.changes.first().uptimeMillis
                                val x = down.position.x
                                if (now - lastTapAt < doubleTapMs + 120 && abs(x - lastTapX) < near) {
                                    // Double tap: four times closer around that moment.
                                    val (a, b) = window
                                    viewTo.invokePair(zoomWindow(start, end, a, b, timeAt(x), frac(x), 0.25))
                                    lastTapAt = 0L
                                } else {
                                    scrubTo(timeAt(x))
                                    lastTapAt = now
                                    lastTapX = x
                                }
                            }
                            break
                        }
                        if (pressed.size >= 2 && mode != 3) {
                            val d = distance(pressed)
                            val cx = (pressed[0].position.x + pressed[1].position.x) / 2
                            if (mode != 2) {
                                mode = 2
                            } else if (d > 0f && prevDist > 0f) {
                                // The moment under the fingers stays under them while they spread or move.
                                val (a, b) = window
                                val anchor = a + (frac(prevX) * (b - a)).toLong()
                                viewTo.invokePair(zoomWindow(start, end, a, b, anchor, frac(cx), (prevDist / d).toDouble()))
                            }
                            prevDist = d
                            prevX = cx
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (mode == 2) {
                            // One finger lifted after a pinch: wait for the other.
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = pressed[0]
                        if (mode == 0) {
                            // The list took the gesture (a scroll that started on the chart): not a tap.
                            if (ch.isConsumed) {
                                mode = 3
                                continue
                            }
                            moved += ch.positionChange()
                            if (abs(moved.x) > slop && abs(moved.x) >= abs(moved.y)) mode = 1
                            else if (abs(moved.y) > slop) mode = 3
                        }
                        if (mode == 1) {
                            ch.consume()
                            scrubTo(timeAt(ch.position.x))
                        }
                    }
                }
            },
    ) {
        TimelineLanes(log, text, clock, axis, gutterPx, speedHeight, labelStyle, c, viewStart, viewEnd, compact)
        TimelineCursor(log, cursor, axis, gutterPx, speedHeight, c, viewStart, viewEnd, compact)
    }
}

private fun ((Long, Long) -> Unit).invokePair(p: Pair<Long, Long>) = invoke(p.first, p.second)

private fun distance(p: List<PointerInputChange>): Float =
    hypot(p[0].position.x - p[1].position.x, p[0].position.y - p[1].position.y)

/** One time label on the axis: [x] is its left edge. */
private class AxisLabel(val x: Float, val layout: TextLayoutResult)

/** Everything but the cursor. Its inputs never change while the cursor moves, so it is skipped then. */
@Composable
private fun TimelineLanes(
    log: RideLogReader.RideLog,
    text: TimelineText,
    clock: TimelineClock,
    axis: SpeedAxis,
    gutterPx: Float,
    speedHeight: Dp,
    labelStyle: TextStyle,
    c: S3Palette,
    viewStart: Long,
    viewEnd: Long,
    compact: Boolean,
) {
    val measurer = rememberTextMeasurer()
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(rideTimelineHeight(speedHeight, compact))
            .graphicsLayer()
            .drawWithCache {
                val g = TimelineGeo(this, size.width, gutterPx, speedHeight, viewStart, viewEnd, compact)
                val labelGap = 8.dp.toPx()
                fun label(s: String): TextLayoutResult = measurer.measure(AnnotatedString(s), labelStyle)
                fun ySpeed(k: Int): Float = (g.speedBottom - (g.speedBottom - g.speedTop) * (k / axis.high)).toFloat()
                fun yBattery(b: Int): Float = g.batBottom - (g.batBottom - g.batTop) * (b.coerceIn(0, 100) / 100f)
                // Samples further apart than this are not joined (a pause, a long fold).
                val joinMs = RideLogReader.GAP_MS * log.sampleStride
                val visible = visibleSamples(log.samples, viewStart - joinMs, viewEnd + joinMs)

                val speedRuns = runs(visible, g::x, joinMs, keepHigh = true) { s ->
                    s.kmh?.takeIf { s.ready }?.let(::ySpeed)
                }
                val batteryRuns = runs(visible, g::x, joinMs, keepHigh = false) { s ->
                    s.battery?.takeIf { s.ready }?.let(::yBattery)
                }
                val supplyRuns = runs(visible, g::x, joinMs, keepHigh = false) { s ->
                    s.supplyOk?.takeIf { s.ready }?.let { ok -> if (ok) g.supTop else g.supBottom }
                }
                // Where rows were written at all (the link lane's baseline), connected or not.
                val recordRuns = runs(visible, g::x, joinMs, keepHigh = true) { g.linkCenter }
                val sampleStepPx = g.pxPerMs * (RideLogReader.SAMPLE_MS * log.sampleStride)
                val speedPath = Path().apply { speedRuns.forEach { addRun(it) } }
                val washPath = Path().apply {
                    speedRuns.filter { it.size > 1 }.forEach { r ->
                        moveTo(r.first().x, g.speedBottom)
                        r.forEach { lineTo(it.x, it.y) }
                        lineTo(r.last().x, g.speedBottom)
                        close()
                    }
                }
                val batteryPath = Path().apply { batteryRuns.forEach { addRun(it) } }
                val supplyPath = Path().apply {
                    supplyRuns.forEach { r ->
                        moveTo(r[0].x, r[0].y)
                        for (i in 1 until r.size) {
                            lineTo(r[i].x, r[i - 1].y)
                            lineTo(r[i].x, r[i].y)
                        }
                        lineTo(minOf(r.last().x + sampleStepPx.coerceAtLeast(1f), g.right), r.last().y)
                    }
                }
                val singles = speedRuns.filter { it.size == 1 }.map { it[0] }

                // GPS speed and the signal dropouts, only in a log that has GPS.
                fun ySpeedGps(k: Double): Float = (g.speedBottom - (g.speedBottom - g.speedTop) * (k / axis.high)).toFloat()
                val gpsPath = if (!log.hasGps) null else Path().apply {
                    runs(visible, g::x, joinMs, keepHigh = true) { s -> s.gpsKmh?.takeIf { s.ready }?.let(::ySpeedGps) }.forEach { addRun(it) }
                }
                val dropMin = 3.dp.toPx()
                val dropouts = log.dropouts.filter { it.endMillis >= viewStart && it.startMillis <= viewEnd }.map { d ->
                    val x0 = g.x(d.startMillis)
                    x0 to maxOf(g.x(d.endMillis), x0 + dropMin)
                }
                val gpsStroke = Stroke(
                    1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
                val dropBar = 4.dp.toPx()

                // Event rug: one tick per pixel column, the most serious kind wins. Built from the reader's
                // buckets (every row of the file) while they are finer than a few pixels, and from the
                // events themselves, which place each tick exactly when zoomed in.
                val rugRank = HashMap<Int, Int>()
                fun rugAt(px: Int, rank: Int) {
                    if (rank > (rugRank[px] ?: 0)) rugRank[px] = rank
                }
                val rug = log.rug
                if (rug.buckets > 0 && rug.bucketMillis * g.pxPerMs <= 3f) {
                    for (i in 0 until rug.buckets) {
                        val r = rug.rank(i)
                        if (r == 0) continue
                        val t = rug.startOf(i) + rug.bucketMillis / 2
                        if (t < viewStart || t > viewEnd) continue
                        rugAt(g.x(t).toInt(), r)
                    }
                }
                log.events.forEach { e ->
                    if (e.atMillis in viewStart..viewEnd) rugAt(g.x(e.atMillis).toInt(), RideLogReader.Rug.rankOf(e))
                }
                fun rugColor(rank: Int): Color = when (rank) {
                    RideLogReader.Rug.RANK_ALERT -> c.signal
                    RideLogReader.Rug.RANK_ERROR -> c.lamp
                    RideLogReader.Rug.RANK_MARK -> c.led
                    else -> c.fog
                }
                val marks = log.events.filter { it.mark && it.atMillis in viewStart..viewEnd }.map { g.x(it.atMillis) }

                // Signal bars. Locked while parked is normal: a thin fog line. Locked while rolling is the
                // finding: a red bar, like every other signal.
                val minBar = 3.dp.toPx()
                class Bar(val topLeft: Offset, val size: Size, val color: Color)
                val bars = TIMELINE_SIGNALS.mapIndexed { i, sig ->
                    val cy = g.rowCenter(i)
                    log.spans(sig).filter { it.endMillis >= viewStart && it.startMillis <= viewEnd }.map { s ->
                        val x0 = g.x(s.startMillis)
                        val x1 = maxOf(g.x(s.endMillis), x0 + minBar)
                        val parked = sig == Signal.LOCKED && !s.moving
                        val h = if (parked) 2.dp.toPx() else 8.dp.toPx()
                        Bar(Offset(x0, cy - h / 2), Size(x1 - x0, h), if (parked) c.fog else c.signal)
                    }
                }.flatten().sortedBy { if (it.color == c.signal) 1 else 0 }
                val supplyLow = log.spans(Signal.SUPPLY_LOW).filter { it.endMillis >= viewStart && it.startMillis <= viewEnd }.map { s ->
                    val x0 = g.x(s.startMillis)
                    x0 to maxOf(g.x(s.endMillis), x0 + minBar)
                }

                // No connection. Per pixel column, the share of its time without a link: mostly down is
                // hatched, a short drop (a flaky link next to a parked bike) is a small tick. Only the
                // longer stretches also shade the value lanes.
                val down = log.linkDown.filter { it.endMillis >= viewStart && it.startMillis <= viewEnd }
                val (downPx, dropTicks) = linkColumns(down, g, viewStart, viewEnd, RideLogReader.SAMPLE_MS)
                val wash = down.map { g.x(it.startMillis) to g.x(it.endMillis) }.filter { (a, b) -> b - a >= 3.dp.toPx() }

                // The compact strip: every rule signal while it held (a lock shut while parked is normal, so not
                // that), and the supply drop under them in lamp.
                val stripBars = if (!compact) emptyList() else TIMELINE_SIGNALS.flatMap { sig ->
                    log.spans(sig).filter { it.endMillis >= viewStart && it.startMillis <= viewEnd && (sig != Signal.LOCKED || it.moving) }
                }.map { s ->
                    val x0 = g.x(s.startMillis)
                    x0 to maxOf(g.x(s.endMillis), x0 + minBar)
                }

                // The compact speed lane is short: only its lowest and highest tick carry a number.
                val tickLabels = axis.ticks.mapIndexed { i, k ->
                    if (!compact || i == 0 || i == axis.ticks.lastIndex) label(k.toString()) else null
                }
                val batteryLabel = label(text.battery)
                val supplyLabel = label(text.supply)
                val signalsLabel = label(text.signals)
                val rowLabels = TIMELINE_SIGNALS.map { sig ->
                    when (sig) {
                        Signal.LOCKED -> label(text.lock)
                        Signal.FAULT_42 -> label(text.fault42)
                        Signal.FAULT_18 -> label(text.fault18)
                        Signal.FAULT_8 -> label(text.fault8)
                        Signal.SHIPPING -> label(text.shipping)
                        Signal.SUPPLY_LOW -> label(text.supply)
                    }
                }
                val linkLabel = label(text.link)
                val (midnights, axisLabels) = axisLabels(g, clock, viewStart, viewEnd, ::label, 12.dp.toPx(), 3.dp.toPx())

                val line = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val thin = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val markDash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                val hatchStep = 4.dp.toPx()
                val hatchH = 8.dp.toPx()

                onDrawBehind {
                    // Labels in the gutter, outside the clip.
                    axis.ticks.forEachIndexed { i, k ->
                        val l = tickLabels[i] ?: return@forEachIndexed
                        drawText(l, topLeft = Offset(g.left - labelGap - l.size.width, ySpeed(k) - l.size.height / 2f))
                    }
                    if (compact) {
                        drawText(
                            signalsLabel,
                            topLeft = Offset(g.left - labelGap - signalsLabel.size.width, (g.stripTop + g.stripBottom) / 2 - signalsLabel.size.height / 2f),
                        )
                        clipRect(left = g.left - 1f, right = g.right + 2.dp.toPx()) {
                            midnights.forEach { x ->
                                drawLine(c.rib, Offset(x, g.speedTop), Offset(x, g.axisTop + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                            }
                            axis.ticks.forEach { k ->
                                val y = ySpeed(k)
                                drawLine(c.rib, Offset(g.left, y), Offset(g.right, y), strokeWidth = 1f)
                            }
                            drawPath(washPath, c.led.copy(alpha = 0.10f))
                            dropouts.forEach { (x0, x1) ->
                                drawRect(c.signal.copy(alpha = 0.12f), Offset(x0, g.speedTop), Size(x1 - x0, g.speedBottom - g.speedTop))
                            }
                            gpsPath?.let { drawPath(it, c.paint.copy(alpha = 0.7f), style = gpsStroke) }
                            drawPath(speedPath, c.led, style = line)
                            singles.forEach { drawCircle(c.led, 2.dp.toPx(), it) }
                            marks.forEach { x ->
                                drawLine(
                                    c.led.copy(alpha = 0.6f), Offset(x, g.speedTop), Offset(x, g.speedBottom),
                                    strokeWidth = 1.dp.toPx(), pathEffect = markDash,
                                )
                            }
                            rugRank.forEach { (px, rank) ->
                                drawLine(rugColor(rank), Offset(px.toFloat(), g.rugTop), Offset(px.toFloat(), g.rugBottom), strokeWidth = 1.5.dp.toPx())
                            }
                            // One strip: the hairline, the supply drop in lamp, the rule's signals in signal over it.
                            val mid = (g.stripTop + g.stripBottom) / 2
                            drawLine(c.rib, Offset(g.left, mid), Offset(g.right, mid), strokeWidth = 1f)
                            supplyLow.forEach { (x0, x1) -> drawRect(c.lamp, Offset(x0, g.stripTop + 2.dp.toPx()), Size(x1 - x0, g.stripBottom - g.stripTop - 4.dp.toPx())) }
                            stripBars.forEach { (x0, x1) -> drawRect(c.signal, Offset(x0, g.stripTop), Size(x1 - x0, g.stripBottom - g.stripTop)) }
                        }
                        axisLabels.forEach { drawText(it.layout, topLeft = Offset(it.x, g.axisTop)) }
                        return@onDrawBehind
                    }
                    drawText(
                        batteryLabel,
                        topLeft = Offset(g.left - labelGap - batteryLabel.size.width, (g.batTop + g.batBottom) / 2 - batteryLabel.size.height / 2f),
                    )
                    drawText(
                        supplyLabel,
                        topLeft = Offset(g.left - labelGap - supplyLabel.size.width, (g.supTop + g.supBottom) / 2 - supplyLabel.size.height / 2f),
                    )
                    TIMELINE_SIGNALS.indices.forEach { i ->
                        val cy = g.rowCenter(i)
                        val l = rowLabels[i]
                        drawText(l, topLeft = Offset(g.left - labelGap - l.size.width, cy - l.size.height / 2f))
                    }
                    drawText(linkLabel, topLeft = Offset(g.left - labelGap - linkLabel.size.width, g.linkCenter - linkLabel.size.height / 2f))

                    clipRect(left = g.left - 1f, right = g.right + 2.dp.toPx()) {
                        // Day boundaries through every lane.
                        midnights.forEach { x ->
                            drawLine(c.rib, Offset(x, g.speedTop), Offset(x, g.axisTop + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                        }
                        // Stretches without a connection: a faint fog over the value lanes.
                        wash.forEach { (x0, x1) ->
                            drawRect(c.fog.copy(alpha = 0.08f), Offset(x0, g.speedTop), Size(x1 - x0, g.supBottom - g.speedTop))
                        }

                        // Speed: grid, wash, line, the rider's marks.
                        axis.ticks.forEach { k ->
                            val y = ySpeed(k)
                            drawLine(c.rib, Offset(g.left, y), Offset(g.right, y), strokeWidth = 1f)
                        }
                        drawPath(washPath, c.led.copy(alpha = 0.10f))
                        dropouts.forEach { (x0, x1) ->
                            drawRect(c.signal.copy(alpha = 0.12f), Offset(x0, g.speedTop), Size(x1 - x0, g.speedBottom - g.speedTop))
                        }
                        gpsPath?.let { drawPath(it, c.paint.copy(alpha = 0.7f), style = gpsStroke) }
                        drawPath(speedPath, c.led, style = line)
                        singles.forEach { drawCircle(c.led, 2.dp.toPx(), it) }
                        dropouts.forEach { (x0, x1) ->
                            drawRect(c.signal, Offset(x0, g.speedBottom - dropBar), Size(x1 - x0, dropBar))
                        }
                        marks.forEach { x ->
                            drawLine(
                                c.led.copy(alpha = 0.6f), Offset(x, g.speedTop), Offset(x, g.speedBottom),
                                strokeWidth = 1.dp.toPx(), pathEffect = markDash,
                            )
                            ringed(Offset(x, g.speedTop), c.led, c.asphalt)
                        }
                        // Event rug under the speed plot.
                        rugRank.forEach { (px, rank) ->
                            drawLine(rugColor(rank), Offset(px.toFloat(), g.rugTop), Offset(px.toFloat(), g.rugBottom), strokeWidth = 1.5.dp.toPx())
                        }

                        // Battery: 0 and 100 as hairlines, the level in lamp.
                        drawLine(c.rib, Offset(g.left, g.batTop), Offset(g.right, g.batTop), strokeWidth = 1f)
                        drawLine(c.rib, Offset(g.left, g.batBottom), Offset(g.right, g.batBottom), strokeWidth = 1f)
                        drawPath(batteryPath, c.lamp, style = thin)

                        // Supply: a logic trace, high at 20 V or more, low below; nothing while unknown.
                        drawLine(c.rib, Offset(g.left, g.supBottom), Offset(g.right, g.supBottom), strokeWidth = 1f)
                        supplyLow.forEach { (x0, x1) ->
                            drawRect(c.lamp.copy(alpha = 0.18f), Offset(x0, g.supTop), Size(x1 - x0, g.supBottom - g.supTop))
                        }
                        drawPath(supplyPath, c.lamp, style = thin)

                        // Signals of the shipping rule: one row each, bars where the signal held.
                        TIMELINE_SIGNALS.indices.forEach { i ->
                            val cy = g.rowCenter(i)
                            drawLine(c.rib, Offset(g.left, cy), Offset(g.right, cy), strokeWidth = 1f)
                        }
                        bars.forEach { drawRect(it.color, it.topLeft, it.size) }

                        // Link: a fog line while rows were written, hatched where the bike was not connected.
                        recordRuns.forEach { r ->
                            val x0 = r.first().x
                            val x1 = maxOf(r.last().x, x0 + 1f)
                            drawLine(c.fog, Offset(x0, g.linkCenter), Offset(x1, g.linkCenter), strokeWidth = 1.5.dp.toPx())
                        }
                        dropTicks.forEach { (x, f) ->
                            val h = (hatchH * f * 2f).coerceIn(3.dp.toPx(), hatchH)
                            drawLine(c.fog, Offset(x, g.linkCenter - h / 2), Offset(x, g.linkCenter + h / 2), strokeWidth = 1.dp.toPx())
                        }
                        downPx.forEach { (x0, x1) ->
                            val top = g.linkCenter - hatchH / 2
                            drawRect(c.asphalt, Offset(x0, top), Size(x1 - x0, hatchH))
                            if (x1 - x0 < hatchStep * 1.5f) {
                                drawRect(c.fog.copy(alpha = 0.45f), Offset(x0, top), Size(x1 - x0, hatchH))
                            } else {
                                clipRect(left = x0, top = top, right = x1, bottom = top + hatchH) {
                                    var x = x0 - hatchH
                                    while (x < x1) {
                                        drawLine(c.fog, Offset(x, top + hatchH), Offset(x + hatchH, top), strokeWidth = 1.dp.toPx())
                                        x += hatchStep
                                    }
                                }
                            }
                        }
                    }

                    // Time axis of the visible window.
                    axisLabels.forEach { drawText(it.layout, topLeft = Offset(it.x, g.axisTop)) }
                }
            },
    )
}

/**
 * The midnights in the window (x) and the axis labels that fit without overlapping. Within one day:
 * start, end and the middle. Across days: each new day at its midnight, then start and end with the day
 * when there is room, else with the time alone.
 */
private fun axisLabels(
    g: TimelineGeo,
    clock: TimelineClock,
    a: Long,
    b: Long,
    label: (String) -> TextLayoutResult,
    room: Float,
    pad: Float,
): Pair<List<Float>, List<AxisLabel>> {
    val zone = clock.zone
    val dayA = Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
    val dayB = Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
    val edge: (Long) -> String = if (b - a < 15 * 60_000L) clock.time else clock.timeShort
    val out = ArrayList<AxisLabel>()
    fun free(x0: Float, x1: Float) = x0 >= g.left - 1f && x1 <= g.right + 1f && out.none { x0 < it.x + it.layout.size.width + room && x1 + room > it.x }
    fun place(l: TextLayoutResult, x: Float): Boolean {
        if (!free(x, x + l.size.width)) return false
        out.add(AxisLabel(x, l))
        return true
    }
    if (dayA == dayB) {
        val first = label(edge(a))
        val last = label(edge(b))
        place(first, g.left)
        place(last, g.right - last.size.width)
        val mid = label(edge(a + (b - a) / 2))
        place(mid, (g.left + g.right) / 2 - mid.size.width / 2f)
        return emptyList<Float>() to out
    }
    val midnights = ArrayList<Pair<Float, Long>>()
    var d = dayA.plusDays(1)
    while (!d.isAfter(dayB) && midnights.size < 400) {
        val t = d.atStartOfDay(zone).toInstant().toEpochMilli()
        if (t in (a + 1)..b) midnights.add(g.x(t) to t)
        d = d.plusDays(1)
    }
    midnights.forEach { (x, t) -> place(label(clock.day(t)), x + pad) }
    val firstLong = label(clock.dayTime(a))
    if (!place(firstLong, g.left)) place(label(edge(a)), g.left)
    val lastLong = label(clock.dayTime(b))
    if (!place(lastLong, g.right - lastLong.size.width)) {
        val last = label(edge(b))
        place(last, g.right - last.size.width)
    }
    return midnights.map { it.first } to out
}

/**
 * The link lane per pixel column: the intervals (px) where the link was down for at least half of the
 * time, and for the other columns with a drop, (x, share of the time down). A stretch shorter than
 * [minMs] (one sample) counts as one sample.
 */
private fun linkColumns(
    down: List<RideLogReader.Stretch>,
    g: TimelineGeo,
    viewStart: Long,
    viewEnd: Long,
    minMs: Long,
): Pair<List<Pair<Float, Float>>, List<Pair<Float, Float>>> {
    val cols = ceil(g.right - g.left).toInt().coerceAtLeast(1)
    val share = FloatArray(cols + 1)
    for (st in down) {
        val a = maxOf(st.startMillis, viewStart)
        val b = minOf(maxOf(st.endMillis, st.startMillis + minMs), viewEnd)
        if (b <= a) continue
        val x0 = g.x(a) - g.left
        val x1 = g.x(b) - g.left
        var col = floor(x0).toInt().coerceIn(0, cols)
        val last = floor(x1).toInt().coerceIn(0, cols)
        while (col <= last) {
            val lo = maxOf(x0, col.toFloat())
            val hi = minOf(x1, col + 1f)
            if (hi > lo) share[col] += (hi - lo)
            col++
        }
    }
    val hatch = ArrayList<Pair<Float, Float>>()
    val ticks = ArrayList<Pair<Float, Float>>()
    var runFrom = -1
    for (col in 0..cols) {
        val f = share[col].coerceAtMost(1f)
        if (f >= 0.5f) {
            if (runFrom < 0) runFrom = col
        } else {
            if (runFrom >= 0) hatch.add(g.left + runFrom to g.left + col)
            runFrom = -1
            if (f > 0f) ticks.add(g.left + col + 0.5f to f)
        }
    }
    if (runFrom >= 0) hatch.add(g.left + runFrom to g.left + cols + 1)
    return hatch to ticks
}

/** The samples from just before [from] to just after [to] (sorted by time, as the reader keeps them). */
private fun visibleSamples(samples: List<RideLogReader.Sample>, from: Long, to: Long): List<RideLogReader.Sample> {
    if (samples.isEmpty()) return samples
    fun firstAtOrAfter(t: Long): Int {
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].atMillis < t) lo = mid + 1 else hi = mid
        }
        return lo
    }
    val i0 = (firstAtOrAfter(from) - 1).coerceAtLeast(0)
    val i1 = (firstAtOrAfter(to) + 1).coerceAtMost(samples.size)
    return if (i0 == 0 && i1 == samples.size) samples else samples.subList(i0, i1)
}

/** The cursor line through every lane, with the speed and battery it crosses. */
@Composable
private fun TimelineCursor(
    log: RideLogReader.RideLog,
    cursor: Long?,
    axis: SpeedAxis,
    gutterPx: Float,
    speedHeight: Dp,
    c: S3Palette,
    viewStart: Long,
    viewEnd: Long,
    compact: Boolean,
) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(rideTimelineHeight(speedHeight, compact))
            .drawBehind {
                val t = cursor ?: return@drawBehind
                if (t < viewStart || t > viewEnd) return@drawBehind
                val g = TimelineGeo(this, size.width, gutterPx, speedHeight, viewStart, viewEnd, compact)
                val x = g.x(t)
                drawLine(c.paint.copy(alpha = 0.55f), Offset(x, g.speedTop - 6.dp.toPx()), Offset(x, g.cursorBottom), strokeWidth = 1.dp.toPx())
                val s = log.sampleAt(t) ?: return@drawBehind
                if (!s.ready) return@drawBehind
                s.gpsKmh?.let { k ->
                    val y = (g.speedBottom - (g.speedBottom - g.speedTop) * (k / axis.high)).toFloat()
                    ringed(Offset(x, y), c.paint, c.asphalt)
                }
                s.kmh?.let { k ->
                    val y = (g.speedBottom - (g.speedBottom - g.speedTop) * (k / axis.high)).toFloat()
                    ringed(Offset(x, y), c.led, c.asphalt)
                }
                if (!compact) s.battery?.let { b ->
                    val y = g.batBottom - (g.batBottom - g.batTop) * (b.coerceIn(0, 100) / 100f)
                    ringed(Offset(x, y), c.lamp, c.asphalt)
                }
            },
    )
}

/**
 * Splits samples into joined runs of points, one point per pixel column: [keepHigh] keeps the highest
 * point of a column (top speed), otherwise the lowest (lowest battery, a supply drop).
 */
private fun runs(
    samples: List<RideLogReader.Sample>,
    x: (Long) -> Float,
    joinMs: Long,
    keepHigh: Boolean,
    y: (RideLogReader.Sample) -> Float?,
): List<List<Offset>> {
    val out = ArrayList<List<Offset>>()
    var run = ArrayList<Offset>()
    var prevT: Long? = null
    var col = Int.MIN_VALUE
    for (s in samples) {
        val yy = y(s)
        val p = prevT
        if (yy == null || (p != null && s.atMillis - p > joinMs)) {
            if (run.isNotEmpty()) out.add(run)
            run = ArrayList()
            col = Int.MIN_VALUE
        }
        if (yy == null) {
            prevT = null
            continue
        }
        val xx = x(s.atMillis)
        val c = floor(xx).toInt()
        if (c == col && run.isNotEmpty()) {
            val last = run.last()
            // Screen y grows downwards: a higher value is a smaller y.
            val keep = if (keepHigh) minOf(last.y, yy) else maxOf(last.y, yy)
            run[run.lastIndex] = Offset(last.x, keep)
        } else {
            run.add(Offset(xx, yy))
            col = c
        }
        prevT = s.atMillis
    }
    if (run.isNotEmpty()) out.add(run)
    return out
}

private fun Path.addRun(r: List<Offset>) {
    if (r.size < 2) return
    moveTo(r[0].x, r[0].y)
    for (i in 1 until r.size) lineTo(r[i].x, r[i].y)
}

private fun DrawScope.ringed(center: Offset, color: Color, surface: Color) {
    drawCircle(surface, radius = 6.dp.toPx(), center = center)
    drawCircle(color, radius = 4.dp.toPx(), center = center)
}

/** A matrix glyph as dots, right edge at [anchor].x, vertically centred on [anchor].y. */
private fun DrawScope.glyph(columns: IntArray, anchor: Offset, pitch: Float, color: Color) {
    val w = columns.size * pitch
    val h = MatrixFont.ROWS * pitch
    val x0 = anchor.x - w
    val y0 = anchor.y - h / 2
    columns.forEachIndexed { cx, mask ->
        for (r in 0 until MatrixFont.ROWS) {
            if (mask and (1 shl r) != 0) {
                drawCircle(color, pitch * 0.42f, Offset(x0 + (cx + 0.5f) * pitch, y0 + (r + 0.5f) * pitch))
            }
        }
    }
}

// --- Chart buttons --------------------------------------------------------------------------------------

/**
 * A quiet text button for the chart's controls ("Ganze Fahrt", "Vergrößern"): outline and label like [QuietButton],
 * 48 dp high so two fit beside the line under the chart; disabled in fog on a rib outline.
 */
@Composable
fun ChartButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = S3.colors
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.5.dp, if (enabled) c.paint.copy(alpha = 0.5f) else c.rib, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = S3.type.label, color = if (enabled) c.paint else c.fog, maxLines = 1)
    }
}

// --- Matrix glyph buttons ---------------------------------------------------------------------------

/** Dot patterns for the small buttons of the log viewer, drawn like the top tube's matrix (7 rows). */
object LogGlyphs {
    val PLUS = rows(".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....")
    val MINUS = rows(".....", ".....", ".....", "#####", ".....", ".....", ".....")
    val UP = rows("..#..", ".###.", "#.#.#", "..#..", "..#..", "..#..", "..#..")
    val DOWN = rows("..#..", "..#..", "..#..", "..#..", "#.#.#", ".###.", "..#..")
    val NEWEST = rows("..#..", "..#..", "#.#.#", ".###.", "..#..", ".....", "#####")

    /** Rows of '#' and '.' (top first) to one 7-bit mask per column, as [MatrixFont] keeps them. */
    private fun rows(vararg r: String): IntArray = IntArray(r[0].length) { col ->
        var m = 0
        r.forEachIndexed { row, s -> if (s[col] == '#') m = m or (1 shl row) }
        m
    }
}

/**
 * A 48 dp button that shows one matrix glyph ([LogGlyphs]) in paint, or rib while disabled.
 * [label] is what TalkBack reads.
 */
@Composable
fun MatrixGlyphButton(glyph: IntArray, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = S3.colors
    val color = if (enabled) c.paint else c.rib
    Box(
        modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val pitch = 2.6.dp.toPx()
            glyph(glyph, Offset(size.width / 2 + glyph.size * pitch / 2, size.height / 2), pitch, color)
        }
    }
}
