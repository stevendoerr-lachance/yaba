package dev.steven.s3control.ui.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.steven.s3control.R
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.paneSplit
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.delay

/*
 * The opening for reduced motion and TalkBack: three panels of the same film, drawn with the same code at fixed
 * instants, static, in the system's palette. No sweep, no haptics, no glow, no AUW, no flash.
 *
 *   1  5,160   the boot standing on the dented bike at the end of the second stomp's hit-stop (bars bent, saddle crooked, the
 *              capsule pressed, the kickstand folded), E42 in signal, steady (the film's own blink has not begun yet: the face is set
 *              by hand)
 *   2  9,700   the wreck on the road with the cracks of the three stomps, the empty capsule, nothing else (40 ms before the silence)
 *   3  13,700  the bike floating with halo and light, YABA in instrument dots (1.5 s after the tableau begins)
 *
 * The face of a panel is normalised (dot, glow, lit, no recoil): no strike look, no flash, no blink, no AUW. The error
 * stays: it is a state, not a call. It shows for 3.6 s, or until a tap or Skip. With TalkBack there is no timer (WCAG 2.2.1): it stays until Skip,
 * and its description is announced once, as a polite live region. The end is a hard cut.
 */

private const val STRIP_MS = 3_600L
private val panelTimes = floatArrayOf(5_160f, Film.T_SILENCE - 40f, Film.T_TABLEAU + 1_500f)

/** The error as it is steady in the first panel: signal red, the film's blink being a matter of time. */
private val panelFace = Face(FrameId.E42, tone = Tone.Signal)

/**
 * The strip's own camera: the whole bike (and what is around it) in a panel of any shape. The panel's height in u is what the
 * picture needs and no more: 270 u in the first, where the knee must be in the picture or the leg is a pole, 130 u in the wreck
 * (it lies 60 u high), 230 u in the third (the halo and the lift reach 207 u above the road). A panel that is wider than it is
 * high (they are 1.5:1 on the cover) is also fitted by width, so that the wreck's 262 u and the bike's 186 u are never cut.
 */
internal fun stripCam(panel: Int, s: Stage): Cam {
    val hU = when (panel) {
        0 -> 270f
        1 -> 130f
        else -> 230f
    }
    val wU = when (panel) {
        0 -> 230f
        1 -> 280f
        else -> 230f
    }
    val k = minOf(s.height / hU, s.width / wU)
    val cx = when (panel) {
        0 -> -4f
        1 -> -6f
        else -> 4f
    }
    return Cam(k, cx, s.top + 0.90f * s.height, s.centerX)
}

@Composable
internal fun IntroStrip(
    timed: Boolean,
    onSeen: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seen by rememberUpdatedState(onSeen)
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(timed) {
        seen()
        if (timed) {
            delay(STRIP_MS)
            finished()
        }
    }
    val description = stringResource(R.string.intro_description)
    val (world, frames) = rememberFilmKit()
    val context = LocalContext.current
    val activity = androidx.compose.runtime.remember(context) { context.findActivity() }
    val layoutInfo by produceState<WindowLayoutInfo?>(initialValue = null, activity) {
        val a = activity ?: return@produceState
        WindowInfoTracker.getOrCreate(a).windowLayoutInfo(a).collect { value = it }
    }
    val tapToEnd = if (timed) Modifier.pointerInput(Unit) { detectTapGestures { finished() } } else Modifier

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(S3.colors.asphalt)
            .then(tapToEnd),
    ) {
        val wide = maxWidth >= 600.dp
        val density = LocalDensity.current
        if (wide) {
            val fold = layoutInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()
                ?.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
            val split = paneSplit(fold, 0f, maxWidth, density)
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // the panels: the hinge never crosses one
                BoxWithConstraints(Modifier.width(split.left).fillMaxHeight()) {
                    val panelH = minOf(260.dp, (maxHeight - S3.s * 2 - S3.l * 2) / 3)
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = S3.l)
                            .semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(S3.s),
                    ) {
                        Panel(0, panelH, world, frames)
                        Panel(1, panelH, world, frames)
                        Panel(2, panelH, world, frames)
                    }
                }
                Spacer(Modifier.width(split.hinge))
                // the group with Skip is read first (Skip first, then the slogan, then the panels' description)
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(horizontal = S3.l)
                        .semantics { isTraversalGroup = true; traversalIndex = -1f },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Slogan(S3.type.display, S3.type.body.copy(fontSize = S3.type.display.fontSize, lineHeight = S3.type.display.lineHeight))
                    Spacer(Modifier.height(S3.l))
                    SkipButton { finished() }
                }
            }
        } else {
            // 3 framed panels (up to 240 dp each), then the lockup and Skip: about 850 dp on the cover, nothing to scroll
            val fixed = 24.dp + 60.dp + S3.m + 52.dp + 2.dp + S3.m + S3.s * 2
            val panelH = minOf(240.dp, (maxHeight - fixed - 48.dp) / 3)
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = S3.gutter).semantics { isTraversalGroup = true },
                verticalArrangement = Arrangement.Center,
            ) {
                Column(Modifier.semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite }) {
                    Panel(0, panelH, world, frames)
                    Spacer(Modifier.height(S3.s))
                    Panel(1, panelH, world, frames)
                    Spacer(Modifier.height(S3.s))
                    Panel(2, panelH, world, frames)
                }
                Spacer(Modifier.height(24.dp))
                Slogan(S3.type.title, S3.type.body.copy(fontSize = S3.type.title.fontSize, lineHeight = S3.type.title.lineHeight))
                Spacer(Modifier.height(S3.m))
                SkipButton { finished() }
            }
        }
    }
}

/** Skip is the first thing TalkBack reads (the spec's order), whatever its place on the screen. */
@Composable
private fun SkipButton(onSkip: () -> Unit) {
    Box(Modifier.semantics { traversalIndex = -1f }) {
        QuietButton(stringResource(R.string.intro_skip), onClick = onSkip, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Slogan(line1: androidx.compose.ui.text.TextStyle, line2: androidx.compose.ui.text.TextStyle) {
    val c = S3.colors
    Text(stringResource(R.string.intro_slogan_1), style = line1, color = c.paint)
    Text(stringResource(R.string.intro_slogan_2), style = line2, color = c.paint)
}

@Composable
private fun Panel(index: Int, height: Dp, world: FilmWorld, frames: FilmFrames) {
    val c = S3.colors
    Box(Modifier.fillMaxWidth().height(height).border(1.dp, c.fog).clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val stage = Stage(0f, 0f, size.width, size.height)
            drawFilm(
                panelTimes[index], stage, c, world, frames,
                SceneOpts(
                    strip = true,
                    lockup = index == 2,
                    camera = stripCam(index, stage),
                    face = if (index == 0) panelFace else null,
                ),
            )
        }
    }
}
