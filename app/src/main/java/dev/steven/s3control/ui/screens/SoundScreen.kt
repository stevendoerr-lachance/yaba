package dev.steven.s3control.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.BellJob
import dev.steven.s3control.R
import dev.steven.s3control.audio.BellConverter
import dev.steven.s3control.audio.BellSlotRecord
import dev.steven.s3control.audio.BundledBell
import dev.steven.s3control.ble.BellTone
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ble.SOUNDS
import dev.steven.s3control.ble.Sound
import dev.steven.s3control.ble.SoundGroup
import dev.steven.s3control.ui.FamilyGate
import dev.steven.s3control.ui.components.BellPreview
import dev.steven.s3control.ui.components.BellToneTile
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.CommandFeedback
import dev.steven.s3control.ui.components.ConnectNotice
import dev.steven.s3control.ui.components.Fact
import dev.steven.s3control.ui.components.Hairline
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.KeptSound
import dev.steven.s3control.ui.components.MatrixDisplay
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.ScreenTitle
import dev.steven.s3control.ui.components.SectionTitle
import dev.steven.s3control.ui.components.SlotRow
import dev.steven.s3control.ui.components.SoundGroupHeader
import dev.steven.s3control.ui.components.SoundLab
import dev.steven.s3control.ui.components.SoundPad
import dev.steven.s3control.ui.components.SoundSourceChooser
import dev.steven.s3control.ui.components.TileNameFit
import dev.steven.s3control.ui.components.TilePadX
import dev.steven.s3control.ui.components.UnderlinedAction
import dev.steven.s3control.ui.components.UploadMeter
import dev.steven.s3control.ui.components.appLocale
import dev.steven.s3control.ui.components.fitTileName
import dev.steven.s3control.ui.components.padLabel
import dev.steven.s3control.ui.components.rememberBellPreview
import dev.steven.s3control.ui.components.rememberClipSending
import dev.steven.s3control.ui.components.rememberCommandFeedback
import dev.steven.s3control.ui.components.waveformFrame
import dev.steven.s3control.ui.theme.S3
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a command was started, so its error shows next to that control. */
private object BellArea
private object CustomArea

/**
 * Klang in three blocks: the bell (four tones and the one slot of your own), every sound the bike can play,
 * and the Klang-Labor (volume per sound, the replaceable sounds in the bike).
 */
@Composable
fun SoundScreen(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val job by vm.bell.collectAsStateWithLifecycle()
    val slot by vm.bellSlot.collectAsStateWithLifecycle()
    // Before the first connect the stored bike's family decides what this page offers (an S2: one sentence, no
    // controls); values, link and busy come from the live state.
    val vault by vm.vault.collectAsStateWithLifecycle()
    val s2TestMode by vm.bike.s2Prefs.testMode.collectAsStateWithLifecycle()
    val preferred = remember(vault) { vm.preferredBike() }
    val gate = FamilyGate.beforeConnect(state, preferred, s2TestMode)
    val feedback = rememberCommandFeedback(vm.bike)
    val uploading = state.upload != null || Op.UPLOAD in state.busy
    // A Sound-Lab clip on its way: the bike takes one transfer at a time, so the bell waits.
    val clipSending = rememberClipSending(vm)
    val bellSending = uploading && !clipSending
    val gutter = if (wide) S3.l else S3.gutter
    // One preview on the phone for the whole screen: a bell started in one chooser stops the one before.
    val preview = rememberBellPreview()

    // A one-minute BLE transfer should not die because the screen went dark.
    KeepScreenOn(uploading)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, top = S3.l, bottom = S3.xl),
    ) {
        // On the inner screen the tab row names the page.
        if (!wide) ScreenTitle(stringResource(R.string.sound_title))
        var above = !wide
        // Nothing here that this bike can play (an S2 has no sound command): one sentence, no controls.
        if (!FamilyGate.anySound(gate)) {
            if (above) Spacer(Modifier.height(S3.l))
            Text(
                stringResource(if (gate.family == BikeFamily.S2) R.string.s2ui_sound_none else R.string.core_error_not_on_this_bike),
                style = S3.type.body,
                color = S3.colors.paint,
            )
            return@Column
        }
        if (!state.isReady) {
            if (above) Spacer(Modifier.height(S3.l))
            // Beside the hero (inner screen) one grey line: the hero already says what blocks the link and owns the action.
            ConnectNotice(vm, state, stringResource(R.string.sound_connect_needed), heroBeside = wide)
            above = true
        }
        if (FamilyGate.bellTones(gate) || FamilyGate.customBell(gate)) {
            if (above) Spacer(Modifier.height(S3.xl))
            SectionTitle(stringResource(R.string.sound_bell_title))
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.sound_bell_hint))
            if (FamilyGate.bellTones(gate)) {
                Spacer(Modifier.height(S3.m))
                BellTiles(vm, state, slot, uploading || clipSending, customUploaded = job is BellJob.Uploaded, feedback)
            }
            if (FamilyGate.customBell(gate)) {
                Spacer(Modifier.height(S3.l))
                CustomBell(vm, state, job, slot, wide, uploading, bellSending, clipSending, feedback, preview)
            }
            above = true
        }
        if (FamilyGate.soundboard(gate)) {
            if (above) Spacer(Modifier.height(S3.xl))
            SoundBoardSection(vm, state, uploading || clipSending, feedback)
            above = true
        }
        if (FamilyGate.soundLab(gate)) {
            if (above) Spacer(Modifier.height(S3.xl))
            SoundLab(vm, state, wide, bellUploading = bellSending, preview = preview)
        }
    }
}

// --- Klingelton ------------------------------------------------------------------------------

/** Smallest size a fixed tone name may take on a tile before the tiles go two across. */
private const val MIN_TILE_SP = 16f

/** The tile name of [tone]: the slot's own sound stands in for "Nebelhorn" once YABA knows what it holds. */
private fun toneName(tone: BellTone, slot: BellSlotRecord?): String =
    if (tone == BellTone.FOGHORN && slot != null) slot.name else tone.label

/**
 * The tile emoji of [tone] (the owner asked for these, keep them): a bundled bell in the slot brings its own;
 * a file of your own keeps the slot's, as the bike still plays it as "Nebelhorn".
 */
internal fun toneEmoji(tone: BellTone, slot: BellSlotRecord?): String {
    if (tone != BellTone.FOGHORN || slot?.bundledId == null) return tone.emoji
    return BundledBell.entries.firstOrNull { it.name == slot.bundledId }?.emoji ?: tone.emoji
}

@Composable
private fun BellTiles(
    vm: AppViewModel,
    state: BikeState,
    slot: BellSlotRecord?,
    uploading: Boolean,
    customUploaded: Boolean,
    feedback: CommandFeedback,
) {
    val c = S3.colors
    val scope = rememberCoroutineScope()
    val setting = Op.BELL in state.busy
    val enabled = state.isReady && !uploading
    var pendingTone by remember { mutableStateOf<BellTone?>(null) }
    val current = state.bell
    val customCaption = when {
        slot != null -> stringResource(R.string.sound_tone_custom_record, BellTone.FOGHORN.label)
        customUploaded -> stringResource(R.string.sound_tone_custom)
        else -> stringResource(R.string.sound_tone_custom_option)
    }
    val canPlay = FamilyGate.soundboard(state)
    val playOnce: (BellTone) -> Unit = { tone ->
        if (canPlay) {
            feedback.run(BellArea, Op.SOUND) { vm.bike.play(Sound(tone.code, toneName(tone, slot), SoundGroup.HORN)) }
        }
    }
    // A tap sets the tone and, once the bike took it, plays it there once. The active tone just plays.
    val choose: (BellTone) -> Unit = { tone ->
        if (current == tone) {
            playOnce(tone)
        } else {
            pendingTone = tone
            feedback.run(BellArea, Op.BELL) { vm.bike.setBell(tone) }
            scope.launch {
                var seenBusy = false
                val end = withTimeoutOrNull(8_000) {
                    vm.bikeState.first { s ->
                        if (Op.BELL in s.busy) {
                            seenBusy = true
                            false
                        } else {
                            seenBusy || s.lastCommandError != null || s.bell == tone
                        }
                    }
                } ?: return@launch
                if (end.isReady && end.lastCommandError == null && end.bell == tone) playOnce(tone)
            }
        }
    }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val titleStyle = S3.type.title
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val names = BellTone.entries.map { toneName(it, slot) }
        val custom = slot != null
        val fixedNames = BellTone.entries.filter { !(custom && it == BellTone.FOGHORN) }.map { it.label }
        // The emoji stands on its own line above the name: the name keeps the tile's whole inner width.
        fun roomPx(n: Int) = with(density) { ((maxWidth - gap * (n - 1)) / n - TilePadX * 2).roundToPx() }
        // Four across from 360 dp, but only while every tone name still reads at 16 sp or more in the
        // expanded cut (a cover screen is read standing); otherwise two across at full size.
        val across = remember(fixedNames, maxWidth, titleStyle, measurer, density) {
            val four = maxWidth >= 360.dp && fixedNames.all { name ->
                fitTileName(name, titleStyle, roomPx(4), measurer).style.fontSize.value >= MIN_TILE_SP
            }
            if (four) 4 else 2
        }
        val textPx = roomPx(across)
        val fits = remember(names, custom, textPx, titleStyle, measurer) {
            val each = names.map { fitTileName(it, titleStyle, textPx, measurer, minSize = 14.sp) }
            // The fixed names share one size; a name of your own takes its own, never a larger one.
            val fixed = BellTone.entries.indices.filter { !(custom && BellTone.entries[it] == BellTone.FOGHORN) }
            val shared = fixed.map { each[it] }.minBy { it.style.fontSize.value }
            val sharedLines = fixed.maxOf { each[it].maxLines }
            BellTone.entries.mapIndexed { i, tone ->
                val own = each[i]
                when {
                    custom && tone == BellTone.FOGHORN && own.style.fontSize.value < shared.style.fontSize.value -> own
                    custom && tone == BellTone.FOGHORN -> TileNameFit(shared.style, own.maxLines)
                    else -> TileNameFit(shared.style, sharedLines)
                }
            }
        }
        EvenTileGrid(columns = across, gap = gap, modifier = Modifier.fillMaxWidth().selectableGroup()) {
            BellTone.entries.forEachIndexed { i, tone ->
                BellToneTile(
                    name = names[i],
                    emoji = toneEmoji(tone, slot),
                    caption = if (tone == BellTone.FOGHORN) customCaption else null,
                    selected = current == tone,
                    busy = setting && pendingTone == tone,
                    enabled = enabled && !setting,
                    onClick = { choose(tone) },
                    nameFit = fits[i],
                )
            }
        }
    }

    val note = when {
        setting -> pendingTone?.let { stringResource(R.string.sound_bell_switching, toneName(it, slot)) }
        state.isReady && current == null -> stringResource(R.string.sound_bell_unknown)
        state.isReady && current != null -> stringResource(R.string.sound_bell_plays, toneName(current, slot))
        else -> null
    }
    if (note != null) {
        Spacer(Modifier.height(S3.m))
        Hint(note, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    feedback.errorFor(BellArea, state.lastCommandError)?.let {
        Spacer(Modifier.height(S3.s))
        Hint(it, color = c.signal)
    }
}

/**
 * Tiles [columns] across, each as tall as the tallest of them: the slot tile's caption or a name on two lines
 * makes the whole grid taller, not one row of a 2 x 2 grid. Children get fixed constraints.
 */
@Composable
private fun EvenTileGrid(columns: Int, gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val inner = (width - gapPx * (columns - 1)).coerceAtLeast(0)
        // Column edges from the whole width, so the pixels left over by the division are spread, not lost.
        fun left(col: Int) = inner * col / columns + gapPx * col
        fun tileWidth(col: Int) = inner * (col + 1) / columns - inner * col / columns
        val tileHeight = measurables.withIndex().maxOfOrNull { (i, m) -> m.minIntrinsicHeight(tileWidth(i % columns)) } ?: 0
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(tileWidth(i % columns), tileHeight)) }
        val rows = (placeables.size + columns - 1) / columns
        layout(width, rows * tileHeight + (rows - 1).coerceAtLeast(0) * gapPx) {
            placeables.forEachIndexed { i, p -> p.placeRelative(left(i % columns), (i / columns) * (tileHeight + gapPx)) }
        }
    }
}

// --- Eigener Ton -----------------------------------------------------------------------------

/**
 * The one slot of your own (the bike plays it as "Nebelhorn"): a row that says what it holds, and under it
 * the chooser or how far the new sound got (converted, sending, done, failed). Choosing works without the bike.
 */
@Composable
private fun CustomBell(
    vm: AppViewModel,
    state: BikeState,
    job: BellJob,
    slot: BellSlotRecord?,
    wide: Boolean,
    /** Any transfer on the link (the bell's or a clip's). */
    uploading: Boolean,
    /** The bell's own transfer is running. */
    sending: Boolean,
    /** A Sound-Lab clip is on its way: the bell is not sent meanwhile. */
    sendBlocked: Boolean,
    feedback: CommandFeedback,
    preview: BellPreview,
) {
    val c = S3.colors
    val context = LocalContext.current
    val locale = appLocale()
    val kept by vm.keptBells.collectAsStateWithLifecycle()
    val guardOn = vm.guardState.collectAsStateWithLifecycle().value.active
    var chooserOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(job) { if (job !is BellJob.Idle) chooserOpen = false }
    val fallbackName = stringResource(R.string.sound_audio_file)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) vm.convertBell(uri, displayName(context, uri, fallbackName))
    }
    val pick: () -> Unit = { runCatching { picker.launch("audio/*") } }
    val canChoose = !uploading && job !is BellJob.Converting
    val foghorn = BellTone.FOGHORN.label
    val ownName = stringResource(R.string.sound_custom_sound_name)
    val openChooser: () -> Unit = {
        if (job !is BellJob.Idle) vm.resetBell()
        chooserOpen = true
    }
    val maxSeconds = BellConverter.MAX_SECONDS.toInt()

    Hairline()
    SlotRow(
        name = ownName,
        caption = slot?.let { stringResource(R.string.soundlab_clip_custom, it.name, dateText(it.sentAt, locale)) }
            ?: stringResource(R.string.sound_custom_row_none, foghorn),
        onPlay = {
            feedback.run(CustomArea, Op.SOUND) {
                vm.bike.play(Sound(BellTone.FOGHORN.code, slot?.name ?: ownName, SoundGroup.HORN))
            }
        },
        playEnabled = state.isReady && !uploading && FamilyGate.soundboard(state),
    ) {
        when {
            sending || job is BellJob.Converting -> {}
            chooserOpen -> UnderlinedAction(stringResource(R.string.soundlab_clip_cancel), onClick = {
                preview.stop()
                chooserOpen = false
            })
            job is BellJob.Ready || job is BellJob.Failed ->
                UnderlinedAction(stringResource(R.string.soundlab_clip_cancel), onClick = { vm.resetBell() })
            else -> UnderlinedAction(
                stringResource(R.string.soundlab_clip_replace),
                onClick = openChooser,
                enabled = canChoose,
                description = stringResource(R.string.sound_custom_replace_cd, foghorn),
            )
        }
    }
    // One red line at a time: a failed transfer says so itself.
    if (job !is BellJob.Failed) {
        feedback.errorFor(CustomArea, state.lastCommandError)?.let {
            Hint(it, color = c.signal)
            Spacer(Modifier.height(S3.s))
        }
    }

    val body: @Composable () -> Unit = {
        if (sending) {
            val name = (job as? BellJob.Ready)?.name ?: ownName
            val fraction = state.upload ?: 0f
            // On the inner screen the hero tube carries the transfer; the pane keeps the line and its percent.
            if (!wide) {
                UploadMeter(fraction = fraction, done = false)
                Spacer(Modifier.height(S3.m))
            }
            Text(
                if (wide) {
                    stringResource(R.string.sound_custom_sending_percent, name, (fraction * 100).toInt().coerceIn(0, 99))
                } else {
                    stringResource(R.string.sound_custom_sending_named, name)
                },
                style = S3.type.bodyStrong,
                color = c.paint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Hint(stringResource(R.string.sound_custom_keep_close))
        } else if (chooserOpen && job is BellJob.Idle) {
            SoundSourceChooser(
                preview = preview,
                slotName = foghorn,
                enabled = canChoose,
                onPick = pick,
                onBundled = { bell, name -> vm.convertBell(bell.uri(context), name, bell.keepLevel, bell.name) },
                note = pluralStringResource(R.plurals.sound_custom_intro, maxSeconds, maxSeconds),
                kept = kept.filter { it.id != slot?.keptId }.map { k ->
                    KeptSound(
                        id = k.id,
                        name = k.name,
                        detail = stringResource(R.string.soundlab_clip_history_line, dateText(k.sentAt, locale)),
                        wave = k.wave?.let(::MatrixFrame),
                    )
                },
                onKept = { vm.chooseKeptBell(it.id) },
                onBike = slot?.bundledId?.let { id -> BundledBell.entries.firstOrNull { it.name == id } },
            )
        } else {
            when (job) {
                BellJob.Idle -> Unit

                BellJob.Converting -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.led, trackColor = c.rib)
                    Spacer(Modifier.height(S3.s))
                    Text(stringResource(R.string.sound_custom_converting), style = S3.type.label, color = c.fog)
                }

                is BellJob.Ready -> {
                    BellFile(job)
                    Spacer(Modifier.height(S3.l))
                    BigButton(
                        stringResource(R.string.sound_custom_send),
                        onClick = { feedback.run(CustomArea, Op.UPLOAD) { vm.uploadBell() } },
                        enabled = state.isReady && !sendBlocked && !state.moving && !guardOn,
                    )
                    // While the Wache is active the controller refuses the upload (it pauses polling for minutes).
                    if (guardOn) Hint(stringResource(R.string.guard_core_error_active), Modifier.padding(top = S3.s)) else SendReason(state, sendBlocked)
                    Spacer(Modifier.height(S3.s))
                    QuietButton(stringResource(R.string.sound_custom_other_file), onClick = openChooser, modifier = Modifier.fillMaxWidth())
                }

                BellJob.Uploaded -> {
                    if (!wide) {
                        UploadMeter(fraction = 1f, done = true)
                        Spacer(Modifier.height(S3.m))
                    }
                    Text(stringResource(R.string.sound_custom_done), style = S3.type.bodyStrong, color = c.paint)
                    Spacer(Modifier.height(S3.l))
                    QuietButton(stringResource(R.string.soundlab_clip_finish), onClick = { vm.resetBell() }, modifier = Modifier.fillMaxWidth())
                }

                is BellJob.Failed -> {
                    val ready = job.ready
                    if (ready != null) {
                        BellFile(ready)
                        Spacer(Modifier.height(S3.m))
                        val sent = job.sentFraction
                        Text(
                            if (sent != null) {
                                stringResource(R.string.sound_custom_failed_at, (sent * 100).toInt().coerceIn(1, 99), foghorn)
                            } else {
                                stringResource(R.string.sound_custom_failed_before, foghorn)
                            },
                            style = S3.type.bodyStrong,
                            color = c.signal,
                        )
                        Spacer(Modifier.height(S3.m))
                        BigButton(
                            stringResource(R.string.sound_custom_resend),
                            onClick = { feedback.run(CustomArea, Op.UPLOAD) { vm.retryBell() } },
                            enabled = state.isReady && !sendBlocked && !state.moving,
                        )
                        SendReason(state, sendBlocked)
                        Spacer(Modifier.height(S3.s))
                    } else {
                        Text(job.message, style = S3.type.bodyStrong, color = c.signal)
                        Spacer(Modifier.height(S3.m))
                    }
                    QuietButton(stringResource(R.string.sound_custom_other_file), onClick = openChooser, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    val hasBody = sending || (chooserOpen && job is BellJob.Idle) || job !is BellJob.Idle
    if (hasBody) {
        Spacer(Modifier.height(S3.s))
        body()
        Spacer(Modifier.height(S3.l))
    }
    Hairline()
}

/** Why sending waits, in one line under the button; nothing when it may go. */
@Composable
private fun SendReason(state: BikeState, sendBlocked: Boolean) {
    val reason = when {
        !state.isReady -> R.string.sound_custom_connect_first
        state.moving -> R.string.sound_send_standstill
        sendBlocked -> R.string.sound_custom_wait_transfer
        else -> null
    } ?: return
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(reason))
}

/** The converted file: its waveform (fog, a picture and not a sound), name and facts. */
@Composable
private fun BellFile(job: BellJob.Ready) {
    val c = S3.colors
    val result = job.result
    val wave = remember(result) { waveformFrame(result.file, 36) }
    val locale = appLocale()

    MatrixDisplay(
        frame = wave,
        columns = 36,
        lit = c.fog,
        glow = Color.Transparent,
        description = stringResource(R.string.sound_custom_waveform, job.name),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(S3.l))
    Text(job.name, style = S3.type.bodyStrong, color = c.paint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (result.trimmed) {
        Text(
            stringResource(R.string.sound_custom_trimmed, BellConverter.MAX_SECONDS.toInt()),
            style = S3.type.label,
            color = c.lamp,
        )
    }
    Spacer(Modifier.height(S3.m))
    Row(horizontalArrangement = Arrangement.spacedBy(S3.l)) {
        Fact(
            stringResource(R.string.sound_fact_length),
            stringResource(R.string.sound_value_seconds, String.format(locale, "%.1f", result.seconds)),
        )
        if (result.sampleRate > 0) {
            Fact(
                stringResource(R.string.sound_fact_sample_rate),
                stringResource(R.string.sound_value_khz, khzNumber(result.sampleRate, locale)),
            )
        }
        Fact(
            stringResource(R.string.sound_fact_size),
            stringResource(R.string.sound_value_kb, (result.file.size + 500) / 1000),
        )
    }
}

/** The picked file's name without its extension ("ufo.mp3" is "ufo"), as the clip slots name theirs. */
private fun displayName(context: Context, uri: Uri, fallback: String): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()
    return name?.takeIf { it.isNotBlank() }?.substringBeforeLast('.')?.ifBlank { null }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: fallback
}

private fun khzNumber(rate: Int, locale: Locale): String =
    DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(rate / 1000.0)

private fun dateText(millis: Long, locale: Locale): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))

// --- Alle Töne am Bike -----------------------------------------------------------------------

/** SoundPad's horizontal padding (12 dp each side, Controls.kt): the room its label really gets. */
private val PadTextInset = 24.dp
private val PadGap = 8.dp
private const val PAD_MAX_COLUMNS = 4

/** Every sound the bike plays, in groups that fold; only "Klingeln und Hupen" stands open at first. */
@Composable
private fun SoundBoardSection(
    vm: AppViewModel,
    state: BikeState,
    uploading: Boolean,
    feedback: CommandFeedback,
) {
    val c = S3.colors
    val enabled = state.isReady && !uploading
    val measurer = rememberTextMeasurer()
    val labelStyle = S3.type.label
    val density = LocalDensity.current

    SectionTitle(stringResource(R.string.sound_board_title))
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(if (uploading) R.string.sound_board_quiet else R.string.sound_board_hint))
    Spacer(Modifier.height(S3.m))

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowPx = constraints.maxWidth
        val gapPx = with(density) { PadGap.roundToPx() }
        val insetPx = with(density) { PadTextInset.roundToPx() }
        Column {
            Hairline()
            SoundGroup.entries.forEach { group ->
                key(group) {
                    val sounds = SOUNDS.filter { it.group == group }
                    if (sounds.isNotEmpty()) {
                        var open by rememberSaveable { mutableStateOf(group == SoundGroup.HORN) }
                        SoundGroupHeader(
                            title = group.label,
                            summary = pluralStringResource(R.plurals.soundlab_group_sounds, sounds.size, sounds.size),
                            expanded = open,
                            onToggle = { open = !open },
                        )
                        if (open) {
                            // As SoundPad sets them (padLabel): fewer pads per row rather than a label cut
                            // mid-word or ellipsized (long German words at 320 dp with large font scale).
                            val labels = sounds.map { padLabel(it.label) }
                            val columns = remember(labels, rowPx, gapPx, insetPx, measurer, labelStyle) {
                                padColumns(labels, PAD_MAX_COLUMNS, rowPx, gapPx, insetPx, measurer, labelStyle)
                            }
                            Column(Modifier.fillMaxWidth().padding(top = S3.xs, bottom = S3.l)) {
                                if (group == SoundGroup.ALARM) {
                                    Hint(stringResource(R.string.sound_board_alarm_warning), color = c.lamp)
                                    Spacer(Modifier.height(S3.s))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(PadGap)) {
                                    sounds.chunked(columns).forEach { row ->
                                        Row(
                                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                            horizontalArrangement = Arrangement.spacedBy(PadGap),
                                        ) {
                                            row.forEach { sound ->
                                                SoundPad(
                                                    label = sound.label,
                                                    emoji = sound.emoji,
                                                    onClick = { feedback.run(group, Op.SOUND) { vm.bike.play(sound) } },
                                                    enabled = enabled,
                                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                                )
                                            }
                                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                                feedback.errorFor(group, state.lastCommandError)?.let {
                                    Spacer(Modifier.height(S3.s))
                                    Hint(it, color = c.signal)
                                }
                            }
                        }
                        Hairline()
                    }
                }
            }
        }
    }
}

/**
 * Most pads per row (up to [maxColumns]) at which every label shows whole in a pad: at most SoundPad's two lines, and
 * every line ends where a line may end (after a space or after a hyphen, which [padLabel] made breakable), never
 * inside a word. Checked on the real layout, not on guessed pieces: the line breaker decides where it breaks.
 * Falls back to one column.
 */
private fun padColumns(
    labels: List<String>,
    maxColumns: Int,
    rowPx: Int,
    gapPx: Int,
    insetPx: Int,
    measurer: TextMeasurer,
    style: TextStyle,
): Int {
    if (rowPx == Constraints.Infinity) return 2
    for (n in maxColumns downTo 2) {
        val textPx = (rowPx - gapPx * (n - 1)) / n - insetPx
        if (textPx <= 0) continue
        val whole = labels.all { label ->
            val layout = measurer.measure(label, style, constraints = Constraints(maxWidth = textPx))
            layout.lineCount <= 2 && (0 until layout.lineCount - 1).all { line -> endsCleanly(label, layout.getLineEnd(line)) }
        }
        if (whole) return n
    }
    return 1
}

/** Whether a line of [text] that ends at [end] (exclusive) ends between words: after a space, a hyphen or a ZWSP. */
internal fun endsCleanly(text: String, end: Int): Boolean {
    if (end <= 0 || end >= text.length) return true
    val last = text[end - 1]
    return last.isWhitespace() || last == '-' || last == '\u200B'
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        if (!on) return@DisposableEffect onDispose { }
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}
