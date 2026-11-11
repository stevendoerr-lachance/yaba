package dev.steven.s3control.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import dev.steven.s3control.R
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.OutburstCue
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.YabaWordmark
import dev.steven.s3control.ui.components.outburstFor
import dev.steven.s3control.ui.components.rememberOutburstStage
import dev.steven.s3control.ui.components.skipOnTouch
import dev.steven.s3control.ui.intro.IntroHandoff
import dev.steven.s3control.ui.intro.LocalIntroHandoff
import dev.steven.s3control.ui.theme.S3

/**
 * The first screen. The top tube is dark while the opening film still covers the screen (the stage is held), then it calls
 * DE PIJP UIT once per installation, and in the same stroke the YABA wordmark boots. The call is the tube's alone: the title,
 * the paragraph and both buttons stand from the first frame (a paragraph that waited three seconds for a joke would arrive
 * after the rider has stopped reading).
 *
 * On the inner screen the Welcome splits along its own turn: the Klappe on the left (the tube and the lockup), the Hand on the
 * right (the paragraph, the small line, the buttons).
 *
 * With no film in front (a later start, no bike yet) the stage is not held and the call runs after the screen's first second.
 * When the gate says no (switch off, reduced motion, TalkBack, drive lock, cap used) YABA simply stands and the text is there.
 */
@Composable
internal fun WelcomeStep(env: OnboardingEnv, onSignIn: () -> Unit, onImport: () -> Unit) {
    val handoff = LocalIntroHandoff.current
    val outburst = outburstFor(Moment.Welcome)
    val cue = remember(outburst.word) { OutburstCue(Moment.Welcome, outburst, 0) }
    val stage = rememberOutburstStage(
        calm = YabaWordmark,
        cue = cue,
        before = MatrixFrame.Empty,
        hold = handoff?.holding == true,
    )
    val slogan = stringResource(R.string.account_welcome_slogan)
    val heroDescription = stringResource(R.string.account_welcome_hero_description)
    val loginButton = stringResource(R.string.account_welcome_login_button)
    val importButton = stringResource(R.string.account_welcome_import_button)
    val introText = stringResource(R.string.account_welcome_intro)
    val disclaimer = stringResource(R.string.account_welcome_disclaimer)
    val title = TitleSpec(
        lines = listOf(stringResource(R.string.account_welcome_slogan_1), stringResource(R.string.account_welcome_slogan_2)),
        description = slogan,
        lockup = true,
    )
    val hero = HeroSpec { modifier -> WelcomeHero(stage, handoff, heroDescription, modifier) }
    val primary: @Composable () -> Unit = { BigButton(loginButton, onSignIn) }
    val secondaries = listOf(FrameAction("import") { QuietButton(importButton, onImport, Modifier.fillMaxWidth()) })
    val spec = if (env.wide) {
        FrameSpec(
            paneTitle = slogan,
            title = title,
            ladder = listOf(Drop.Hero60, Drop.HeroNone, Drop.TitleCompact, Drop.TitleToSemantics),
            hero = hero,
            // the Hand: the paragraph and the mandatory line stand with the buttons, always
            fields = {
                Column {
                    Text(introText, style = S3.type.body, color = S3.colors.paint)
                    Spacer(Modifier.height(S3.m))
                    Text(disclaimer, style = S3.type.caption, color = S3.colors.fog)
                    Spacer(Modifier.height(S3.m))
                }
            },
            primary = primary,
            secondaries = secondaries,
        )
    } else {
        FrameSpec(
            paneTitle = slogan,
            title = title,
            // "Nicht mit VanMoof affiliiert" is always on the Welcome (brand): the tube, the paragraph and the title's size go
            // first, and if the line itself has to go, TalkBack still reads it with the heading (see OnboardingFrame).
            ladder = listOf(
                Drop.Hero60, Drop.HeroNone, Drop.BodyToSemantics, Drop.TitleCompact, Drop.Caption, Drop.TitleToSemantics,
            ),
            hero = hero,
            body = BodySpec(introText),
            caption = disclaimer,
            primary = primary,
            secondaries = secondaries,
        )
    }
    // A tap anywhere skips the call and goes to the sentence (it consumes nothing).
    OnboardingFrame(spec, env, Modifier.fillMaxSize().skipOnTouch(stage))
}

/**
 * The tube that tells the opening film where to land: its box in the window, 40 dp by 18 dp padded like every BikeTube. The
 * film draws its last frame into exactly this rectangle, so the handoff is not a cut.
 */
@Composable
private fun WelcomeHero(
    stage: dev.steven.s3control.ui.components.OutburstStage,
    handoff: IntroHandoff?,
    description: String,
    modifier: Modifier,
) {
    DisposableEffect(handoff) { onDispose { handoff?.clearSlot() } }
    OnboardingTube(
        stage = stage,
        tone = TubeTone.Led,
        description = description,
        modifier = modifier.onGloballyPositioned { handoff?.reportSlot(it.boundsInWindow()) },
    )
}
