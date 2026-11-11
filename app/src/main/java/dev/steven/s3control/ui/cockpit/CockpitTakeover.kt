package dev.steven.s3control.ui.cockpit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ui.Destination
import dev.steven.s3control.ui.LocalNavigator
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.LedDot
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.ShellCommandError
import dev.steven.s3control.ui.components.ShellFitText
import dev.steven.s3control.ui.components.ShellSignalLine
import dev.steven.s3control.ui.components.UnlockSlider
import dev.steven.s3control.ui.components.WorkingBigButton
import dev.steven.s3control.ui.components.rememberReducedMotion
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.delay

/*
 * The takeover: a shipping-mode or kick-lock-while-riding event replaces the cockpit. Phase 1 is the
 * moment, read-only while riding: red field, the glyph where the speed was, one headline, one line
 * and the evidence of the last 12 s. Its big capsule sets the moment's mark and acknowledges; while
 * the bike moves that hands the cockpit back (with the memory line), and phase 2 waits for the stop.
 * Phase 2 is what to do once stopped: steps, the rule signals before the trigger, the situation, and
 * the one action that saves the evidence.
 */

@Composable
internal fun CockpitTakeover(vm: AppViewModel, v: CockpitView, t: TakeoverView, plan: CockpitPlan, close: () -> Unit) {
    if (t.phase == 1) {
        when (val w = plan.window) {
            CockpitWindow.CoverPortrait -> Phase1Portrait(vm, t, plan)
            CockpitWindow.CoverLandscape -> Phase1Landscape(vm, t, plan)
            is CockpitWindow.Book -> Phase1Book(vm, t, plan, w)
            is CockpitWindow.Table -> Phase1Table(vm, t, plan, w)
        }
    } else {
        when (val w = plan.window) {
            CockpitWindow.CoverPortrait -> Phase2Portrait(vm, t, plan, close)
            CockpitWindow.CoverLandscape -> Phase2Landscape(vm, t, plan, close)
            is CockpitWindow.Book -> Phase2Book(vm, t, plan, w, close)
            is CockpitWindow.Table -> Phase2Table(vm, t, plan, w, close)
        }
    }
}

/** Paints the status bar strip signal while the phase-2 band sits under it. */
@Composable
internal fun TakeoverTopInset(height: Dp) {
    Box(Modifier.fillMaxWidth().height(height).background(S3.colors.signal))
}

private fun glyphFor(t: TakeoverView): IntArray = when (t.variant) {
    TakeoverVariant.SHIPPING -> CockpitFrames.ship()
    TakeoverVariant.KICKLOCK -> CockpitFrames.locked()
    TakeoverVariant.ASLEEP, TakeoverVariant.OFF -> CockpitFrames.code(t.snapshot.faults.minOrNull())
}

/**
 * The variant's glyph, lit onSignal on the red field. In phase 1 it blinks three times (500 ms on,
 * 500 ms off) right after the trigger, then stays; steady under reduced motion.
 */
@Composable
private fun TakeoverGlyph(t: TakeoverView, pitch: Dp, blink: Boolean, modifier: Modifier = Modifier) {
    val c = S3.colors
    val reduce = rememberReducedMotion()
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(t.variant, t.snapshot.atMillis, blink, reduce) {
        alpha.snapTo(1f)
        if (!blink || reduce || System.currentTimeMillis() - t.snapshot.atMillis > 3_000) return@LaunchedEffect
        repeat(3) {
            alpha.snapTo(1f)
            delay(500)
            alpha.snapTo(0f)
            delay(500)
        }
        alpha.snapTo(1f)
    }
    MatrixField(
        columns = remember(t.variant, t.snapshot) { glyphFor(t) },
        contentClass = 100 + t.variant.ordinal,
        lit = c.onSignal,
        pitch = pitch,
        modifier = modifier.clearAndSetSemantics { },
        unlit = Color.Transparent,
        glow = Color.Transparent,
        litAlpha = { alpha.value },
    )
}

/** The headline: TalkBack hears it once, at once (assertive). It never changes while shown. */
@Composable
private fun TakeoverHeadline(t: TakeoverView, style: TextStyle, lines: Int, minSize: androidx.compose.ui.unit.TextUnit = 26.sp) {
    val title = variantHeadline(t.variant)
    // One node: the heading is read once, not once for the box and again for its text.
    Box(
        Modifier.clearAndSetSemantics {
            heading()
            contentDescription = title
            liveRegion = LiveRegionMode.Assertive
        },
    ) {
        ShellFitText(title, style = style, color = S3.colors.onSignal, maxLines = lines, minFontSize = minSize)
    }
}

/**
 * The line under the headline. On screen the shipping countdown ticks; TalkBack reads the state
 * (counting, any moment, off) and announces only a change of state, politely.
 */
@Composable
private fun TakeoverLine(t: TakeoverView, style: TextStyle, lines: Int, modifier: Modifier = Modifier) {
    val spoken = takeoverSpoken(t)
    Text(
        takeoverLine(t),
        style = style,
        color = S3.colors.onSignal,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spoken
            liveRegion = LiveRegionMode.Polite
        },
    )
}

/** The evidence of the last 12 s (strong) and the frozen moment. */
@Composable
private fun TakeoverFacts(
    t: TakeoverView,
    modifier: Modifier = Modifier,
    evidenceStyle: TextStyle = S3.type.bodyStrong.copy(fontSize = 18.sp, lineHeight = 24.sp),
    snapStyle: TextStyle = S3.type.body,
    lines: Int = 3,
) {
    val c = S3.colors
    val clock = rememberClockFormats()
    val evidence = evidenceLine(t.evidence)
    Column(modifier) {
        if (evidence != null) {
            Text(evidence, style = evidenceStyle, color = c.onSignal, maxLines = lines, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
        }
        Text(snapshotLine(t.snapshot, clock), style = snapStyle, color = c.onSignal, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "Verstanden" exactly where the mark capsule was, and it marks too: the first tap sets the
 * moment's mark (even in the first 1.5 s, when it does nothing else), so slapping the big button
 * at the moment never loses it. The second line says which mark.
 */
@Composable
private fun TakeoverCapsule(vm: AppViewModel, t: TakeoverView, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val c = S3.colors
    val big = width >= 300.dp && height >= 140.dp
    val label = stringResource(R.string.cockpit_to_ack)
    val second = if (t.markNo > 0) {
        stringResource(R.string.cockpit_mark_spoken, t.markNo)
    } else {
        stringResource(R.string.cockpit_to_ack_marks, t.nextMarkNo)
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .border(if (t.markNo > 0) 4.dp else 2.dp, c.onSignal, shape)
            .clickable(role = Role.Button) { vm.cockpitTakeoverTap() }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                stateDescription = second
                onClick(label) { vm.cockpitTakeoverTap(); true }
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FitLine(
                label,
                (if (big) S3.type.display else S3.type.title).copy(textAlign = TextAlign.Center),
                c.onSignal,
                if (big) 26.sp else 20.sp,
                Modifier.fillMaxWidth(),
            )
            if (height >= 96.dp) {
                Spacer(Modifier.height(4.dp))
                FitLine(second, S3.type.body.copy(textAlign = TextAlign.Center, fontFeatureSettings = TNUM), c.onSignal, 13.sp, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The band's red reaches past its own edges into the system bar insets, so the field meets the
 * screen edge. The flags pick which edges bleed (never the one facing the asphalt).
 */
private fun Modifier.bleed(color: Color, left: Boolean = true, top: Boolean = true, right: Boolean = false, bottom: Boolean = true): Modifier =
    drawBehind {
        val ext = 120.dp.toPx()
        val l = if (left) -ext else 0f
        val t = if (top) -ext else 0f
        val r = size.width + if (right) ext else 0f
        val b = size.height + if (bottom) ext else 0f
        drawRect(color, topLeft = Offset(l, t), size = Size(r - l, b - t))
    }

private val LineBig = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)

// --- Phase 1 per window -----------------------------------------------------------------------------

@Composable
private fun Phase1Portrait(vm: AppViewModel, t: TakeoverView, plan: CockpitPlan) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = plan.gutter.dp)
            .padding(top = plan.top.dp, bottom = plan.bottom.dp),
    ) {
        // The status row stays empty, so the glyph lands exactly on the speed matrix.
        Spacer(Modifier.height((plan.statusH + plan.gap).dp))
        TakeoverGlyph(t, plan.pitch.dp, blink = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            TakeoverHeadline(t, S3.type.display, 2)
            Spacer(Modifier.height(8.dp))
            TakeoverLine(t, S3.type.body.merge(LineBig), 3)
            Spacer(Modifier.height(12.dp))
            TakeoverFacts(t)
        }
        Spacer(Modifier.height(plan.gap.dp))
        TakeoverCapsule(vm, t, plan.capsuleW.dp, plan.capsuleH.dp, Modifier.fillMaxWidth().height(plan.capsuleH.dp))
    }
}

@Composable
private fun Phase1Landscape(vm: AppViewModel, t: TakeoverView, plan: CockpitPlan) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = plan.gutter.dp)
            .padding(top = plan.top.dp, bottom = plan.bottom.dp),
    ) {
        Column(Modifier.width(plan.columnW.dp)) {
            Spacer(Modifier.height((plan.statusH + 4f).dp))
            TakeoverGlyph(t, plan.pitch.dp, blink = true)
        }
        Spacer(Modifier.width(plan.gap.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                TakeoverHeadline(t, S3.type.display, 1)
                Spacer(Modifier.height(4.dp))
                TakeoverLine(t, S3.type.body.copy(fontSize = 20.sp, lineHeight = 24.sp), 2)
                Spacer(Modifier.height(8.dp))
                TakeoverFacts(
                    t,
                    evidenceStyle = S3.type.bodyStrong.copy(lineHeight = 20.sp),
                    snapStyle = S3.type.body.copy(lineHeight = 20.sp),
                    lines = 2,
                )
            }
            Spacer(Modifier.height(12.dp))
            TakeoverCapsule(vm, t, plan.capsuleW.dp, plan.capsuleH.dp, Modifier.fillMaxWidth().height(plan.capsuleH.dp))
        }
    }
}

/** The countdown or the line under the headline on the inner screen: 28 sp, read across the room. */
private val LineInner = TextStyle(fontSize = 28.sp, lineHeight = 34.sp)

@Composable
private fun Phase1Book(vm: AppViewModel, t: TakeoverView, plan: CockpitPlan, w: CockpitWindow.Book) {
    val pad = plan.gutter.dp
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(w.splitX.dp).fillMaxHeight().padding(pad).clipToBounds()) {
            Spacer(Modifier.height((plan.statusH + 16f).dp))
            TakeoverGlyph(t, plan.pitch.dp, blink = true, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(24.dp))
            TakeoverHeadline(t, S3.type.display, 3)
            Spacer(Modifier.height(12.dp))
            TakeoverLine(t, S3.type.bodyStrong.merge(LineInner), 4)
        }
        Spacer(Modifier.width(w.hinge.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(pad)) {
            Spacer(Modifier.height((CockpitSizes.EXIT_ROW + plan.gap).dp))
            TakeoverFacts(t, Modifier.fillMaxWidth().weight(1f).clipToBounds(), lines = 4)
            TakeoverCapsule(vm, t, plan.capsuleW.dp, plan.capsuleH.dp, Modifier.fillMaxWidth().height(plan.capsuleH.dp))
        }
    }
}

@Composable
private fun Phase1Table(vm: AppViewModel, t: TakeoverView, plan: CockpitPlan, w: CockpitWindow.Table) {
    val pad = plan.gutter.dp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(w.splitY.dp).padding(pad)) {
            Column(Modifier.width(plan.columnW.dp)) {
                Spacer(Modifier.height((plan.statusH + 4f).dp))
                TakeoverGlyph(t, plan.pitch.dp, blink = true)
            }
            Spacer(Modifier.width(plan.gap.dp))
            // Headline and countdown together, on the standing half.
            Column(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                Spacer(Modifier.height(CockpitSizes.EXIT_ROW.dp))
                TakeoverHeadline(t, S3.type.display, 2)
                Spacer(Modifier.height(12.dp))
                TakeoverLine(t, S3.type.bodyStrong.merge(LineInner), 3)
            }
        }
        Spacer(Modifier.height(w.hinge.dp))
        Row(Modifier.fillMaxWidth().weight(1f).padding(pad)) {
            TakeoverFacts(t, Modifier.width(plan.columnW.dp).fillMaxHeight().clipToBounds(), lines = 4)
            Spacer(Modifier.width(plan.gap.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Spacer(Modifier.weight(1f))
                TakeoverCapsule(vm, t, plan.capsuleW.dp, plan.capsuleH.dp, Modifier.fillMaxWidth().height(plan.capsuleH.dp))
            }
        }
    }
}

// --- Phase 2 pieces ---------------------------------------------------------------------------------

/** Headline, meta and evidence on red: the band that keeps the moment in view while acting. */
@Composable
private fun BandText(t: TakeoverView, headlineStyle: TextStyle, headlineLines: Int, modifier: Modifier = Modifier) {
    val c = S3.colors
    val clock = rememberClockFormats()
    val evidence = evidenceLine(t.evidence)
    Column(modifier) {
        Box(Modifier.semantics { heading() }) {
            ShellFitText(
                variantHeadline(t.variant),
                style = headlineStyle,
                color = c.onSignal,
                maxLines = headlineLines,
                minFontSize = 18.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(takeoverMeta(t, clock), style = S3.type.body, color = c.onSignal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (evidence != null) {
            Text(evidence, style = S3.type.bodyStrong, color = c.onSignal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Numbered steps; the number in fog, the text in paint. They never drop. */
@Composable
private fun Steps(t: TakeoverView, modifier: Modifier = Modifier) {
    val c = S3.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stepTexts(t.variant).forEachIndexed { i, s ->
            Row(Modifier.semantics(mergeDescendants = true) {}) {
                Text("${i + 1}", style = S3.type.data.copy(fontFeatureSettings = TNUM), color = c.fog, modifier = Modifier.width(28.dp))
                Text(s, style = S3.type.body.copy(fontSize = 18.sp, lineHeight = 26.sp), color = c.paint)
            }
        }
        if (SituationTag.STEERING in t.tags) {
            ShellSignalLine(stringResource(R.string.cockpit_tag_steering_warning))
        }
    }
}

@Composable
private fun Timeline(t: TakeoverView, modifier: Modifier = Modifier) {
    val clock = rememberClockFormats()
    SignalTimeline(t.timeline, stringResource(R.string.cockpit_to_timeline, clock.seconds(t.snapshot.atMillis)), modifier)
}

/** The frozen moment on asphalt (phase 2): fog, under the timeline. */
@Composable
private fun SnapshotNote(t: TakeoverView, modifier: Modifier = Modifier) {
    val clock = rememberClockFormats()
    Text(snapshotLine(t.snapshot, clock), style = S3.type.body, color = S3.colors.fog, modifier = modifier, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** What was going on: four tags, in [columns] per row. A tap writes one MARK line. */
@Composable
private fun Tags(vm: AppViewModel, t: TakeoverView, columns: Int, modifier: Modifier = Modifier) {
    val c = S3.colors
    val all = SituationTag.entries
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cockpit_to_situation), style = S3.type.bodyStrong, color = c.paint)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            all.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tag ->
                        TagButton(stringResource(tag.labelRes()), tag in t.tags, { vm.tagSituation(tag) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * The one action for the state the bike is in (connect, search, read the log, unlock), then "back
 * to the cockpit". Side by side in landscape. While the bike moves the log read is only announced:
 * it pauses polling, and the cockpit would lose its speed.
 */
@Composable
private fun TakeoverActions(vm: AppViewModel, t: TakeoverView, close: () -> Unit, sideBySide: Boolean, modifier: Modifier = Modifier) {
    val c = S3.colors
    val nav = LocalNavigator.current
    val clock = rememberClockFormats()
    val back = stringResource(R.string.cockpit_to_back)
    val dismiss = { vm.cockpitDismissTakeover() }
    val primary: @Composable (Modifier) -> Unit = { m ->
        when (t.primary) {
            TakeoverPrimary.CONNECT -> BigButton(stringResource(R.string.bike_prompt_connect), { vm.preferredBike()?.let(vm::connect) }, m)
            TakeoverPrimary.RETRY -> BigButton(stringResource(R.string.bike_prompt_retry), { vm.bike.retryNow() }, m)
            TakeoverPrimary.READ_AT_STOP -> BigButton(stringResource(R.string.cockpit_to_read_at_stop), {}, m, enabled = false)
            TakeoverPrimary.READ_LOG, TakeoverPrimary.READING_LOG -> WorkingBigButton(
                stringResource(R.string.bike_log_read),
                stringResource(R.string.bike_log_reading_percent, t.logPercent ?: 0),
                busy = t.primary == TakeoverPrimary.READING_LOG,
                onClick = { vm.readBikeLog() },
                modifier = m,
            )
            TakeoverPrimary.LOG_OK -> BigButton(back, dismiss, m)
            TakeoverPrimary.LOG_FAILED -> BigButton(stringResource(R.string.bike_log_read_again), { vm.readBikeLog() }, m)
            TakeoverPrimary.UNLOCK -> {
                val state by vm.bikeState.collectAsStateWithLifecycle()
                Column(m) {
                    UnlockSlider(
                        locked = true,
                        busy = Op.UNLOCK in state.busy,
                        onUnlock = { vm.bike.clearCommandError(); vm.bike.unlock() },
                        onLock = null,
                    )
                    ShellCommandError(state.lastCommandError, onExpire = { vm.bike.clearCommandError() }, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    val below: @Composable () -> Unit = {
        when (t.primary) {
            TakeoverPrimary.CONNECT, TakeoverPrimary.RETRY -> Hint(stringResource(R.string.cockpit_to_search_hint))
            TakeoverPrimary.READ_AT_STOP -> Hint(stringResource(R.string.cockpit_to_read_at_stop_hint))
            TakeoverPrimary.LOG_OK -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LedDot(c.led)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.cockpit_to_log_saved, clock.seconds(t.logAtMillis), t.logLines.toString()),
                        style = S3.type.label,
                        color = c.paint,
                    )
                }
                QuietButton(stringResource(R.string.cockpit_to_diagnosis), { close(); nav.go(Destination.DIAGNOSIS) }, Modifier.fillMaxWidth())
            }
            TakeoverPrimary.LOG_FAILED -> Text(stringResource(R.string.bike_log_failed), style = S3.type.label, color = c.signal)
            else -> Unit
        }
    }
    val showBack = t.primary != TakeoverPrimary.LOG_OK
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sideBySide) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                primary(Modifier.weight(1f))
                if (showBack) QuietButton(back, dismiss, Modifier.weight(1f).height(58.dp))
            }
            below()
        } else {
            primary(Modifier.fillMaxWidth())
            below()
            if (showBack) QuietButton(back, dismiss, Modifier.fillMaxWidth())
        }
    }
}

/**
 * A column whose optional blocks give way when space runs out: [drops] holds, per child, 0 for
 * "never" or the order in which it goes (1 first). The last child sits at the bottom and never drops.
 * Dropped blocks are measured but not placed, so they are neither drawn nor read.
 */
@Composable
private fun DropColumn(drops: List<Int>, gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth, minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val g = gap.roundToPx()
        val keep = BooleanArray(placeables.size) { true }
        fun total(): Int {
            val kept = placeables.indices.filter { keep[it] }
            return kept.sumOf { placeables[it].height } + g * (kept.size - 1).coerceAtLeast(0)
        }
        val order = drops.withIndex().filter { it.value > 0 && it.index < placeables.size - 1 }.sortedBy { it.value }
        for (d in order) {
            if (total() <= constraints.maxHeight) break
            keep[d.index] = false
        }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else total()
        layout(constraints.maxWidth, height) {
            var y = 0
            val last = placeables.lastIndex
            for (i in 0 until last) {
                if (!keep[i]) continue
                placeables[i].place(0, y)
                y += placeables[i].height + g
            }
            if (last >= 0) placeables[last].place(0, maxOf(y, height - placeables[last].height))
        }
    }
}

// --- Phase 2 per window -----------------------------------------------------------------------------

@Composable
private fun Phase2Portrait(vm: AppViewModel, t: TakeoverView, plan: CockpitPlan, close: () -> Unit) {
    val c = S3.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.signal)
                .padding(horizontal = plan.gutter.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TakeoverGlyph(t, 10.dp, blink = false)
            Spacer(Modifier.width(16.dp))
            BandText(t, S3.type.title, 2, Modifier.weight(1f))
        }
        DropColumn(
            drops = listOf(0, 1, 2, 0),
            gap = 20.dp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = plan.gutter.dp)
                .padding(top = 16.dp, bottom = plan.bottom.dp),
        ) {
            Steps(t)
            Timeline(t)
            Tags(vm, t, columns = 2)
            TakeoverActions(vm, t, close, sideBySide = false)
        }
    }
}

@Composable
private fun Phase2Landscape(vm: AppViewModel, t: TakeoverView, plan: CockpitPlan, close: () -> Unit) {
    val c = S3.colors
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width((plan.gutter + plan.columnW).dp)
                .fillMaxHeight()
                .bleed(c.signal)
                .padding(start = plan.gutter.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                .clipToBounds(),
        ) {
            TakeoverGlyph(t, 14.dp, blink = false)
            Spacer(Modifier.height(12.dp))
            BandText(t, S3.type.title, 2)
        }
        DropColumn(
            drops = listOf(0, 2, 0),
            gap = 12.dp,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = plan.gutter.dp, vertical = 12.dp),
        ) {
            Steps(t)
            Tags(vm, t, columns = 4)
            TakeoverActions(vm, t, close, sideBySide = true)
        }
    }
}

/**
 * Book: the band only as tall as glyph, headline and evidence, in the left pane; the timeline and
 * the frozen moment under it. Steps, situation and the action in the right pane.
 */
@Composable
private fun Phase2Book(vm: AppViewModel, t: TakeoverView, plan: CockpitPlan, w: CockpitWindow.Book, close: () -> Unit) {
    val c = S3.colors
    val pad = plan.gutter.dp
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(w.splitX.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().bleed(c.signal, bottom = false).padding(pad)) {
                TakeoverGlyph(t, 14.dp, blink = false)
                Spacer(Modifier.height(16.dp))
                BandText(t, S3.type.title, 2)
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(pad).clipToBounds()) {
                Timeline(t)
                Spacer(Modifier.height(16.dp))
                SnapshotNote(t)
            }
        }
        Spacer(Modifier.width(w.hinge.dp))
        DropColumn(
            drops = listOf(0, 1, 0),
            gap = 24.dp,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(pad),
        ) {
            Steps(t)
            Tags(vm, t, columns = 2)
            TakeoverActions(vm, t, close, sideBySide = false)
        }
    }
}

/**
 * Table: a band of glyph and headline (about 150 dp) across the standing half, the timeline in the
 * free room under it on the right and the frozen moment on the left; steps, situation and the
 * action on the lying half.
 */
@Composable
private fun Phase2Table(vm: AppViewModel, t: TakeoverView, plan: CockpitPlan, w: CockpitWindow.Table, close: () -> Unit) {
    val c = S3.colors
    val pad = plan.gutter.dp
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().height(w.splitY.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .bleed(c.signal, right = true, bottom = false)
                    .padding(horizontal = pad, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TakeoverGlyph(t, 14.dp, blink = false)
                Spacer(Modifier.width(24.dp))
                BandText(t, S3.type.title, 2, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = pad).padding(top = 16.dp, bottom = pad)) {
                SnapshotNote(t, Modifier.width(plan.columnW.dp))
                Spacer(Modifier.width(plan.gap.dp))
                Timeline(t, Modifier.weight(1f).clipToBounds())
            }
        }
        Spacer(Modifier.height(w.hinge.dp))
        Row(Modifier.fillMaxWidth().weight(1f).padding(pad)) {
            Steps(t, Modifier.width(plan.columnW.dp))
            Spacer(Modifier.width(plan.gap.dp))
            DropColumn(drops = listOf(1, 0), gap = 16.dp, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Tags(vm, t, columns = 2)
                TakeoverActions(vm, t, close, sideBySide = false)
            }
        }
    }
}
