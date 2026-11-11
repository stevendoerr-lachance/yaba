package dev.steven.s3control.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** One sweep from the left, the length of the matrix's own: 520 ms, FastOutSlowIn. */
internal const val STEP_SWEEP_MS = 520

/**
 * The change from one step to the next, the way the top tube redraws: one stroke from the left. The new step is drawn left of
 * the edge, the old one right of it, and the edge runs across in 520 ms. Back goes the same way (there is no other direction in
 * this app: nothing fades, nothing slides up).
 *
 * While the old step is still there it is gone for TalkBack and for the keyboard focus, and every touch is swallowed: the
 * person cannot press into a layout that is leaving. When it is over the new step's pane title is announced. Reduced
 * motion is a cut.
 */
@Composable
internal fun <T> StepSweep(target: T, reduced: Boolean, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    var shown by remember { mutableStateOf(target) }
    var leaving by remember { mutableStateOf<T?>(null) }
    val edge = remember { Animatable(1f) }

    LaunchedEffect(target) {
        if (target == shown) return@LaunchedEffect
        // A sweep that is still running is finished at once; the next one starts from the step that stands now.
        edge.snapTo(1f)
        if (reduced) {
            leaving = null
            shown = target
            return@LaunchedEffect
        }
        leaving = shown
        shown = target
        edge.snapTo(0f)
        edge.animateTo(1f, tween(STEP_SWEEP_MS, easing = FastOutSlowInEasing))
        leaving = null
    }

    Box(modifier.fillMaxSize()) {
        val old = leaving
        if (old != null) {
            key(old) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(left = size.width * edge.value) { this@drawWithContent.drawContent() }
                        }
                        .clearAndSetSemantics {}
                        .focusProperties { canFocus = false },
                ) { content(old) }
            }
        }
        key(shown) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (edge.value >= 1f) {
                            drawContent()
                        } else {
                            clipRect(right = size.width * edge.value) { this@drawWithContent.drawContent() }
                        }
                    },
            ) { content(shown) }
        }
        if (old != null) {
            // Everything that is touched while the sweep runs is swallowed here, above both layers.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}
