package dev.steven.s3control.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.OutburstCue
import dev.steven.s3control.ui.components.UnlockSlider
import dev.steven.s3control.ui.components.coverUntilSentence
import dev.steven.s3control.ui.components.rememberBikeFeedback
import dev.steven.s3control.ui.components.rememberHaptics
import dev.steven.s3control.ui.components.rememberReducedMotion
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.components.rememberOutburstStage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the step waits for the bike to report its lock before it shows the slider anyway. */
private const val LOCK_WAIT_MS = 2_000L

/**
 * The first unlock (step 4 of 4, the slider is its own progress and there is no rail). The closed lock on the tube is a
 * state of the bike but not a fault here, so it is fog, not traffic red; it brightens to led as the slider travels (and the
 * slider wears led too). The person slides, the bike unlocks over Bluetooth, and the tube sweeps to the open lock in led (HÈ HÈ
 * first, when a bike was not found earlier in this session). Then a short moment, the way the matrix talks: the `confirm`
 * haptic on the glyph, two dots running out of it along the middle row to both ends of the tube, and the sentence with the time
 * and the battery writing itself in from the left (and, a beat later, "Gute Fahrt." exactly once in the whole life of the
 * installation), then a button that ends the onboarding: nothing advances on its own, so nobody who listens with TalkBack is
 * hurried (the text is there for TalkBack from the first frame). Reduced motion: no ripple, no writing, the text stands.
 *
 * While the lock is not known the tube shows the Bluetooth glyph, for two seconds at most.
 */
@Composable
internal fun UnlockStep(
    vm: AppViewModel,
    env: OnboardingEnv,
    s: BikeState,
    clock: UnlockClock,
    onUnlockStarted: () -> Unit,
    onDone: () -> Unit,
) {
    val feedback = rememberBikeFeedback(vm)
    val moments = rememberUnlockMoments(s, clock)
    UnlockView(
        env = env,
        s = s,
        note = moments.note,
        cue = moments.cue,
        error = feedback.errorFor("unlock"),
        onUnlock = {
            clock.start()
            onUnlockStarted()
            feedback.run("unlock", Op.UNLOCK) { vm.bike.unlock() }
        },
        onDone = onDone,
    )
}

/** Hides what is drawn from the right until [progress] (0..1, read in the draw phase) has uncovered it from the left: no fade. */
private fun Modifier.writtenIn(progress: () -> Float): Modifier = drawWithContent {
    val p = progress()
    if (p >= 1f) drawContent() else clipRect(right = size.width * p.coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
}

@Composable
internal fun UnlockView(
    env: OnboardingEnv,
    s: BikeState,
    note: UnlockNote?,
    cue: OutburstCue?,
    error: String?,
    onUnlock: () -> Unit,
    onDone: () -> Unit,
) {
    val unlocked = s.isReady && s.lock == LockState.UNLOCKED && note != null

    var waitOver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOCK_WAIT_MS)
        waitOver = true
    }
    val lockKnown = s.lock != null || waitOver

    val ble = MatrixFont.BLE.toString()
    val calm = remember(unlocked, lockKnown) {
        MatrixFrame.text(
            when {
                unlocked -> MatrixFont.UNLOCKED.toString()
                !lockKnown -> ble
                else -> MatrixFont.LOCKED.toString()
            },
        )
    }
    val stage = rememberOutburstStage(calm = calm, cue = cue)
    // The closed lock is the place the ride starts from, not a fault: fog, brightening to led as the slider travels.
    val tone = if (!unlocked && lockKnown) TubeTone.Calm else TubeTone.Led
    var slide by remember { mutableFloatStateOf(0f) }

    // --- the moment of the swap -------------------------------------------------------------------------------------
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    var played by rememberSaveable { mutableStateOf(false) }
    val ripple = remember { Animatable(1f) }
    val sentence = remember { Animatable(if (played || reduced) 1f else 0f) }
    val ride = remember { Animatable(if (played || reduced) 1f else 0f) }
    LaunchedEffect(unlocked) {
        if (!unlocked) {
            ripple.snapTo(1f)
            return@LaunchedEffect
        }
        if (played || reduced) {
            sentence.snapTo(1f); ride.snapTo(1f)
            return@LaunchedEffect
        }
        // the glyph sweeps in over 520 ms; it stands at about 300 ms, and then the moment starts
        delay(300)
        played = true
        haptics.confirm()
        coroutineScope {
            // not over a call that is on the tube (HÈ HÈ): the middle row is the call's then
            if (!stage.performing) launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(640, easing = LinearEasing)) }
            launch { delay(140); sentence.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
            launch { delay(900); ride.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        }
    }

    val title = stringResource(if (unlocked) R.string.shell_lock_unlocked else R.string.onb_unlock_title)
    val locale = LocalConfiguration.current.locales[0]
    val body = if (unlocked && note != null) {
        BodySpec(
            text = stringResource(R.string.shell_unlock_done_text, String.format(locale, "%.1f", note.seconds), note.battery),
            strongExtra = if (note.first) stringResource(R.string.shell_unlock_first_ride) else null,
            modifier = Modifier.coverUntilSentence(stage, Moment.Relief).writtenIn { sentence.value },
            strongModifier = Modifier.writtenIn { ride.value },
        )
    } else {
        BodySpec(stringResource(R.string.onb_unlock_text))
    }

    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = listOf(
            Drop.Hero60, Drop.HeroNone, Drop.BodyToSemantics, Drop.TitleCompact, Drop.StatusWhenNonEmpty, Drop.TitleToSemantics,
        ),
        hero = HeroSpec { modifier ->
            OnboardingTube(stage, tone, title, modifier, mix = slide, ripple = if (unlocked) ({ ripple.value }) else null)
        },
        body = body,
        fields = if (unlocked) null else {
            {
                UnlockSlider(
                    locked = s.lock?.let { it != LockState.UNLOCKED },
                    busy = Op.UNLOCK in s.busy || Op.LOCK in s.busy,
                    onUnlock = onUnlock,
                    onLock = null,
                    enabled = s.isReady && lockKnown,
                    lockedAccent = S3.colors.led,
                    onProgress = { slide = it },
                )
            }
        },
        status = { StatusLine(error) },
        statusEmpty = error == null,
        primary = if (unlocked) {
            { BigButton(stringResource(R.string.shell_dest_ride), onClick = onDone) }
        } else null,
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize())
}
