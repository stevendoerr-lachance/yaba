package dev.steven.s3control.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.SpeedPlus
import dev.steven.s3control.ble.SpeedPlusView
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.rememberOutburstStage

/**
 * The bike's factory wheel size is open ([SpeedPlusView.ASK]): it reads 24″ and no model name says whether it is an S3 with
 * Speed++ on (28″ from the factory) or an X3. The account only names a SKU, and after a new install YABA's first read of a
 * bike with Speed++ on is 24″. Read once 5564 is in the state ([BikeState.wheelStock]); an S2 never has one.
 */
internal fun wheelQuestionOpen(s: BikeState): Boolean {
    val stock = s.wheelStock ?: return false
    return SpeedPlus.view(s.isReady, s.wheelSize, stock) == SpeedPlusView.ASK
}

/** The question's tube: both factory sizes with a space between them, 26 of the tube's columns (digits only, in every language). */
internal const val WheelTubeText = "28 24"

/** How long after the link is up the search step waits for an S3's wheel size ([wheelPending]). */
internal const val WHEEL_WAIT_MS = 8_000L

/**
 * The link is up, but an S3 has not reported its wheel size yet: the controller sets the link ready first and reads 5564 a few
 * seconds later (after the subscriptions and the versions). Until then, for [WHEEL_WAIT_MS] at most, the search step stays
 * ("Liest …"), so the question never replaces the unlock slider under the person's thumb. A bike that does not report the
 * size goes on to the unlock when the time is up; an S2 has no wheel size and is never held.
 */
internal fun wheelPending(s: BikeState, nowMillis: Long): Boolean {
    if (!s.isReady || s.family != BikeFamily.S3 || s.wheelStock != null) return false
    val since = s.connectedAtMillis ?: return false
    return nowMillis - since < WHEEL_WAIT_MS
}

/**
 * The question between the search and the first unlock, only while [wheelQuestionOpen]: "Laufradgröße ab Werk: 28″ (S3) |
 * 24″ (X3)", then "Weiter". The answer sets the speed factor before the first ride ([dev.steven.s3control.ble.BikeController.setStockWheel])
 * and nothing is sent to the bike; the same bar stays on the Bike tab under Experimente, where the answer can be changed. The
 * tube shows both sizes, 28 and 24, in the bar's order ([WheelTubeText]): under the title a single 24 would read as the
 * answer. That the bike is set to 24″ right now, the sentence says. No choice is made for the person: "Weiter" waits for one.
 */
@Composable
internal fun WheelStep(vm: AppViewModel, env: OnboardingEnv, s: BikeState) {
    var choice by rememberSaveable { mutableStateOf<Int?>(null) }
    WheelView(
        env = env,
        choice = choice,
        enabled = s.isReady,
        onChoice = { choice = it },
        onConfirm = { choice?.let { vm.bike.setStockWheel(it) } },
    )
}

@Composable
internal fun WheelView(env: OnboardingEnv, choice: Int?, enabled: Boolean, onChoice: (Int) -> Unit, onConfirm: () -> Unit) {
    val title = stringResource(R.string.speedplus_stock_title)
    val calm = remember { MatrixFrame.text(WheelTubeText) }
    val stage = rememberOutburstStage(calm = calm)
    val later = stringResource(
        R.string.speedplus_stock_later,
        stringResource(R.string.shell_dest_bike),
        stringResource(R.string.bike_exp_title),
    )
    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = listOf(
            Drop.Caption, Drop.Rail, Drop.Hero60, Drop.HeroNone, Drop.StatusWhenNonEmpty, Drop.TitleCompact, Drop.BodyToSemantics,
            Drop.TitleToSemantics,
        ),
        rail = 3,
        hero = HeroSpec { modifier -> OnboardingTube(stage, TubeTone.Led, title, modifier) },
        body = BodySpec(stringResource(R.string.speedplus_stock_question)),
        caption = later,
        fields = {
            ChoiceBar(
                options = listOf(
                    SpeedPlus.WHEEL_28 to stringResource(R.string.speedplus_stock_28),
                    SpeedPlus.WHEEL_24 to stringResource(R.string.speedplus_stock_24),
                ),
                selected = choice,
                onSelect = onChoice,
                enabled = enabled,
            )
        },
        primary = { BigButton(stringResource(R.string.speedplus_stock_next), onClick = onConfirm, enabled = enabled && choice != null) },
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize())
}
