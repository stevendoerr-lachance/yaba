package dev.steven.s3control.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.ui.BleReadiness
import dev.steven.s3control.ui.onboarding.OnboardingEnv
import dev.steven.s3control.ui.onboarding.OnboardingHost

/**
 * The onboarding, shown by AppRoot while no bike key is stored and, once there is one, until the first unlock is through
 * (the settings' `onboardingDone`): welcome, a way to a key (sign in, key file, or by hand), Bluetooth access, the search,
 * the first unlock. Every step lives in `ui/onboarding`; this is only the entry: it hands the host the window's shape (cover
 * or two panes split at the fold) and the Bluetooth readiness that AppRoot already watches.
 *
 * No step of the onboarding scrolls, on the cover screen or with the keyboard open: what does not fit is taken away, in an
 * order each step knows (see OnboardingFrame).
 */
@Composable
fun WelcomeScreen(
    vm: AppViewModel,
    wide: Boolean,
    layoutInfo: WindowLayoutInfo?,
    readiness: BleReadiness,
    onboardingDone: Boolean,
    modifier: Modifier = Modifier,
) {
    val fold = remember(layoutInfo) {
        layoutInfo?.displayFeatures
            ?.filterIsInstance<FoldingFeature>()
            ?.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
    }
    var originX by remember { mutableFloatStateOf(0f) }
    val env = remember(wide, fold, originX) { OnboardingEnv(wide, fold, originX) }
    // The settings are already done and the key is there: AppRoot swaps this screen out in the next frame; show nothing meanwhile.
    if (onboardingDone && vm.vault.value.bikes.isNotEmpty()) return
    OnboardingHost(vm, env, readiness, modifier.onGloballyPositioned { originX = it.positionInWindow().x })
}
