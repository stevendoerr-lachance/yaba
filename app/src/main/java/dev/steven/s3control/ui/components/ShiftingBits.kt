package dev.steven.s3control.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.CustomShiftStore
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.Handle
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ble.ShiftCheck
import dev.steven.s3control.ble.ShiftMode
import dev.steven.s3control.ble.ShiftPoints
import dev.steven.s3control.ble.ShiftProfile
import dev.steven.s3control.ble.ShiftProfiles
import dev.steven.s3control.ble.ShiftRules
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.UnitSystem
import dev.steven.s3control.ble.compareVersions
import dev.steven.s3control.ui.onboarding.TextLink
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Schalten: the e-shifter's mode and its six shift points, drawn as what they are — gear bands on
 * one speed axis (0..40 km/h). The bike stores no profile id: a row is lit because its six values
 * are the ones the bike reports. Changes are allowed while riding (the owner's call) and apply on the
 * shifter's next pass; no gear is ever sent.
 */

/** The shared speed axis of every strip, km/h. */
private const val AXIS = ShiftRules.MAX_KMH.toFloat()

/** Room above a strip for the speed notch and below it for the current-gear underline. */
private val StripBleed = 7.dp

/** Name column of a profile row in the wide layout; the axis labels start after it too. */
private val WideNameColumn = 96.dp

/**
 * Below this width the rows stack name over strip. The Find N6 inner pane (~361 dp) stays compact on
 * purpose: stacked rows give the strips a third more width than the 96 dp name column would leave.
 */
private val CompactBelow = 400.dp

/** Editor buttons side by side from this width on. */
private val ButtonsInRow = 300.dp

/** Disabled look, matching ChoiceBar's dimmed segments. */
private const val DimAlpha = 0.45f

private val Sending: @Composable RowScope.() -> Unit = { ShellSending() }

private val PointsSaver = Saver<ShiftPoints?, IntArray>(
    save = { it?.toList()?.toIntArray() },
    restore = { ShiftPoints.fromList(it.toList()) },
)

// --- The section ------------------------------------------------------------------------------

/**
 * The whole "Schalten" section for RideControls: title, mode bar, gear readout, then the profile
 * stack with the Eigene editor (Automatisch) or the handlebar gestures (Manuell).
 * [send] wraps a bike command the way RideControls tracks it (error shown under this section).
 */
@Composable
fun ShiftSection(
    vm: AppViewModel,
    s: BikeState,
    error: String?,
    onExpire: () -> Unit,
    send: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val store = remember { CustomShiftStore.get(context) }
    val saved by store.sets.collectAsStateWithLifecycle()

    val ready = s.isReady
    val busy = Op.SHIFT in s.busy
    // A region change picks another points slot: no shifting write queues behind it.
    val canTap = s.shiftWritable && !busy && Op.SPEED !in s.busy
    val region = s.speedLimit
    val limit = region?.takeIf { it != SpeedLimit.NO_LIMIT }?.kmh
    val mac = s.bike?.normalizedMac
    val savedCustom = mac?.let { saved[it] }?.takeIf { ShiftRules.isValid(it) }
    val current = s.shiftPointsCurrent
    val violations = current?.let { ShiftRules.violations(it) }.orEmpty()
    val currentValid = current != null && violations.isEmpty()
    val customOnBike = current != null && s.shiftProfile == null
    val invalidOnBike = customOnBike && !currentValid
    val gear = if (ready) s.gear else null
    val checkFailed = ready && s.shiftCheck != null && s.shiftCheck != ShiftCheck.OK

    // Which row was tapped, so it can show "Sending" at its end while the write runs.
    var pendingRow by rememberSaveable { mutableIntStateOf(-1) }
    var wasBusy by remember { mutableStateOf(busy) }
    LaunchedEffect(busy) {
        if (wasBusy && !busy && s.lastCommandError == null) haptics.confirm()
        if (!busy) pendingRow = -1
        wasBusy = busy
    }

    // Folded by default: the profile on the bike; "Alle Profile zeigen" opens the rest and the legend.
    var showAll by rememberSaveable { mutableStateOf(false) }

    // Eigene editor: draft survives folding; Verwerfen drops it and closes.
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(stateSaver = PointsSaver) { mutableStateOf<ShiftPoints?>(null) }
    var seedFromStandard by rememberSaveable { mutableStateOf(false) }
    var selectedHandle by rememberSaveable { mutableIntStateOf(-1) }

    fun toggleEditor() {
        haptics.tick()
        if (editorOpen) {
            editorOpen = false
            return
        }
        if (draft == null) {
            val (start, fromStandard) = when {
                customOnBike && currentValid -> current!! to false
                savedCustom != null -> savedCustom to false
                current != null && s.shiftProfile != null -> current to false
                else -> ShiftProfiles.points(ShiftProfile.STANDARD, region) to (current != null && !currentValid)
            }
            draft = start
            seedFromStandard = fromStandard
            // A selected point shows its stepper right away: the editor explains itself.
            selectedHandle = Handle.UP12.ordinal
        }
        editorOpen = true
    }

    fun discard() {
        haptics.tick()
        draft = null
        selectedHandle = -1
        editorOpen = false
    }

    fun chooseProfile(index: Int, profile: ShiftProfile) {
        if (!canTap) return
        haptics.tick()
        editorOpen = false
        draft = null
        selectedHandle = -1
        if (s.shiftProfile != profile) {
            pendingRow = index
            // The controller resolves the profile for the region the bike reports inside the command.
            send { vm.bike.setShiftProfile(profile) }
        }
    }

    fun applyDraft() {
        val p = draft ?: return
        if (!canTap) return
        pendingRow = ShiftProfile.entries.size
        val atRegion = region
        val forMac = mac
        // Remember a set the bike took that is not a profile, so the editor can start from it later.
        send {
            vm.bike.setShiftPoints(p, atRegion) { ok ->
                if (ok && forMac != null && ShiftProfiles.match(p, atRegion) == null) store.save(forMac, p)
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < CompactBelow
        Column(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.shift_section), trailing = if (busy) Sending else null)
            Spacer(Modifier.height(S3.m))

            s.shifterFault?.let { code ->
                ShellSignalLine(stringResource(R.string.shift_fault, code, s.describeFault(code).name))
                Spacer(Modifier.height(S3.m))
            }
            if (checkFailed) {
                val detail = ShiftCheck.detail(s.raw[Gatt.E_SHIFTER_MODE.short], s.raw[Gatt.E_SHIFT_POINTS.short])
                Hint(stringResource(R.string.shift_check_failed, s.firmware ?: "?", detail), color = c.lamp)
                Spacer(Modifier.height(S3.m))
            }
            if (ready) {
                s.shiftNotice?.let {
                    Hint(it, color = c.lamp)
                    Spacer(Modifier.height(S3.m))
                }
            }

            // Mode: mirrors 5538. Manual exists from 1.7.2 on.
            val firmware = s.firmware
            val manualOffered = firmware == null || compareVersions(firmware, "1.7.2") >= 0 || s.shiftMode == ShiftMode.MANUAL
            val modes = buildList {
                add(ShiftMode.AUTO to stringResource(ShiftMode.AUTO.labelRes))
                if (manualOffered) add(ShiftMode.MANUAL to stringResource(ShiftMode.MANUAL.labelRes))
            }
            ChoiceBar(
                options = modes,
                selected = s.shiftMode,
                onSelect = { mode -> if (mode != s.shiftMode && canTap) send { vm.bike.setShiftMode(mode) } },
                enabled = s.shiftWritable,
            )
            if (ready && s.moving) {
                Spacer(Modifier.height(S3.s))
                Hint(stringResource(R.string.shift_riding_note))
            }

            val manual = s.shiftMode == ShiftMode.MANUAL
            Spacer(Modifier.height(S3.l))
            GearReadout(gear)
            Spacer(Modifier.height(S3.m))

            if (manual) {
                ManualContent(s)
            } else {
                val reading = ready && s.shiftSupported && current == null && !checkFailed
                val customPoints = if (customOnBike) current else savedCustom
                val custom = ShiftProfile.entries.size
                // Folded, the stack is the one row the bike runs (Eigene if that is it), or none while that is not known
                // (not connected, still reading); the rest waits behind the link.
                val onBike = when {
                    customOnBike -> custom
                    else -> ShiftProfile.entries.indexOfFirst { s.shiftProfile == it }.takeIf { it >= 0 }
                }
                val shown = if (showAll) (0..custom).toList() else listOfNotNull(onBike)
                // The axis sits right under the last strip shown; an empty Eigene row has none.
                val axisAfter = shown.lastOrNull { it < custom || customPoints != null }
                val axis: @Composable () -> Unit = {
                    Spacer(Modifier.height(S3.xs))
                    ShiftAxis(limitKmh = limit, startInset = if (compact) 0.dp else WideNameColumn)
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .selectableGroup()
                        .alpha(if (reading || !ready) DimAlpha else 1f),
                ) {
                    ShiftProfile.entries.forEachIndexed { index, profile ->
                        if (index !in shown) return@forEachIndexed
                        val points = ShiftProfiles.points(profile, region)
                        val name = stringResource(profile.labelRes)
                        val reason = stringResource(profile.reasonRes)
                        val selected = s.shiftProfile == profile
                        ShiftProfileRow(
                            name = name,
                            points = points,
                            selected = selected,
                            enabled = canTap,
                            compact = compact,
                            description = rowDescription(name, points, reason),
                            onClick = { chooseProfile(index, profile) },
                            caption = reason,
                            values = if (selected) points.short() else null,
                            sending = busy && pendingRow == index,
                            currentGear = gear,
                            speedKmh = s.speedKmh,
                            limitKmh = limit,
                        )
                        if (index == axisAfter) axis()
                    }

                    if (custom in shown) {
                        val customName = stringResource(R.string.shift_profile_custom)
                        val caption = when {
                            invalidOnBike -> stringResource(R.string.shift_reason_invalid)
                            customPoints != null -> stringResource(R.string.shift_reason_custom)
                            else -> stringResource(R.string.shift_custom_empty)
                        }
                        val notes = if (invalidOnBike && current != null) conflictNotes(current, violations) else emptyList()
                        val editAction = stringResource(if (editorOpen) R.string.shift_edit_close_action else R.string.shift_edit_action)
                        val editLabel = stringResource(
                            when {
                                editorOpen -> R.string.shift_edit_close
                                customPoints == null && !invalidOnBike -> R.string.shift_edit_set
                                else -> R.string.shift_edit
                            },
                        )
                        // Opening the editor writes nothing, so it works whenever the values are known.
                        val canEdit = s.shiftWritable
                        ShiftProfileRow(
                            name = customName,
                            points = customPoints,
                            selected = customOnBike,
                            enabled = canEdit,
                            compact = compact,
                            description = customPoints?.let { rowDescription(customName, it, caption) } ?: "$customName. $caption",
                            onClick = { toggleEditor() },
                            caption = caption,
                            captionColor = if (invalidOnBike) c.lamp else c.fog,
                            values = if (customOnBike) current?.short() else null,
                            notes = notes,
                            showConflicts = invalidOnBike,
                            sending = busy && pendingRow == custom,
                            currentGear = gear,
                            speedKmh = s.speedKmh,
                            limitKmh = limit,
                            actions = if (canEdit) listOf(CustomAccessibilityAction(editAction) { toggleEditor(); true }) else emptyList(),
                            trailing = { EditToggle(editLabel, expanded = editorOpen, enabled = canEdit, onClick = { toggleEditor() }) },
                        )
                        if (axisAfter == custom) axis()
                    }
                }

                if (reading) {
                    Spacer(Modifier.height(S3.s))
                    Hint(stringResource(R.string.shift_reading))
                }
                val foldState = stringResource(if (showAll) R.string.shift_expanded else R.string.shift_collapsed)
                TextLink(
                    stringResource(if (showAll) R.string.shift_profiles_hide else R.string.shift_profiles_show),
                    { haptics.tick(); showAll = !showAll },
                    Modifier.semantics { stateDescription = foldState },
                )
                if (showAll) {
                    Column(verticalArrangement = Arrangement.spacedBy(S3.xs)) {
                        Hint(stringResource(R.string.shift_legend))
                        if (region != null) Hint(stringResource(R.string.shift_region_short, stringResource(region.regionRes)))
                        if (s.unit == UnitSystem.IMPERIAL) Hint(stringResource(R.string.shift_units_note))
                    }
                }

                val d = draft
                if (editorOpen && d != null) {
                    Spacer(Modifier.height(S3.l))
                    ShiftEditor(
                        draft = d,
                        onDraft = { draft = it },
                        limitKmh = limit,
                        canApply = canTap,
                        onApply = { applyDraft() },
                        onDiscard = { discard() },
                        wide = !compact,
                        current = current,
                        region = region,
                        fromStandard = seedFromStandard,
                        selected = Handle.entries.getOrNull(selectedHandle),
                        onSelect = { h -> selectedHandle = h?.ordinal ?: -1 },
                    )
                }
                Spacer(Modifier.height(S3.m))
                Hint(stringResource(R.string.shift_ease_off))
            }

            error?.let {
                Spacer(Modifier.height(S3.s))
                ShellCommandError(it, onExpire = onExpire)
            }
        }
    }
}

@Composable
private fun rowDescription(name: String, p: ShiftPoints, reason: String?): String {
    val base = stringResource(R.string.shift_row_cd, name, p.up12, p.up23, p.up34, p.down21, p.down32, p.down43)
    return if (reason != null) "$base $reason" else base
}

/**
 * The broken rules of an invalid set in words, the worst first; six zeros get their own sentence
 * (they are not a near miss but the bike shifting to 4 and starting off there).
 */
@Composable
private fun conflictNotes(p: ShiftPoints, rules: Set<ShiftRules.Rule>): List<Pair<String, Color>> {
    val c = S3.colors
    if (p == ShiftPoints.ZERO) return listOf(stringResource(R.string.shift_invalid_zero) to c.lamp)
    val sentences = rules.map { rule ->
        val a = rule.handles[0]
        val aLabel = stringResource(if (a.up) R.string.shift_handle_up else R.string.shift_handle_down, a.from, a.to)
        when (rule) {
            ShiftRules.Rule.DOWN21_MIN -> stringResource(R.string.shift_conflict_min, aLabel, p[a], ShiftRules.MIN_DOWN21)
            ShiftRules.Rule.UP12_RANGE -> stringResource(R.string.shift_conflict_range, aLabel, p[a], ShiftRules.MIN_UP12, ShiftRules.MAX_UP12)
            ShiftRules.Rule.UP34_MAX -> stringResource(R.string.shift_conflict_max, aLabel, p[a], ShiftRules.MAX_KMH)
            else -> {
                val b = rule.handles[1]
                val bLabel = stringResource(if (b.up) R.string.shift_handle_up else R.string.shift_handle_down, b.from, b.to)
                val gap = rule in setOf(ShiftRules.Rule.GAP_12, ShiftRules.Rule.GAP_23, ShiftRules.Rule.GAP_34)
                stringResource(if (gap) R.string.shift_conflict_gap else R.string.shift_conflict_spacing, aLabel, p[a], bLabel, p[b])
            }
        }
    }
    val shown = sentences.take(3).map { it to c.fog }
    val more = if (sentences.size > 3) listOf(stringResource(R.string.shift_conflict_more, sentences.size - 3) to c.fog) else emptyList()
    return shown + more + listOf(stringResource(R.string.shift_invalid_on_bike) to c.lamp)
}

/** A word joiner after each slash between letters, so "km/h" (or "км/ч") never breaks across lines. */
private val UnitSlash = Regex("(?<=\\p{L})/(?=\\p{L})")

private fun keepUnits(text: String): String = text.replace(UnitSlash, "/\u2060")

/** "Bearbeiten" / "Schließen" at the end of the Eigene row: a real button, so TalkBack can always reach it. */
@Composable
private fun EditToggle(label: String, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = S3.colors
    val state = stringResource(if (expanded) R.string.shift_expanded else R.string.shift_collapsed)
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .padding(start = S3.s)
            .heightIn(min = 36.dp)
            .clip(shape)
            .border(1.5.dp, if (enabled) c.paint.copy(alpha = 0.5f) else c.rib, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { stateDescription = state }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = S3.type.label, color = if (enabled) c.paint else c.fog, maxLines = 1)
    }
}

/** The gear the bike commanded, big. */
@Composable
private fun GearReadout(gear: Int?) {
    val c = S3.colors
    val cd = gear?.let { stringResource(R.string.shift_gear_cd, it) } ?: stringResource(R.string.shift_gear_unknown_cd)
    // No live region: while riding the gear changes far too often for TalkBack to keep announcing it.
    Column(Modifier.widthIn(min = 56.dp).clearAndSetSemantics { contentDescription = cd }) {
        Text(gear?.toString() ?: "–", style = S3.type.dataLarge, color = c.paint, maxLines = 1)
        Text(stringResource(R.string.shift_gear), style = S3.type.caption, color = c.fog, maxLines = 1)
    }
}

/** Manuell: the handlebar does the shifting; YABA only shows how, with the keys lighting while held. */
@Composable
private fun ManualContent(s: BikeState) {
    val c = S3.colors
    ShiftKeyCaps(bell = s.buttons?.horn == true, boost = s.buttons?.boost == true)
    Spacer(Modifier.height(S3.m))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        for (res in listOf(R.string.shift_manual_stop, R.string.shift_manual_boost)) {
            Text(stringResource(res), style = S3.type.body, color = c.paint)
        }
    }
    Spacer(Modifier.height(S3.m))
    Hint(stringResource(R.string.shift_manual_points_kept))
}

// --- Gear strip ----------------------------------------------------------------------------------

/** A broken rule on the strip: the span it covers and the points it ties together, km/h. */
private class Conflict(val from: Float, val to: Float, val at: List<Float>)

/**
 * One set of shift points as gear bands on the 0..40 km/h axis. Solid where the gear is certain
 * (gear 1: 0 → down 2→1, gear 2: up 1→2 → down 3→2, …); between them the hysteresis zone stays
 * empty, marked by a tick hanging at the up point and one rising at the down point. On the selected
 * strip the current gear's whole hold range is tinted and underlined, and the live speed is a notch
 * above the strip. [showConflicts] hatches every broken rule in signal.
 */
@Composable
fun ShiftBand(
    points: ShiftPoints?,
    selected: Boolean,
    currentGear: Int?,
    speedKmh: Int?,
    limitKmh: Int?,
    height: Dp,
    modifier: Modifier = Modifier,
    showConflicts: Boolean = false,
) {
    val c = S3.colors
    val reduce = rememberReducedMotion()
    // Both move every frame while they animate: read in the draw phase only, so the row does not recompose for them.
    val bounds = rememberBoundaries(points, reduce)
    val cursor = rememberCursor(if (selected) speedKmh else null, reduce)
    val gear by rememberUpdatedState(currentGear)
    val digits = rememberDigits(height)
    val conflicts = remember(points, showConflicts) {
        if (!showConflicts || points == null) {
            emptyList()
        } else {
            ShiftRules.violations(points).map { rule ->
                val (from, to) = ShiftRules.span(rule, points)
                Conflict(from.toFloat(), to.toFloat(), rule.handles.map { points[it].toFloat() })
            }
        }
    }
    // The paths (strip outline, speed notch) and the limit's dash are built once per size, points, limit and colours.
    val draw = remember(points, limitKmh, c, selected, digits, conflicts, height) {
        val build: CacheDrawScope.() -> DrawResult = {
            val y0 = StripBleed.toPx()
            val h = height.toPx()
            val paths = StripPaths(
                clip = Path().apply { addRoundRect(RoundRect(0f, y0, size.width, y0 + h, CornerRadius(6.dp.toPx()))) },
                notch = notchPath(this, y0),
                dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
            )
            onDrawBehind {
                drawGearStrip(
                    x0 = 0f, y0 = y0, w = size.width, h = h,
                    b = bounds(), selected = selected, gear = gear, cursor = cursor(), limit = limitKmh,
                    c = c, digits = digits, conflicts = conflicts, paths = paths,
                )
            }
        }
        build
    }
    Spacer(modifier.fillMaxWidth().height(height + StripBleed * 2).drawWithCache(draw))
}

/** A strip's paths, prepared once ([ShiftBand]); [notch] points down with its tip at x = 0. */
private class StripPaths(val clip: Path, val notch: Path, val dash: PathEffect)

/** The speed notch above a strip whose top is [y0], tip at x = 0 (translated to the speed when drawn). */
private fun notchPath(density: androidx.compose.ui.unit.Density, y0: Float): Path = with(density) {
    val half = 5.dp.toPx()
    Path().apply {
        moveTo(-half, y0 - StripBleed.toPx() + 0.5.dp.toPx())
        lineTo(half, y0 - StripBleed.toPx() + 0.5.dp.toPx())
        lineTo(0f, y0 - 0.5.dp.toPx())
        close()
    }
}

/**
 * The six boundaries (up12, up23, up34, down21, down32, down43), animated 250 ms or snapped. The returned function
 * reads the animation, so call it where the value is drawn.
 */
@Composable
private fun rememberBoundaries(points: ShiftPoints?, reduce: Boolean): () -> FloatArray? {
    val anims = remember { Array(6) { Animatable(Float.NaN) } }
    LaunchedEffect(points, reduce) {
        val p = points ?: return@LaunchedEffect
        for (h in Handle.entries) {
            val a = anims[h.ordinal]
            val target = p[h].toFloat()
            launch { if (reduce || a.value.isNaN()) a.snapTo(target) else a.animateTo(target, tween(250)) }
        }
    }
    val latest by rememberUpdatedState(points)
    return remember(anims) {
        {
            val p = latest
            when {
                p == null -> null
                anims[0].value.isNaN() -> FloatArray(6) { p[Handle.entries[it]].toFloat() }
                else -> FloatArray(6) { anims[it].value }
            }
        }
    }
}

/** Live speed on the selected strip, eased linearly over 300 ms between readings; read where it is drawn. */
@Composable
private fun rememberCursor(speedKmh: Int?, reduce: Boolean): () -> Float? {
    val anim = remember { Animatable(Float.NaN) }
    val target = speedKmh?.takeIf { it >= 1 }?.coerceAtMost(ShiftRules.MAX_KMH)?.toFloat()
    LaunchedEffect(target, reduce) {
        if (target == null) return@LaunchedEffect
        if (reduce || anim.value.isNaN()) anim.snapTo(target) else anim.animateTo(target, tween(300, easing = LinearEasing))
    }
    val latest by rememberUpdatedState(target)
    return remember(anim) {
        {
            val goal = latest
            if (goal == null) null else if (anim.value.isNaN()) goal else anim.value
        }
    }
}

/** Gear digits 1..4 sized to the strip (in dp, so they always fit inside the band). */
@Composable
private fun rememberDigits(strip: Dp): List<TextLayoutResult> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = S3.type.data
    return remember(measurer, density, strip, base) {
        val size = with(density) { (strip * 0.62f).coerceIn(10.dp, 18.dp).toSp() }
        val style = base.copy(fontSize = size, lineHeight = size)
        (1..4).map { measurer.measure("$it", style) }
    }
}

private fun bandsOf(b: FloatArray): List<Pair<Float, Float>> =
    listOf(0f to b[3], b[0] to b[4], b[1] to b[5], b[2] to AXIS)

/** Where the bike stays in [gear]: from its down point (0 for gear 1) to its up point (the axis end for 4). */
private fun holdOf(b: FloatArray, gear: Int): Pair<Float, Float> =
    (if (gear == 1) 0f else b[gear + 1]) to (if (gear == 4) AXIS else b[gear - 1])

/** Draws one strip at ([x0], [y0]) with size [w] × [h]; [b] in km/h, see [rememberBoundaries]. */
private fun DrawScope.drawGearStrip(
    x0: Float,
    y0: Float,
    w: Float,
    h: Float,
    b: FloatArray?,
    selected: Boolean,
    gear: Int?,
    cursor: Float?,
    limit: Int?,
    c: S3Palette,
    digits: List<TextLayoutResult>,
    conflicts: List<Conflict> = emptyList(),
    /** Prepared paths for a strip at x0 = 0 of this size ([ShiftBand]); built here when null. */
    paths: StripPaths? = null,
) {
    val radius = CornerRadius(6.dp.toPx())
    fun x(k: Float) = x0 + k.coerceIn(0f, AXIS) / AXIS * w
    val lx = limit?.let { x(it.toFloat()) }
    drawRoundRect(c.frame, Offset(x0, y0), Size(w, h), radius)
    if (b != null) {
        val bands = bandsOf(b)
        val band = if (selected) c.led else c.rib
        val hold = if (selected && gear != null && gear in 1..4) holdOf(b, gear) else null
        val clip = paths?.clip ?: Path().apply { addRoundRect(RoundRect(x0, y0, x0 + w, y0 + h, radius)) }
        clipPath(clip) {
            // The current gear's hysteresis zones, tinted: the speed notch always sits over the gear it names.
            if (hold != null && hold.second > hold.first) {
                drawRect(c.led.copy(alpha = 0.32f), Offset(x(hold.first), y0), Size(x(hold.second) - x(hold.first), h))
            }
            for ((from, to) in bands) if (to > from) drawRect(band, Offset(x(from), y0), Size(x(to) - x(from), h))
            // Hysteresis: up point hangs from the top, down point rises from the bottom, both inside the zone.
            val t = 1.5.dp.toPx()
            val len = h * 0.42f
            val tick = if (selected) c.paint else c.fog
            for (k in 0..2) {
                drawRect(tick, Offset(x(b[k]) - t, y0), Size(t, len))
                drawRect(tick, Offset(x(b[k + 3]), y0 + h - len), Size(t, len))
            }
            for (cf in conflicts) drawConflict(cf, ::x, y0, h, c)
            val minDigit = 14.dp.toPx()
            val digitColor = if (selected) c.onLed else c.fog
            bands.forEachIndexed { i, (from, to) ->
                val d = digits[i]
                val left = x(from)
                val right = x(to)
                digitLeft(left, right, d.size.width.toFloat(), minDigit, lx)?.let { dx ->
                    drawText(d, digitColor, Offset(dx, y0 + (h - d.size.height) / 2f))
                }
            }
        }
        if (hold != null && hold.second > hold.first) {
            // Underline under the strip: readable on any band colour, in both themes.
            val ty = y0 + h + 2.5.dp.toPx()
            drawLine(
                c.paint, Offset(x(hold.first) + 1.5.dp.toPx(), ty), Offset(x(hold.second) - 1.5.dp.toPx(), ty),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
    if (lx != null) {
        val ext = 3.dp.toPx()
        drawLine(
            c.signal, Offset(lx, y0 - ext), Offset(lx, y0 + h + ext),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = paths?.dash ?: PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
        )
    }
    if (selected && cursor != null) {
        // A notch above the strip: it never covers a gear digit.
        val notch = paths?.notch ?: notchPath(this, y0)
        translate(left = x(cursor)) { drawPath(notch, c.paint) }
    }
}

/** A broken rule: hatched in signal across the full strip height, with a line at each point it ties. */
private fun DrawScope.drawConflict(cf: Conflict, x: (Float) -> Float, y0: Float, h: Float, c: S3Palette) {
    val minW = 4.dp.toPx()
    var left = x(cf.from)
    var right = x(cf.to)
    if (right - left < minW) {
        val mid = (left + right) / 2f
        left = mid - minW / 2f
        right = mid + minW / 2f
    }
    drawRect(c.signal.copy(alpha = 0.22f), Offset(left, y0), Size(right - left, h))
    clipRect(left, y0, right, y0 + h) {
        val step = 5.dp.toPx()
        var sx = left - h
        while (sx < right) {
            drawLine(c.signal, Offset(sx, y0 + h), Offset(sx + h, y0), strokeWidth = 1.25.dp.toPx())
            sx += step
        }
    }
    for (k in cf.at) {
        val px = x(k)
        drawRect(c.signal, Offset(px - 1.dp.toPx(), y0), Size(2.dp.toPx(), h))
    }
}

/**
 * Left edge for a gear digit in the band [left]..[right], or null when the band is too narrow.
 * Moves the digit to the roomier side when the limit line would run through it.
 */
private fun DrawScope.digitLeft(left: Float, right: Float, dw: Float, minBand: Float, avoid: Float?): Float? {
    if (right - left < maxOf(minBand, dw + 2.dp.toPx())) return null
    val centre = (left + right) / 2f
    val clear = dw / 2f + 3.dp.toPx()
    if (avoid == null || avoid <= left || avoid >= right || abs(avoid - centre) >= clear) return centre - dw / 2f
    val leftRoom = avoid - left
    val rightRoom = right - avoid
    val (from, to) = if (rightRoom >= leftRoom) avoid to right else left to avoid
    if (to - from < dw + 2 * 3.dp.toPx()) return centre - dw / 2f
    return (from + to) / 2f - dw / 2f
}

/** One label of the Schalten axis as it is drawn: [left] and [width] in px; [limit] is the assist limit's label (signal). */
internal data class ShiftAxisLabel(val text: String, val left: Float, val width: Float, val limit: Boolean) {
    val right: Float get() = left + width
}

/**
 * Where the labels of the Schalten axis go: 0, 10, 20 and 30 centred on their ticks, the end label ([withUnit] of 40,
 * "40 km/h") right-aligned on the axis end, the limit centred on its own place. Every label is kept inside 0..[canvasWidth].
 * A plain label (the end label too) is dropped unless at least [gap] px stand between its drawn bounds and the limit's drawn
 * bounds, on either side; the limit itself always stays. The unit stood only in the end label, so when that goes it moves:
 * to the limit ("32 km/h") if the kept labels stay [gap] clear of it, else to the last kept tick that has room for it (the
 * limit [gap] clear, a neighbouring tick half that). The number keeps its place on its tick, the unit follows it. The axis
 * runs from [x0] over [axisWidth] px for 0..[ShiftRules.MAX_KMH] km/h. [widthOf] measures a text in px. The limit comes
 * last in the list, so it is drawn over everything else.
 */
internal fun shiftAxisLabels(
    limitKmh: Int?,
    x0: Float,
    axisWidth: Float,
    canvasWidth: Float,
    gap: Float,
    withUnit: (Int) -> String,
    widthOf: (String) -> Float,
): List<ShiftAxisLabel> {
    fun x(k: Int) = x0 + k / AXIS * axisWidth
    fun inside(text: String, left: Float, limit: Boolean): ShiftAxisLabel {
        val w = widthOf(text)
        return ShiftAxisLabel(text, left.coerceIn(0f, (canvasWidth - w).coerceAtLeast(0f)), w, limit)
    }
    fun centred(k: Int, limit: Boolean) = inside("$k", x(k) - widthOf("$k") / 2f, limit)
    // the number where it stands alone, the unit after it
    fun unitAt(k: Int, limit: Boolean) = inside(withUnit(k), x(k) - widthOf("$k") / 2f, limit)
    fun apart(a: ShiftAxisLabel, b: ShiftAxisLabel, space: Float) = a.right + space <= b.left || a.left >= b.right + space

    val limit = limitKmh?.let { centred(it, limit = true) }
    val ticks = (0..ShiftRules.MAX_KMH step 10).map { k ->
        if (k == ShiftRules.MAX_KMH) {
            // the unit's label ends where the plain number would end, inside the canvas
            // (a dimmed stack draws through a clipping layer)
            val end = withUnit(k)
            val w = widthOf(end)
            val right = minOf(x(k) + widthOf("$k") / 2f, canvasWidth)
            k to ShiftAxisLabel(end, (right - w).coerceAtLeast(0f), w, limit = false)
        } else {
            k to centred(k, limit = false)
        }
    }
    if (limitKmh == null || limit == null) return ticks.map { it.second }
    val kept = ticks.filter { apart(it.second, limit, gap) }
    if (kept.lastOrNull()?.first == ShiftRules.MAX_KMH) return kept.map { it.second } + limit

    val limitUnit = unitAt(limitKmh, limit = true)
    if (kept.all { apart(it.second, limitUnit, gap) }) return kept.map { it.second } + limitUnit
    for (i in kept.indices.reversed()) {
        val label = unitAt(kept[i].first, limit = false)
        val before = kept.getOrNull(i - 1)?.second
        val after = kept.getOrNull(i + 1)?.second
        val room = apart(label, limit, gap) &&
            (before == null || apart(label, before, gap / 2f)) && (after == null || apart(label, after, gap / 2f))
        if (room) return kept.mapIndexed { j, t -> if (j == i) label else t.second } + limit
    }
    return kept.map { it.second } + limit
}

/**
 * Axis labels 0, 10, 20, 30, 40 km/h under the stack; the assist limit in signal at its own place, 8 dp clear of the others.
 * When "40 km/h" has no room beside the limit, the unit follows the limit or the last tick with room ([shiftAxisLabels]).
 */
@Composable
fun ShiftAxis(limitKmh: Int?, startInset: Dp, modifier: Modifier = Modifier, endInset: Dp = 0.dp) {
    val c = S3.colors
    val measurer = rememberTextMeasurer()
    val style = S3.type.caption
    val height = with(LocalDensity.current) { style.lineHeight.toDp() }
    val limitCd = limitKmh?.let { stringResource(R.string.shift_limit_cd, it) }
    // "40 km/h", and the one a tick or the limit takes when the end label has no room
    val withUnit = (listOfNotNull(limitKmh) + (0..ShiftRules.MAX_KMH step 10)).associateWith { stringResource(R.string.shift_axis_kmh, it) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { if (limitCd != null) contentDescription = limitCd },
    ) {
        val x0 = startInset.toPx()
        val w = size.width - x0 - endInset.toPx()
        val measured = HashMap<String, TextLayoutResult>()
        fun layout(text: String) = measured.getOrPut(text) { measurer.measure(text, style) }
        val labels = shiftAxisLabels(limitKmh, x0, w, size.width, AXIS_LABEL_GAP.toPx(), { withUnit[it] ?: "$it" }) {
            layout(it).size.width.toFloat()
        }
        for (l in labels) drawText(layout(l.text), if (l.limit) c.signal else c.fog, Offset(l.left, 0f))
    }
}

/** What must stand clear between the limit's label and any other label of the axis, on both sides. */
private val AXIS_LABEL_GAP = 8.dp

// --- Profile row ---------------------------------------------------------------------------------

/**
 * One radio row of the profile stack. Compact: name, caption and values above a 16 dp strip. Wide:
 * name in a 96 dp column, the rest beside it on an 18 dp strip. The strip is always the row's last
 * line, so the axis can sit right under the last one. Keeps full colour while riding so the live
 * notch stays readable.
 */
@Composable
fun ShiftProfileRow(
    name: String,
    points: ShiftPoints?,
    selected: Boolean,
    enabled: Boolean,
    compact: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    captionColor: Color = S3.colors.fog,
    values: String? = null,
    notes: List<Pair<String, Color>> = emptyList(),
    showConflicts: Boolean = false,
    sending: Boolean = false,
    currentGear: Int? = null,
    speedKmh: Int? = null,
    limitKmh: Int? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = S3.colors
    val nameColor = if (selected) c.paint else c.fog
    @Composable
    fun Name(maxLines: Int, mod: Modifier) = Text(
        name,
        style = S3.type.bodyStrong,
        color = nameColor,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = mod.clearAndSetSemantics { },
    )

    @Composable
    fun Details(stripHeight: Dp) {
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            caption?.let { Text(keepUnits(it), style = S3.type.caption, color = captionColor) }
            values?.let { Text(keepUnits(it), style = S3.type.caption, color = c.paint) }
            for ((text, color) in notes) Text(keepUnits(text), style = S3.type.caption, color = color)
        }
        if (points != null) {
            Spacer(Modifier.height(2.dp))
            ShiftBand(points, selected, if (selected) currentGear else null, speedKmh, limitKmh, stripHeight, showConflicts = showConflicts)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 52.dp else 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                if (actions.isNotEmpty()) customActions = actions
            }
            .padding(vertical = if (compact) 6.dp else 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Name(1, Modifier.weight(1f))
                if (sending) ShellSending()
                trailing?.invoke()
            }
            Spacer(Modifier.height(2.dp))
            Details(16.dp)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(WideNameColumn).padding(end = S3.s)) {
                    Name(2, Modifier)
                    trailing?.let {
                        Spacer(Modifier.height(S3.xs))
                        it()
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (sending) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ShellSending() }
                    Details(18.dp)
                }
            }
        }
    }
}

// --- Eigene editor -------------------------------------------------------------------------------

private val EditorPad = 10.dp
private val EditorStrip = 40.dp
private val TriangleHeight = 12.dp
private val TriangleWidth = 14.dp

/** Handle labels follow the font scale up to this factor; beyond it the drawing would not fit the lanes. */
private const val MaxLabelScale = 1.3f

/**
 * Six handles on the shared axis: up points above the strip (filled led triangles pointing down),
 * down points below (outlined paint triangles pointing up). Dragging is the coarse input, the
 * stepper under it the precise one. Every value is clamped by [ShiftRules] while it moves, so the
 * draft is always valid. Nothing is sent before "Übernehmen".
 */
@Composable
fun ShiftEditor(
    draft: ShiftPoints,
    onDraft: (ShiftPoints) -> Unit,
    limitKmh: Int?,
    canApply: Boolean,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
    current: ShiftPoints? = null,
    region: SpeedLimit? = null,
    fromStandard: Boolean = false,
    selected: Handle? = null,
    onSelect: (Handle?) -> Unit = {},
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val reduce = rememberReducedMotion()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val digits = rememberDigits(EditorStrip)
    val dataStyle = S3.type.data
    val captionStyle = S3.type.caption
    // In sp, so they follow the system font size (capped: the lanes have to hold them).
    val scale = (minOf(density.fontScale, MaxLabelScale) / density.fontScale)
    val valueStyle = remember(dataStyle, scale) { (17 * scale).sp.let { dataStyle.copy(fontSize = it, lineHeight = it) } }
    val laneStyle = remember(captionStyle, scale) { (12 * scale).sp.let { captionStyle.copy(fontSize = it, lineHeight = it) } }
    val valueH = remember(measurer, valueStyle, density) { with(density) { measurer.measure("40", valueStyle).size.height.toDp() } }
    val laneH = remember(measurer, laneStyle, density) { with(density) { measurer.measure("hoch", laneStyle).size.height.toDp() } }
    val valueRow = max(22.dp, valueH + 6.dp)
    val lane = max(28.dp, TriangleHeight + laneH + 6.dp)

    val laneUp = stringResource(R.string.shift_lane_up)
    val laneDown = stringResource(R.string.shift_lane_down)
    val higher = stringResource(R.string.shift_step_higher)
    val lower = stringResource(R.string.shift_step_lower)
    val labels = Handle.entries.associateWith { h ->
        stringResource(if (h.up) R.string.shift_handle_up else R.string.shift_handle_down, h.from, h.to)
    }

    val draftState = rememberUpdatedState(draft)
    val onDraftState = rememberUpdatedState(onDraft)
    val onSelectState = rememberUpdatedState(onSelect)
    // The handle whose position stopped a drag, outlined in lamp for 600 ms (until the next touch with reduced motion).
    var flash by remember { mutableStateOf<Handle?>(null) }
    var flashKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(flashKey) {
        if (flashKey > 0 && !reduce) {
            delay(600)
            flash = null
        }
    }

    fun step(h: Handle, delta: Int) {
        val p = draftState.value
        val next = ShiftRules.clamp(h, p, p[h] + delta)
        if (next != p) {
            haptics.tick()
            onDraftState.value(next)
        }
    }

    val drawingHeight = valueRow * 2 + lane * 2 + EditorStrip
    Column(modifier.fillMaxWidth()) {
        Hint(stringResource(R.string.shift_editor_hint))
        Spacer(Modifier.height(S3.s))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(drawingHeight)
                .semantics { isTraversalGroup = true }
                .pointerInput(valueRow, lane) {
                    val pad = EditorPad.toPx()
                    val stripCentre = (valueRow + lane + EditorStrip / 2).toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (reduce) flash = null
                        val stripW = size.width - 2 * pad
                        fun xOf(v: Int) = pad + v / AXIS * stripW
                        val laneHandles = Handle.entries.filter { it.up == (down.position.y < stripCentre) }
                        val start = draftState.value
                        val h = laneHandles.minBy { abs(xOf(start[it]) - down.position.x) }
                        var local = start
                        var atBound = false
                        fun moveTo(x: Float) {
                            val want = ((x - pad) / stripW * AXIS).roundToInt()
                            val range = ShiftRules.range(h, local)
                            val next = ShiftRules.clamp(h, local, want)
                            if (next != local) {
                                local = next
                                haptics.tick()
                                onDraftState.value(next)
                            }
                            val outside = !range.isEmpty() && (want > range.last || want < range.first)
                            if (outside && !atBound) {
                                haptics.reject()
                                flash = ShiftRules.blocker(h, local, upward = want > range.last)
                                flashKey++
                            }
                            atBound = outside
                        }
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (drag != null) {
                            onSelectState.value(h)
                            moveTo(drag.position.x)
                            horizontalDrag(drag.id) { change ->
                                moveTo(change.position.x)
                                change.consume()
                            }
                        } else {
                            val last = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (last != null && !last.pressed && !last.isConsumed) {
                                haptics.tick()
                                onSelectState.value(h)
                            }
                        }
                    }
                },
        ) {
            val boxWidth = maxWidth
            Canvas(Modifier.fillMaxWidth().height(drawingHeight)) {
                val pad = EditorPad.toPx()
                val w = size.width - 2 * pad
                val valueRowPx = valueRow.toPx()
                val lanePx = lane.toPx()
                val stripTop = valueRowPx + lanePx
                val stripH = EditorStrip.toPx()
                val stripBottom = stripTop + stripH
                fun x(v: Int) = pad + v.coerceIn(0, ShiftRules.MAX_KMH) / AXIS * w
                drawGearStrip(
                    x0 = pad, y0 = stripTop, w = w, h = stripH,
                    b = FloatArray(6) { draft[Handle.entries[it]].toFloat() },
                    selected = true, gear = null, cursor = null, limit = limitKmh,
                    c = c, digits = digits,
                )
                val tw = TriangleWidth.toPx()
                val th = TriangleHeight.toPx()
                // Lane names at the left, unless a handle sits there.
                val gapPx = 4.dp.toPx()
                val upName = measurer.measure(laneUp, laneStyle)
                if (upName.size.width + gapPx < x(draft.up12) - tw / 2) {
                    drawText(upName, c.fog, Offset(0f, valueRowPx + (lanePx - th - upName.size.height) / 2f))
                }
                val downName = measurer.measure(laneDown, laneStyle)
                if (downName.size.width + gapPx < x(draft.down21) - tw / 2) {
                    drawText(downName, c.fog, Offset(0f, stripBottom + th + (lanePx - th - downName.size.height) / 2f))
                }
                for (h in Handle.entries) {
                    val hx = x(draft[h])
                    val isSel = h == selected
                    // The selected point is drawn larger: its triangle, its value, and a cut through the strip.
                    val k = if (isSel) 1.4f else 1f
                    val tip = if (h.up) stripTop else stripBottom
                    val base = if (h.up) tip - th * k else tip + th * k
                    val tri = Path().apply {
                        moveTo(hx - tw * k / 2, base)
                        lineTo(hx + tw * k / 2, base)
                        lineTo(hx, tip)
                        close()
                    }
                    if (isSel) drawRect(c.paint, Offset(hx - 0.75.dp.toPx(), stripTop), Size(1.5.dp.toPx(), stripH))
                    // Up and down differ in shape as well as colour: filled led above, outlined paint below.
                    if (h.up) {
                        drawPath(tri, c.led)
                    } else {
                        drawPath(tri, c.paint, style = Stroke(if (isSel) 2.5.dp.toPx() else 1.75.dp.toPx()))
                    }
                    if (h == flash) drawPath(tri, c.lamp, style = Stroke(2.dp.toPx()))
                    val text = measurer.measure("${draft[h]}", valueStyle)
                    val left = (hx - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                    val top = if (h.up) (valueRowPx - text.size.height) / 2f else stripBottom + lanePx + (valueRowPx - text.size.height) / 2f
                    drawText(text, if (selected == null || isSel) c.paint else c.fog, Offset(left, top))
                    if (isSel) {
                        val uy = top + text.size.height + 1.dp.toPx()
                        drawLine(c.paint, Offset(left, uy), Offset(left + text.size.width, uy), strokeWidth = 2.dp.toPx())
                    }
                }
            }
            // TalkBack: one adjustable node per handle; touch is handled by the parent.
            for (h in Handle.entries) {
                val v = draft[h]
                val range = ShiftRules.range(h, draft)
                val hx = EditorPad + (boxWidth - EditorPad * 2) * (v.coerceIn(0, ShiftRules.MAX_KMH) / AXIS)
                val hy = if (h.up) valueRow + lane - TriangleHeight / 2 else valueRow + lane + EditorStrip + TriangleHeight / 2
                val label = labels.getValue(h)
                val kmh = stringResource(R.string.shift_axis_kmh, v)
                Box(
                    Modifier
                        .offset(x = hx - 12.dp, y = hy - 12.dp)
                        .size(24.dp)
                        .semantics {
                            contentDescription = label
                            stateDescription = kmh
                            traversalIndex = h.ordinal.toFloat()
                            if (!range.isEmpty()) {
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    v.toFloat(),
                                    range.first.toFloat()..range.last.toFloat(),
                                    steps = (range.count() - 2).coerceAtLeast(0),
                                )
                                setProgress { target ->
                                    val p = draftState.value
                                    val next = ShiftRules.clamp(h, p, target.roundToInt())
                                    if (next != p) onDraftState.value(next)
                                    onSelectState.value(h)
                                    true
                                }
                            }
                            customActions = listOf(
                                CustomAccessibilityAction(higher) { step(h, +1); true },
                                CustomAccessibilityAction(lower) { step(h, -1); true },
                            )
                        },
                )
            }
        }
        // The editor's own axis, inset like its strip, so the labels sit under the handles.
        ShiftAxis(limitKmh = limitKmh, startInset = EditorPad, endInset = EditorPad)

        if (selected != null) {
            val v = draft[selected]
            val range = ShiftRules.range(selected, draft)
            Spacer(Modifier.height(S3.m))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(labels.getValue(selected), style = S3.type.body, color = c.paint)
                    Text(stringResource(R.string.shift_axis_kmh, v), style = S3.type.dataLarge, color = c.paint, maxLines = 1)
                }
                QuietButton(
                    "−",
                    onClick = { step(selected, -1) },
                    enabled = !range.isEmpty() && v > range.first,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = lower },
                )
                Spacer(Modifier.width(S3.s))
                QuietButton(
                    "+",
                    onClick = { step(selected, +1) },
                    enabled = !range.isEmpty() && v < range.last,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = higher },
                )
            }
            if (!range.isEmpty()) {
                Spacer(Modifier.height(S3.xs))
                Hint(stringResource(R.string.shift_allowed, range.first, range.last))
                val blocker = (if (v >= range.last) ShiftRules.blocker(selected, draft, upward = true) else null)
                    ?: (if (v <= range.first) ShiftRules.blocker(selected, draft, upward = false) else null)
                blocker?.let { Hint(stringResource(R.string.shift_blocked_by, labels.getValue(it))) }
            }
        }

        Spacer(Modifier.height(S3.m))
        Hint(stringResource(R.string.shift_rules))
        if (limitKmh != null && draft.up34 >= limitKmh) {
            Spacer(Modifier.height(S3.xs))
            Hint(stringResource(R.string.shift_top_gear_above_limit, draft.up34, limitKmh), color = c.lamp)
        }
        // One seed caption at a time: "from Standard" while the draft is still that seed.
        val match = ShiftProfiles.match(draft, region)
        val seedNote = when {
            fromStandard && match == ShiftProfile.STANDARD -> stringResource(R.string.shift_draft_from_standard)
            match != null -> stringResource(R.string.shift_draft_matches, stringResource(match.labelRes))
            else -> null
        }
        seedNote?.let {
            Spacer(Modifier.height(S3.xs))
            Hint(it)
        }

        Spacer(Modifier.height(S3.m))
        val applyEnabled = draft != current && canApply
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (wide || maxWidth >= ButtonsInRow) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BigButton(stringResource(R.string.shift_apply), onClick = onApply, enabled = applyEnabled, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(S3.s))
                    QuietButton(stringResource(R.string.shift_discard), onClick = onDiscard, modifier = Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    BigButton(stringResource(R.string.shift_apply), onClick = onApply, enabled = applyEnabled)
                    Spacer(Modifier.height(S3.s))
                    QuietButton(stringResource(R.string.shift_discard), onClick = onDiscard, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// --- Manual: handlebar gestures --------------------------------------------------------------------

/**
 * The two handlebar gestures as small diagrams whose keys light while the rider holds them (BUTTONS
 * 5568). The keys are drawn as physical keys — outlined, with a key-travel edge and an LED dot — so
 * they never read as buttons on the screen.
 */
@Composable
fun ShiftKeyCaps(bell: Boolean, boost: Boolean, modifier: Modifier = Modifier) {
    val c = S3.colors
    val bellName = stringResource(R.string.shift_key_bell)
    val boostName = stringResource(R.string.shift_key_boost)
    val twice = stringResource(R.string.shift_gesture_twice)
    val hold = stringResource(R.string.shift_gesture_hold)
    val pressed = stringResource(R.string.shift_key_pressed)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.m)) {
        Text(stringResource(R.string.shift_keys_live), style = S3.type.caption, color = c.fog)
        GestureRow(
            sentence = stringResource(R.string.shift_manual_up),
            result = stringResource(R.string.shift_lane_up),
            state = if (boost) "$boostName $pressed" else null,
        ) {
            KeyCap(boostName, boost)
            GestureMark(twice)
        }
        GestureRow(
            sentence = stringResource(R.string.shift_manual_down),
            result = stringResource(R.string.shift_lane_down),
            state = listOfNotNull(bellName.takeIf { bell }, boostName.takeIf { boost }).takeIf { it.isNotEmpty() }
                ?.joinToString(" + ", postfix = " $pressed"),
        ) {
            KeyCap(bellName, bell)
            GestureMark(hold)
            GestureMark("+")
            KeyCap(boostName, boost)
            GestureMark(twice)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GestureRow(sentence: String, result: String, state: String?, keys: @Composable () -> Unit) {
    val c = S3.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = sentence
                if (state != null) stateDescription = state
            },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(S3.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            keys()
            GestureMark("→")
            Text(result, style = S3.type.bodyStrong, color = c.paint)
        }
        Spacer(Modifier.height(S3.xs))
        Text(sentence, style = S3.type.caption, color = c.fog)
    }
}

@Composable
private fun GestureMark(text: String) {
    Text(text, style = S3.type.body, color = S3.colors.fog)
}

@Composable
private fun KeyCap(label: String, lit: Boolean) {
    val c = S3.colors
    val reduce = rememberReducedMotion()
    val edge by animateColorAsState(if (lit) c.led else c.rib, if (reduce) snap() else spring(), label = "capEdge")
    val dot by animateColorAsState(if (lit) c.led else c.ledOff, if (reduce) snap() else spring(), label = "capDot")
    val text by animateColorAsState(if (lit) c.paint else c.fog, if (reduce) snap() else spring(), label = "capText")
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .drawBehind {
                // Key travel: a heavier bottom edge, as on the real rubber keys.
                val r = 7.dp.toPx()
                drawRoundRect(edge, Offset(0f, 2.dp.toPx()), Size(size.width, size.height - 2.dp.toPx()), CornerRadius(r))
            }
            .padding(bottom = 2.dp)
            .clip(shape)
            .background(c.asphalt)
            .border(if (lit) 2.dp else 1.5.dp, edge, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(label, style = S3.type.label, color = text, maxLines = 1)
    }
}
