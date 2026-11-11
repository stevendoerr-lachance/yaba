package dev.steven.s3control.ui.onboarding

import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.LoginFieldsState
import dev.steven.s3control.ui.components.LoginFields
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.OutburstCue
import dev.steven.s3control.ui.components.Outbursts
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.ServerFailure
import dev.steven.s3control.ui.components.ServerRecord
import dev.steven.s3control.ui.components.classifyServerError
import dev.steven.s3control.ui.components.coverUntilSentence
import dev.steven.s3control.ui.components.outburstFor
import dev.steven.s3control.ui.components.rememberLoginSubmit
import dev.steven.s3control.ui.components.rememberOutburstStage
import dev.steven.s3control.ui.components.skipOnTouch
import androidx.compose.foundation.layout.fillMaxSize
import java.util.Date

/**
 * Sign in with the VanMoof account (step 1 of 4, one of three ways to a key). No tube and no call here: these are credentials
 * (brand block 2, "Keine Matrix"). A wrong password and a phone without internet are one plain line of red text. Only when
 * VanMoof's server itself fails (a 5xx, a timeout, seven days of silence) the fields leave the layout, the tube comes back
 * with its call and the code, and a retry sits where the button was; the fields keep what was typed, so retrying sends the
 * same values again.
 */
@Composable
internal fun SignInStep(vm: AppViewModel, fields: LoginFieldsState, env: OnboardingEnv, onImport: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val submit = rememberLoginSubmit(vm, fields)
    val context = LocalContext.current
    val failure = remember(account) { classifyServerError(context.resources, account.error) }
    val record = remember(account) { failure?.let { Outbursts.recordServerFailure(context, account, it) } }
    // "Cannot be reached, check your internet connection" is the right message for a phone without a connection; only after
    // seven days of silence is it the server that gets the notice (and DE PIJP UIT).
    val serverNotice = record != null && !(failure is ServerFailure.Unreachable && record.moment != Moment.ServerDead)

    val importButton = stringResource(R.string.account_welcome_import_button)
    val importAction = FrameAction("import") { QuietButton(importButton, onImport, Modifier.fillMaxWidth()) }

    if (serverNotice && record != null) {
        ServerNoticeView(record, env, retry = { submit.run() }, importAction = importAction)
        return
    }

    val title = stringResource(R.string.account_welcome_login_title)
    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = listOf(
            Drop.Caption, Drop.Rail, Drop.BodyToSemantics, Drop.TitleCompact,
            Drop.StatusWhenNonEmpty, Drop.PrimaryUnderIme, Drop.TitleToSemantics,
        ),
        rail = 1,
        body = BodySpec(stringResource(R.string.account_welcome_login_text)),
        caption = stringResource(R.string.account_welcome_login_caption),
        fields = { LoginFields(fields, onSubmit = submit.run) },
        status = { StatusLine(account.error) },
        statusEmpty = account.error == null,
        primary = {
            BigButton(
                text = stringResource(if (submit.running) R.string.account_login_running else R.string.onb_signin_button),
                onClick = submit.run,
                enabled = submit.canSubmit,
            )
        },
        secondaries = listOf(importAction),
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize())
}

/** The server's own failure: the tube with its call and its code, two sentences, and the retry. Never for a 4xx. */
@Composable
internal fun ServerNoticeView(record: ServerRecord, env: OnboardingEnv, retry: () -> Unit, importAction: FrameAction) {
    val context = LocalContext.current
    val time = remember(record.at) { DateFormat.getTimeFormat(context).format(Date(record.at)) }
    val dead = record.moment == Moment.ServerDead
    val failure = record.failure
    val title = stringResource(
        when {
            dead -> R.string.core_server_dead_title
            failure is ServerFailure.Http -> R.string.core_server_error_title
            else -> R.string.core_server_timeout_title
        },
    )
    val text = when {
        dead -> stringResource(R.string.onb_server_text_dead, record.days)
        failure is ServerFailure.Http -> stringResource(R.string.onb_server_text_http, time, failure.code)
        else -> stringResource(R.string.onb_server_text_timeout, time)
    }
    val daysFrame = stringResource(R.string.curse_matrix_days, record.days)
    val calm = remember(dead, failure, daysFrame) {
        MatrixFrame.text(
            when {
                dead -> daysFrame
                failure is ServerFailure.Http -> failure.code.toString()
                else -> "10 S"
            },
        )
    }
    val cue = record.moment?.let { OutburstCue(it, outburstFor(it), record.at) }
    val stage = rememberOutburstStage(calm = calm, cue = cue)
    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = listOf(
            Drop.Caption, Drop.Rail, Drop.Hero60, Drop.HeroNone, Drop.BodyToSemantics, Drop.TitleCompact, Drop.AllSecondaries,
        ),
        rail = 1,
        hero = HeroSpec { modifier -> OnboardingTube(stage, TubeTone.Led, title, modifier) },
        body = BodySpec(text, modifier = Modifier.coverUntilSentence(stage).semantics { liveRegion = LiveRegionMode.Polite }),
        caption = stringResource(R.string.account_welcome_login_fallback),
        primary = { BigButton(stringResource(R.string.core_server_retry), retry) },
        secondaries = listOf(importAction),
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize().skipOnTouch(stage))
}
