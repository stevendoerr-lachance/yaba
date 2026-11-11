package dev.steven.s3control.ui.intro

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.steven.s3control.R
import dev.steven.s3control.ui.components.Haptics
import dev.steven.s3control.ui.components.Outbursts
import dev.steven.s3control.ui.components.rememberHaptics
import dev.steven.s3control.ui.components.rememberReducedMotion
import dev.steven.s3control.ui.components.talkBackOn
import dev.steven.s3control.ui.paneSplit
import dev.steven.s3control.ui.theme.NightRide
import dev.steven.s3control.ui.theme.S3
import kotlin.math.roundToInt

/*
 * The opening film: a giant boot stomps on a bike three times while its display blinks the error E42, the bike bursts
 * into its own dots, and comes back glowing. It plays on top of the composed app and ends on the first screen's own top
 * tube (the handoff) or, when there is none, on a slogan lockup (replay, or a rider who already has a bike).
 *
 * One clock drives everything (a float of ms in a state read only in draw), pausing when the app is stopped.
 * Haptics are cues on that clock, never chained delays. A tap, the Skip button or Back starts the reveal sweep at
 * once from the current frame; touches are consumed until it ends. Reduced motion and TalkBack get the strip.
 */

private enum class Ending { Handoff, Lockup }

/**
 * [resumeMs] is where the clock starts (a recreated activity resumes), [onClock] reports it every 250 ms so it
 * can be saved. [onSeen] fires when the reveal or a skip starts (or the strip first shows): only then the opening
 * counts as seen. [onFinished] is the hard cut to the app.
 */
@Composable
fun IntroFilm(
    resumeMs: Long,
    handoff: IntroHandoff,
    onClock: (Long) -> Unit,
    onSeen: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onReveal: () -> Unit = {},
) {
    val context = LocalContext.current
    val reduced = rememberReducedMotion()
    val talkBack = remember { talkBackOn(context) }
    if (reduced || talkBack) {
        IntroStrip(timed = !talkBack, onSeen = onSeen, onFinished = onFinished, modifier = modifier)
        return
    }
    FilmPlayer(resumeMs, handoff, onClock, onSeen, onFinished, onReveal, modifier)
}

@Composable
private fun FilmPlayer(
    resumeMs: Long,
    handoff: IntroHandoff,
    onClock: (Long) -> Unit,
    onSeen: () -> Unit,
    onFinished: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val c = NightRide
    val (_, frames) = rememberFilmKit()
    val haptics = rememberHaptics()
    val description = stringResource(R.string.intro_description)
    val skipLabel = stringResource(R.string.intro_skip)

    // The call may only be staged when the switch is on and nothing rides; haptics follow the drive lock only.
    val gate = remember {
        val driveLocked = Outbursts.driveLock(context).locked()
        Pair(Outbursts.policy(context).enabled && !driveLocked, !driveLocked)
    }
    val ouch = gate.first
    val hapticsOn = gate.second

    val clock = remember { mutableFloatStateOf(resumeMs.toFloat()) }
    val skipped = remember { mutableStateOf(false) }
    val revealMs = remember { mutableFloatStateOf(0f) }
    val ending = remember { mutableStateOf<Ending?>(null) }
    val slotKept = remember { mutableStateOf<Rect?>(null) }
    // where the film's canvas starts in the window: the slot is reported in window px, the canvas draws in its own
    val origin = remember { mutableStateOf(Offset.Zero) }
    val onSeenNow by rememberUpdatedState(onSeen)
    val onFinishedNow by rememberUpdatedState(onFinished)
    val onClockNow by rememberUpdatedState(onClock)
    val onRevealNow by rememberUpdatedState(onReveal)

    fun revealProgress(): Float = when {
        skipped.value -> revealMs.floatValue / Film.REVEAL_MS
        ending.value == Ending.Lockup -> Ease.ph(clock.floatValue, Film.T_LOCKUP_WIPE, Film.T_LOCKUP_END)
        else -> Ease.ph(clock.floatValue, Film.T_CUT, Film.T_END)
    }

    fun skip() {
        if (skipped.value || revealProgress() > 0f) return
        skipped.value = true
        revealMs.floatValue = 0f
    }

    // The clock. It runs only while the app is on screen, so a long stay in the background does not skip to the
    // end, and a frame that took long (the first one) never jumps the film ahead by more than 100 ms.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        var seenSent = false
        var revealSent = false
        var done = false
        var reported = 0L
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (!done) {
                val now = withFrameNanos { it }
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000f).coerceIn(0f, 100f)
                last = now
                if (skipped.value) revealMs.floatValue += dt else clock.floatValue += dt
                val t = clock.floatValue
                if (ending.value == null && t >= Film.T_DECIDE) {
                    ending.value = if (handoff.tubeSlot != null) Ending.Handoff else Ending.Lockup
                }
                // keep the last known slot: if a rotation hides the hero meanwhile, the film still lands (near)
                handoff.tubeSlot?.let { slotKept.value = it }
                if (!seenSent && (skipped.value || revealProgress() > 0f)) {
                    seenSent = true
                    onSeenNow()
                }
                // once the edge has crossed the middle of the screen the system bars may take the theme's own icons
                if (!revealSent && Ease.fastOutSlowIn(revealProgress()) >= 0.5f) {
                    revealSent = true
                    onRevealNow()
                }
                if (revealProgress() >= 1f) {
                    done = true
                    onFinishedNow()
                }
                if (now - reported > 250_000_000L) {
                    reported = now
                    onClockNow(t.toLong())
                }
            }
        }
    }

    // Haptic cues on the clock: a cue the clock has already passed by more than 120 ms (a resume, a jump) is skipped.
    LaunchedEffect(hapticsOn) {
        if (!hapticsOn) return@LaunchedEffect
        var next = Film.cues.indexOfFirst { it.at > resumeMs }.let { if (it < 0) Film.cues.size else it }
        androidx.compose.runtime.snapshotFlow { clock.floatValue }.collect { now ->
            while (next < Film.cues.size && Film.cues[next].at <= now) {
                val cue = Film.cues[next]
                if (now - cue.at <= Film.CUE_LATE_MS) fire(haptics, cue.kind)
                next++
            }
        }
    }

    BackHandler(enabled = true) { skip() }

    // the fold: the stage is the left pane only when the screen is split by a half-opened or occluding fold
    val activity = remember(context) { context.findActivity() }
    val layoutInfo by produceState<WindowLayoutInfo?>(initialValue = null, activity) {
        val a = activity ?: return@produceState
        WindowInfoTracker.getOrCreate(a).windowLayoutInfo(a).collect { value = it }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wide = maxWidth >= 600.dp
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // The stage is the whole window, also on the unfolded inner screen (a flat screen has no crease to hide behind).
        // Only a fold that really separates the screen (half-opened, or a hinge that occludes) makes it the left pane.
        val stageRight = if (wide) {
            val fold = layoutInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()
                ?.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL && it.isSeparating }
            if (fold != null) with(density) { paneSplit(fold, 0f, maxWidth, density).left.toPx() } else widthPx
        } else widthPx
        val stage = Stage(0f, 0f, stageRight, heightPx)
        // the exploded view is as tall as this stage's sky allows (a world is cached per step of the stretch)
        val world = FilmWorld.forStage(stage)
        val opts = remember(ouch, ending.value, slotKept.value, origin.value) {
            SceneOpts(
                ouch = ouch,
                lockup = ending.value == Ending.Lockup,
                slot = if (ending.value == Ending.Handoff) slotKept.value?.translate(-origin.value) else null,
            )
        }
        val showSkip by remember { derivedStateOf { clock.floatValue < Film.T_HANDOFF && !skipped.value } }

        // the film layer: everything right of the reveal edge; left of it is the real screen
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description; isTraversalGroup = true }
                .onGloballyPositioned { origin.value = it.positionInWindow() }
                .drawWithContent {
                    val edge = size.width * Ease.fastOutSlowIn(revealProgress())
                    clipRect(left = edge) {
                        drawRect(c.asphalt)
                        this@drawWithContent.drawContent()
                    }
                }
                .pointerInput(Unit) {
                    // consume every touch; a tap (after the first 600 ms) skips
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val up = waitForUpOrCancellation()
                        up?.consume()
                        if (up != null && clock.floatValue > Film.TAP_GRACE_MS) skip()
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawFilm(clock.floatValue, stage, c, world, frames, opts)
            }
            if (ending.value == Ending.Lockup) Lockup(stage, clock)
        }

        if (showSkip) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 4.dp, end = 8.dp)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { traversalIndex = -1f }
                    .clickable(role = Role.Button) { skip() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(skipLabel, style = S3.type.label, color = c.fog)
            }
        }
    }
}

/** The slogan lockup A, local language, under the bike: line 1 the Klappe (800/125), line 2 the Hand (400/100). */
@Composable
private fun Lockup(stage: Stage, clock: State<Float>) {
    val c = NightRide
    val density = LocalDensity.current
    val k0 = stage.k0
    val left = stage.centerX + k0 * (Geo.TUBE_L + Geo.PAD_X)
    val top = stage.top + Camera.tableauGf(stage) * stage.height + with(density) { 36.dp.toPx() }
    // the Hand: the body face at the title's size (Archivo 400/100, or Roboto Flex in Russian)
    val line2 = S3.type.body.copy(fontSize = S3.type.title.fontSize, lineHeight = S3.type.title.lineHeight)
    Column(
        Modifier
            .absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .widthIn(max = with(density) { (stage.right - left - 12.dp.toPx()).toDp() })
            .drawWithContent {
                val p = Ease.fastOutSlowIn(Ease.ph(clock.value, Film.LOCKUP_TEXT_FROM, Film.LOCKUP_TEXT_TO))
                clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
            },
    ) {
        Text(stringResource(R.string.intro_slogan_1), style = S3.type.title, color = c.paint)
        Text(stringResource(R.string.intro_slogan_2), style = line2, color = c.paint)
    }
}

private fun fire(haptics: Haptics, kind: Film.Haptic) {
    when (kind) {
        Film.Haptic.Tick -> haptics.tick()
        Film.Haptic.Ramp -> haptics.ramp()
        Film.Haptic.Thud -> haptics.thud()
        Film.Haptic.Confirm -> haptics.confirm()
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
