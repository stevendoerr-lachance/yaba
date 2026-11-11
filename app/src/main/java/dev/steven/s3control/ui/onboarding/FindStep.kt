package dev.steven.s3control.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.OutburstCue
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.WorkingBigButton
import dev.steven.s3control.ui.components.coverUntilSentence
import dev.steven.s3control.ui.components.rememberOutburstStage

/** What the buttons of the search step do. The step wires them to the view model; a test or a preview passes no-ops. */
internal class FindActions(
    val connect: () -> Unit,
    val retry: () -> Unit,
    val stop: () -> Unit,
    val quit: () -> Unit,
    val mute: () -> Unit,
    val changeKey: () -> Unit,
)

/**
 * Find the bike (step 3 of 4). The Bluetooth access that was just granted is the consent: the search starts when the step
 * appears (the auto-connect of the shell waits for the end of the onboarding, so this is the only caller), and the Bluetooth
 * glyph on the tube is joined by the search word. "Stop searching" stays stopped; the idle step then says so and offers
 * "Search now". More than 12 seconds without an answer, the bike standing: BALEN (GEDOE when the VanMoof app is installed,
 * never both), the tube settles on the glyph and a question mark (or the search word), and the sentence names the one thing
 * that usually holds the connection. The way out for a wrong key ("Change key") is always there and never deletes anything.
 */
@Composable
internal fun FindStep(vm: AppViewModel, env: OnboardingEnv, bike: BikeCredentials, s: BikeState, onChangeKey: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    // how often "Quit VanMoof app" was tapped: the revoke line only appears once quitting did not help
    var quits by remember { mutableIntStateOf(0) }
    val moments = rememberFindMoments(vm, s.link, bike.normalizedMac, attempt)
    val connect = {
        vm.autoConnectTriedMac = bike.normalizedMac
        vm.connect(bike)
    }
    // Once per appearance of the step: a tap on "Stop searching" is not undone by this, and neither by a change of the
    // language (the activity is recreated then, which is not a new appearance: the saved state remembers the start).
    var startedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(bike.normalizedMac) {
        if (startedFor != bike.normalizedMac) {
            startedFor = bike.normalizedMac
            if (s.link == Link.OFFLINE) connect()
        }
    }
    val actions = FindActions(
        connect = connect,
        retry = {
            attempt++
            if (s.link == Link.OFFLINE) connect() else vm.bike.retryNow()
        },
        stop = { vm.bike.disconnect() },
        quit = {
            vm.quitOfficialApp()
            attempt++
            quits++
        },
        mute = { vm.openOfficialAppInfo() },
        changeKey = onChangeKey,
    )
    FindView(env, bike.name, s, moments.notFound, moments.vanMoofApp, moments.cue, actions, quitTried = quits > 0)
}

@Composable
internal fun FindView(
    env: OnboardingEnv,
    bikeName: String,
    s: BikeState,
    notFound: Boolean,
    vanMoofApp: Boolean,
    cue: OutburstCue?,
    actions: FindActions,
    quitTried: Boolean = false,
) {
    val idle = s.link == Link.OFFLINE
    val searching = !idle && s.link != Link.READY
    // Connected, the first values still on their way (an S3's wheel size decides the next step, see wheelPending): the step
    // stays a few seconds and its button says what is happening. Nothing here can be tapped then; Back still leaves.
    val reading = s.link == Link.READY
    // What the working button says is what the link is doing: the step has no status line that would say it twice.
    val workingText = stringResource(
        when (s.link) {
            Link.CONNECTING -> R.string.bike_link_connecting
            Link.AUTHENTICATING -> R.string.bike_link_authenticating
            Link.LOST -> R.string.bike_link_lost
            else -> R.string.bike_link_searching
        },
    )

    val scanWord = stringResource(R.string.onb_matrix_scan)
    val ble = MatrixFont.BLE.toString()
    val calm = remember(notFound, vanMoofApp, searching, scanWord) {
        // The glyph, two empty columns and the word: 36 columns for SUCHE, 30 for SCAN (a space would cost 4 and overflow).
        when {
            notFound -> MatrixFrame.text(ble) + MatrixFrame.gap(2) + MatrixFrame.text(if (vanMoofApp) scanWord else "?")
            searching -> MatrixFrame.text(ble) + MatrixFrame.gap(2) + MatrixFrame.text(scanWord)
            else -> MatrixFrame.text(ble)
        }
    }
    val stage = rememberOutburstStage(calm = calm, cue = cue)

    val title = stringResource(if (notFound) R.string.shell_notfound_title else R.string.onb_find_title)
    val bodyText = when {
        notFound && vanMoofApp -> stringResource(R.string.onb_official_text)
        notFound -> stringResource(R.string.onb_notfound_text, bikeName)
        idle -> stringResource(R.string.onb_find_idle_text)
        else -> stringResource(R.string.onb_find_text)
    }
    val retryText = stringResource(R.string.bike_prompt_retry)
    val changeKeyText = stringResource(R.string.onb_change_key)

    val ladder = when {
        notFound && vanMoofApp -> listOf(
            Drop.StatusWhenNonEmpty, Drop.Rail, Drop.Hero60, Drop.HeroNone, Drop.Secondary("mute"), Drop.BodyToSemantics,
            Drop.TitleCompact, Drop.Secondary("changeKey"), Drop.TitleToSemantics,
        )
        notFound -> listOf(
            Drop.StatusWhenNonEmpty, Drop.Rail, Drop.Hero60, Drop.HeroNone, Drop.BodyToSemantics, Drop.TitleCompact,
            Drop.Secondary("changeKey"), Drop.TitleToSemantics,
        )
        else -> listOf(
            Drop.StatusWhenNonEmpty, Drop.Rail, Drop.Hero60, Drop.HeroNone, Drop.BodyToSemantics, Drop.TitleCompact,
            Drop.TitleToSemantics,
        )
    }

    val primary: @Composable () -> Unit = when {
        notFound && vanMoofApp -> { { BigButton(stringResource(R.string.official_quit), onClick = actions.quit) } }
        notFound -> { { BigButton(retryText, onClick = actions.retry) } }
        searching -> { { WorkingBigButton(stringResource(R.string.onb_find_button), workingText, busy = true, onClick = {}) } }
        reading -> { { WorkingBigButton(stringResource(R.string.onb_find_button), stringResource(R.string.bike_raw_reading), busy = true, onClick = {}) } }
        else -> { { BigButton(stringResource(R.string.onb_find_button), onClick = actions.connect) } }
    }

    // Two buttons at most (the step's one action and the way to repeat it); the ways out are lines of text, so nothing sounds
    // destructive in a first-run flow: "Revoke Bluetooth access" only appears once quitting the VanMoof app did not help.
    val secondaries = buildList {
        when {
            notFound && vanMoofApp -> {
                add(FrameAction("retry") { QuietButton(retryText, actions.retry, Modifier.fillMaxWidth()) })
                if (quitTried) add(FrameAction("mute") { TextLink(stringResource(R.string.official_mute), actions.mute) })
            }
            searching && !notFound -> add(FrameAction("stop") {
                QuietButton(stringResource(R.string.bike_prompt_stop), onClick = actions.stop, modifier = Modifier.fillMaxWidth())
            })
        }
        if (notFound) add(FrameAction("changeKey") { TextLink(changeKeyText, actions.changeKey) })
    }

    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = ladder,
        rail = 3,
        hero = HeroSpec { modifier -> OnboardingTube(stage, TubeTone.Led, title, modifier) },
        body = BodySpec(bodyText, modifier = if (notFound) Modifier.coverUntilSentence(stage, Moment.NotFound) else Modifier),
        // Only what the link adds in red (an error from the bike's side); "searching" and "connecting" are the button's own text.
        // Not while the bike is "not found": the paragraph says that already, and the same thing twice, the second time in red, is
        // an alarm for a bike that is only asleep.
        status = { StatusLine(if (notFound) null else s.linkMessage) },
        statusEmpty = notFound || s.linkMessage == null,
        primary = primary,
        secondaries = secondaries,
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize())
}
