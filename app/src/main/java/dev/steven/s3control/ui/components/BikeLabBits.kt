package dev.steven.s3control.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.LabOp
import dev.steven.s3control.ble.LabProtocol
import dev.steven.s3control.ble.LabState
import dev.steven.s3control.ride.BikeLogArchive
import dev.steven.s3control.ui.theme.S3
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Bike-Lab: everything the firmware knows but no app shows, surfaced as live values and the
 * parked-only writes that go with them (clock, light threshold, lamp channels, boost flag, sleep,
 * self-test, siren). New file; it only reads [AppViewModel.labState] and calls [AppViewModel.lab].
 *
 * Guards here are technical, never legal, and each carries its own reason: a true assist-cut write is
 * offered only while the bike is not rolling and says so; the clock and the boost flag do not cut assist
 * and give their own reason (a stuck-button mis-detect, an accidental level change); flash-backed values
 * are written on Save and read back before being shown as saved; a switched-off lamp does not self-heal,
 * so restore is offered; the self-test archives the log first because its report pushes the oldest lines
 * out. The bell may be muted, the threshold may be 0, the lamps may blink — none of that is blocked.
 *
 * A lab error is shown inline, under the control that failed (via [LabState.errorOp]), not only up top.
 *
 * The section is folded ([DisclosureSection], a fold row like the other look-up folds of the Bike tab): the live values are
 * polled only while it is open.
 */
@Composable
fun BikeLabSection(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val lab by vm.labState.collectAsStateWithLifecycle()
    val c = S3.colors
    val ready = state.isReady
    val parked = remember(state) { LabProtocol.isParked(state) }
    val guardOn = vm.guardState.collectAsStateWithLifecycle().value.active

    LaunchedEffect(ready) { if (ready) vm.lab.readConfig() }

    DisclosureSection(stringResource(R.string.bikelab_title), modifier, foldRow = true) {
        // Composed only while open: the live telemetry runs exactly as long as someone looks at it.
        DisposableEffect(Unit) {
            vm.lab.startLive()
            onDispose { vm.lab.stopLive() }
        }
        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bikelab_intro), style = S3.type.body, color = c.paint)
            if (!ready) {
                Spacer(Modifier.height(S3.s))
                Hint(stringResource(R.string.bikelab_needs_link))
            }
            // Errors that belong to no particular control (a bare READ) still surface near the top.
            InlineLabError(lab, LabOp.READ, Modifier.padding(top = S3.m))

            Spacer(Modifier.height(S3.l))
            LiveValues(vm, lab)

            Spacer(Modifier.height(S3.xl))
            ConfigValues(vm, lab, ready, parked)

            if (guardOn) {
                // The Wache owns 5521/5523 now: the lab's assist-cut writes are refused (the guard design 2.4).
                Spacer(Modifier.height(S3.xl))
                LampNote(stringResource(R.string.guard_core_error_active))
            } else {
                Spacer(Modifier.height(S3.xl))
                ParkedActions(vm, lab, ready, parked)

                Spacer(Modifier.height(S3.xl))
                SirenBlock(vm, lab, parked)
            }
        }
    }
}

/**
 * A paragraph that is a condition, not a state: the text in paint, with a lamp dot before it (lamp stays a dot,
 * never the colour of a sentence).
 */
@Composable
internal fun LampNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        LedDot(S3.colors.lamp, Modifier.padding(top = 1.dp), dot = 8.dp)
        Spacer(Modifier.width(S3.xs))
        Text(text, style = S3.type.caption, color = S3.colors.paint, modifier = Modifier.weight(1f))
    }
}

/** The lab error rendered inline, in signal red, under the control it belongs to (its [LabOp]). */
@Composable
private fun InlineLabError(lab: LabState, op: LabOp, modifier: Modifier = Modifier) {
    val err = lab.error?.takeIf { lab.errorOp == op } ?: return
    Text(
        err,
        style = S3.type.label,
        color = S3.colors.signal,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

// --- Live values ---------------------------------------------------------------------------------

@Composable
private fun LiveValues(vm: AppViewModel, lab: LabState) {
    BikeSubTitle(stringResource(R.string.bikelab_live_title))
    Spacer(Modifier.height(S3.s))

    TempRow(stringResource(R.string.bikelab_temp_motor), lab.motorTempC, hotAt = 60, waiting = false)
    TempRow(stringResource(R.string.bikelab_temp_controller), lab.controllerTempC, hotAt = 60, waiting = false)
    // The board sensor (5545) is notify-only: it stays null until the first change, so say so rather than "–".
    TempRow(stringResource(R.string.bikelab_temp_board), lab.boardTempC, hotAt = null, waiting = true)

    LabValueRow(stringResource(R.string.lab_char_cadence), lab.cadenceRaw?.toString() ?: DASH)
    val torque = lab.calibratedTorque
    LabValueRow(
        if (lab.torqueZero != null) stringResource(R.string.bikelab_torque_calibrated) else stringResource(R.string.lab_char_torque),
        torque?.toString() ?: DASH,
    )
    Spacer(Modifier.height(S3.s))
    QuietButton(
        text = stringResource(R.string.bikelab_torque_calibrate),
        onClick = { vm.lab.calibrateTorqueZero() },
        enabled = lab.torqueRaw != null,
    )
    Hint(stringResource(R.string.bikelab_torque_hint), Modifier.padding(top = S3.xs))
}

@Composable
private fun TempRow(label: String, celsius: Int?, hotAt: Int?, waiting: Boolean) {
    val c = S3.colors
    val hot = hotAt != null && celsius != null && celsius >= hotAt
    LabValueRow(
        label,
        celsius?.let { stringResource(R.string.bikelab_temp_value, it) }
            ?: if (waiting) stringResource(R.string.labx_board_temp_waiting) else DASH,
        valueColor = if (hot) c.lamp else null,
    )
}

// --- Read-once config + flash writes -------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigValues(vm: AppViewModel, lab: LabState, ready: Boolean, parked: Boolean) {
    BikeSubTitle(stringResource(R.string.bikelab_config_title))
    Spacer(Modifier.height(S3.s))

    LabValueRow(stringResource(R.string.bikelab_cycles), lab.chargeCycles?.toString() ?: DASH)
    if (lab.powerBankPresent == true) {
        LabValueRow(stringResource(R.string.bikelab_powerbank_cycles), lab.powerBankCycles?.toString() ?: DASH)
    }

    LabValueRow(stringResource(R.string.bikelab_lux), lab.luxRaw?.toString() ?: DASH)
    Spacer(Modifier.height(S3.m))
    LightThreshold(vm, lab, ready)

    Spacer(Modifier.height(S3.l))
    ClockRow(vm, lab, ready)

    Spacer(Modifier.height(S3.l))
    LampChannels(vm, lab, ready)

    // Boost-in-motion flag (5534 byte 1). Not an assist-cut write; its own reason (an accidental level
    // change while rolling), and it stays "nicht gelesen" until the bike's byte 1 has been decoded.
    Spacer(Modifier.height(S3.l))
    val boostRead = lab.boostInMotion != null
    SwitchRow(
        label = stringResource(R.string.bikelab_boost),
        caption = if (boostRead) stringResource(R.string.bikelab_boost_caption) else stringResource(R.string.labx_not_read),
        checked = lab.boostInMotion == true,
        onCheckedChange = { on -> vm.lab.setBoostInMotion(on) },
        enabled = ready && boostRead && parked && LabOp.BOOST !in lab.busy,
    )
    InlineLabError(lab, LabOp.BOOST, Modifier.padding(top = S3.xs))
    if (ready && boostRead && !parked) LampNote(stringResource(R.string.labx_error_boost_rolling), Modifier.padding(top = S3.xs))
}

@Composable
private fun LightThreshold(vm: AppViewModel, lab: LabState, ready: Boolean) {
    val c = S3.colors
    val stored = lab.lightThreshold
    val read = stored != null
    // The slider edits a draft; the bike only hears it on Save. Until 5584 is read there is nothing to edit.
    var draft by rememberSaveable(stored) { mutableFloatStateOf((stored ?: 200).toFloat()) }
    val value = draft.roundToInt()
    val dirty = read && value != stored
    val label = stringResource(R.string.bikelab_threshold)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = S3.type.body, color = c.paint, modifier = Modifier.weight(1f))
        Text(
            when {
                !read -> stringResource(R.string.labx_not_read)
                value == 200 -> stringResource(R.string.labx_threshold_factory)
                else -> value.toString()
            },
            style = S3.type.data,
            color = if (read) c.paint else c.fog,
        )
    }
    Slider(
        value = draft,
        onValueChange = { draft = it },
        valueRange = 0f..600f,
        enabled = read && LabOp.LIGHT !in lab.busy,
        colors = SliderDefaults.colors(
            thumbColor = c.led,
            activeTrackColor = c.led,
            inactiveTrackColor = c.rib,
        ),
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = if (read) value.toString() else ""
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S3.m)) {
        WorkingQuietButton(
            text = stringResource(R.string.bikelab_threshold_save),
            busyText = stringResource(R.string.bikelab_working),
            busy = LabOp.LIGHT in lab.busy,
            onClick = { vm.lab.setLightThreshold(value) },
            enabled = ready && read && dirty && LabOp.LIGHT !in lab.busy,
        )
    }
    InlineLabError(lab, LabOp.LIGHT, Modifier.padding(top = S3.xs))
    Hint(stringResource(R.string.bikelab_threshold_hint), Modifier.padding(top = S3.xs))
}

@Composable
private fun ClockRow(vm: AppViewModel, lab: LabState, ready: Boolean) {
    val c = S3.colors
    val skew = lab.clockSkewSeconds
    val skewText = when {
        lab.clockEpoch == null -> stringResource(R.string.bikelab_clock_unknown)
        skew == null -> stringResource(R.string.bikelab_clock_unknown)
        abs(skew) <= 2 -> stringResource(R.string.bikelab_clock_ok)
        skew > 0 -> stringResource(R.string.bikelab_clock_ahead, skew)
        else -> stringResource(R.string.bikelab_clock_behind, -skew)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.bikelab_clock), style = S3.type.body, color = c.paint)
            Text(skewText, style = S3.type.caption, color = c.fog)
        }
        WorkingQuietButton(
            text = stringResource(R.string.bikelab_clock_set),
            busyText = stringResource(R.string.bikelab_working),
            busy = LabOp.CLOCK in lab.busy,
            onClick = { vm.lab.syncClock() },
            enabled = ready && LabOp.CLOCK !in lab.busy,
        )
    }
    InlineLabError(lab, LabOp.CLOCK, Modifier.padding(top = S3.xs))
    // The clock's technical reason is a stuck-button mis-detect, not assist loss.
    Hint(stringResource(R.string.labx_error_clock_jump), Modifier.padding(top = S3.xs))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LampChannels(vm: AppViewModel, lab: LabState, ready: Boolean) {
    val c = S3.colors
    // Only act on values actually read: null stock means "not read", not 0/0/0.
    val read = lab.lampsRead
    val shown = lab.lampOverride ?: lab.lampStock ?: listOf(0, 0, 0)
    val busy = LabOp.LAMP in lab.busy

    Text(stringResource(R.string.bikelab_lamps_title), style = S3.type.bodyStrong, color = c.paint, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(S3.s))
    if (!read) {
        Text(stringResource(R.string.labx_not_read), style = S3.type.caption, color = c.fog)
        Spacer(Modifier.height(S3.s))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        for (i in 0..2) {
            val on = read && (shown.getOrNull(i) ?: 0) != 0
            val accent = if (on) c.led else c.fog
            val labelText = stringResource(if (on) R.string.bikelab_lamp_on else R.string.bikelab_lamp_off, i + 1)
            // TalkBack: "Kanal N" is the label, "an"/"aus" the state, with the Switch role, so the state and
            // the action do not read as the same thing.
            val channelCd = stringResource(R.string.labx_lamp_channel, i + 1)
            val stateCd = stringResource(if (on) R.string.labx_lamp_state_on else R.string.labx_lamp_state_off)
            QuietButton(
                text = labelText,
                onClick = {
                    val next = (0..2).map { ch ->
                        val cur = (shown.getOrNull(ch) ?: 0) != 0
                        val v = if (ch == i) !cur else cur
                        if (v) 100 else 0
                    }
                    vm.lab.setLampChannels(next[0], next[1], next[2])
                },
                enabled = ready && read && !busy,
                color = accent,
                modifier = Modifier.semantics {
                    role = Role.Switch
                    contentDescription = channelCd
                    stateDescription = stateCd
                },
            )
        }
    }
    if (lab.lampsOverridden) {
        Spacer(Modifier.height(S3.s))
        WorkingQuietButton(
            text = stringResource(R.string.bikelab_lamps_restore),
            busyText = stringResource(R.string.bikelab_working),
            busy = busy,
            onClick = { vm.lab.restoreLamps() },
            enabled = ready && !busy,
            color = c.lamp,
        )
    }
    InlineLabError(lab, LabOp.LAMP, Modifier.padding(top = S3.xs))
    Hint(stringResource(R.string.bikelab_lamps_hint), Modifier.padding(top = S3.xs))
}

// --- Parked-only actions (assist-cut) ------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParkedActions(vm: AppViewModel, lab: LabState, ready: Boolean, parked: Boolean) {
    val c = S3.colors
    val enabled = ready && parked
    BikeSubTitle(stringResource(R.string.bikelab_actions_title))
    Spacer(Modifier.height(S3.s))

    FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        WorkingQuietButton(
            text = stringResource(R.string.bikelab_sleep),
            busyText = stringResource(R.string.bikelab_working),
            busy = LabOp.SLEEP in lab.busy,
            onClick = { vm.lab.sleepNow() },
            enabled = enabled && LabOp.SLEEP !in lab.busy,
        )
        WorkingQuietButton(
            text = stringResource(R.string.bikelab_alarm_stop),
            busyText = stringResource(R.string.bikelab_working),
            busy = LabOp.ALARM_STOP in lab.busy,
            onClick = { vm.lab.stopAlarm() },
            enabled = enabled && LabOp.ALARM_STOP !in lab.busy,
        )
    }
    InlineLabError(lab, LabOp.ALARM_STOP, Modifier.padding(top = S3.xs))
    InlineLabError(lab, LabOp.SLEEP, Modifier.padding(top = S3.xs))
    Hint(stringResource(R.string.labx_alarm_stop_note), Modifier.padding(top = S3.xs))
    ReLock(vm, lab, enabled)

    Spacer(Modifier.height(S3.m))
    SelfTest(vm, lab, enabled)

    if (ready && !parked) LampNote(stringResource(R.string.labx_error_assist_cut), Modifier.padding(top = S3.m))
}

/** Offered only after an alarm stop cleared the soft-lock on a bike that was locked: a one-tap re-lock. */
@Composable
private fun ReLock(vm: AppViewModel, lab: LabState, enabled: Boolean) {
    if (!lab.relockSuggested) return
    Spacer(Modifier.height(S3.s))
    WorkingQuietButton(
        text = stringResource(R.string.labx_relock_button),
        busyText = stringResource(R.string.bikelab_working),
        busy = LabOp.RELOCK in lab.busy,
        onClick = { vm.lab.reLock() },
        enabled = enabled && LabOp.RELOCK !in lab.busy,
        color = S3.colors.signal,
    )
    InlineLabError(lab, LabOp.RELOCK, Modifier.padding(top = S3.xs))
    Hint(stringResource(R.string.labx_relock_hint), Modifier.padding(top = S3.xs))
}

/**
 * The self-test (55C1 = 3) archives the bike log first, because its report pushes the oldest lines out
 * of the ring buffer. The guard is not a bare Boolean: the download must have reached the last block
 * ([AppViewModel.BikeLog.complete]) and the file must be on disk ([BikeLogArchive.holds]) before the
 * test is sent; a partial download or an unsaved file does not arm it.
 */
@Composable
private fun SelfTest(vm: AppViewModel, lab: LabState, enabled: Boolean) {
    val c = S3.colors
    val context = LocalContext.current
    val archive = remember(context) { BikeLogArchive(File(context.filesDir, "logs")) }
    val bikeLog by vm.bikeLog.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf(false) }
    var armedAt by rememberSaveable { mutableLongStateOf(0L) }
    val archiving = armedAt != 0L
    val busy = LabOp.SELFTEST in lab.busy

    // Once a fresh, complete, saved log download lands, run the test; otherwise report why not.
    LaunchedEffect(bikeLog, armedAt) {
        val log = bikeLog
        if (armedAt != 0L && log != null && log.atMillis >= armedAt) {
            armedAt = 0L
            val text = log.text
            val saved = !text.isNullOrEmpty() &&
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { archive.holds(text, log.atMillis) }
            if (!log.failed && log.complete && saved) vm.lab.selfTest(archivedLog = true)
        }
    }

    WorkingQuietButton(
        text = stringResource(R.string.bikelab_selftest),
        busyText = stringResource(if (archiving) R.string.bikelab_selftest_archiving else R.string.bikelab_working),
        busy = busy || archiving,
        onClick = { ask = true },
        enabled = enabled && !busy && !archiving,
    )
    InlineLabError(lab, LabOp.SELFTEST, Modifier.padding(top = S3.xs))
    Hint(stringResource(R.string.bikelab_selftest_hint), Modifier.padding(top = S3.xs))

    OutburstBlockWhile(ask)
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(stringResource(R.string.bikelab_selftest_dialog_title), style = S3.type.section, color = c.paint) },
            text = { Text(stringResource(R.string.labx_selftest_dialog_text), style = S3.type.body, color = c.paint) },
            confirmButton = {
                TextButton({
                    ask = false
                    armedAt = System.currentTimeMillis()
                    vm.clearBikeLog()
                    vm.readBikeLog()
                }) { Text(stringResource(R.string.bikelab_selftest_dialog_confirm), style = S3.type.bodyStrong, color = c.lamp) }
            },
            dismissButton = {
                TextButton({ ask = false }) {
                    Text(stringResource(R.string.bikelab_cancel), style = S3.type.bodyStrong, color = c.paint)
                }
            },
        )
    }
}

// --- Sirene -------------------------------------------------------------------------------------

/** The text of one siren stage, for its confirm dialog. */
@Composable
private fun sirenStageText(stage: Int): String = stringResource(
    when (stage) {
        2 -> R.string.labx_siren_stage2
        3 -> R.string.labx_siren_stage3
        else -> R.string.labx_siren_stage4
    },
)

/**
 * The bike's own siren, parked-only because it cuts assist. Each stage asks first, with what it does; while one
 * runs, the stages give way to one wide "Alarm aus" in signal, which is the way back from every stage.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SirenBlock(vm: AppViewModel, lab: LabState, parked: Boolean) {
    val c = S3.colors
    val haptics = rememberHaptics()
    var ask by remember { mutableStateOf<Int?>(null) }
    var stopping by remember { mutableStateOf(false) }
    val sirenBusy = LabOp.SIREN in lab.busy || LabOp.ALARM_STOP in lab.busy
    val running = (lab.sirenStage ?: 0) >= 2

    // The stop went through: the stage is back to none. A refused stop is felt too.
    LaunchedEffect(lab.sirenStage, lab.error, lab.errorOp) {
        if (!stopping) return@LaunchedEffect
        if (lab.errorOp == LabOp.ALARM_STOP && lab.error != null) {
            haptics.reject()
            stopping = false
        } else if ((lab.sirenStage ?: 0) < 2) {
            haptics.confirm()
            stopping = false
        }
    }

    BikeSubTitle(stringResource(R.string.bikelab_siren_title))
    Spacer(Modifier.height(S3.s))
    if (running) {
        Text(stringResource(R.string.bikelab_siren_active, lab.sirenStage ?: 0), style = S3.type.label, color = c.signal)
        Spacer(Modifier.height(S3.s))
        WorkingBigButton(
            text = stringResource(R.string.bikelab_siren_stop),
            busyText = stringResource(R.string.bikelab_working),
            busy = LabOp.ALARM_STOP in lab.busy,
            onClick = {
                stopping = true
                vm.lab.stopAlarm()
            },
            enabled = parked && LabOp.ALARM_STOP !in lab.busy,
            color = c.signal,
            onColor = c.onSignal,
        )
        InlineLabError(lab, LabOp.ALARM_STOP, Modifier.padding(top = S3.xs))
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
            for (stage in 2..4) {
                StagePill(stringResource(R.string.bikelab_siren_stage, stage), { ask = stage }, enabled = parked && !sirenBusy)
            }
            StagePill(stringResource(R.string.bikelab_siren_stop), { vm.lab.stopAlarm() }, enabled = parked && !sirenBusy)
        }
    }
    InlineLabError(lab, LabOp.SIREN, Modifier.padding(top = S3.xs))
    Hint(stringResource(R.string.labx_siren_note), Modifier.padding(top = S3.s), color = c.paint)
    ReLock(vm, lab, parked)
    if (!parked) LampNote(stringResource(R.string.labx_error_assist_cut), Modifier.padding(top = S3.s))

    OutburstBlockWhile(ask != null)
    ask?.let { stage ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.bikelab_siren_dialog_title, stage), style = S3.type.section, color = c.paint) },
            text = { Text(sirenStageText(stage), style = S3.type.body, color = c.paint) },
            confirmButton = {
                TextButton({
                    ask = null
                    vm.lab.triggerSiren(stage)
                }) { Text(stringResource(R.string.bikelab_siren_dialog_confirm), style = S3.type.bodyStrong, color = c.signal) }
            },
            dismissButton = {
                TextButton({ ask = null }) { Text(stringResource(R.string.bikelab_cancel), style = S3.type.bodyStrong, color = c.paint) }
            },
        )
    }
}

/** A siren pill: a rib outline and paint text. The siren is a deliberate act, not an alarm already on. */
@Composable
private fun StagePill(text: String, onClick: () -> Unit, enabled: Boolean) {
    val c = S3.colors
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .border(1.5.dp, c.rib, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = S3.type.bodyStrong, color = if (enabled) c.paint else c.fog)
    }
}

// --- Alarm stop in the ride controls -------------------------------------------------------------

/**
 * The "stop the running alarm" button for RideScreen's alarm section. It appears only while the bike's
 * alarm is actually going off and the bike is parked (5523 = 0 cuts assist, so standstill only). It also
 * clears the soft-lock, and when the bike was locked it offers a one-tap re-lock right here.
 */
@Composable
fun AlarmStopRow(vm: AppViewModel, state: BikeState, modifier: Modifier = Modifier) {
    val lab by vm.labState.collectAsStateWithLifecycle()
    val parked = remember(state) { LabProtocol.isParked(state) }
    val c = S3.colors
    Column(modifier.fillMaxWidth()) {
        WorkingQuietButton(
            text = stringResource(R.string.bikelab_alarm_stop),
            busyText = stringResource(R.string.bikelab_working),
            busy = LabOp.ALARM_STOP in lab.busy,
            onClick = { vm.lab.stopAlarm() },
            enabled = parked && LabOp.ALARM_STOP !in lab.busy,
            color = c.signal,
        )
        InlineLabError(lab, LabOp.ALARM_STOP, Modifier.padding(top = S3.xs))
        Hint(stringResource(R.string.labx_alarm_stop_note), Modifier.padding(top = S3.xs))
        ReLock(vm, lab, parked)
    }
}

// --- Small shared bits ---------------------------------------------------------------------------

/** A quieter heading inside the Bike-Lab, one step below [BikeSectionTitle] (section). Marked as a heading. */
@Composable
private fun BikeSubTitle(text: String) {
    Text(
        text,
        style = S3.type.bodyStrong,
        color = S3.colors.paint,
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).semantics { heading() },
    )
}

/** Label/value row like [ValueRow], but the value colour can carry meaning (e.g. a hot temperature). */
@Composable
private fun LabValueRow(label: String, value: String, valueColor: Color? = null) {
    val c = S3.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.Top,
        ) {
            Text(label, style = S3.type.body, color = c.fog, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(S3.m))
            Text(
                value,
                style = S3.type.data,
                color = valueColor ?: if (value == DASH) c.fog else c.paint,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Hairline()
    }
}
