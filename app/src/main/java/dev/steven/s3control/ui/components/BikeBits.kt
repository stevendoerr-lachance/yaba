package dev.steven.s3control.ui.components

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.steven.s3control.AppViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ui.LocalNavigator
import dev.steven.s3control.ui.rememberBleReadiness
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Shared pieces of the Bike and Diagnose screens: LED check rows, switch rows,
 * busy-aware buttons, the connect prompt and command feedback tied to one control.
 */

/** Placeholder for a value the bike has not reported. A symbol, not text: it is not translated. */
const val DASH = "–"

/** The verdict of a live check. The LED colour carries it; [spoken] tells TalkBack. */
enum class Health(@StringRes val spokenRes: Int) {
    OK(R.string.bike_health_ok),
    PROBLEM(R.string.bike_health_problem),
    ATTENTION(R.string.bike_health_attention),
    UNKNOWN(R.string.bike_health_unknown),
    ;

    val spoken: String
        @Composable @ReadOnlyComposable get() = stringResource(spokenRes)
}

/** Locale the app currently renders in (follows the per-app language). Use it for numbers and dates. */
@Composable
@ReadOnlyComposable
fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** One distance format for every screen: grouped, one decimal, e.g. "4.710,5 km" or "4,710.5 km". */
fun formatDistanceKm(km: Double, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%,.1f km", km)

@Composable
@ReadOnlyComposable
fun Health.color(): Color = when (this) {
    Health.OK -> S3.colors.led
    Health.PROBLEM -> S3.colors.signal
    Health.ATTENTION -> S3.colors.lamp
    Health.UNKNOWN -> S3.colors.rib
}

/**
 * Screen heading. Expanded black; steps down a size on very narrow cover screens. On the cover screen it is the tab's first
 * line and carries the shell's corner (the language) at its end ([ShellCornerLine]): beside it the title keeps one line down
 * to four fifths of its size ("My S3" at 38 sp), and a longer one wraps at whole words.
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val style = if (maxWidth < 330.dp) S3.type.display.copy(fontSize = 36.sp, lineHeight = 38.sp) else S3.type.display
        if (LocalShellCorner.current == null) {
            Text(text, style = style, color = S3.colors.paint, modifier = Modifier.semantics { heading() })
        } else {
            ShellCornerLine {
                ShellFitText(
                    text,
                    style,
                    S3.colors.paint,
                    modifier = Modifier.semantics(mergeDescendants = true) { heading() },
                    maxLines = 3,
                    oneLineDownTo = style.fontSize * 0.8f,
                )
            }
        }
    }
}

/** Section heading with heading semantics for TalkBack. */
@Composable
fun BikeSectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
    SectionTitle(text, modifier.semantics { heading() }, trailing)
}

/** One-pixel rule in rib colour. Structure without boxes. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(S3.colors.rib))
}

/**
 * A single LED of the matrix: lit dots glow a little in the dark theme,
 * an unknown state is drawn like an unlit matrix dot.
 */
@Composable
fun LedDot(color: Color, modifier: Modifier = Modifier, dot: Dp = 10.dp) {
    val c = S3.colors
    val lit = color != c.rib
    Canvas(modifier.size(dot + 8.dp)) {
        val r = dot.toPx() / 2f
        if (lit && c.isDark) drawCircle(color.copy(alpha = 0.22f), r * 1.8f)
        drawCircle(color, if (lit) r else r * 0.82f)
    }
}

/**
 * Live-check row: LED + label (+ hint, + note in the LED colour) + value in the condensed data face.
 * Below 360 dp, or with the font scaled past 1.15, the value goes under the label (left, data face) and the hint
 * takes the whole width beside the LED, so neither squeezes the other into a column of single words.
 */
@Composable
fun CheckRow(
    label: String,
    value: String,
    health: Health,
    modifier: Modifier = Modifier,
    hint: String? = null,
    note: String? = null,
    noteColor: Color = S3.colors.lamp,
    spoken: String = health.spoken,
) {
    val c = S3.colors
    val valueColor = if (health == Health.PROBLEM) c.signal else if (value == DASH) c.fog else c.paint
    val scaled = LocalDensity.current.fontScale > 1.15f
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = scaled || maxWidth < 360.dp
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(vertical = 12.dp)
                    .semantics(mergeDescendants = true) { stateDescription = spoken },
                verticalAlignment = Alignment.Top,
            ) {
                // 18 dp canvas, centred on the first text line (24 sp line height).
                LedDot(health.color(), Modifier.padding(top = 3.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = S3.type.body, color = c.paint)
                    if (stacked) Text(value, style = S3.type.data, color = valueColor, modifier = Modifier.padding(top = 2.dp))
                    if (hint != null) Hint(hint, Modifier.padding(top = 2.dp))
                    if (note != null) Text(note, style = S3.type.caption, color = noteColor, modifier = Modifier.padding(top = 4.dp))
                }
                if (!stacked) {
                    Spacer(Modifier.width(S3.m))
                    Text(
                        value,
                        style = S3.type.data,
                        color = valueColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Hairline()
        }
    }
}

/** Label/value row for version lists and similar tables. */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.Top,
        ) {
            Text(label, style = S3.type.body, color = S3.colors.fog, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(S3.m))
            Text(value, style = S3.type.data, color = S3.colors.paint, textAlign = TextAlign.End, modifier = Modifier.padding(top = 1.dp))
        }
        Hairline()
    }
}

/** Whole-row switch: label, caption, M3 switch. The row is the touch target. */
@Composable
fun SwitchRow(
    label: String,
    caption: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = { haptics.tick(); onCheckedChange(it) },
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = S3.type.bodyStrong, color = if (enabled) c.paint else c.fog)
            if (caption != null) Hint(caption, Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(S3.m))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onLed,
                checkedTrackColor = c.led,
                checkedBorderColor = c.led,
                uncheckedThumbColor = c.fog,
                uncheckedTrackColor = c.frame,
                uncheckedBorderColor = c.fog,
                disabledCheckedThumbColor = c.frame,
                disabledCheckedTrackColor = c.rib,
                disabledUncheckedThumbColor = c.rib,
                disabledUncheckedTrackColor = c.frame,
                disabledUncheckedBorderColor = c.rib,
            ),
        )
    }
}

/** Outlined text field colours in our palette. */
@Composable
fun bikeFieldColors(): TextFieldColors {
    val c = S3.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.paint,
        unfocusedTextColor = c.paint,
        disabledTextColor = c.fog,
        focusedBorderColor = c.led,
        unfocusedBorderColor = c.fog.copy(alpha = 0.6f),
        disabledBorderColor = c.rib,
        cursorColor = c.led,
        focusedLabelColor = c.led,
        unfocusedLabelColor = c.fog,
        disabledLabelColor = c.rib,
        focusedSupportingTextColor = c.fog,
        unfocusedSupportingTextColor = c.fog,
        disabledSupportingTextColor = c.rib,
    )
}

/** [QuietButton] that turns into a spinner + "…" label while its command runs. */
@Composable
fun WorkingQuietButton(
    text: String,
    busyText: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = S3.colors.paint,
) {
    if (!busy) {
        QuietButton(text, onClick, modifier, enabled, color)
    } else {
        val shape = RoundedCornerShape(26.dp)
        Row(
            modifier
                .heightIn(min = 52.dp)
                .clip(shape)
                .border(1.5.dp, color.copy(alpha = 0.5f), shape)
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .semantics(mergeDescendants = true) { stateDescription = busyText },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp, trackColor = Color.Transparent)
            Text(busyText, style = S3.type.bodyStrong, color = color)
        }
    }
}

/** [BigButton] that shows a spinner + "…" label while its command runs. */
@Composable
fun WorkingBigButton(
    text: String,
    busyText: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = S3.colors.led,
    onColor: Color = S3.colors.onLed,
) {
    if (!busy) {
        BigButton(text, onClick, modifier, enabled, color, onColor)
    } else {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .clip(RoundedCornerShape(29.dp))
                .background(color.copy(alpha = 0.8f))
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .semantics(mergeDescendants = true) { stateDescription = busyText },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), color = onColor, strokeWidth = 2.dp, trackColor = Color.Transparent)
            Text(busyText, style = S3.type.bodyStrong, color = onColor)
        }
    }
}

/** Link state as the blinking LED line. */
@Composable
fun LinkLine(state: BikeState, modifier: Modifier = Modifier) {
    val c = S3.colors
    val reduce = rememberReducedMotion()
    val (text, color, pulse) = when (state.link) {
        Link.READY -> Triple(R.string.bike_link_ready, c.led, false)
        Link.SEARCHING -> Triple(R.string.bike_link_searching, c.lamp, true)
        Link.CONNECTING -> Triple(R.string.bike_link_connecting, c.lamp, true)
        Link.AUTHENTICATING -> Triple(R.string.bike_link_authenticating, c.lamp, true)
        Link.LOST -> Triple(R.string.bike_link_lost, c.signal, true)
        Link.OFFLINE -> Triple(R.string.bike_link_offline, c.fog, false)
    }
    LinkIndicator(stringResource(text), color, pulsing = pulse && !reduce, modifier = modifier)
}

/**
 * What stands between the user and a working link, with the one action that fixes it.
 * Renders nothing while the bike is connected. Quiet buttons only: the screens that embed it
 * exist for something else, and the ride hero already carries the loud "Verbinden".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectPrompt(vm: AppViewModel, state: BikeState, modifier: Modifier = Modifier, reason: String? = null) {
    if (state.isReady) return
    val nav = LocalNavigator.current
    val ble = rememberBleReadiness()
    val bike = state.bike ?: vm.preferredBike()
    // While the Wache is active YABA keeps searching for the armed bike: "Stop" waits until it has ended.
    val guardActive = vm.guardState.collectAsStateWithLifecycle().value.active
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        if (reason != null) Text(reason, style = S3.type.body, color = S3.colors.paint)
        when {
            bike == null -> {
                Hint(stringResource(R.string.bike_prompt_no_bike))
                Spacer(Modifier.height(S3.xs))
                QuietButton(stringResource(R.string.bike_add_bike), nav.openGarage)
            }
            !ble.permissionsGranted -> {
                Hint(
                    stringResource(
                        if (Build.VERSION.SDK_INT >= 31) R.string.bike_prompt_permission_nearby
                        else R.string.bike_prompt_permission_location,
                    ),
                )
                Spacer(Modifier.height(S3.xs))
                QuietButton(stringResource(R.string.bike_prompt_grant_permission), ble.requestPermissions)
            }
            !ble.bluetoothOn -> {
                Hint(stringResource(R.string.bike_prompt_bluetooth_off))
                Spacer(Modifier.height(S3.xs))
                QuietButton(stringResource(R.string.bike_prompt_bluetooth_on), ble.requestEnable)
            }
            state.link == Link.OFFLINE -> {
                Spacer(Modifier.height(S3.xs))
                QuietButton(stringResource(R.string.bike_prompt_connect), { vm.connect(bike) })
                Hint(stringResource(R.string.bike_prompt_close_vanmoof))
            }
            else -> {
                Hint(state.linkMessage ?: stringResource(R.string.bike_prompt_searching, bike.name))
                Spacer(Modifier.height(S3.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
                    if (state.link == Link.LOST) QuietButton(stringResource(R.string.bike_prompt_retry), { vm.bike.retryNow() })
                    QuietButton(stringResource(R.string.bike_prompt_stop), { vm.bike.disconnect() }, enabled = !guardActive)
                }
                if (guardActive) Hint(stringResource(R.string.guard_link_locked))
            }
        }
    }
}

/** Wall clock that ticks once a second while [active]. */
@Composable
fun rememberNow(active: Boolean): Long {
    val now by produceState(System.currentTimeMillis(), active) {
        value = System.currentTimeMillis()
        while (active) {
            delay(1_000 - System.currentTimeMillis() % 1_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** 75 s → "01:15", 3 h → "3:00:00". */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * The bike reports one global `lastCommandError`. This ties it to the control that started
 * the command, so the message appears right under that control and nowhere else.
 */
@Stable
class BikeFeedback internal constructor(private val vm: AppViewModel) {
    private class Pending(val key: String, val op: Op, val startedAt: Long, val onResult: ((String?) -> Unit)?) {
        var sawBusy = false
    }

    private var pending: Pending? = null

    /** Key of the control whose command is in flight (from tap until the bike answers). */
    var running by mutableStateOf<String?>(null)
        private set
    internal var failure by mutableStateOf<Pair<String, String>?>(null)
    internal var success by mutableStateOf<String?>(null)

    /** Clears old errors, remembers [key] as the owner, then fires [action]. [onResult] gets null on success. */
    fun run(key: String, op: Op, onResult: ((error: String?) -> Unit)? = null, action: () -> Unit) {
        vm.bike.clearCommandError()
        failure = null
        success = null
        pending = Pending(key, op, System.currentTimeMillis(), onResult)
        running = key
        action()
        observe(vm.bikeState.value)
    }

    fun isBusy(key: String): Boolean = running == key
    fun errorFor(key: String): String? = failure?.takeIf { it.first == key }?.second
    fun succeeded(key: String): Boolean = success == key

    internal fun observe(s: BikeState) {
        val p = pending ?: return
        if (p.op in s.busy) {
            p.sawBusy = true
            return
        }
        val err = s.lastCommandError
        when {
            err != null -> {
                finish()
                failure = p.key to err
                p.onResult?.invoke(err)
            }
            p.sawBusy -> {
                finish()
                success = p.key
                p.onResult?.invoke(null)
            }
            // The command never started (or finished before we could see it): stop waiting.
            System.currentTimeMillis() - p.startedAt > 3_000 -> finish()
        }
    }

    private fun finish() {
        pending = null
        running = null
    }
}

@Composable
fun rememberBikeFeedback(vm: AppViewModel): BikeFeedback {
    val fb = remember(vm) { BikeFeedback(vm) }
    LaunchedEffect(fb) { vm.bikeState.collect { fb.observe(it) } }
    val running = fb.running
    LaunchedEffect(running) {
        if (running != null) {
            delay(3_500)
            fb.observe(vm.bikeState.value)
        }
    }
    val failure = fb.failure
    LaunchedEffect(failure) {
        if (failure != null) {
            delay(4_000)
            if (vm.bikeState.value.lastCommandError == failure.second) vm.bike.clearCommandError()
            fb.failure = null
        }
    }
    val success = fb.success
    LaunchedEffect(success) {
        if (success != null) {
            delay(4_000)
            fb.success = null
        }
    }
    return fb
}

/** Inline answer under a control: the bike's error in signal red, or a short confirmation. */
@Composable
fun BikeFeedbackNote(fb: BikeFeedback, key: String, modifier: Modifier = Modifier, okText: String? = null) {
    val err = fb.errorFor(key)
    val ok = if (okText != null && fb.succeeded(key)) okText else null
    val text = err ?: ok
    if (text != null) {
        Text(
            text,
            style = S3.type.label,
            color = if (err != null) S3.colors.signal else S3.colors.led,
            modifier = modifier.padding(top = S3.s).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
