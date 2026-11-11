package dev.steven.s3control.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.audio.BellConvertException
import dev.steven.s3control.audio.BellConverter
import dev.steven.s3control.audio.BundledBell
import dev.steven.s3control.audio.ClipSlot
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.LabOp
import dev.steven.s3control.ble.LabState
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ble.SOUNDS
import dev.steven.s3control.ble.Sound
import dev.steven.s3control.ble.SoundGroup
import dev.steven.s3control.ble.SoundTiers
import dev.steven.s3control.ui.theme.S3
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Sound-Lab on the Klang screen (the firmware notes 2.1 and 2.2):
 *  - volume per sound: the three firmware tiers (20/30/38) or silent, read from 5572, edited as a
 *    draft and written in one go on Save (the write rewrites two flash sectors);
 *  - five replaceable clips (lock, unlock, power on, power off, charging) over the bell's OAD path.
 *
 * Technical guards only: tiers are read before they can be edited (read-modify-write) and written on
 * Save, never per tap; nothing is muted for the owner or blocked. Clips go only to the five slots
 * [ClipSlot] allows (filetype 0, the firmware slot, is not among them), never during another transfer,
 * and every file is kept on the phone before it goes out, because the bike cannot give a sound back.
 */

// --- Entry ----------------------------------------------------------------------------------------

/**
 * The Klang-Labor block: volume per sound, then the sounds in the bike that can be replaced. [bellUploading]: the
 * custom bell is on its way, so the clips and the tier save wait (the bike takes one OAD transfer at a time).
 * [preview]: the screen's one phone preview, so a bell in a clip chooser stops the one playing elsewhere.
 */
@Composable
fun SoundLab(
    vm: AppViewModel,
    state: BikeState,
    wide: Boolean,
    bellUploading: Boolean,
    modifier: Modifier = Modifier,
    preview: BellPreview? = null,
) {
    val lab by vm.labState.collectAsStateWithLifecycle()
    val clipSending = rememberClipSending(vm)
    val ownPreview = preview ?: rememberBellPreview()
    KeepScreenOnWhile(clipSending)
    Column(modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.sound_lab_title))
        Spacer(Modifier.height(S3.l))
        SoundTierSection(vm, state, lab, transferring = bellUploading || clipSending)
        Spacer(Modifier.height(S3.xl))
        ClipSection(vm, state, wide, bellUploading, ownPreview)
    }
}

/** True while a clip goes to the bike: the bell upload and the sound board stay quiet meanwhile. */
@Composable
fun rememberClipSending(vm: AppViewModel): Boolean {
    val lab by vm.labState.collectAsStateWithLifecycle()
    val job by ClipJobs.job.collectAsStateWithLifecycle()
    return job is ClipJob.Sending || LabOp.CLIP in lab.busy
}

// --- Volume per sound (5572) ----------------------------------------------------------------------

/** The four steps of a ladder, quietest first. */
private val TierSteps = listOf(
    SoundTiers.Tier.SILENT, SoundTiers.Tier.LOW, SoundTiers.Tier.MEDIUM, SoundTiers.Tier.HIGH,
)
private const val TOP_LEVEL = 38f
private val LadderStep = 48.dp
private val LadderGap = 4.dp
private val LadderWidth = LadderStep * 4 + LadderGap * 3
private val LadderBarMax = 28.dp
/** Narrowest room a sound name gets beside its ladder before the ladder goes underneath. */
private val NameMinWidth = 140.dp

private const val ID_NEGATIVE = 0x02
private const val ID_FIND_MY = 0x05
private const val ID_ALARM_ONE = 0x0E
private const val ID_ALARM_TWO = 0x0F
private const val ID_FIND_LOUD = 0x1A

private val TiersSaver = Saver<SoundTiers?, LongArray>(
    save = { tiers -> tiers?.let { longArrayOf(it.low, it.medium, it.high) } },
    restore = { SoundTiers(it[0], it[1], it[2]) },
)

private sealed interface TierOutcome {
    data object Saved : TierOutcome
    data object NotTaken : TierOutcome
    data class Failed(val message: String) : TierOutcome
}

/** Parts of a sound name a line may not break inside: words, compounds split after their hyphens. */
private fun namePieces(label: String): List<String> =
    label.split(Regex("\\s+")).flatMap { word ->
        val parts = word.split('-')
        parts.mapIndexed { i, p -> if (i < parts.lastIndex) "$p-" else p }
    }.filter { it.isNotEmpty() }

/** The name with a break opportunity after each hyphen, so "Entsperr-Countdown" wraps at the hyphen. */
private fun breakableName(label: String): String = label.replace("-", "-​")

/** Ids whose tier differs between [a] and [b], over all 32 bits (unnamed ids included). */
private fun changedIds(a: SoundTiers, b: SoundTiers): Int = (0 until 32).count { a.tierOf(it) != b.tierOf(it) }

@Composable
private fun SoundTierSection(vm: AppViewModel, state: BikeState, lab: LabState, transferring: Boolean) {
    val c = S3.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val read = lab.soundTiers

    // The draft the ladders edit, and the bike value it was taken from. A new read replaces the draft
    // only while the owner has not changed anything; unsaved edits survive a re-read and a fold.
    var draft by rememberSaveable(stateSaver = TiersSaver) { mutableStateOf<SoundTiers?>(null) }
    var base by rememberSaveable(stateSaver = TiersSaver) { mutableStateOf<SoundTiers?>(null) }
    var outcome by remember { mutableStateOf<TierOutcome?>(null) }
    var readNote by remember { mutableStateOf<String?>(null) }
    // Open groups by enum name, comma-separated (a plain string survives the saved state as is).
    var openGroups by rememberSaveable { mutableStateOf("") }
    val expanded = openGroups.split(',').filter { it.isNotEmpty() }.toSet()

    LaunchedEffect(read) {
        if (read == null) return@LaunchedEffect
        if (draft == null || draft == base) draft = read
        base = read
    }
    // Read once per connection, as soon as the bike is there and nothing has been read yet.
    LaunchedEffect(state.isReady) {
        val now = vm.labState.value
        if (state.isReady && now.soundTiers == null && LabOp.READ !in now.busy && vm.bike.featureSession != null) {
            vm.lab.readConfig()
        }
    }

    val reading = LabOp.READ in lab.busy
    val saving = LabOp.SOUND in lab.busy
    // Without a read there is nothing to modify: the ladders then show the factory table, locked.
    val shown = if (read != null) draft ?: read else SoundTiers.DEFAULT_1_9_1
    val editable = read != null && !saving
    val changes = if (read != null) changedIds(shown, read) else 0
    val noTiers = stringResource(R.string.soundlab_tiers_none)

    val readNow: () -> Unit = {
        readNote = null
        scope.launch {
            vm.lab.clearError()
            vm.lab.readConfig()
            val started = withTimeoutOrNull(3_000) { vm.labState.first { LabOp.READ in it.busy || it.error != null } }
            val end = if (started != null) vm.labState.first { LabOp.READ !in it.busy } else vm.labState.value
            readNote = end.error ?: noTiers.takeIf { end.soundTiers == null }
        }
    }
    val save: () -> Unit = save@{
        val target = draft ?: return@save
        outcome = null
        haptics.confirm()
        scope.launch {
            vm.lab.clearError()
            vm.lab.setSoundTiers(target)
            val started = withTimeoutOrNull(3_000) { vm.labState.first { LabOp.SOUND in it.busy || it.error != null } }
            val end = if (started != null) vm.labState.first { LabOp.SOUND !in it.busy } else vm.labState.value
            val error = end.error
            outcome = when {
                error != null -> TierOutcome.Failed(error)
                end.soundTiers == target -> TierOutcome.Saved
                else -> TierOutcome.NotTaken
            }
        }
    }

    SoundSubTitle(stringResource(R.string.soundlab_tiers_title))
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.soundlab_tiers_hint))

    if (read == null) {
        Spacer(Modifier.height(S3.m))
        Text(stringResource(R.string.soundlab_tiers_unread), style = S3.type.body, color = c.paint)
        Spacer(Modifier.height(S3.m))
        QuietButton(
            stringResource(if (reading) R.string.soundlab_tiers_reading else R.string.soundlab_tiers_read),
            onClick = readNow,
            enabled = state.isReady && !reading,
        )
        if (!state.isReady) {
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.soundlab_tiers_offline))
        }
        readNote?.takeIf { !reading }?.let {
            Spacer(Modifier.height(S3.s))
            Hint(it, color = c.signal)
        }
    }

    Spacer(Modifier.height(S3.m))
    val activeBell = state.bell?.code
    val measurer = rememberTextMeasurer()
    val nameStyle = S3.type.bodyStrong
    val density = LocalDensity.current
    val labels = SOUNDS.map { it.label }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Ladders go under the names when any name has a word that would not fit beside them whole
        // (long German compounds on the cover screen, large font scale): one layout for every row.
        val room = maxWidth - LadderWidth - S3.m
        val stacked = remember(labels, room, nameStyle, density, measurer) {
            val roomPx = with(density) { room.roundToPx() }
            room < NameMinWidth || labels.flatMap(::namePieces).distinct().any { piece ->
                measurer.measure(piece, nameStyle, softWrap = false, maxLines = 1).size.width > roomPx
            }
        }
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.rib))
            SoundGroup.entries.forEach { group ->
                val sounds = SOUNDS.filter { it.group == group }
                if (sounds.isNotEmpty()) {
                    key(group) {
                        TierGroup(
                            group = group,
                            sounds = sounds,
                            shown = shown,
                            bike = read,
                            activeBell = activeBell,
                            expanded = group.name in expanded,
                            onToggle = {
                                val next = if (group.name in expanded) expanded - group.name else expanded + group.name
                                openGroups = next.joinToString(",")
                            },
                            stacked = stacked,
                            enabled = editable,
                            onSelect = { id, tier ->
                                draft = shown.withTier(id, tier)
                                outcome = null
                            },
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rib))
                    }
                }
            }
        }
    }

    if (read != null) {
        Spacer(Modifier.height(S3.l))
        if (changes > 0) {
            BigButton(
                if (saving) {
                    stringResource(R.string.soundlab_tiers_saving)
                } else {
                    pluralStringResource(R.plurals.soundlab_tiers_save, changes, changes)
                },
                onClick = save,
                enabled = state.isReady && !saving && !transferring && !state.moving,
            )
            Spacer(Modifier.height(S3.s))
            QuietButton(
                stringResource(R.string.soundlab_tiers_discard),
                onClick = {
                    draft = read
                    outcome = null
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(S3.s))
        }
        if (shown != SoundTiers.DEFAULT_1_9_1) {
            QuietButton(
                stringResource(R.string.soundlab_tiers_factory),
                onClick = {
                    draft = SoundTiers.DEFAULT_1_9_1
                    outcome = null
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(S3.s))
        }
        Hint(stringResource(R.string.soundlab_tiers_flash))
        if (changes > 0 && state.isReady && state.moving) Hint(stringResource(R.string.soundlab_tiers_standstill))
        if (changes > 0 && transferring) Hint(stringResource(R.string.soundlab_tiers_wait), color = c.lamp)
        if (!state.isReady) Hint(stringResource(R.string.soundlab_tiers_offline))
        when (val o = outcome) {
            TierOutcome.Saved -> Hint(stringResource(R.string.soundlab_tiers_saved), color = c.led)
            TierOutcome.NotTaken -> Hint(stringResource(R.string.soundlab_tiers_not_taken), color = c.lamp)
            is TierOutcome.Failed -> Hint(o.message, color = c.signal)
            null -> {}
        }
    }
}

/** One sound group: a header that tells how its sounds stand, and the ladders when opened. */
@Composable
private fun TierGroup(
    group: SoundGroup,
    sounds: List<Sound>,
    shown: SoundTiers,
    bike: SoundTiers?,
    activeBell: Int?,
    expanded: Boolean,
    onToggle: () -> Unit,
    stacked: Boolean,
    enabled: Boolean,
    onSelect: (Int, SoundTiers.Tier) -> Unit,
) {
    val c = S3.colors
    val muted = sounds.count { shown.tierOf(it.id) == SoundTiers.Tier.SILENT }
    val changed = if (bike == null) 0 else sounds.count { shown.tierOf(it.id) != bike.tierOf(it.id) }
    val countText = pluralStringResource(R.plurals.soundlab_group_sounds, sounds.size, sounds.size)
    val mutedText = pluralStringResource(R.plurals.soundlab_group_muted, muted, muted)
    val changedText = pluralStringResource(R.plurals.soundlab_group_changed, changed, changed)
    val summary = listOfNotNull(countText, mutedText.takeIf { muted > 0 }, changedText.takeIf { changed > 0 })
        .joinToString(", ")

    SoundGroupHeader(
        title = group.label,
        summary = summary,
        expanded = expanded,
        onToggle = onToggle,
        summaryColor = if (changed > 0) c.led else c.fog,
    )
    if (expanded) {
        Column(
            Modifier.fillMaxWidth().padding(top = S3.xs, bottom = S3.l),
            verticalArrangement = Arrangement.spacedBy(S3.m),
        ) {
            sounds.forEach { sound ->
                key(sound.id) {
                    val tier = shown.tierOf(sound.id)
                    TierRow(
                        sound = sound,
                        tier = tier,
                        was = bike?.tierOf(sound.id),
                        note = if (tier == SoundTiers.Tier.SILENT) muteNote(sound.id, activeBell) else null,
                        stacked = stacked,
                        enabled = enabled,
                        onSelect = { onSelect(sound.id, it) },
                    )
                }
            }
        }
    }
}

/** What going silent costs, for the few sounds where that is not obvious. Information, not a gate. */
@Composable
private fun muteNote(id: Int, activeBell: Int?): String? = when (id) {
    activeBell -> stringResource(R.string.soundlab_note_bell)
    ID_ALARM_ONE, ID_ALARM_TWO -> stringResource(R.string.soundlab_note_alarm)
    ID_FIND_LOUD, ID_FIND_MY -> stringResource(R.string.soundlab_note_find)
    ID_NEGATIVE -> stringResource(R.string.soundlab_note_error)
    else -> null
}

@Composable
private fun tierName(tier: SoundTiers.Tier): String = stringResource(
    when (tier) {
        SoundTiers.Tier.SILENT -> R.string.soundlab_tier_silent
        SoundTiers.Tier.LOW -> R.string.soundlab_tier_low
        SoundTiers.Tier.MEDIUM -> R.string.soundlab_tier_medium
        SoundTiers.Tier.HIGH -> R.string.soundlab_tier_high
    },
)

/** "Mittel, Pegel 30", or just "Stumm". */
@Composable
private fun tierLine(tier: SoundTiers.Tier): String {
    val name = tierName(tier)
    return if (tier == SoundTiers.Tier.SILENT) name else stringResource(R.string.soundlab_tier_level, name, tier.resolvedLevel)
}

@Composable
private fun TierRow(
    sound: Sound,
    tier: SoundTiers.Tier,
    was: SoundTiers.Tier?,
    note: String?,
    stacked: Boolean,
    enabled: Boolean,
    onSelect: (SoundTiers.Tier) -> Unit,
) {
    val c = S3.colors
    val moved = was != null && was != tier
    val caption = if (moved) {
        stringResource(R.string.soundlab_tier_changed, tierName(tier), tierName(was!!))
    } else {
        tierLine(tier)
    }
    val name: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(
                breakableName(sound.label),
                style = S3.type.bodyStrong,
                color = c.paint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(caption, style = S3.type.caption, color = if (moved) c.led else c.fog)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (stacked) {
            name(Modifier.fillMaxWidth())
            Spacer(Modifier.height(S3.xs))
            TierLadder(sound.label, tier, enabled, onSelect, Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                name(Modifier.weight(1f).padding(end = S3.m))
                TierLadder(sound.label, tier, enabled, onSelect, Modifier.width(LadderWidth))
            }
        }
        if (note != null) {
            Spacer(Modifier.height(S3.xs))
            Hint(note, color = c.lamp)
        }
    }
}

/**
 * The four volume steps as a rising ladder, each bar as tall as its firmware level (0, 20, 30, 38),
 * lit up to the chosen one like [PowerLadder]. Locked (but still showing the tier) when [enabled] is false.
 */
@Composable
private fun TierLadder(
    soundName: String,
    tier: SoundTiers.Tier,
    enabled: Boolean,
    onSelect: (SoundTiers.Tier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val offLabel = stringResource(R.string.soundlab_tier_off)
    Row(
        modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(LadderGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        TierSteps.forEach { step ->
            val selected = step == tier
            val lit = step != SoundTiers.Tier.SILENT && step.resolvedLevel <= tier.resolvedLevel
            val flatOn = selected && step == SoundTiers.Tier.SILENT
            val fill by animateColorAsState(
                when {
                    !enabled && (lit || flatOn) -> c.fog.copy(alpha = 0.55f)
                    !enabled -> c.rib.copy(alpha = 0.5f)
                    lit -> c.led
                    flatOn -> c.lamp
                    else -> c.rib
                },
                label = "tierStep",
            )
            val stepText = tierLine(step)
            val description = stringResource(R.string.soundlab_tier_step_cd, soundName, stepText)
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                        haptics.tick()
                        onSelect(step)
                    }
                    // The step reads as one item ("Sperren: Mittel, Pegel 30"), not as its digits.
                    .clearAndSetSemantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    if (step == SoundTiers.Tier.SILENT) offLabel else step.resolvedLevel.toString(),
                    style = if (selected) S3.type.data else S3.type.label,
                    color = when {
                        selected && enabled && flatOn -> c.lamp
                        selected && enabled -> c.paint
                        else -> c.fog
                    },
                    maxLines = 1,
                )
                val barHeight = if (step == SoundTiers.Tier.SILENT) 4.dp else LadderBarMax * (step.resolvedLevel / TOP_LEVEL)
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(fill),
                )
            }
        }
    }
}

// --- Replaceable clips (OAD filetype 0x0D..0x13) ----------------------------------------------------

/** A clip in the bike's format (VM_SOUND, canonical WAV), ready to go into a slot. */
private class Clip(val bytes: ByteArray, val name: String, val seconds: Float, val sampleRate: Int, val trimmed: Boolean) {
    val wave: MatrixFrame by lazy { waveformFrame(bytes, 36) }
}

private enum class FailReason { READ, STORE, UPLOAD }

/** Where the one clip job stands. Process-wide, so a transfer outlives a trip to another screen. */
private sealed interface ClipJob {
    val slot: ClipSlot?

    data object Idle : ClipJob {
        override val slot: ClipSlot? get() = null
    }

    class Choosing(override val slot: ClipSlot) : ClipJob
    class Converting(override val slot: ClipSlot, val name: String) : ClipJob
    class Ready(override val slot: ClipSlot, val clip: Clip) : ClipJob

    /** [touched]: the bike took the metadata, so the slot is erased until the file arrives in full. */
    class Sending(override val slot: ClipSlot, val clip: Clip, val progress: Float, val touched: Boolean) : ClipJob
    class Sent(override val slot: ClipSlot, val clip: Clip) : ClipJob

    /** [progress] is set only when the slot was already erased; [detail] is the raw cause. */
    class Failed(
        override val slot: ClipSlot,
        val clip: Clip?,
        val reason: FailReason,
        val progress: Float?,
        val detail: String?,
    ) : ClipJob
}

private object ClipJobs {
    val job = MutableStateFlow<ClipJob>(ClipJob.Idle)
    /** Bumped whenever the local archive changes, so the slot list reloads. */
    val revision = MutableStateFlow(0)
}

/** One file YABA sent (or was about to send) to a slot, kept on the phone. */
private class ArchiveEntry(
    val id: String,
    val name: String,
    val seconds: Float,
    /** Last complete transfer, 0 if it never arrived in full. */
    val sentAt: Long,
    val file: File,
    val wave: MatrixFrame?,
)

/** What YABA knows about a slot: the file it holds (null = factory sound), every kept file, erased or not. */
private class SlotRecord(val current: ArchiveEntry?, val history: List<ArchiveEntry>, val empty: Boolean)

/**
 * The phone-side copy of every clip YABA sends: `files/soundlab/<slot>/<crc>.vms` plus a properties
 * file with its name, and `slot.properties` with what the slot holds. A file is written here before
 * the transfer starts; the bike cannot read a slot back, so this is the only way back to it.
 */
private class ClipArchive(context: Context) {
    private val root = File(context.filesDir, "soundlab")

    private fun dir(slot: ClipSlot) = File(root, slot.name.lowercase(Locale.ROOT))
    private fun slotFile(slot: ClipSlot) = File(dir(slot), "slot.properties")

    fun load(): Map<ClipSlot, SlotRecord> = ClipSlot.entries.associateWith { slot ->
        val d = dir(slot)
        val entries = d.listFiles { f -> f.name.endsWith(".vms") }.orEmpty().map { f ->
            val id = f.nameWithoutExtension
            val meta = readProps(File(d, "$id.properties"))
            ArchiveEntry(
                id = id,
                name = meta.getProperty("name") ?: id,
                seconds = meta.getProperty("seconds")?.toFloatOrNull() ?: 0f,
                sentAt = meta.getProperty("sentAt")?.toLongOrNull() ?: 0L,
                file = f,
                wave = runCatching { waveformFrame(f.readBytes(), BELL_WAVE_COLUMNS) }.getOrNull(),
            )
        }.sortedWith(compareByDescending<ArchiveEntry> { it.sentAt }.thenByDescending { it.file.lastModified() })
        val st = readProps(slotFile(slot))
        // A pending mark left behind means the app died mid-transfer: the slot is erased.
        val pending = !st.getProperty("pending").isNullOrEmpty()
        val current = st.getProperty("current")?.let { id -> entries.firstOrNull { it.id == id } }
        SlotRecord(
            current = if (pending) null else current,
            history = entries,
            empty = pending || st.getProperty("empty") == "true",
        )
    }

    /** Keeps [clip] on the phone and marks the slot as being written. Returns the file's id. */
    fun keep(slot: ClipSlot, clip: Clip): String {
        val d = dir(slot)
        if (!d.isDirectory && !d.mkdirs()) error("cannot create ${d.path}")
        val id = String.format(Locale.ROOT, "%08x", CRC32().apply { update(clip.bytes) }.value)
        val file = File(d, "$id.vms")
        if (!file.isFile || file.length() != clip.bytes.size.toLong()) writeAtomically(file, clip.bytes)
        val metaFile = File(d, "$id.properties")
        val meta = readProps(metaFile)
        meta.setProperty("name", clip.name)
        meta.setProperty("seconds", clip.seconds.toString())
        writeProps(metaFile, meta)
        val st = readProps(slotFile(slot))
        st.setProperty("pending", id)
        writeProps(slotFile(slot), st)
        return id
    }

    /** After a transfer: [ok] makes [id] the slot's file; a failure after the erase leaves the slot empty. */
    fun finish(slot: ClipSlot, id: String, ok: Boolean, touched: Boolean) {
        val st = readProps(slotFile(slot))
        st.remove("pending")
        if (ok) {
            st.setProperty("current", id)
            st.setProperty("empty", "false")
            val metaFile = File(dir(slot), "$id.properties")
            val meta = readProps(metaFile)
            meta.setProperty("sentAt", System.currentTimeMillis().toString())
            writeProps(metaFile, meta)
        } else if (touched) {
            st.remove("current")
            st.setProperty("empty", "true")
        }
        writeProps(slotFile(slot), st)
    }

    private fun readProps(f: File): Properties = Properties().apply {
        if (f.isFile) runCatching { f.inputStream().use { load(it) } }
    }

    private fun writeProps(f: File, p: Properties) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) error("cannot write ${f.path}")
        }
    }

    private fun writeAtomically(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) error("cannot write ${f.path}")
        }
    }
}

private class ClipLoadException(message: String) : Exception(message)

/**
 * A picked file as a clip. A file that already is a VM_SOUND clip (a factory file, say) goes to the
 * bike unchanged; anything else takes the bell's conversion.
 */
private suspend fun loadPicked(context: Context, uri: Uri, name: String): Clip {
    val raw = withContext(Dispatchers.IO) { readVmSound(context, uri) }
    if (raw != null) return vmClip(context, raw, name)
    val result = BellConverter.convert(context, uri)
    return Clip(result.file, name, result.seconds, result.sampleRate, result.trimmed)
}

private suspend fun loadBundled(context: Context, bell: BundledBell, name: String): Clip {
    val result = BellConverter.convert(context, bell.uri(context))
    return Clip(result.file, name, result.seconds, result.sampleRate, result.trimmed)
}

private suspend fun loadKept(context: Context, entry: ArchiveEntry): Clip {
    val bytes = withContext(Dispatchers.IO) { entry.file.readBytes() }
    return vmClip(context, bytes, entry.name)
}

/** The whole file if it starts with the VM_SOUND magic, else null (read stops past the slot size). */
private fun readVmSound(context: Context, uri: Uri): ByteArray? {
    val input = context.contentResolver.openInputStream(uri) ?: throw ClipLoadException(context.getString(R.string.soundlab_clip_read_failed))
    return input.use { s ->
        val magic = ByteArray(8)
        var n = 0
        while (n < magic.size) {
            val r = s.read(magic, n, magic.size - n)
            if (r < 0) break
            n += r
        }
        if (n < magic.size || String(magic, Charsets.US_ASCII) != "VM_SOUND") return@use null
        val out = ByteArrayOutputStream()
        out.write(magic)
        val buf = ByteArray(16 * 1024)
        while (out.size() <= ClipSlot.MAX_CLIP_BYTES) {
            val r = s.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
        }
        out.toByteArray()
    }
}

/** Checks a VM_SOUND file the way the bike will (magic, WAV, size) and reads its length from the header. */
private fun vmClip(context: Context, bytes: ByteArray, name: String): Clip {
    if (bytes.size > ClipSlot.MAX_CLIP_BYTES) {
        throw ClipLoadException(context.getString(R.string.soundlab_clip_too_big, ClipSlot.MAX_CLIP_BYTES / 1000))
    }
    if (!ClipSlot.looksLikeVmSound(bytes)) throw ClipLoadException(context.getString(R.string.soundlab_clip_not_wav))
    // WAV header starts at byte 28: sample rate at +24, byte rate at +28, data length at +40.
    val rate = le32(bytes, 52)
    val byteRate = le32(bytes, 56)
    val dataLen = if (bytes.size >= 72) le32(bytes, 68) else 0
    val seconds = if (byteRate > 0) dataLen.toFloat() / byteRate else 0f
    return Clip(bytes, name, seconds, rate, trimmed = false)
}

private fun le32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

/**
 * Keeps the clip on the phone, then sends it. The transfer runs in the lab's scope; only keeping the
 * file is tied to the screen, and the job is reset to Ready if the screen goes before that is done.
 * A file that cannot be kept is not sent.
 */
private fun startUpload(vm: AppViewModel, archive: ClipArchive, ready: ClipJob.Ready, scope: CoroutineScope) {
    val slot = ready.slot
    val clip = ready.clip
    ClipJobs.job.value = ClipJob.Sending(slot, clip, 0f, touched = false)
    scope.launch {
        val kept = try {
            withContext(Dispatchers.IO) { archive.keep(slot, clip) }
        } catch (e: CancellationException) {
            ClipJobs.job.value = ready
            throw e
        } catch (e: Exception) {
            null
        }
        if (kept == null) {
            ClipJobs.job.value = ClipJob.Failed(slot, clip, FailReason.STORE, null, null)
            return@launch
        }
        ClipJobs.revision.update { it + 1 }
        vm.lab.uploadClip(
            slot,
            clip.bytes,
            onProgress = { p ->
                // The first progress report comes right after the bike took the metadata.
                ClipJobs.job.update { j -> if (j is ClipJob.Sending && j.slot == slot) ClipJob.Sending(slot, clip, p, true) else j }
            },
            onDone = { ok ->
                val last = ClipJobs.job.value as? ClipJob.Sending
                val touched = last?.touched == true
                runCatching { archive.finish(slot, kept, ok, touched) }
                ClipJobs.revision.update { it + 1 }
                ClipJobs.job.value = if (ok) {
                    ClipJob.Sent(slot, clip)
                } else {
                    ClipJob.Failed(
                        slot, clip, FailReason.UPLOAD,
                        progress = last?.progress?.takeIf { touched },
                        detail = vm.labState.value.error,
                    )
                }
            },
        )
    }
}

@Composable
private fun ClipSection(vm: AppViewModel, state: BikeState, wide: Boolean, bellUploading: Boolean, preview: BellPreview) {
    val c = S3.colors
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val job by ClipJobs.job.collectAsStateWithLifecycle()
    val revision by ClipJobs.revision.collectAsStateWithLifecycle()
    val archive = remember(app) { ClipArchive(app) }
    val records by produceState(emptyMap<ClipSlot, SlotRecord>(), archive, revision) {
        value = withContext(Dispatchers.IO) { runCatching { archive.load() }.getOrDefault(emptyMap()) }
    }
    val feedback = rememberCommandFeedback(vm.bike)
    var confirm by remember { mutableStateOf<ClipJob.Ready?>(null) }
    val fallbackName = stringResource(R.string.sound_audio_file)
    val locale = currentLabLocale()

    val load: (ClipSlot, String, suspend () -> Clip) -> Unit = { slot, name, block ->
        ClipJobs.job.value = ClipJob.Converting(slot, name)
        scope.launch {
            try {
                val clip = block()
                ClipJobs.job.value = ClipJob.Ready(slot, clip)
            } catch (e: CancellationException) {
                if (ClipJobs.job.value is ClipJob.Converting) ClipJobs.job.value = ClipJob.Choosing(slot)
                throw e
            } catch (e: Exception) {
                // Only our own and the converter's messages are written for people; anything else
                // (a decoder or provider exception) gets the plain "could not be read".
                val message = if (e is ClipLoadException || e is BellConvertException) e.message else null
                ClipJobs.job.value = ClipJob.Failed(slot, null, FailReason.READ, null, message)
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val slot = (ClipJobs.job.value as? ClipJob.Choosing)?.slot
        if (uri != null && slot != null) {
            val name = displayName(context, uri, fallbackName)
            load(slot, name) { loadPicked(app, uri, name) }
        }
    }
    val send: (ClipJob.Ready) -> Unit = { ready -> startUpload(vm, archive, ready, scope) }
    val cancelText = stringResource(R.string.soundlab_clip_cancel)
    val replaceText = stringResource(R.string.soundlab_clip_replace)

    SoundSubTitle(stringResource(R.string.soundlab_clips_title))
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.soundlab_clips_hint))
    Spacer(Modifier.height(S3.m))

    Hairline()
    ClipSlot.entries.forEach { slot ->
        key(slot) {
            val slotName = SOUNDS.firstOrNull { it.id == slot.soundId }?.label ?: slot.name
            val record = records[slot]
            val current = record?.current
            val open = job.slot == slot
            val busyElsewhere = job is ClipJob.Sending || job is ClipJob.Converting
            val play = { feedback.run(slot, Op.SOUND) { vm.bike.play(Sound(slot.soundId, slotName, SoundGroup.SIGNAL)) } }
            val close = { preview.stop(); ClipJobs.job.value = ClipJob.Idle }
            SlotRow(
                name = slotName,
                caption = when {
                    record?.empty == true -> stringResource(R.string.soundlab_clip_empty)
                    current != null -> stringResource(R.string.soundlab_clip_custom, current.name, dateText(current.sentAt, locale))
                    else -> stringResource(R.string.soundlab_clip_factory)
                },
                captionColor = if (record?.empty == true) c.signal else c.fog,
                onPlay = play,
                playEnabled = state.isReady && !bellUploading && job !is ClipJob.Sending,
            ) {
                when {
                    // A transfer cannot be called back, and a conversion lands on its own.
                    open && (job is ClipJob.Sending || job is ClipJob.Converting || job is ClipJob.Sent) -> {}
                    open -> UnderlinedAction(cancelText, onClick = close)
                    else -> UnderlinedAction(
                        replaceText,
                        onClick = { ClipJobs.job.value = ClipJob.Choosing(slot) },
                        enabled = !busyElsewhere,
                        description = stringResource(R.string.soundlab_clip_replace_cd, slotName),
                    )
                }
            }
            feedback.errorFor(slot, state.lastCommandError)?.let {
                Hint(it, color = c.signal)
                Spacer(Modifier.height(S3.s))
            }
            if (open) {
                val led = c.led
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = S3.l)
                        .drawBehind {
                            drawRoundRect(
                                color = led,
                                size = Size(3.dp.toPx(), size.height),
                                cornerRadius = CornerRadius(1.5.dp.toPx()),
                            )
                        }
                        .padding(start = S3.m),
                ) {
                    ClipEditor(
                        job = job,
                        slotName = slotName,
                        record = record,
                        state = state,
                        wide = wide,
                        bellUploading = bellUploading,
                        preview = preview,
                        onBundled = { bell, name -> load(slot, name) { loadBundled(app, bell, name) } },
                        onKept = { entry -> load(slot, entry.name) { loadKept(app, entry) } },
                        onPick = { runCatching { picker.launch(arrayOf("audio/*", "application/octet-stream")) } },
                        onSend = { ready ->
                            val factory = record == null || (record.current == null && !record.empty)
                            if (factory) confirm = ready else send(ready)
                        },
                        onRetry = { clip -> send(ClipJob.Ready(slot, clip)) },
                        onChooseAgain = { ClipJobs.job.value = ClipJob.Choosing(slot) },
                        onClose = close,
                    )
                }
            }
            Hairline()
        }
    }

    confirm?.let { ready ->
        val slotName = SOUNDS.firstOrNull { it.id == ready.slot.soundId }?.label ?: ready.slot.name
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.soundlab_clip_confirm_title), style = S3.type.section, color = c.paint) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(S3.s)) {
                    Text(stringResource(R.string.soundlab_clip_confirm_text, slotName), style = S3.type.body, color = c.paint)
                    // The factory tone cannot be recovered (the bike has no read path for clip slots, and there
                    // is no bundled copy), so say so plainly before the first replacement of a factory slot.
                    Text(stringResource(R.string.labx_clip_factory_warning), style = S3.type.body, color = c.lamp)
                }
            },
            confirmButton = {
                TextButton({
                    confirm = null
                    send(ready)
                }) { Text(stringResource(R.string.soundlab_clip_confirm_ok), style = S3.type.bodyStrong, color = c.lamp) }
            },
            dismissButton = {
                TextButton({ confirm = null }) {
                    Text(stringResource(R.string.soundlab_clip_cancel), style = S3.type.bodyStrong, color = c.paint)
                }
            },
        )
    }
}

@Composable
private fun ClipEditor(
    job: ClipJob,
    slotName: String,
    record: SlotRecord?,
    state: BikeState,
    wide: Boolean,
    bellUploading: Boolean,
    preview: BellPreview,
    onBundled: (BundledBell, String) -> Unit,
    onKept: (ArchiveEntry) -> Unit,
    onPick: () -> Unit,
    onSend: (ClipJob.Ready) -> Unit,
    onRetry: (Clip) -> Unit,
    onChooseAgain: () -> Unit,
    onClose: () -> Unit,
) {
    val c = S3.colors
    val locale = currentLabLocale()
    when (job) {
        ClipJob.Idle -> {}

        is ClipJob.Choosing -> {
            val history = record?.history.orEmpty().filter { it.id != record?.current?.id }
            val keptDetail = stringResource(R.string.soundlab_clip_history_unsent)
            val kept = history.map { entry ->
                KeptSound(
                    id = entry.id,
                    name = entry.name,
                    detail = if (entry.sentAt > 0) {
                        stringResource(R.string.soundlab_clip_history_line, dateText(entry.sentAt, locale))
                    } else {
                        keptDetail
                    },
                    wave = entry.wave,
                )
            }
            SoundSourceChooser(
                preview = preview,
                slotName = slotName,
                enabled = true,
                onPick = onPick,
                onBundled = onBundled,
                note = if (record == null || (record.current == null && !record.empty)) {
                    stringResource(R.string.soundlab_clip_factory_note)
                } else {
                    null
                },
                pickHint = stringResource(R.string.soundlab_clip_pick_hint),
                kept = kept,
                onKept = { k -> history.firstOrNull { it.id == k.id }?.let(onKept) },
            )
        }

        is ClipJob.Converting -> {
            Text(job.name, style = S3.type.bodyStrong, color = c.paint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(S3.s))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.led, trackColor = c.rib)
            Spacer(Modifier.height(S3.s))
            Text(stringResource(R.string.sound_custom_converting), style = S3.type.label, color = c.fog)
        }

        is ClipJob.Ready -> ReadyClip(
            clip = job.clip,
            slotName = slotName,
            state = state,
            bellUploading = bellUploading,
            onSend = { onSend(job) },
            onOther = onChooseAgain,
        )

        is ClipJob.Sending -> {
            val percent = (job.progress * 100).toInt().coerceIn(0, 99)
            // On the inner screen the hero tube carries the transfer; the pane keeps the line and its percent.
            if (!wide) {
                UploadMeter(fraction = job.progress, done = false)
                Spacer(Modifier.height(S3.m))
            }
            Text(
                if (wide) {
                    stringResource(R.string.soundlab_clip_sending_percent, job.clip.name, slotName, percent)
                } else {
                    stringResource(R.string.soundlab_clip_sending, job.clip.name, slotName)
                },
                style = S3.type.bodyStrong,
                color = c.paint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Hint(stringResource(R.string.soundlab_clip_keep_close))
        }

        is ClipJob.Sent -> {
            if (!wide) {
                UploadMeter(fraction = 1f, done = true)
                Spacer(Modifier.height(S3.m))
            }
            Text(stringResource(R.string.soundlab_clip_done, job.clip.name, slotName), style = S3.type.bodyStrong, color = c.paint)
            Spacer(Modifier.height(S3.l))
            QuietButton(stringResource(R.string.soundlab_clip_finish), onClick = onClose, modifier = Modifier.fillMaxWidth())
        }

        is ClipJob.Failed -> {
            val text = when (job.reason) {
                FailReason.READ -> job.detail ?: stringResource(R.string.soundlab_clip_read_failed)
                FailReason.STORE -> stringResource(R.string.soundlab_clip_store_failed)
                FailReason.UPLOAD -> {
                    val p = job.progress
                    if (p != null) {
                        stringResource(R.string.soundlab_clip_failed_at, (p * 100).toInt().coerceIn(0, 99), slotName)
                    } else {
                        stringResource(R.string.soundlab_clip_failed_before, slotName)
                    }
                }
            }
            Text(text, style = S3.type.bodyStrong, color = c.signal)
            if (job.reason == FailReason.UPLOAD) {
                job.detail?.let {
                    Spacer(Modifier.height(S3.xs))
                    Hint(it)
                }
            }
            Spacer(Modifier.height(S3.m))
            val clip = job.clip
            if (clip != null) {
                BigButton(
                    stringResource(R.string.soundlab_clip_retry),
                    onClick = { onRetry(clip) },
                    enabled = state.isReady && !bellUploading && !state.moving,
                )
                SendBlockedReason(state, bellUploading)
                Spacer(Modifier.height(S3.s))
            }
            QuietButton(stringResource(R.string.sound_custom_other_file), onClick = onChooseAgain, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Why a send waits, in one line under its button; nothing when it may go. */
@Composable
private fun SendBlockedReason(state: BikeState, bellUploading: Boolean) {
    val c = S3.colors
    val reason = when {
        !state.isReady -> R.string.sound_custom_connect_first
        state.moving -> R.string.sound_send_standstill
        bellUploading -> R.string.soundlab_clip_wait_bell
        else -> null
    } ?: return
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(reason), color = if (reason == R.string.soundlab_clip_wait_bell) c.lamp else c.fog)
}

/** The converted clip, its facts, and the one big action: send it into the slot. */
@Composable
private fun ReadyClip(
    clip: Clip,
    slotName: String,
    state: BikeState,
    bellUploading: Boolean,
    onSend: () -> Unit,
    onOther: () -> Unit,
) {
    val c = S3.colors
    val locale = currentLabLocale()
    // A picture of the file, not something sounding: fog, so the screen keeps one lit matrix.
    MatrixDisplay(
        frame = clip.wave,
        columns = 36,
        lit = c.fog,
        glow = Color.Transparent,
        description = stringResource(R.string.sound_custom_waveform, clip.name),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(S3.l))
    Text(clip.name, style = S3.type.bodyStrong, color = c.paint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (clip.trimmed) {
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
            stringResource(R.string.sound_value_seconds, String.format(locale, "%.1f", clip.seconds)),
        )
        if (clip.sampleRate > 0) {
            Fact(
                stringResource(R.string.sound_fact_sample_rate),
                stringResource(
                    R.string.sound_value_khz,
                    DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(clip.sampleRate / 1000.0),
                ),
            )
        }
        Fact(
            stringResource(R.string.sound_fact_size),
            stringResource(R.string.sound_value_kb, (clip.bytes.size + 500) / 1000),
        )
    }
    Spacer(Modifier.height(S3.l))
    BigButton(
        stringResource(R.string.soundlab_clip_send, slotName),
        onClick = onSend,
        enabled = state.isReady && !bellUploading && !state.moving,
    )
    if (state.isReady && !state.moving && !bellUploading) {
        Spacer(Modifier.height(S3.s))
        Hint(stringResource(R.string.soundlab_clip_keep_close))
    } else {
        SendBlockedReason(state, bellUploading)
    }
    Spacer(Modifier.height(S3.m))
    QuietButton(stringResource(R.string.sound_custom_other_file), onClick = onOther, modifier = Modifier.fillMaxWidth())
}

// --- Small helpers --------------------------------------------------------------------------------

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

private fun dateText(millis: Long, locale: Locale): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))

@Composable
private fun currentLabLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** A transfer of several seconds should not die because the screen went dark. */
@Composable
private fun KeepScreenOnWhile(on: Boolean) {
    val view: View = LocalView.current
    DisposableEffect(view, on) {
        if (!on) return@DisposableEffect onDispose { }
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}
