package dev.steven.s3control.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ui.LocalNavigator
import dev.steven.s3control.ui.rememberBleReadiness
import dev.steven.s3control.ui.theme.S3
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One line of text that shrinks (down to [minSize]) instead of wrapping or clipping.
 * Expanded type is wide; tone names must still fit a quarter of the inner screen.
 * Uses subcomposition: do not place it inside an intrinsic-size measurement.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 12.sp,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val fitted = remember(text, style, maxPx, bounded) {
            if (!bounded || !style.fontSize.isSp) return@remember style
            val base = style.fontSize.value
            val ratio = if (style.lineHeight.isSp) style.lineHeight.value / base else 1.2f
            var size = base
            var s = style
            while (size > minSize.value && measurer.measure(text, s, softWrap = false, maxLines = 1).size.width > maxPx) {
                size -= 1f
                s = style.copy(fontSize = size.sp, lineHeight = (size * ratio).sp)
            }
            s
        }
        Text(text, style = fitted, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** How a tone name is set on a tile: its type and how many lines it may take. */
@Immutable
class TileNameFit(val style: TextStyle, val maxLines: Int)

/**
 * The largest size (from [base] down to [minSize]) at which [text] stands on one line in [widthPx]; when even
 * [minSize] needs a second line, the largest size at which it takes two lines without a word cut in half.
 * Measures text: call it where the width is known (a BoxWithConstraints), not inside intrinsic measurement.
 */
fun fitTileName(
    text: String,
    base: TextStyle,
    widthPx: Int,
    measurer: TextMeasurer,
    minSize: TextUnit = 14.sp,
): TileNameFit {
    if (widthPx <= 0 || !base.fontSize.isSp) return TileNameFit(base, 2)
    val ratio = if (base.lineHeight.isSp) base.lineHeight.value / base.fontSize.value else 1.15f
    fun at(size: Float) = base.copy(fontSize = size.sp, lineHeight = (size * ratio).sp)
    val sizes = generateSequence(base.fontSize.value) { it - 1f }.takeWhile { it >= minSize.value }.toList()
    sizes.firstOrNull { measurer.measure(text, at(it), softWrap = false, maxLines = 1).size.width <= widthPx }
        ?.let { return TileNameFit(at(it), 1) }
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    sizes.firstOrNull { size ->
        val style = at(size)
        words.all { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= widthPx } &&
            measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx)).lineCount <= 2
    }?.let { return TileNameFit(at(it), 2) }
    return TileNameFit(at(minSize.value), 2)
}

/**
 * A sound's emoji on its tile, pad or row (bell tones, the sound board, the bundled bells). The owner asked for
 * these after his first ride test. BRAND bans emoji (Don't 8) and names exactly these three places as the one
 * exception, so a brand pass keeps them; SoundEmojiTest fails if one of them stops drawing its emoji. Emoji keep
 * their own colours; [faded] (a disabled control) only fades them. Silent for TalkBack: the control around it
 * already says the sound's name.
 */
@Composable
internal fun SoundEmoji(emoji: String, modifier: Modifier = Modifier, faded: Boolean = false) {
    Text(
        emoji,
        style = SoundEmojiStyle,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.alpha(if (faded) 0.35f else 1f).clearAndSetSemantics { },
    )
}

/** About the size of the title cut beside it; the line height leaves the glyph room, nothing is clipped. */
private val SoundEmojiStyle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp)

/**
 * A bell tone as a selectable tile: its emoji top left, the tone name bottom left in the expanded title cut. The
 * active tone is lit LED-blue like the matrix; the others are frame with a rib outline. Corners stay at 4 dp: a
 * tile, not a card. [name] may come with its own [nameFit] (see [fitTileName]): the emoji has its own line, so the
 * name keeps the tile's full width. [caption] says what a slot holds, else nothing.
 */
@Composable
fun BellToneTile(
    name: String,
    /** The tone's emoji ([SoundEmoji]). The owner asked for these on the tiles: keep them in a brand pass. */
    emoji: String,
    caption: String?,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    nameFit: TileNameFit? = null,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(TileCorner)
    val bg by animateColorAsState(if (selected) c.led else c.frame, label = "toneBg")
    val fg by animateColorAsState(
        when {
            selected -> c.onLed
            !enabled -> c.fog
            else -> c.paint
        },
        label = "toneFg",
    )
    val sub = if (selected) c.onLed.copy(alpha = 0.72f) else c.fog
    val busyState = stringResource(R.string.sound_tile_busy)
    val fit = nameFit ?: TileNameFit(S3.type.title, 2)
    Column(
        modifier
            .heightIn(min = ToneTileMinHeight)
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.rib, shape))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                haptics.tick(); onClick()
            }
            .semantics { if (busy) stateDescription = busyState }
            .padding(horizontal = TilePadX, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            SoundEmoji(emoji, faded = !enabled && !selected)
            Spacer(Modifier.weight(1f))
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                    color = fg,
                    strokeWidth = 2.dp,
                    trackColor = Color.Transparent,
                )
            }
        }
        // At least this much air between emoji and name; a taller tile beside it (SoundScreen gives every tile the
        // tallest one's height) adds the rest here, so every name stays on the tile's bottom edge.
        Spacer(Modifier.height(6.dp))
        Spacer(Modifier.weight(1f))
        Text(name, style = fit.style, color = fg, maxLines = fit.maxLines, overflow = TextOverflow.Ellipsis)
        if (caption != null) {
            Text(caption, style = S3.type.caption, color = sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** At most 4 dp: BRAND rules out the 8 to 16 dp card. */
private val TileCorner = 4.dp

/** Emoji line, a one-line name and a one-line caption. More text grows the tile; names are never shrunk for height. */
private val ToneTileMinHeight = 108.dp

/** A tile's own horizontal padding; the name gets the tile width minus twice this. */
val TilePadX = 10.dp

/**
 * Upload progress on the dot matrix: bell glyph, then a level bar that fills column by column.
 * Every new column sweeps across the display, so the transfer visibly "runs".
 */
@Composable
fun UploadMeter(fraction: Float, done: Boolean, modifier: Modifier = Modifier) {
    val c = S3.colors
    val barWidth = 29 // 5 (bell) + 2 (gap) + 29 = 36 columns
    val f = if (done) 1f else fraction.coerceIn(0f, 1f)
    val filled = if (done) barWidth else (f * barWidth).toInt()
    val frame = remember(filled) {
        MatrixFrame.text(MatrixFont.BELL.toString()) + MatrixFrame.gap(2) +
            MatrixFrame.bar((filled + 0.5f) / barWidth, barWidth)
    }
    val percent = if (done) 100 else (f * 100).toInt().coerceIn(0, 99)
    val progressState = if (done) {
        stringResource(R.string.sound_upload_done_state)
    } else {
        stringResource(R.string.sound_upload_progress_state, percent)
    }
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                stateDescription = progressState
            },
    ) {
        MatrixDisplay(frame = frame, columns = 36, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(S3.m))
        Text(stringResource(R.string.sound_upload_percent, percent), style = S3.type.dataLarge, color = if (done) c.led else c.paint)
    }
}

/**
 * The converted bell as a matrix waveform: peak level per column, mirrored around the middle row.
 * Reads the PCM from the WAV "data" chunk inside the bike file; garbage in gives a flat line, not a crash.
 */
fun waveformFrame(file: ByteArray, columns: Int): MatrixFrame {
    val flat = MatrixFrame(IntArray(columns) { 0b0001000 })
    val start = pcmStart(file) ?: return flat
    val samples = (file.size - start) / 2
    if (samples < columns) return flat
    val peaks = IntArray(columns)
    for (col in 0 until columns) {
        val from = start + (col.toLong() * samples / columns).toInt() * 2
        val to = start + ((col + 1).toLong() * samples / columns).toInt() * 2
        var peak = 0
        var i = from
        while (i + 1 < to) {
            val v = (file[i].toInt() and 0xFF) or (file[i + 1].toInt() shl 8)
            peak = max(peak, abs(v))
            i += 2
        }
        peaks[col] = peak
    }
    val top = max(1, peaks.max()).toFloat()
    return MatrixFrame(IntArray(columns) { col ->
        val r = peaks[col] / top
        when {
            r < 0.08f -> 0b0001000
            r < 0.35f -> 0b0011100
            r < 0.70f -> 0b0111110
            else -> 0b1111111
        }
    })
}

private fun pcmStart(file: ByteArray): Int? {
    var wave = -1
    for (i in 0..minOf(file.size - 4, 256)) {
        if (file[i] == 'W'.code.toByte() && file[i + 1] == 'A'.code.toByte() &&
            file[i + 2] == 'V'.code.toByte() && file[i + 3] == 'E'.code.toByte()
        ) { wave = i; break }
    }
    if (wave < 0) return null
    var i = wave + 4
    while (i + 8 <= file.size) {
        val id = String(file, i, 4, Charsets.US_ASCII)
        val len = (file[i + 4].toInt() and 0xFF) or ((file[i + 5].toInt() and 0xFF) shl 8) or
            ((file[i + 6].toInt() and 0xFF) shl 16) or ((file[i + 7].toInt() and 0xFF) shl 24)
        if (id == "data") return i + 8
        if (len < 0) return null
        i += 8 + len + (len and 1)
    }
    return null
}

/**
 * Ties `lastCommandError` to the control that caused it. Call [run] with an area key and the
 * command's [Op]; [errorFor] returns the error only for that area. A failed command's error is
 * cleared after about four seconds; a successful one releases the area right away.
 */
@Stable
class CommandFeedback internal constructor(private val bike: BikeController) {
    private class Pending(val area: Any, val op: Op)

    private var pending by mutableStateOf<Pending?>(null)

    fun run(area: Any, op: Op, block: () -> Unit) {
        bike.clearCommandError()
        pending = Pending(area, op)
        block()
    }

    fun errorFor(area: Any, error: String?): String? =
        if (error != null && pending?.area == area) error else null

    internal suspend fun watch() {
        snapshotFlow { pending }.collectLatest { p ->
            if (p == null) return@collectLatest
            val started = withTimeoutOrNull(3_000) {
                bike.state.first { p.op in it.busy || it.lastCommandError != null }
            }
            if (started != null) {
                val end = bike.state.first { p.op !in it.busy }
                if (end.lastCommandError != null) {
                    delay(4_000)
                    bike.clearCommandError()
                }
            }
            if (pending === p) pending = null
        }
    }
}

@Composable
fun rememberCommandFeedback(bike: BikeController): CommandFeedback {
    val feedback = remember(bike) { CommandFeedback(bike) }
    LaunchedEffect(feedback) { feedback.watch() }
    return feedback
}

/**
 * Shown instead of silently dead controls: what blocks the link and the one action that helps
 * (allow Bluetooth, switch it on, connect, search again, or go to the garage when there is no bike yet).
 * [heroBeside]: the ride hero stands in the left pane and already says what blocks the link, with its one action;
 * then only [text] stands here, one grey line.
 */
@Composable
fun ConnectNotice(vm: AppViewModel, state: BikeState, text: String, modifier: Modifier = Modifier, heroBeside: Boolean = false) {
    if (heroBeside) {
        Hint(text, modifier.fillMaxWidth())
        return
    }
    val c = S3.colors
    val ble = rememberBleReadiness()
    val navigator = LocalNavigator.current
    val connecting = state.link == Link.SEARCHING || state.link == Link.CONNECTING || state.link == Link.AUTHENTICATING
    val title = stringResource(
        when {
            !ble.permissionsGranted -> R.string.sound_connect_ble_denied
            !ble.bluetoothOn -> R.string.sound_connect_ble_off
            state.link == Link.SEARCHING -> R.string.sound_connect_searching
            state.link == Link.CONNECTING -> R.string.sound_connect_connecting
            state.link == Link.AUTHENTICATING -> R.string.sound_connect_authenticating
            state.link == Link.LOST -> R.string.sound_connect_lost
            else -> R.string.sound_connect_offline
        },
    )
    val bike = state.bike ?: vm.preferredBike()
    val action: Pair<Int, () -> Unit>? = when {
        !ble.permissionsGranted -> R.string.sound_connect_allow to ble.requestPermissions
        !ble.bluetoothOn -> R.string.sound_connect_enable to ble.requestEnable
        connecting -> null
        // The hero's own verb for the same action.
        state.link == Link.LOST && state.bike != null -> R.string.shell_hero_search_again to { vm.bike.retryNow() }
        bike != null -> R.string.sound_connect_connect to { vm.connect(bike) }
        else -> R.string.sound_open_garage to navigator.openGarage
    }
    val glyph = remember { MatrixFrame.text(MatrixFont.BLE.toString()) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S3.m)) {
            MatrixDisplay(
                frame = glyph,
                columns = 7,
                lit = if (connecting) c.led else c.fog,
                unlit = c.rib.copy(alpha = 0.55f),
                glow = if (connecting) c.ledGlow else Color.Transparent,
                modifier = Modifier.width(32.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = S3.type.bodyStrong, color = c.paint)
                Hint(text)
                val msg = state.linkMessage
                if (state.link == Link.LOST && msg != null) Hint(msg)
            }
        }
        if (connecting) {
            Spacer(Modifier.height(S3.m))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.led, trackColor = c.rib)
        }
        if (action != null) {
            Spacer(Modifier.height(S3.m))
            QuietButton(stringResource(action.first), onClick = action.second)
        }
    }
}
