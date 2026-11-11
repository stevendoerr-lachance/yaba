package dev.steven.s3control.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.steven.s3control.R
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ui.BleReadiness
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.delay
import kotlin.math.max

/*
 * Small pieces the shell (AppRoot, ride screen) shares. Names carry a "Shell" prefix so they
 * never collide with other screens' helpers in this package.
 */

/**
 * Remembers which bike command the shell sent last, so `state.lastCommandError` can be shown
 * under the control that caused it instead of somewhere random. AppRoot clears it again once
 * that command finished without an error.
 */
@Stable
class ShellCommandTracker {
    var op: Op? by mutableStateOf(null)
        private set

    fun mark(op: Op) { this.op = op }
    fun clear() { op = null }
}

/** Provided by AppRoot; one tracker for both panes of the two-pane layout. */
val LocalShellCommands = staticCompositionLocalOf { ShellCommandTracker() }

/** AppRoot's BLE readiness, so panels don't each register their own permission launchers. */
val LocalShellBle = compositionLocalOf<BleReadiness?> { null }

/**
 * Whether the Bluetooth permission prompt was already shown once. Android stops showing it after
 * two refusals, so from then on the hero also offers the app settings. Held by AppRoot, so it
 * survives folding (the hero then lives in a different pane).
 */
@Stable
class ShellBlePrompt(asked: Boolean = false) {
    var asked: Boolean by mutableStateOf(asked)

    companion object {
        val Saver: Saver<ShellBlePrompt, Boolean> = Saver(save = { it.asked }, restore = { ShellBlePrompt(it) })
    }
}

val LocalShellBlePrompt = staticCompositionLocalOf { ShellBlePrompt() }

/**
 * What the cover screen's shell puts in the top right corner of every tab: the language ([LanguageButton]). AppRoot provides
 * it around the cover screen's tabs only. The inner screen has the language at the end of its tab row; the cockpit, the
 * opening film, the Garage, the log viewer and the cover's Fahrtenschreiber have none, and the onboarding has its own.
 */
val LocalShellCorner = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** What the corner shows on a tab's first line: the language's little tube (32 dp high). */
private val CornerTube = LanguageTubeSize.height

/**
 * The tube's top under the first line's top. Its centre then meets the cap height's middle of a 44 sp title (about 20 dp
 * down), and Fahren's status line, centred on the tube, stands in the same place.
 */
private val CornerInset = 4.dp

/** Between a first line's text and the corner. */
private val CornerGap = 16.dp

private const val CORNER_LINE = "line"
private const val CORNER = "corner"

/**
 * A tab's first line (Fahren's link status, the big title of Klang, Diagnose and Bike) with the shell's corner at its end.
 * The line gets the width minus the corner and [CornerGap], so a long title or bike name never runs under it. The corner's
 * tube stands [CornerInset] under the line's top, the same place on every tab; a line lower than that (Fahren's status) is
 * centred on the tube. The corner's 48 dp touch row reaches past the tube into the page's top padding and the gap under the
 * line. It scrolls with the page, so it never stands over content. Without a corner (the inner screen, a gallery) the line
 * stands alone, as before.
 */
@Composable
fun ShellCornerLine(modifier: Modifier = Modifier, line: @Composable () -> Unit) {
    val corner = LocalShellCorner.current
    if (corner == null) {
        Box(modifier.fillMaxWidth()) { line() }
        return
    }
    Layout(
        content = {
            Box(Modifier.layoutId(CORNER_LINE)) { line() }
            Box(Modifier.layoutId(CORNER)) { corner() }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val tube = CornerTube.roundToPx()
        val inset = CornerInset.roundToPx()
        val gap = CornerGap.roundToPx()
        val cornerPlaceable = measurables.first { it.layoutId == CORNER }.measure(Constraints())
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val lineWidth = (width - cornerPlaceable.width - gap).coerceAtLeast(0)
        val linePlaceable = measurables.first { it.layoutId == CORNER_LINE }
            .measure(Constraints(minWidth = lineWidth, maxWidth = lineWidth))
        val height = max(linePlaceable.height, inset + tube)
        val lineY = (inset + (tube - linePlaceable.height) / 2).coerceIn(0, height - linePlaceable.height)
        layout(width, height) {
            linePlaceable.placeRelative(0, lineY)
            cornerPlaceable.placeRelative(width - cornerPlaceable.width, inset + (tube - cornerPlaceable.height) / 2)
        }
    }
}

/**
 * Heading text that shrinks in 2 sp steps (down to [minFontSize]) until it fits in [maxLines]
 * without breaking a word in the middle. Bike names come from the owner and can be anything.
 *
 * With [oneLineDownTo] the text first tries one line, down to that size: a title beside the shell's corner ("My S3")
 * steps down a little rather than break; what needs more than that wraps at whole words in its full size.
 */
@Composable
fun ShellFitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    minFontSize: TextUnit = 26.sp,
    oneLineDownTo: TextUnit = TextUnit.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidth = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val fitted = remember(text, style, maxWidth, maxLines, minFontSize, oneLineDownTo) {
            if (!bounded) {
                style
            } else {
                fitOneLine(measurer, text, style, maxWidth, oneLineDownTo)
                    ?: fitStyle(measurer, text, style, maxWidth, maxLines, minFontSize)
            }
        }
        Text(text, style = fitted, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** [style] on one line of [maxWidth], in 2 sp steps down to [floor]; null if it needs less than that (or no [floor]). */
private fun fitOneLine(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Int, floor: TextUnit): TextStyle? {
    if (!floor.isSp || !style.fontSize.isSp) return null
    val ratio = if (style.lineHeight.isSp) style.lineHeight.value / style.fontSize.value else 1.1f
    var size = style.fontSize.value
    while (size >= floor.value) {
        val candidate = style.copy(fontSize = size.sp, lineHeight = (size * ratio).sp)
        if (measurer.measure(text, candidate, softWrap = false, maxLines = 1).size.width <= maxWidth) return candidate
        size -= 2f
    }
    return null
}

/**
 * One font size for a set of single-line labels (a destination bar): the largest copy of [style],
 * in 1 sp steps down to [min], at which [fits] accepts the measured widths in px.
 */
fun shellFitLabels(
    measurer: TextMeasurer,
    texts: List<String>,
    style: TextStyle,
    min: TextUnit,
    fits: (widths: List<Int>) -> Boolean,
): TextStyle {
    if (!style.fontSize.isSp) return style
    val base = style.fontSize.value
    val floor = if (min.isSp) min.value else base
    var size = base
    while (true) {
        val candidate = shellLabelStyle(style, size)
        val widths = texts.map { measurer.measure(it, candidate, softWrap = false, maxLines = 1).size.width }
        if (fits(widths) || size - 1f < floor) return candidate
        size -= 1f
    }
}

/** [style] at [size] sp, its line height scaled along (as [shellFitLabels] shrinks it). */
fun shellLabelStyle(style: TextStyle, size: Float): TextStyle {
    if (!style.fontSize.isSp) return style
    val ratio = if (style.lineHeight.isSp) style.lineHeight.value / style.fontSize.value else 1.2f
    return style.copy(fontSize = size.sp, lineHeight = (size * ratio).sp)
}

private fun fitStyle(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidth: Int,
    maxLines: Int,
    min: TextUnit,
): TextStyle {
    if (!style.fontSize.isSp) return style
    val base = style.fontSize.value
    val ratio = if (style.lineHeight.isSp) style.lineHeight.value / base else 1.1f
    val floor = if (min.isSp) min.value else base
    val words = text.split(' ', '\n').filter { it.isNotEmpty() }
    var size = base
    while (true) {
        val candidate = style.copy(fontSize = size.sp, lineHeight = (size * ratio).sp)
        val whole = measurer.measure(text, candidate, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth))
        val fits = !whole.hasVisualOverflow && words.all { word ->
            measurer.measure(word, candidate, softWrap = false, maxLines = 1).size.width <= maxWidth
        }
        if (fits || size - 2f < floor) return candidate
        size -= 2f
    }
}

/**
 * A single matrix LED: a dot that lights (and, in the dark theme, glows) when [lit].
 * Used for the active destination and for status dots next to text.
 */
@Composable
fun ShellLed(
    lit: Boolean,
    modifier: Modifier = Modifier,
    color: Color = S3.colors.led,
    glow: Color = S3.colors.ledGlow,
    dot: Dp = 6.dp,
) {
    val reduce = rememberReducedMotion()
    val level by animateFloatAsState(
        if (lit) 1f else 0f,
        if (reduce) snap() else tween(220),
        label = "led",
    )
    Canvas(modifier.size(dot * 3)) {
        val r = dot.toPx() / 2f
        if (glow.alpha > 0f && level > 0f) {
            drawCircle(glow.copy(alpha = glow.alpha * 0.45f * level), r * 3f)
            drawCircle(glow.copy(alpha = glow.alpha * level), r * 1.8f)
        }
        if (level > 0f) drawCircle(color.copy(alpha = color.alpha * level), r * (0.55f + 0.45f * level))
    }
}

/**
 * The bike's answer when a command failed, inline under the control, in signal red.
 * Calls [onExpire] after four seconds so the line doesn't linger.
 */
@Composable
fun ShellCommandError(message: String?, onExpire: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    val expire by rememberUpdatedState(onExpire)
    LaunchedEffect(message) {
        delay(4_000)
        expire()
    }
    ShellSignalLine(message, modifier)
}

/** One line of signal-red text with a red dot: errors, a running alarm. */
@Composable
fun ShellSignalLine(text: String, modifier: Modifier = Modifier, color: Color = S3.colors.signal) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text, style = S3.type.label, color = color, modifier = Modifier.weight(1f))
    }
}

/** "Sending" plus a small spinner, for a section title's trailing slot while its command runs. */
@Composable
fun RowScope.ShellSending(text: String = stringResource(R.string.shell_sending)) {
    val c = S3.colors
    Row(
        Modifier
            .align(Alignment.CenterVertically)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = S3.type.caption, color = c.fog)
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = c.fog,
            strokeWidth = 2.dp,
            trackColor = Color.Transparent,
        )
    }
}

/**
 * A title with the sentence that explains it. The title and everything that can be pressed stand from the first
 * frame; only the sentence ([textModifier]) may wait for a call on the top tube (see [coverUntilSentence]).
 * The space between the two is the turn from the loud part to the fine part.
 */
@Composable
fun ShellNotice(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    titleColor: Color = S3.colors.paint,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.m)) {
        Text(title, style = S3.type.section, color = titleColor, modifier = Modifier.semantics { heading() })
        Text(text, style = S3.type.body, color = S3.colors.paint, modifier = textModifier)
    }
}
