package dev.steven.s3control.ui.intro

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * The seam between the opening film and the first screen (the Welcome). The film plays on top of the composed
 * app: the Welcome is already there, dark, and reports where its top tube is; the film ends on exactly that
 * tube, and the screen takes over without a cut.
 *
 * Order matters (see MainActivity): the film is dropped, one frame passes, and only then [release] is called,
 * so the Welcome's call is never staged behind the film and never burned there.
 */
@Stable
class IntroHandoff(holding: Boolean) {
    /** True from cold start until the reveal sweep ended (or the skip finished). The Welcome holds its call meanwhile. */
    var holding by mutableStateOf(holding)
        private set

    /** Window pixels: the Welcome's [dev.steven.s3control.ui.components.BikeTube] box, null when there is none. */
    var tubeSlot by mutableStateOf<Rect?>(null)
        private set

    fun reportSlot(r: Rect) { tubeSlot = r }
    fun clearSlot() { tubeSlot = null }

    /** MainActivity only. */
    fun release() { holding = false }
}

val LocalIntroHandoff = staticCompositionLocalOf<IntroHandoff?> { null }
