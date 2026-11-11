package dev.steven.s3control.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.steven.s3control.R
import dev.steven.s3control.audio.BundledBell
import dev.steven.s3control.ui.theme.S3

/*
 * What the Klang screen shares between its blocks: the one chooser for a new sound (the custom bell's "Eigener Ton"
 * and every clip slot's "Ersetzen"), the row a slot is shown in, the group header of the sound board and the
 * volume ladders, and the quiet underlined action that sits at a row's end.
 */

// --- Small parts ----------------------------------------------------------------------------------

/**
 * A row's one action as underlined text, 48 dp to touch: "Ersetzen", "Wählen", "Abbrechen". Never competes with a
 * BigButton; a button for TalkBack, with [description] naming what it acts on when the text alone does not.
 */
@Composable
internal fun UnderlinedAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = S3.colors.fog,
    description: String? = null,
) {
    val c = S3.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = S3.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = S3.type.label,
            color = if (enabled) color else c.rib,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
        )
    }
}

/** A heading inside a block ("Lautstärke pro Ton", "Töne im Bike"): the expanded cut, one step below SectionTitle. */
@Composable
internal fun SoundSubTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = S3.type.section.copy(fontSize = 17.sp, lineHeight = 22.sp),
        color = S3.colors.paint,
        modifier = modifier.semantics { heading() },
    )
}

/** A plain disclosure chevron; [turn] in degrees (0 = closed, 180 = open). */
@Composable
internal fun Chevron(turn: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer { rotationZ = turn }) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.40f)
            lineTo(w * 0.50f, h * 0.62f)
            lineTo(w * 0.72f, h * 0.40f)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The header of a group that folds (sound board groups, volume ladders): name, one line on how its sounds stand,
 * a chevron. The whole row toggles; TalkBack hears "ausgeklappt" or "eingeklappt".
 */
@Composable
internal fun SoundGroupHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summaryColor: Color = S3.colors.fog,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val reduceMotion = rememberReducedMotion()
    val stateText = stringResource(if (expanded) R.string.soundlab_state_expanded else R.string.soundlab_state_collapsed)
    val turn by animateFloatAsState(
        if (expanded) 180f else 0f,
        if (reduceMotion) snap() else tween(200),
        label = "groupChevron",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) { haptics.tick(); onToggle() }
            .semantics { stateDescription = stateText }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = S3.type.bodyStrong, color = c.paint)
            Text(summary, style = S3.type.caption, color = summaryColor)
        }
        Chevron(turn, c.fog, Modifier.size(24.dp))
    }
}

/**
 * One sound slot of the bike (the custom bell, a replaceable clip): its name, what it holds, and one action at the
 * end. Tapping the name plays the slot at the bike.
 */
@Composable
internal fun SlotRow(
    name: String,
    caption: String,
    onPlay: () -> Unit,
    playEnabled: Boolean,
    modifier: Modifier = Modifier,
    captionColor: Color = S3.colors.fog,
    trailing: @Composable () -> Unit = {},
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val playLabel = stringResource(R.string.soundlab_clip_play_action)
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clickable(enabled = playEnabled, role = Role.Button, onClickLabel = playLabel) { haptics.tick(); onPlay() }
                .padding(vertical = 10.dp),
        ) {
            Text(name, style = S3.type.bodyStrong, color = c.paint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(caption, style = S3.type.caption, color = captionColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

// --- The chooser ----------------------------------------------------------------------------------

/** A file YABA kept on this phone, offered again by the chooser. */
internal class KeptSound(val id: String, val name: String, val detail: String, val wave: MatrixFrame?)

/**
 * Where a new sound for [slotName] comes from, in this order: a file of your own, files YABA kept on this phone,
 * the bells that ship with the app. Works without the bike: choosing only converts; sending is a step of its own.
 * Every bundled bell plays on the phone with a tap ([preview], one at a time for the whole screen). [onBike] is
 * the bundled bell the slot holds now, told on its row. Cancelling lives in the slot's own row, not here.
 */
@Composable
internal fun SoundSourceChooser(
    preview: BellPreview,
    slotName: String,
    enabled: Boolean,
    onPick: () -> Unit,
    onBundled: (BundledBell, String) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    pickHint: String? = null,
    kept: List<KeptSound> = emptyList(),
    onKept: (KeptSound) -> Unit = {},
    onBike: BundledBell? = null,
) {
    val c = S3.colors
    val facts = rememberBundledBellFacts()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()

    Column(modifier.fillMaxWidth()) {
        if (note != null) {
            Hint(note)
            Spacer(Modifier.height(S3.m))
        }
        QuietButton(
            stringResource(R.string.sound_custom_pick),
            onClick = onPick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (pickHint != null) {
            Spacer(Modifier.height(S3.xs))
            Hint(pickHint)
        }

        if (kept.isNotEmpty()) {
            Spacer(Modifier.height(S3.l))
            Text(stringResource(R.string.soundlab_clip_history), style = S3.type.label, color = c.fog)
            Spacer(Modifier.height(S3.xs))
            Hairline()
            kept.forEach { entry ->
                key(entry.id) {
                    KeptSoundRow(
                        entry = entry,
                        enabled = enabled,
                        chooseDescription = stringResource(R.string.soundlab_clip_choose_cd, entry.name, slotName),
                        onChoose = { preview.stop(); onKept(entry) },
                    )
                    Hairline()
                }
            }
        }

        Spacer(Modifier.height(S3.l))
        Text(stringResource(R.string.soundlab_clip_bundled), style = S3.type.label, color = c.fog)
        Hint(stringResource(R.string.bells_hint))
        // A live region that is always there, so TalkBack reads a hint the moment it appears: a silent
        // preview should not go unexplained.
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            if (preview.muted) Hint(stringResource(R.string.bells_muted), color = c.lamp)
            if (preview.failed) Hint(stringResource(R.string.bells_preview_failed), color = c.signal)
        }
        Spacer(Modifier.height(S3.s))
        Hairline()
        BundledBell.entries.forEach { bell ->
            key(bell) {
                val name = stringResource(bell.nameRes)
                val here = onBike == bell
                BundledBellRow(
                    name = name,
                    emoji = bell.emoji,
                    facts = facts[bell],
                    playing = preview.playing == bell,
                    positionMs = preview::positionMs,
                    onBike = here,
                    chooseEnabled = enabled,
                    onPreview = { preview.toggle(bell) },
                    onChoose = if (here) null else ({ preview.stop(); onBundled(bell, name) }),
                    chooseDescription = stringResource(R.string.soundlab_clip_choose_cd, name, slotName),
                    locale = locale,
                )
                Hairline()
            }
        }
    }
}

/** A kept file: name, its waveform in fog and when it was sent; "Wählen" at the end. */
@Composable
private fun KeptSoundRow(entry: KeptSound, enabled: Boolean, chooseDescription: String, onChoose: () -> Unit) {
    val c = S3.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
                .clearAndSetSemantics { contentDescription = "${entry.name}, ${entry.detail}" },
        ) {
            Text(entry.name, style = S3.type.bodyStrong, color = c.paint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.wave?.let { wave ->
                    MatrixDisplay(
                        frame = wave,
                        columns = wave.columns.size,
                        lit = c.fog,
                        glow = Color.Transparent,
                        modifier = Modifier.width(96.dp),
                    )
                    Spacer(Modifier.width(S3.s))
                }
                Text(entry.detail, style = S3.type.label, color = c.fog, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        UnderlinedAction(
            stringResource(R.string.bells_choose),
            onClick = onChoose,
            enabled = enabled,
            description = chooseDescription,
        )
    }
}
