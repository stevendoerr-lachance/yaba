package dev.steven.s3control.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.steven.s3control.R
import dev.steven.s3control.ui.theme.S3
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Section heading. Expanded black type; the only decoration a section gets. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(text, style = S3.type.section, color = S3.colors.paint, modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) trailing()
    }
}

/** Plain explanatory line under a control. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = S3.colors.fog) {
    Text(text, style = S3.type.caption, color = color, modifier = modifier)
}

/**
 * Assist levels as a rising ladder. Level 0 is the flat step on the left.
 */
@Composable
fun PowerLadder(
    level: Int?,
    maxLevel: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val offLabel = stringResource(R.string.shell_power_off)
    val offDescription = stringResource(R.string.shell_power_off_cd)
    Row(
        modifier.fillMaxWidth().height(112.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0..maxLevel) {
            val active = level != null && i <= level && i > 0
            val isCurrent = level == i
            val fill by animateColorAsState(
                when {
                    !enabled -> c.rib.copy(alpha = 0.5f)
                    active -> c.led
                    isCurrent && i == 0 -> c.paint
                    else -> c.rib
                },
                tween(durationMillis = 160 + i * 40),
                label = "step$i",
            )
            val heightFraction = if (i == 0) 0.18f else 0.30f + 0.70f * i / maxLevel
            val stepDescription = if (i == 0) offDescription else stringResource(R.string.shell_power_level_cd, i)
            Column(
                Modifier
                    .weight(1f)
                    .selectable(
                        selected = isCurrent,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { haptics.tick(); onSelect(i) },
                    )
                    .semantics { contentDescription = stepDescription },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    if (i == 0) offLabel else i.toString(),
                    style = if (isCurrent) S3.type.data else S3.type.label,
                    color = if (isCurrent) c.paint else c.fog,
                )
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(80.dp * heightFraction)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(fill),
                )
            }
        }
    }
}

/** Room between two segments of a [ChoiceBar]. */
private val SegmentGap = 3.dp

/** A segment's own side padding. */
private val SegmentPadX = 8.dp

/**
 * The state dot in front of the selected label, and its gap to the label. Amber or red on the led segment is close in
 * lightness, so the dot sits in a ring of the segment's text colour.
 */
private val SegmentDot = 6.dp
private val SegmentDotRing = 1.5.dp
private val SegmentDotGap = 6.dp

/**
 * Mutually exclusive options as one split bar. All labels share one size, shrunk together (down to 12 sp) until each
 * fits its segment, so a long word or a large font never ends in an ellipsis. [selectedDot] draws a 6 dp dot of that
 * colour before the selected label: a state (light on, alarm armed) that must not become a surface. [readOnly]: the
 * bar shows a state this app does not change (the S2 alarm outside test mode). Nothing reacts to a tap, TalkBack still
 * hears the options as radio buttons with the selected one; the selected segment keeps its colours and its dot, only
 * the other labels go grey. [enabled] false (offline) still greys everything.
 */
@Composable
fun <T> ChoiceBar(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = S3.colors.led,
    onAccent: Color = S3.colors.onLed,
    selectedDot: Color? = null,
    readOnly: Boolean = false,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = S3.type.bodyStrong
    val labels = options.map { it.second }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val n = options.size.coerceAtLeast(1)
        // Every label is fitted as if it carried the dot, so selecting another segment never changes the size.
        val dotRoom = if (selectedDot != null) SegmentDot + SegmentDotRing * 2 + SegmentDotGap else 0.dp
        val slot = with(density) {
            ((maxWidth - SegmentGap * (n - 1)) / n - SegmentPadX * 2 - dotRoom).roundToPx()
        }
        val bounded = constraints.hasBoundedWidth
        val fitted = remember(labels, slot, base, bounded) {
            if (!bounded || slot <= 0) base else shellFitLabels(measurer, labels, base, min = 12.sp) { widths -> widths.all { it <= slot } }
        }
        // At the floor a label that still does not fit may break onto a second line rather than lose letters.
        val fits = remember(labels, slot, fitted) {
            !bounded || labels.all { measurer.measure(it, fitted, softWrap = false, maxLines = 1).size.width <= slot }
        }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(SegmentGap)) {
            options.forEachIndexed { index, (value, label) ->
                val isSel = value == selected
                val bg by animateColorAsState(if (isSel) accent else c.frame, label = "seg")
                val fg by animateColorAsState(
                    when {
                        !enabled -> c.rib
                        isSel -> onAccent
                        readOnly -> c.fog
                        else -> c.paint
                    },
                    label = "segText",
                )
                val outer = 22.dp
                val inner = 4.dp
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) outer else inner,
                    bottomStart = if (index == 0) outer else inner,
                    topEnd = if (index == options.lastIndex) outer else inner,
                    bottomEnd = if (index == options.lastIndex) outer else inner,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .clip(shape)
                        .background(bg)
                        .then(
                            if (readOnly) {
                                Modifier.semantics(mergeDescendants = true) {
                                    role = Role.RadioButton
                                    this.selected = isSel
                                    if (!enabled) disabled()
                                }
                            } else {
                                Modifier.selectable(isSel, enabled = enabled, role = Role.RadioButton) {
                                    haptics.tick(); onSelect(value)
                                }
                            },
                        )
                        .padding(horizontal = SegmentPadX, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSel && selectedDot != null) {
                            Box(
                                Modifier
                                    .size(SegmentDot + SegmentDotRing * 2)
                                    .clip(CircleShape)
                                    .background(fg)
                                    .padding(SegmentDotRing)
                                    .clip(CircleShape)
                                    .background(if (enabled) selectedDot else c.rib),
                            )
                            Spacer(Modifier.width(SegmentDotGap))
                        }
                        Text(
                            label,
                            style = fitted,
                            color = fg,
                            maxLines = if (fits) 1 else 2,
                            softWrap = !fits,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Slide-to-unlock in the shape of the top tube. Sliding all the way fires [onUnlock];
 * when [canLock] the thumb can be pushed back to lock.
 */
@Composable
fun UnlockSlider(
    locked: Boolean?,
    busy: Boolean,
    onUnlock: () -> Unit,
    onLock: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lockedAccent: Color? = null,
    onProgress: ((Float) -> Unit)? = null,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val isLocked = locked != false
    // A closed lock is traffic red (a state of the bike). A screen where closed is only where the ride begins (the first
    // unlock) hands its own accent: the slider then wears it, with the glyph on it in the matching dark.
    val lockedColor = lockedAccent ?: c.signal
    val onLocked = if (lockedAccent != null) c.onLed else c.onSignal
    val trackColor by animateColorAsState(
        when {
            !enabled -> c.frame
            isLocked -> lockedColor.copy(alpha = if (c.isDark) 0.16f else 0.12f)
            else -> c.led.copy(alpha = if (c.isDark) 0.14f else 0.10f)
        },
        label = "track",
    )
    val thumbColor by animateColorAsState(
        when {
            !enabled -> c.rib
            isLocked -> lockedColor
            else -> c.led
        },
        label = "thumb",
    )
    val thumbGlyph = if (isLocked) MatrixFont.LOCKED.toString() else MatrixFont.UNLOCKED.toString()
    val thumbSize = 60.dp
    val dragEnabled = enabled && !busy && (isLocked || onLock != null)
    val lockDescription = stringResource(R.string.shell_slider_cd)
    val lockState = stringResource(
        when (locked) {
            true -> R.string.shell_lock_locked
            false -> R.string.shell_lock_unlocked
            null -> R.string.shell_slider_state_unknown
        },
    )
    val unlockAction = stringResource(R.string.shell_slider_action_unlock)
    val lockAction = stringResource(R.string.shell_slider_action_lock)
    val trackText = stringResource(
        when {
            !enabled -> R.string.shell_link_offline
            busy -> if (isLocked) R.string.shell_slider_unlocking else R.string.shell_slider_locking
            locked == null -> R.string.shell_lock_unknown
            isLocked -> R.string.shell_slider_slide_unlock
            onLock != null -> R.string.shell_slider_slide_lock
            else -> R.string.shell_lock_unlocked
        },
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(trackColor)
            .border(1.dp, thumbColor.copy(alpha = 0.35f), RoundedCornerShape(36.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = lockDescription
                stateDescription = lockState
                // Switch Access, Voice Access and TalkBack cannot drag: give them the action directly.
                if (dragEnabled) {
                    if (isLocked) {
                        onClick(label = unlockAction) { haptics.confirm(); onUnlock(); true }
                    } else if (onLock != null) {
                        onClick(label = lockAction) { haptics.confirm(); onLock(); true }
                    }
                }
            },
    ) {
        val density = LocalDensity.current
        val travel = with(density) { (maxWidth - thumbSize - 12.dp).toPx() }
        val offset = remember { Animatable(0f) }
        var armed by remember { mutableStateOf(false) }
        var placed by remember { mutableStateOf(false) }
        // Slides that sent a command, and the last one the placement below has waited for.
        var commits by remember { mutableIntStateOf(0) }
        val waited = remember { intArrayOf(0) }

        // Snap on first placement (no fake animation when the screen appears); afterwards the thumb
        // follows the bike's answer, and stays where the user left it while the command runs. A command
        // that never starts (refused before it was sent) would leave the thumb at the far end: after
        // 1.2 s without busy it goes back to the end that matches the lock.
        LaunchedEffect(isLocked, travel, busy, commits) {
            if (busy) {
                waited[0] = commits
                return@LaunchedEffect
            }
            val target = if (isLocked) 0f else travel
            if (!placed) {
                offset.snapTo(target)
                placed = true
                return@LaunchedEffect
            }
            if (commits != waited[0]) {
                // Busy turning true restarts this effect before the delay ends.
                delay(1_200)
                waited[0] = commits
            }
            offset.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
        }

        // how far the thumb is, 0 (closed end) to 1 (open end), for a screen that answers the slide (the tube's lock glyph)
        val progressSink by rememberUpdatedState(onProgress)
        LaunchedEffect(travel) {
            if (progressSink != null && travel > 0f) {
                snapshotFlow { (offset.value / travel).coerceIn(0f, 1f) }.collect { progressSink?.invoke(it) }
            }
        }

        val thumbOnLeft = offset.value < travel / 2
        Text(
            text = trackText,
            style = S3.type.bodyStrong,
            color = if (enabled) c.paint else c.fog,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(
                    start = if (thumbOnLeft) thumbSize + 16.dp else 20.dp,
                    end = if (thumbOnLeft) 20.dp else thumbSize + 16.dp,
                ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .offset { IntOffset((offset.value + with(density) { 6.dp.toPx() }).roundToInt(), with(density) { 6.dp.toPx() }.roundToInt()) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor)
                .draggable(
                    enabled = dragEnabled,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val v = (offset.value + delta).coerceIn(0f, travel)
                            offset.snapTo(v)
                            val nowArmed = if (isLocked) v > travel * 0.82f else v < travel * 0.18f
                            if (nowArmed && !armed) haptics.tick()
                            armed = nowArmed
                        }
                    },
                    onDragStopped = {
                        val committed = armed
                        armed = false
                        if (committed) {
                            haptics.confirm()
                            // Leave the thumb at the end; the effect moves it once the bike answers.
                            offset.animateTo(if (isLocked) travel else 0f, spring(stiffness = Spring.StiffnessMedium))
                            if (isLocked) onUnlock() else onLock?.invoke()
                            commits++
                        } else {
                            offset.animateTo(if (isLocked) 0f else travel, spring(stiffness = Spring.StiffnessMediumLow))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            MatrixDisplay(
                frame = remember(thumbGlyph) { MatrixFrame.text(thumbGlyph) },
                columns = 7,
                lit = when {
                    !enabled -> c.fog
                    isLocked -> onLocked
                    else -> c.onLed
                },
                unlit = Color.Transparent,
                glow = Color.Transparent,
                modifier = Modifier.width(26.dp),
            )
        }
    }
}

/**
 * The hero: the S3 top tube seen from above, matrix display inside,
 * head light at the front end and rear light at the back, lit when the lights are on.
 */
@Composable
fun BikeTube(
    frame: MatrixFrame,
    lightsOn: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = 40,
    description: String? = null,
    lit: Color = S3.colors.led,
    glow: Color = S3.colors.ledGlow,
    sweepMillis: Int = 520,
    sweepEasing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.FastOutSlowInEasing,
    look: DotLook = DotLook.Calm,
) {
    val c = S3.colors
    val lamp by animateFloatAsState(if (lightsOn) 1f else 0f, tween(600), label = "lamp")
    // Clip to the tube so the light glow stays inside the capsule.
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50))) {
        Canvas(Modifier.matchParentSize()) {
            drawTubeBody(c, Rect(0f, 0f, size.width, size.height), lamp, TubeDefaults.BarWidth.toPx(), 1.dp.toPx())
        }
        MatrixDisplay(
            frame = frame,
            columns = columns,
            description = description,
            lit = lit,
            glow = glow,
            sweepMillis = sweepMillis,
            sweepEasing = sweepEasing,
            look = look,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TubeDefaults.PadX, vertical = TubeDefaults.PadY),
        )
    }
}

/**
 * A sound-board pad: the sound's emoji at the top, its name at the bottom. Flashes LED-blue while the bike plays it.
 * [label] goes through [padLabel], so a compound breaks after its hyphen and never inside a word.
 */
@Composable
fun SoundPad(
    label: String,
    /**
     * The sound's emoji ([SoundEmoji]). The owner asked for these on the sound board (BRAND, Don't 8, the one
     * exception to "no emoji"): required, so a pad cannot lose it quietly. SoundEmojiTest checks it is drawn.
     */
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var flash by remember { mutableStateOf(0) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash > 0) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(900))
        }
    }
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "padScale")
    Column(
        modifier
            .scale(scale)
            .heightIn(min = 92.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(lerpColor(c.frame, c.led, glow.value * 0.85f))
            .clickable(interaction, indication = null, enabled = enabled) {
                haptics.tick(); flash++; onClick()
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        SoundEmoji(emoji, faded = !enabled)
        Spacer(Modifier.height(6.dp))
        Text(
            padLabel(label),
            style = S3.type.label,
            color = if (!enabled) c.rib else lerpColor(c.paint, c.onLed, glow.value),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A pad's [label] as it is set: a zero-width space after every hyphen. Android's line breaker leaves a hard hyphen to
 * automatic hyphenation, which Compose text does not run, so "Entsperr-Countdown" would break inside "Countdown"; the
 * zero-width space gives the line its break after the hyphen. The sound board measures this same string.
 */
fun padLabel(label: String): String = label.replace("-", "-\u200B")

/** Primary action: wide, heavy, unmistakable. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = S3.colors.led,
    onColor: Color = S3.colors.onLed,
) {
    val c = S3.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "btn")
    Box(
        modifier
            .fillMaxWidth()
            .scale(scale)
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(if (enabled) color else c.rib)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = S3.type.bodyStrong, color = if (enabled) onColor else c.fog, textAlign = TextAlign.Center)
    }
}

/** Secondary action: outline only. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = S3.colors.paint,
) {
    val c = S3.colors
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(1.5.dp, if (enabled) color.copy(alpha = 0.5f) else c.rib, RoundedCornerShape(26.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = S3.type.bodyStrong, color = if (enabled) color else c.fog, textAlign = TextAlign.Center)
    }
}

/** Label/value pair in the condensed data face, for bike facts. */
@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = S3.colors.paint) {
    Column(modifier) {
        Text(value, style = S3.type.data, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = S3.type.caption, color = S3.colors.fog, maxLines = 1)
    }
}

/** Blinking LED dot + text for the connection state. */
@Composable
fun LinkIndicator(text: String, color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(1f) }
    val reduceMotion = rememberReducedMotion()
    LaunchedEffect(pulsing, reduceMotion) {
        if (pulsing && !reduceMotion) {
            while (true) {
                alpha.animateTo(0.25f, tween(520)); alpha.animateTo(1f, tween(520))
            }
        } else alpha.snapTo(1f)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Alpha is read in the draw phase, so pulsing does not recompose the row every frame.
        Box(Modifier.size(8.dp).drawBehind { drawCircle(color.copy(alpha = alpha.value)) })
        Text(text, style = S3.type.label, color = S3.colors.paint)
    }
}
