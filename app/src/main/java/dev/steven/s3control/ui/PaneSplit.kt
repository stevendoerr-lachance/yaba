package dev.steven.s3control.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

/** Where the two panes of the inner screen end and begin: the left pane's width and the hinge's width. */
internal class ShellPaneSplit(val left: Dp, val hinge: Dp)

/**
 * Split at a vertical fold (bounds are window coordinates). Without one, split in the middle:
 * on a book foldable that is where the crease is, so the gap straddles it either way.
 *
 * [originXPx] is the window x of the container the panes live in; the opening film and the onboarding use
 * 0f and the window width.
 */
internal fun paneSplit(fold: FoldingFeature?, originXPx: Float, total: Dp, density: Density): ShellPaneSplit {
    val fallback = ShellPaneSplit(total / 2, 0.dp)
    if (fold == null) return fallback
    val bounds = fold.bounds
    val start = with(density) { (bounds.left - originXPx).toDp() }
    val end = with(density) { (bounds.right - originXPx).toDp() }
    if (end < start || start < total * 0.25f || end > total * 0.75f) return fallback
    return ShellPaneSplit(start, end - start)
}
