package dev.steven.s3control.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.theme.S3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/*
 * Charts for the Fahrtenschreiber (ui/screens/LogInsightsScreen.kt). Quiet by construction: 2 dp lines,
 * hairline grid one step off the surface, one y-axis per chart (two measures = two charts), at most two
 * colours per chart from the palette pairs that pass the CVD check in both themes (led/fog, led/signal;
 * lamp only where it means a warning), a third series only as a dashed twin of a related one. Text never
 * wears a series colour.
 */

/** One sample. [value] NaN = no value (the line breaks there). */
@Immutable
data class TracePoint(val epoch: Long, val value: Double)

@Immutable
data class TraceSeries(
    val label: String,
    val color: Color,
    val points: List<TracePoint>,
    val dashed: Boolean = false,
    /** Hold each value until the next one (a limit that changes in steps). */
    val step: Boolean = false,
    /** ~10 % wash under the line, for the one series a chart is about. */
    val wash: Boolean = false,
)

/** A labelled horizontal reference (a firmware threshold), drawn as a solid hairline. */
@Immutable
data class TraceRef(val value: Double, val label: String)

/** A dot on the chart for an event (for example a shift retry), ringed in the surface colour. */
@Immutable
data class TraceMark(val epoch: Long, val value: Double, val color: Color)

/** Samples further apart than this are not joined (the bike slept or a row is missing). */
private const val JOIN_SECONDS = 95L

/** Legend for two or more series: a short line key in the series colour, the label in text colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraceLegend(series: List<TraceSeries>, modifier: Modifier = Modifier, extra: Pair<String, Color>? = null) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(S3.m),
        verticalArrangement = Arrangement.spacedBy(S3.xs),
    ) {
        series.forEach { s -> LegendKey(s.label, s.color, dashed = s.dashed) }
        extra?.let { (label, color) -> LegendKey(label, color, dot = true) }
    }
}

/** One legend entry: [dot] draws a ringed dot instead of a line (for event marks and wake dots). */
@Composable
fun LegendKey(label: String, color: Color, dashed: Boolean = false, dot: Boolean = false, bar: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 18.dp, height = 12.dp).clearAndSetSemantics { }) {
            val y = size.height / 2
            when {
                dot -> drawCircle(color, radius = 4.dp.toPx(), center = Offset(size.width / 2, y))
                bar -> drawRoundRect(color, Offset(0f, y - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                else -> drawLine(
                    color, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = S3.type.caption, color = S3.colors.paint)
    }
}

/** Clean axis steps: 1, 2, 2.5, 5 × 10^n, so that the range splits into about three bands. */
internal fun niceStep(range: Double): Double {
    if (range <= 0.0 || range.isNaN()) return 1.0
    val raw = range / 3.0
    val mag = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
}

/**
 * A time chart with one y-axis. Drag or tap to move the shared scrub line ([scrub], [onScrub]);
 * the values at that moment are shown by the screen above the charts, never only in the chart.
 *
 * [yFloor] is always on the axis (0 for speed, current, battery); [yTop] fixes the top (100 for %).
 */
@Composable
fun TraceChart(
    title: String,
    series: List<TraceSeries>,
    xStart: Long,
    xEnd: Long,
    scrub: Long?,
    onScrub: (Long) -> Unit,
    formatTime: (Long) -> String,
    formatTick: (Double) -> String,
    description: String,
    modifier: Modifier = Modifier,
    yFloor: Double = 0.0,
    yTop: Double? = null,
    refs: List<TraceRef> = emptyList(),
    marks: List<TraceMark> = emptyList(),
    markLegend: String? = null,
    plotHeight: Dp = 128.dp,
) {
    val c = S3.colors
    val scrubTo by rememberUpdatedState(onScrub)
    val measurer = rememberTextMeasurer()
    val tickStyle = S3.type.caption.copy(color = c.fog)
    val density = LocalDensity.current

    // Axis: computed in composition so the gesture layer and the drawing agree on the plot bounds.
    val axis = remember(series, refs, yFloor, yTop) {
        val values = series.flatMap { s -> s.points.map { it.value } }.filter { !it.isNaN() } + refs.map { it.value }
        val dataMax = values.maxOrNull() ?: (yFloor + 1)
        val dataMin = values.minOrNull() ?: yFloor
        if (yTop != null) {
            Triple(yFloor, yTop, (yTop - yFloor) / 4)
        } else {
            val lowRaw = minOf(yFloor, dataMin)
            val step = niceStep(maxOf(dataMax, yFloor + 1) - lowRaw)
            val low = floor(lowRaw / step) * step
            val high = maxOf(ceil(dataMax / step) * step, low + step)
            Triple(low, high, step)
        }
    }
    val (yLow, yHigh, yStep) = axis
    val ticks = remember(axis) {
        val list = ArrayList<Double>()
        var v = yLow
        while (v <= yHigh + yStep / 1000) { list.add(v); v += yStep }
        list
    }
    val tickLabels = remember(ticks, formatTick) { ticks.map(formatTick) }
    val gutterPx = remember(tickLabels, tickStyle, measurer) {
        (tickLabels.maxOfOrNull { measurer.measure(AnnotatedString(it), tickStyle).size.width } ?: 0) +
            with(density) { 8.dp.toPx() }
    }
    val span = (xEnd - xStart).coerceAtLeast(1)

    Column(modifier.fillMaxWidth()) {
        Text(title, style = S3.type.bodyStrong, color = c.paint)
        if (series.size >= 2 || markLegend != null) {
            Spacer(Modifier.height(S3.xs))
            TraceLegend(series, extra = markLegend?.let { it to (marks.firstOrNull()?.color ?: c.signal) })
        }
        Spacer(Modifier.height(S3.s))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(plotHeight + 22.dp)
                .semantics { contentDescription = description }
                .pointerInput(xStart, xEnd, gutterPx) {
                    fun epochAt(x: Float): Long {
                        val w = size.width - gutterPx
                        val f = ((x - gutterPx) / w).coerceIn(0f, 1f)
                        return xStart + (f * span).toLong()
                    }
                    detectTapGestures(onTap = { scrubTo(epochAt(it.x)) })
                }
                .pointerInput(xStart, xEnd, gutterPx) {
                    fun epochAt(x: Float): Long {
                        val w = size.width - gutterPx
                        val f = ((x - gutterPx) / w).coerceIn(0f, 1f)
                        return xStart + (f * span).toLong()
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { scrubTo(epochAt(it.x)) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            scrubTo(epochAt(change.position.x))
                        },
                    )
                },
        ) {
            val top = 6.dp.toPx()
            val bottom = size.height - 22.dp.toPx()
            val left = gutterPx
            val right = size.width - 2.dp.toPx()
            fun x(epoch: Long): Float = left + (right - left) * ((epoch - xStart).toFloat() / span)
            fun y(v: Double): Float = (bottom - (bottom - top) * ((v - yLow) / (yHigh - yLow))).toFloat()

            // Grid and ticks.
            ticks.forEachIndexed { i, v ->
                val yy = y(v)
                drawLine(c.rib, Offset(left, yy), Offset(right, yy), strokeWidth = 1f)
                val layout = measurer.measure(AnnotatedString(tickLabels[i]), tickStyle)
                drawText(layout, topLeft = Offset(left - 8.dp.toPx() - layout.size.width, yy - layout.size.height / 2f))
            }
            // Reference thresholds: a hairline in fog, label right-aligned above it.
            refs.forEach { r ->
                if (r.value < yLow || r.value > yHigh) return@forEach
                val yy = y(r.value)
                drawLine(c.fog, Offset(left, yy), Offset(right, yy), strokeWidth = 1.dp.toPx())
                val layout = measurer.measure(AnnotatedString(r.label), tickStyle)
                drawText(layout, topLeft = Offset(right - layout.size.width, yy - layout.size.height - 1.dp.toPx()))
            }
            // Series.
            series.forEach { s -> drawSeries(s, ::x, ::y, bottom) }
            // Event marks.
            marks.forEach { m ->
                if (m.epoch < xStart || m.epoch > xEnd) return@forEach
                ringedDot(Offset(x(m.epoch), y(m.value.coerceIn(yLow, yHigh))), m.color, c.asphalt)
            }
            // Time labels: start, end, and the middle when there is room.
            val first = measurer.measure(AnnotatedString(formatTime(xStart)), tickStyle)
            val last = measurer.measure(AnnotatedString(formatTime(xEnd)), tickStyle)
            val ty = bottom + 4.dp.toPx()
            drawText(first, topLeft = Offset(left, ty))
            drawText(last, topLeft = Offset(right - last.size.width, ty))
            val mid = measurer.measure(AnnotatedString(formatTime(xStart + span / 2)), tickStyle)
            val midX = (left + right) / 2 - mid.size.width / 2f
            if (midX > left + first.size.width + 12.dp.toPx() && midX + mid.size.width < right - last.size.width - 12.dp.toPx()) {
                drawText(mid, topLeft = Offset(midX, ty))
            }
            // Scrub line and the values it crosses.
            if (scrub != null && scrub in xStart..xEnd) {
                val sx = x(scrub)
                drawLine(c.paint.copy(alpha = 0.55f), Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.dp.toPx())
                series.forEach { s ->
                    val p = nearest(s.points, scrub, s.step) ?: return@forEach
                    if (p.value.isNaN()) return@forEach
                    ringedDot(Offset(sx, y(p.value.coerceIn(yLow, yHigh))), s.color, c.asphalt)
                }
            }
        }
    }
}

/** The value of [points] at [epoch]: the held value for a step series, else the closest joined sample. */
fun nearest(points: List<TracePoint>, epoch: Long, step: Boolean = false): TracePoint? {
    if (points.isEmpty()) return null
    if (step) return points.lastOrNull { it.epoch <= epoch } ?: points.first().takeIf { it.epoch - epoch <= JOIN_SECONDS }
    val best = points.minByOrNull { abs(it.epoch - epoch) } ?: return null
    return best.takeIf { abs(it.epoch - epoch) <= JOIN_SECONDS }
}

private fun DrawScope.drawSeries(s: TraceSeries, x: (Long) -> Float, y: (Double) -> Float, baseline: Float) {
    val stroke = Stroke(
        width = 2.dp.toPx(),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
    )
    // Split into runs of joined samples.
    val runs = ArrayList<List<TracePoint>>()
    var run = ArrayList<TracePoint>()
    for (p in s.points) {
        val prev = run.lastOrNull()
        if (p.value.isNaN() || (prev != null && !s.step && p.epoch - prev.epoch > JOIN_SECONDS)) {
            if (run.isNotEmpty()) runs.add(run)
            run = ArrayList()
            if (p.value.isNaN()) continue
        }
        run.add(p)
    }
    if (run.isNotEmpty()) runs.add(run)
    for (r in runs) {
        if (r.size == 1) {
            drawCircle(s.color, radius = 2.dp.toPx(), center = Offset(x(r[0].epoch), y(r[0].value)))
            continue
        }
        val path = Path()
        r.forEachIndexed { i, p ->
            val px = x(p.epoch)
            val py = y(p.value)
            when {
                i == 0 -> path.moveTo(px, py)
                s.step -> { path.lineTo(px, y(r[i - 1].value)); path.lineTo(px, py) }
                else -> path.lineTo(px, py)
            }
        }
        if (s.wash) {
            val fill = Path().apply {
                addPath(path)
                lineTo(x(r.last().epoch), baseline)
                lineTo(x(r.first().epoch), baseline)
                close()
            }
            drawPath(fill, s.color.copy(alpha = 0.10f))
        }
        drawPath(path, s.color, style = stroke)
    }
}

private fun DrawScope.ringedDot(center: Offset, color: Color, surface: Color) {
    drawCircle(surface, radius = 6.dp.toPx(), center = center)
    drawCircle(color, radius = 4.dp.toPx(), center = center)
}

/** A small single-series line for a ride row (speed over the ride). No axis: the row text carries the values. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 72.dp, height = 28.dp).clearAndSetSemantics { }) {
        val v = values.filter { !it.isNaN() }
        if (v.size < 2) return@Canvas
        val max = (v.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val pad = 2.dp.toPx()
        val path = Path()
        v.forEachIndexed { i, value ->
            val px = pad + (size.width - 2 * pad) * i / (v.size - 1)
            val py = size.height - pad - (size.height - 2 * pad) * (value / max).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Counts per gear as columns (gear 1–4): ≤ 24 dp wide, rounded at the data end, square on the
 * baseline, the value on the cap. One series, so no legend; the caption names it.
 */
@Composable
fun GearBars(counts: List<Int>, gearLabel: (Int) -> String, color: Color, description: String, modifier: Modifier = Modifier) {
    val c = S3.colors
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier
            .fillMaxWidth()
            .height(112.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(S3.s),
        verticalAlignment = Alignment.Bottom,
    ) {
        counts.forEachIndexed { i, n ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(n.toString(), style = S3.type.label, color = if (n > 0) c.paint else c.fog)
                Spacer(Modifier.height(S3.xs))
                val h = (64.dp * (n.toFloat() / max)).coerceAtLeast(if (n > 0) 4.dp else 0.dp)
                Box(
                    Modifier
                        .width(24.dp)
                        .height(h)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(color),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.rib))
                Spacer(Modifier.height(S3.xs))
                Text(gearLabel(i + 1), style = S3.type.caption, color = c.fog)
            }
        }
    }
}

/** One day of the wake strip: rides as spans, wakes as dots, on a 0–24 h track. Times are seconds of the day. */
@Immutable
data class DayLane(
    val label: String,
    val rides: List<Pair<Int, Int>>,
    val wakes: List<Int>,
    val moved: List<Int>,
    val description: String,
)

/**
 * Days stacked, each a 24-hour track: rides in [rideColor] as spans, wakes as dots in [wakeColor],
 * shaken-without-ride wakes in [movedColor]. Shape (span vs dot) separates rides from wakes even without colour.
 */
@Composable
fun DayStrip(
    lanes: List<DayLane>,
    hourLabels: List<String>,
    rideColor: Color,
    wakeColor: Color,
    movedColor: Color,
    modifier: Modifier = Modifier,
) {
    val c = S3.colors
    val measurer = rememberTextMeasurer()
    val tick = S3.type.caption.copy(color = c.fog)
    Column(modifier.fillMaxWidth()) {
        lanes.forEach { lane ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) { contentDescription = lane.description },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(lane.label, style = S3.type.caption, color = c.paint, modifier = Modifier.width(76.dp).clearAndSetSemantics { })
                Canvas(Modifier.weight(1f).height(32.dp)) {
                    // Two bands on one track: wake dots above the hairline, ride spans on it, so a ride
                    // that starts at its wake-up never hides the dot.
                    val base = size.height / 2 + 5.dp.toPx()
                    val dotY = size.height / 2 - 6.dp.toPx()
                    val w = size.width
                    fun x(sec: Int) = w * (sec / 86_400f)
                    for (h in 0..4) {
                        val xx = x(h * 6 * 3600)
                        drawLine(c.rib, Offset(xx, dotY), Offset(xx, base + 4.dp.toPx()), strokeWidth = 1f)
                    }
                    drawLine(c.rib, Offset(0f, base), Offset(w, base), strokeWidth = 1f)
                    lane.rides.forEach { (a, b) ->
                        val x0 = x(a)
                        val x1 = maxOf(x(b), x0 + 4.dp.toPx())
                        drawRoundRect(rideColor, Offset(x0, base - 3.dp.toPx()), Size(x1 - x0, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                    }
                    lane.wakes.forEach { ringedDot(Offset(x(it), dotY), wakeColor, c.asphalt) }
                    lane.moved.forEach { ringedDot(Offset(x(it), dotY), movedColor, c.asphalt) }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(76.dp))
            Canvas(Modifier.weight(1f).height(18.dp).clearAndSetSemantics { }) {
                hourLabels.forEachIndexed { i, label ->
                    val layout = measurer.measure(AnnotatedString(label), tick)
                    val xx = size.width * i / (hourLabels.size - 1) - when (i) {
                        0 -> 0f
                        hourLabels.lastIndex -> layout.size.width.toFloat()
                        else -> layout.size.width / 2f
                    }
                    drawText(layout, topLeft = Offset(xx, 0f))
                }
            }
        }
    }
}

/**
 * The firmware's current-limit table: rows = battery temperature, columns = state of charge. The cell
 * the bike is in now is lit like a matrix dot (led background); every value stays readable as text.
 */
@Composable
fun DerateGrid(
    rowLabels: List<String>,
    columnLabels: List<String>,
    cells: List<List<String>>,
    current: Pair<Int, Int>?,
    currentState: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val c = S3.colors
    val labelWidth = 60.dp
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(labelWidth))
            columnLabels.forEach { label ->
                Text(
                    label,
                    style = S3.type.caption,
                    color = c.fog,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(bottom = S3.xs),
                )
            }
        }
        rowLabels.forEachIndexed { r, rowLabel ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.rib))
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(rowLabel, style = S3.type.caption, color = c.fog, modifier = Modifier.width(labelWidth))
                cells[r].forEachIndexed { col, value ->
                    val lit = current?.first == r && current.second == col
                    Box(Modifier.weight(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (lit) c.led else Color.Transparent)
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .then(if (lit) Modifier.semantics { stateDescription = currentState } else Modifier),
                        ) {
                            Text(value, style = S3.type.label, color = if (lit) c.onLed else c.paint, maxLines = 1)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rib))
    }
}

/** Shared text style for numbers that sit in columns (tabular figures). */
internal val TabularDigits: TextStyle = TextStyle(fontFeatureSettings = "tnum")
