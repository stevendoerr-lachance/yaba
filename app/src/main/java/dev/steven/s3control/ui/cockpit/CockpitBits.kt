package dev.steven.s3control.ui.cockpit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.steven.s3control.R
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ride.RideLogReader
import dev.steven.s3control.ui.components.Fact
import dev.steven.s3control.ui.components.LedDot
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.ShellFitText
import dev.steven.s3control.ui.components.formatClock
import dev.steven.s3control.ui.components.rememberHaptics
import dev.steven.s3control.ui.components.rememberNow
import dev.steven.s3control.ui.components.rememberReducedMotion
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The cockpit's pieces. Sizes are in dp (or sp of 26 and more), so the plan in CockpitLayout.kt can
 * count on them; colour keeps one meaning each: led live, signal fault, lamp power, fog unknown.
 */

// --- Matrix -----------------------------------------------------------------------------------------

/** The frame the speed field shows for a view; always 11 columns. */
internal fun cockpitFrame(v: CockpitView): IntArray = when (v.matrix) {
    MatrixKind.LOG -> CockpitFrames.bar((v.logPercent ?: 0) / 100f)
    MatrixKind.SPEED -> CockpitFrames.speed(v.speed ?: 0)
    MatrixKind.STALE, MatrixKind.NO_SPEED -> CockpitFrames.dashes()
    MatrixKind.BLE_SIGNAL, MatrixKind.BLE_FOG -> CockpitFrames.bleQuery()
    MatrixKind.BLE_OFFLINE -> CockpitFrames.bleCentred()
}

private class LastFrame {
    var cls = -1
    var cols = IntArray(0)
    var lit = Color.Transparent
}

/**
 * The 11 × 7 dot field. A new value just switches dots; only a change of [contentClass] (speed,
 * dashes, BLE, bar) sweeps the new content in from the left, and never under reduced motion.
 */
@Composable
internal fun MatrixField(
    columns: IntArray,
    contentClass: Int,
    lit: Color,
    pitch: Dp,
    modifier: Modifier = Modifier,
    unlit: Color = S3.colors.ledOff,
    glow: Color = Color.Transparent,
    description: String? = null,
    litAlpha: () -> Float = { 1f },
) {
    val reduce = rememberReducedMotion()
    val last = remember { LastFrame() }
    val sweep = remember { Animatable(1f) }
    var oldCols by remember { mutableStateOf(IntArray(0)) }
    var oldLit by remember { mutableStateOf(Color.Transparent) }
    SideEffect {
        if (last.cls == contentClass || last.cls == -1) {
            last.cols = columns
            last.lit = lit
        }
    }
    LaunchedEffect(contentClass) {
        val first = last.cls == -1
        if (!first && last.cls != contentClass) {
            oldCols = last.cols
            oldLit = last.lit
        }
        last.cls = contentClass
        last.cols = columns
        last.lit = lit
        if (!first && !reduce) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
        } else {
            sweep.snapTo(1f)
        }
    }
    val n = CockpitFrames.COLUMNS
    Canvas(
        modifier
            .size(pitch * n, pitch * MatrixFont.ROWS)
            .semantics { if (description != null) contentDescription = description },
    ) {
        val p = size.width / n
        val rOn = p * 0.34f
        val rOff = p * 0.265f
        val sweepCol = sweep.value * (n + 2)
        val a = litAlpha().coerceIn(0f, 1f)
        for (c in 0 until n) {
            val useNew = c < sweepCol
            val mask = if (useNew) columns.getOrElse(c) { 0 } else oldCols.getOrElse(c) { 0 }
            val color = if (useNew) lit else oldLit
            val leading = useNew && c >= sweepCol - 1.5f && sweep.value < 1f
            for (y in 0 until MatrixFont.ROWS) {
                val on = mask and (1 shl y) != 0
                val center = Offset(p * (c + 0.5f), p * (y + 0.5f))
                if ((on && a > 0f) || leading) {
                    val dot = if (on) color.copy(alpha = color.alpha * a) else color.copy(alpha = 0.35f)
                    if (glow.alpha > 0f && on) {
                        drawCircle(glow.copy(alpha = glow.alpha * 0.45f * a), rOn * 2.3f, center)
                        drawCircle(glow.copy(alpha = glow.alpha * a), rOn * 1.55f, center)
                    }
                    drawCircle(dot, rOn, center)
                } else if (unlit.alpha > 0f) {
                    drawCircle(unlit, rOff, center)
                }
            }
        }
    }
}

/** Speed as the matrix, the width of the column. If it moves, the data is live. */
@Composable
internal fun CockpitMatrix(v: CockpitView, pitch: Dp, modifier: Modifier = Modifier) {
    val c = S3.colors
    val lit = when (v.matrix) {
        MatrixKind.LOG, MatrixKind.SPEED -> c.led
        MatrixKind.BLE_SIGNAL -> c.signal
        else -> c.fog
    }
    MatrixField(
        columns = remember(v.matrix, v.speed, v.logPercent) { cockpitFrame(v) },
        contentClass = v.matrix.contentClass,
        lit = lit,
        pitch = pitch,
        modifier = modifier,
        // A third of the hero's glow: at night on the handlebar the full halo blurs the digits.
        glow = if (lit == c.led) c.ledGlow.copy(alpha = c.ledGlow.alpha / 3f) else Color.Transparent,
        description = matrixSpoken(v),
    )
}

// --- Rows -------------------------------------------------------------------------------------------

/**
 * Link dot and word; under it in fog either "Fahrtprotokoll aus" (with a grey dot; the row then
 * starts the logger on tap) or the reconnect count. Nothing pulses. With [modeInstead] (a short
 * landscape window without the unit row) the mode word stands in for the link word while live.
 */
@Composable
internal fun LinkStatus(
    v: CockpitView,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    modeInstead: Boolean = false,
    onStartLogger: (() -> Unit)? = null,
) {
    val c = S3.colors
    val dot = when (v.link) {
        CockpitLink.LIVE -> c.led
        CockpitLink.LOST -> if (v.lostWhileMoving) c.signal else c.fog
        CockpitLink.OFFLINE -> c.rib
        else -> c.fog
    }
    val mode = v.mode.takeIf { modeInstead && v.link == CockpitLink.LIVE }
    val word = mode?.let { stringResource(it.labelRes) } ?: linkText(v.link)
    val wordColor = if (mode != null && v.modeSignal) c.signal else c.paint
    val loggerOff = v.loggerOffQuiet
    val startLabel = stringResource(R.string.bike_logger_start)
    val tappable = loggerOff && onStartLogger != null
    Row(
        modifier
            .then(if (tappable) Modifier.clickable(role = Role.Button) { onStartLogger?.invoke() } else Modifier)
            .semantics(mergeDescendants = true) {
                if (tappable) onClick(startLabel) { onStartLogger?.invoke(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedDot(dot, dot = 12.dp)
        Spacer(Modifier.width(6.dp))
        Column {
            // "Verbindung unterbrochen" may need two lines next to the exit slot; the second line then waits.
            val showCount = !loggerOff && v.reconnects > 0 && !compact && v.link == CockpitLink.LIVE
            val second = loggerOff || showCount
            ShellFitText(
                word,
                style = S3.type.bodyStrong.copy(lineHeight = 20.sp),
                color = wordColor,
                maxLines = if (second) 1 else 2,
                minFontSize = 14.sp,
            )
            if (loggerOff) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LedDot(c.fog, dot = 6.dp)
                    Spacer(Modifier.width(4.dp))
                    FitLine(stringResource(R.string.cockpit_alert_logger_off), S3.type.label, c.fog, visualSp(11f))
                }
            } else if (showCount) {
                Text(
                    pluralStringResource(R.plurals.cockpit_link_reconnects, v.reconnects, v.reconnects),
                    style = S3.type.label,
                    color = c.fog,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Battery as a 34 sp figure, for the status row when the battery row is not there. */
@Composable
internal fun BatteryInline(v: CockpitView, modifier: Modifier = Modifier) {
    val c = S3.colors
    val b = v.battery ?: return
    val spoken = stringResource(R.string.cockpit_battery_spoken, b)
    Row(modifier.clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
        if (v.batteryLow || v.chargerIn) {
            LedDot(c.lamp, dot = 10.dp)
            Spacer(Modifier.width(2.dp))
        }
        Text(
            stringResource(R.string.cockpit_battery_value, b),
            style = S3.type.dataLarge.copy(fontFeatureSettings = TNUM),
            color = c.paint,
            maxLines = 1,
        )
    }
}

/**
 * Left: gear and assist level while the mode is ordinary ("3. Gang", "Stufe 2"; an S2 has no gear), the mode word in
 * signal when it means trouble, or the mode word when neither value is known. Right: the unit. One line, fixed height.
 */
@Composable
internal fun UnitRow(v: CockpitView, height: Dp, modifier: Modifier = Modifier) {
    val c = S3.colors
    val data = S3.type.data.copy(fontFeatureSettings = TNUM)
    val gear = v.gear?.takeIf { !v.modeSignal }
    val level = v.powerLevel?.takeIf { !v.modeSignal }
    Row(modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        if (gear != null || level != null) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(S3.m), verticalAlignment = Alignment.CenterVertically) {
                gear?.let {
                    Text(stringResource(R.string.cockpit_val_gear, it), style = data, color = c.paint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                level?.let {
                    Text(
                        if (it == 0) stringResource(R.string.shell_power_off) else stringResource(R.string.cockpit_val_level, it),
                        style = data,
                        color = c.paint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Text(
                v.mode?.let { stringResource(it.labelRes) } ?: "–",
                style = S3.type.data,
                color = if (v.modeSignal) c.signal else if (v.mode == null) c.fog else c.paint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.cockpit_unit_kmh),
            style = S3.type.data,
            color = c.fog,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

// --- Alert line -------------------------------------------------------------------------------------

/** A lamp ring: power, not fault. */
@Composable
internal fun LampRing(modifier: Modifier = Modifier, ring: Dp = 16.dp, color: Color = S3.colors.lamp) {
    Canvas(modifier.size(ring + 8.dp)) {
        val stroke = 2.5.dp.toPx()
        drawCircle(color, radius = ring.toPx() / 2f - stroke / 2f, style = Stroke(stroke))
    }
}

/**
 * Exactly one alert at a fixed height. The code (or a dot) carries the colour; the text is always
 * paint. Empty means empty space, and the height stays.
 */
@Composable
internal fun AlertLine(
    alert: AlertView?,
    height: Dp,
    lines: Int,
    inner: Boolean,
    loggerError: String?,
    onAction: (AlertAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = S3.colors
    val clock = rememberClockFormats()
    if (alert == null) {
        Spacer(modifier.fillMaxWidth().height(height))
        return
    }
    val text = alertText(alert, clock)
    val more = alert.more
    val moreSpoken = if (more > 0) pluralStringResource(R.plurals.cockpit_alert_more_spoken, more, more) else ""
    val reopen = alert.action == AlertAction.REOPEN
    val action = alert.action
    val hasButton = action == AlertAction.RETRY || action == AlertAction.START_LOGGER
    val textStyle = S3.type.bodyStrong.copy(fontSize = 20.sp, lineHeight = 24.sp)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (reopen) Modifier.clickable(role = Role.Button) { onAction(AlertAction.REOPEN) } else Modifier)
            .semantics(mergeDescendants = true) {
                liveRegion = if (alert.assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                if (more > 0 && hasButton) stateDescription = moreSpoken
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A code needs its 56 dp; a dot only its own width, so the sentence starts close to it.
        Box(Modifier.widthIn(min = if (alert.kind == AlertKind.FAULT) 56.dp else 24.dp), contentAlignment = Alignment.CenterStart) {
            when {
                alert.kind == AlertKind.FAULT -> Text(
                    alert.code.toString(),
                    style = S3.type.dataLarge.copy(fontSize = 44.sp, lineHeight = 44.sp, fontFeatureSettings = TNUM),
                    color = c.signal,
                    maxLines = 1,
                )
                alert.kind == AlertKind.HISTORY -> Unit
                alert.tone == AlertTone.LAMP -> LampRing()
                alert.tone == AlertTone.SIGNAL -> LedDot(c.signal, dot = 16.dp)
                else -> LedDot(c.fog, dot = 16.dp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            ShellFitText(
                text,
                style = if (alert.kind == AlertKind.HISTORY) S3.type.bodyStrong else textStyle,
                color = if (alert.kind == AlertKind.HISTORY || alert.kind == AlertKind.BACK_AGAIN) c.fog else c.paint,
                maxLines = lines,
                minFontSize = 16.sp,
            )
            val sub = when {
                alert.kind == AlertKind.LOGGER_OFF && loggerError != null ->
                    stringResource(R.string.bike_logger_start_failed, loggerError)
                inner && alert.since > 0 && alert.kind != AlertKind.HISTORY && alert.kind != AlertKind.BACK_AGAIN ->
                    stringResource(R.string.cockpit_alert_since, clock.seconds(alert.since))
                else -> null
            }
            if (sub != null) {
                Text(sub, style = S3.type.caption, color = c.fog, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // With a button in the line the count gives way: the sentence needs the room more.
        if (more > 0 && !hasButton) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.cockpit_alert_more, more),
                style = S3.type.data.copy(fontFeatureSettings = TNUM),
                color = c.fog,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { contentDescription = moreSpoken },
            )
        }
        if (hasButton) {
            Spacer(Modifier.width(8.dp))
            val label = stringResource(if (action == AlertAction.RETRY) R.string.cockpit_action_retry else R.string.cockpit_action_logger)
            val spoken = stringResource(if (action == AlertAction.RETRY) R.string.bike_prompt_retry else R.string.bike_logger_start)
            CompactAction(label, spoken) { onAction(action) }
        }
    }
}

/** An outline capsule at least 56 dp tall: the glove target inside the alert line. */
@Composable
internal fun CompactAction(label: String, spoken: String, onClick: () -> Unit) {
    val c = S3.colors
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(1.5.dp, c.paint.copy(alpha = 0.5f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = S3.type.bodyStrong, color = c.paint, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
    }
}

// --- Battery and lamps ------------------------------------------------------------------------------

/** "64 %" at 56 sp with "Akku" under it, or the charger note in lamp. */
@Composable
internal fun BatteryFigure(v: CockpitView, modifier: Modifier = Modifier) {
    val c = S3.colors
    val b = v.battery
    val spoken = buildString {
        append(if (b != null) stringResource(R.string.cockpit_battery_spoken, b) else stringResource(R.string.cockpit_battery))
        if (v.chargerIn) append(", ").append(stringResource(R.string.cockpit_charger_in))
    }
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (v.batteryLow) {
                LedDot(c.lamp, dot = 10.dp)
                Spacer(Modifier.width(2.dp))
            }
            Text(
                if (b != null) stringResource(R.string.cockpit_battery_value, b) else "–",
                style = S3.type.dataLarge.copy(fontSize = 56.sp, lineHeight = 54.sp, fontFeatureSettings = TNUM),
                color = if (b != null) c.paint else c.fog,
                maxLines = 1,
            )
        }
        if (v.chargerIn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedDot(c.lamp, dot = 10.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.cockpit_charger_in), style = S3.type.caption, color = c.lamp, maxLines = 1)
            }
        } else {
            Text(stringResource(R.string.cockpit_battery), style = S3.type.caption, color = c.fog, maxLines = 1)
        }
    }
}

@Composable
private fun LampIndicator(lamp: LampView) {
    val c = S3.colors
    when (lamp.state) {
        LampState.OK -> LedDot(c.led, dot = 16.dp)
        LampState.SIGNAL_DOT -> LedDot(c.signal, dot = 16.dp)
        LampState.CAUTION -> LampRing()
        LampState.UNKNOWN -> LedDot(c.rib, dot = 16.dp)
        LampState.TRIPPED -> Box(
            Modifier
                .height(24.dp)
                .widthIn(min = 40.dp)
                .clip(RoundedCornerShape(50))
                .background(c.signal)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (lamp.code > 0) lamp.code.toString() else lampValueText(lamp.value),
                style = S3.type.label.copy(fontWeight = FontWeight(600), lineHeight = 16.sp, fontFeatureSettings = TNUM),
                color = c.onSignal,
                maxLines = 1,
            )
        }
    }
}

/**
 * One lamp of the rule: indicator (24 dp), name, value. Shape backs up colour: dot normal, signal
 * capsule tripped, lamp ring power, ghost dot unknown. The counter stays after recovery.
 * [valueText] replaces the [LampValue] word (the S2 error lamp names its fault).
 */
@Composable
internal fun CockpitLamp(
    name: String,
    lamp: LampView,
    showValue: Boolean,
    modifier: Modifier = Modifier,
    minNameSize: TextUnit = visualSp(12f),
    valueText: String? = null,
) {
    val c = S3.colors
    val value = valueText ?: lampValueText(lamp.value)
    val spoken = buildString {
        append(stringResource(R.string.cockpit_lamp_spoken, name, value))
        if (lamp.state == LampState.TRIPPED && lamp.code > 0) append(", ").append(stringResource(R.string.cockpit_fault_short, lamp.code))
        if (lamp.count > 0) append(", ").append(pluralStringResource(R.plurals.cockpit_lamp_count_spoken, lamp.count, lamp.count))
    }
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            LampIndicator(lamp)
            if (lamp.count > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.cockpit_lamp_count, lamp.count),
                    style = S3.type.label.copy(fontFeatureSettings = TNUM),
                    color = c.signal,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // "Akku-Erkennung" shrinks before it is cut, down to 12 sp.
        FitLine(name, S3.type.label, c.paint, minNameSize)
        if (showValue) {
            FitLine(
                value,
                S3.type.data.copy(lineHeight = 22.sp, fontFeatureSettings = TNUM),
                when (lamp.state) {
                    LampState.CAUTION -> c.lamp
                    LampState.UNKNOWN -> c.fog
                    else -> c.paint
                },
                visualSp(14f),
            )
        }
    }
}

/**
 * One line that shrinks in 1 sp steps down to [min] before it ellipsises. With [sameAs] the size is
 * the one at which every text in it fits too, so swapping labels never changes the size.
 */
@Composable
internal fun FitLine(
    text: String,
    style: TextStyle,
    color: Color,
    min: TextUnit,
    modifier: Modifier = Modifier,
    sameAs: List<String> = emptyList(),
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        val fitted = remember(text, sameAs, style, maxW, min) {
            dev.steven.s3control.ui.components.shellFitLabels(measurer, listOf(text) + sameAs, style, min) { w -> w.all { it <= maxW } }
        }
        Text(text, style = fitted, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * A size floor in what the eye sees: [sp] at the default text size. With a larger font scale the
 * floor stays at the same physical size, so a long name still fits on its line instead of being cut.
 */
@Composable
@ReadOnlyComposable
internal fun visualSp(sp: Float): TextUnit = (sp / LocalDensity.current.fontScale.coerceAtLeast(1f)).sp

/** The dimension line under the three rule lamps, with 45° end ticks; [height] grows with the font. */
@Composable
internal fun RuleMeasure(height: Dp = 24.dp, modifier: Modifier = Modifier) {
    val c = S3.colors
    val label = stringResource(R.string.cockpit_rule)
    Column(modifier.fillMaxWidth().height(height)) {
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(height - 4.dp)
                .drawBehind {
                    val y = size.height - 1.5.dp.toPx()
                    val stroke = 1.dp.toPx()
                    // A 6 dp tick at 45°: 2.1 dp each way from its centre.
                    val half = 6.dp.toPx() / (2f * 1.4142f)
                    drawLine(c.fog, Offset(0f, y), Offset(size.width, y), stroke)
                    for (x in listOf(half, size.width - half)) {
                        drawLine(c.fog, Offset(x - half, y + half), Offset(x + half, y - half), stroke)
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            FitLine(label, CockpitMeasure.copy(textAlign = TextAlign.Center), c.fog, 11.sp, Modifier.fillMaxWidth())
        }
    }
}

// --- Mark capsule -----------------------------------------------------------------------------------

/**
 * The biggest target on the screen: writes "Marke n" into the event log and the running ride log.
 * A new mark (touch or volume key) flashes the fill led, then for 3 s the label itself reads
 * "Marke n" at the same size inside a 3 dp led outline, so it reads at a glance.
 */
@Composable
internal fun MarkCapsule(
    v: CockpitView,
    width: Dp,
    height: Dp,
    onMark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = S3.colors
    val clock = rememberClockFormats()
    val reduce = rememberReducedMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val flash = remember { Animatable(0f) }
    LaunchedEffect(v.lastMarkNo) {
        if (!v.justMarked) return@LaunchedEffect
        if (reduce) {
            flash.snapTo(1f)
            delay(900)
            flash.snapTo(0f)
        } else {
            flash.animateTo(1f, tween(100))
            delay(600)
            flash.animateTo(0f, tween(300))
        }
    }
    val f = flash.value
    val fill = lerp(c.frame, c.led, f)
    val main = lerp(c.paint, c.onLed, f)
    val quiet = lerp(c.fog, c.onLed, f)
    val big = width >= 300.dp && height >= 140.dp
    val label = stringResource(R.string.cockpit_mark)
    val done = stringResource(R.string.cockpit_mark_event, v.lastMarkNo.coerceAtLeast(1))
    val state = when {
        v.justMarked -> stringResource(R.string.cockpit_mark_spoken, v.lastMarkNo)
        v.marks == 0 -> stringResource(R.string.cockpit_mark_none)
        else -> pluralStringResource(R.plurals.cockpit_mark_state, v.marks, v.marks)
    }
    val shape = RoundedCornerShape(50)
    val outline = v.justMarked || pressed
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(if (outline) 3.dp else 1.dp, if (outline) c.led else c.rib, shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onMark)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                stateDescription = state
                liveRegion = LiveRegionMode.Polite
                onClick(label) { onMark(); true }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FitLine(
                if (v.justMarked) done else label,
                (if (big) S3.type.display else S3.type.title).copy(textAlign = TextAlign.Center, fontFeatureSettings = TNUM),
                main,
                if (big) 26.sp else 20.sp,
                Modifier.fillMaxWidth(),
                sameAs = listOf(label, done),
            )
            Spacer(Modifier.height(4.dp))
            FitLine(
                markLine2(v, clock),
                S3.type.body.copy(fontFeatureSettings = TNUM, textAlign = TextAlign.Center),
                if (v.justMarked) main else quiet,
                visualSp(12f),
                Modifier.fillMaxWidth(),
            )
            if (height >= 150.dp) {
                FitLine(
                    markLine3(v, clock),
                    S3.type.body.copy(textAlign = TextAlign.Center),
                    quiet,
                    visualSp(12f),
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// --- Exit slot --------------------------------------------------------------------------------------

/**
 * The deliberate way out, in the kick-lock slot's shape: hold 1.2 s and the knob travels across,
 * then the cockpit closes. Letting go early sends the knob back.
 */
@Composable
internal fun ExitSlot(length: Dp, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val reduce = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val close by rememberUpdatedState(onClose)
    val spoken = stringResource(R.string.cockpit_close)
    val knob = 36.dp
    val inset = 4.dp
    Box(
        modifier
            .width(length)
            .height(48.dp)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = spoken
                onClick(spoken) { close(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        haptics.tick()
                        val run = scope.launch {
                            val left = ((1f - progress.value) * 1_200).toInt().coerceAtLeast(1)
                            progress.animateTo(1f, tween(left, easing = LinearEasing))
                            haptics.confirm()
                            close()
                        }
                        tryAwaitRelease()
                        if (run.isActive) {
                            run.cancel()
                            scope.launch {
                                if (reduce) progress.snapTo(0f) else progress.animateTo(0f, tween(150))
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(c.rib),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .padding(start = inset + knob + 10.dp, end = 12.dp)
                    .alpha((1f - progress.value * 1.6f).coerceIn(0f, 1f)),
                contentAlignment = Alignment.CenterStart,
            ) {
                // One line, fitted down to 12 sp (at the default text size).
                FitLine(stringResource(R.string.cockpit_exit_hold), S3.type.label.copy(lineHeight = 16.sp), c.paint, visualSp(12f))
            }
            Box(
                Modifier
                    .offset(x = inset + (length - inset * 2 - knob) * progress.value, y = (44.dp - knob) / 2)
                    .size(knob)
                    .clip(CircleShape)
                    .background(c.led),
            )
        }
    }
}

// --- Takeover pieces --------------------------------------------------------------------------------

/** A situation tag: outline capsule; once chosen led and done. */
@Composable
internal fun TagButton(text: String, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = S3.colors
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (chosen) c.led else Color.Transparent)
            .border(1.5.dp, if (chosen) c.led else c.paint.copy(alpha = 0.5f), shape)
            .clickable(enabled = !chosen, role = Role.Button, onClick = onClick)
            .semantics { if (chosen) stateDescription = text }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = S3.type.bodyStrong, color = if (chosen) c.onLed else c.paint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The four rule signals over the last eight samples before the trigger: did they fail together? */
@Composable
internal fun SignalTimeline(samples: List<SignalSample>, caption: String, modifier: Modifier = Modifier) {
    val c = S3.colors
    val rows = listOf(
        stringResource(R.string.cockpit_lamp_coil) to samples.map { it.coil },
        stringResource(R.string.cockpit_lamp_switch) to samples.map { it.switch },
        stringResource(R.string.cockpit_lamp_key) to samples.map { it.key },
        stringResource(R.string.cockpit_lamp_supply) to samples.map { it.supply },
    )
    Column(modifier.fillMaxWidth()) {
        Text(caption, style = S3.type.caption, color = c.fog, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        for ((label, sigs) in rows) {
            val known = sigs.count { it != Sig.NONE }
            val failed = sigs.count { it == Sig.TRIPPED }
            val spoken = stringResource(R.string.cockpit_timeline_row_spoken, label, failed, known)
            Row(
                Modifier.height(28.dp).clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FitLine(label, S3.type.label, c.paint, 11.sp, Modifier.width(128.dp))
                Canvas(Modifier.width(24.dp * sigs.size).height(28.dp)) {
                    val pitch = 24.dp.toPx()
                    sigs.forEachIndexed { i, s ->
                        val center = Offset(pitch * (i + 0.5f), size.height / 2f)
                        when (s) {
                            Sig.TRIPPED -> drawCircle(c.signal, 9.dp.toPx(), center)
                            Sig.OK -> drawCircle(c.led, 5.dp.toPx(), center)
                            Sig.NONE -> drawCircle(c.rib, 4.dp.toPx(), center)
                        }
                    }
                }
            }
        }
    }
}

// --- Inner screen extras ----------------------------------------------------------------------------

/** Connected since (a clock time), reconnects, samples: three facts in one 44 dp row. */
@Composable
internal fun FactsRow(v: CockpitView, modifier: Modifier = Modifier) {
    val clock = rememberClockFormats()
    Row(modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Fact(
            stringResource(R.string.cockpit_fact_connected),
            v.connectedAt?.let { stringResource(R.string.cockpit_fact_connected_value, clock.minutes(it)) } ?: "–",
            Modifier.weight(1f),
        )
        Fact(stringResource(R.string.cockpit_fact_reconnects), stringResource(R.string.cockpit_fact_reconnects_value, v.reconnects), Modifier.weight(1f))
        Fact(
            stringResource(R.string.cockpit_fact_samples),
            if (v.loggerActive) v.loggerSamples.toString() else "–",
            Modifier.weight(1f),
        )
    }
}

private val RECENT_KINDS = setOf(EventLog.Kind.ALERT, EventLog.Kind.ERROR, EventLog.Kind.MARK, EventLog.Kind.LINK)

/**
 * The newest alerts, marks and link changes; a fixed number of rows, never a scrolling list. A
 * long message wraps to a second line instead of being cut mid-word.
 */
@Composable
internal fun RecentList(events: List<EventLog.Event>, rows: Int, rowHeight: Dp, modifier: Modifier = Modifier) {
    val c = S3.colors
    val clock = rememberClockFormats()
    val list = remember(events, rows) { events.asReversed().asSequence().filter { it.kind in RECENT_KINDS }.take(rows).toList() }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.height(26.dp), contentAlignment = Alignment.TopStart) {
            Text(stringResource(R.string.cockpit_recent), style = S3.type.label, color = c.fog, maxLines = 1)
        }
        for (e in list) {
            Row(
                Modifier.fillMaxWidth().height(rowHeight).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    clock.seconds(e.atMillis),
                    style = S3.type.data.copy(fontFeatureSettings = TNUM),
                    color = c.fog,
                    maxLines = 1,
                    modifier = Modifier.width(92.dp),
                )
                Box(
                    Modifier
                        .padding(top = 9.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        // The same meaning as in Diagnose and the ride log viewer (RideLogReader.toneOf): a link
                        // lost while riding and a low supply are amber, kick lock, fault and shipping red.
                        .background(
                            when (RideLogReader.toneOf(e.kind.name, e.code?.name, e.kind == EventLog.Kind.MARK)) {
                                RideLogReader.Tone.SIGNAL -> c.signal
                                RideLogReader.Tone.LAMP -> c.lamp
                                RideLogReader.Tone.MARK -> c.led
                                RideLogReader.Tone.PLAIN -> c.fog
                            },
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    e.message,
                    style = S3.type.body.copy(lineHeight = 22.sp),
                    color = c.paint,
                    fontWeight = if (e.kind == EventLog.Kind.MARK) FontWeight(700) else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Fills its box; used where a block keeps its height but has nothing to say. */
@Composable
internal fun Blank(modifier: Modifier = Modifier) = Spacer(modifier.fillMaxSize())
