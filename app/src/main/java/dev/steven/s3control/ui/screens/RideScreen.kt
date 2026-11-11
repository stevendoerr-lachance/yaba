package dev.steven.s3control.ui.screens

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.AlarmStage
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.GuardPhase
import dev.steven.s3control.ble.GuardRules
import dev.steven.s3control.ble.GuardState
import dev.steven.s3control.ble.LightMode
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.SpeedPlus
import dev.steven.s3control.ble.SpeedPlusView
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ui.BleReadiness
import dev.steven.s3control.ui.Destination
import dev.steven.s3control.ui.FamilyGate
import dev.steven.s3control.ui.LocalNavigator
import dev.steven.s3control.ui.cockpit.CockpitOffer
import dev.steven.s3control.ui.cockpit.CockpitRuntime
import dev.steven.s3control.ui.cockpit.rememberCockpitOpener
import dev.steven.s3control.ui.cockpit.rememberHeroCockpitOffer
import dev.steven.s3control.ui.components.AlarmStopRow
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.BikeTube
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.Fact
import dev.steven.s3control.ui.components.GuardSection
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.LinkIndicator
import dev.steven.s3control.ui.components.LocalShellBle
import dev.steven.s3control.ui.components.LocalShellBlePrompt
import dev.steven.s3control.ui.components.LocalShellCommands
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.PowerLadder
import dev.steven.s3control.ui.components.OfficialAppNotice
import dev.steven.s3control.ui.components.OutburstBlockWhile
import dev.steven.s3control.ui.components.OutburstCue
import dev.steven.s3control.ui.components.OutburstStage
import dev.steven.s3control.ui.components.OutburstTube
import dev.steven.s3control.ui.components.Outbursts
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.SectionTitle
import dev.steven.s3control.ui.components.ShellCommandError
import dev.steven.s3control.ui.components.ShellCornerLine
import dev.steven.s3control.ui.components.ShellFitText
import dev.steven.s3control.ui.components.ShellLed
import dev.steven.s3control.ui.components.ShellNotice
import dev.steven.s3control.ui.components.ShellSending
import dev.steven.s3control.ui.components.ShellSignalLine
import dev.steven.s3control.ui.components.ShiftSection
import dev.steven.s3control.ui.components.SpeedSign
import dev.steven.s3control.ui.components.UnlockSlider
import dev.steven.s3control.ui.onboarding.TextLink
import dev.steven.s3control.ui.components.coverUntilSentence
import dev.steven.s3control.ui.components.outburstFor
import dev.steven.s3control.ui.components.rememberHaptics
import dev.steven.s3control.ui.components.rememberOutburstStage
import dev.steven.s3control.ui.components.rememberReducedMotion
import dev.steven.s3control.ui.components.skipOnTouch
import dev.steven.s3control.ui.theme.LocalS3Palette
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Palette
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Commands whose errors the hero shows under the lock slider. */
private val LockOps = setOf(Op.LOCK, Op.UNLOCK)

/** Commands whose errors RideControls shows under their own section. */
private val ControlOps = setOf(Op.SPEED, Op.POWER, Op.SHIFT, Op.LIGHT, Op.ALARM)

/** A proper name, the same in every language. */
private const val DefaultModel = "VanMoof S3"

/** The same for a bike YABA knows as an S2/X2 (the account usually names the model itself). */
private const val DefaultModelS2 = "VanMoof S2"

/**
 * The Fahren destination. On the cover screen: the bike on top, its controls below. In the right
 * pane of the inner screen ([wide]) the bike already sits in the left pane, so only the controls.
 * RideControls stays at the same place in both cases, so folding keeps its state (an open dialog).
 */
@Composable
fun RideScreen(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val side = if (wide) S3.l else S3.gutter
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = side, end = side, top = S3.l, bottom = S3.xl),
    ) {
        if (!wide) {
            BikeHeroPanel(vm, controlsVisible = true)
            Spacer(Modifier.height(S3.xl))
        }
        RideControls(vm, wide = false)
    }
}

// --- Hero ----------------------------------------------------------------------------------------

/**
 * The bike itself: name, link, the top tube with its matrix, the key numbers, the lock lever,
 * and whatever it takes to get connected. Does not scroll on its own.
 */
@Composable
fun BikeHeroPanel(vm: AppViewModel, modifier: Modifier = Modifier) =
    BikeHeroPanel(vm, controlsVisible = false, modifier = modifier)

/**
 * [controlsVisible]: RideControls are on screen next to or below the hero, so the hero leaves
 * assist level and speed limit to them and gives the battery the room. [diagnosisBeside]: Diagnose is open in the
 * right pane and explains the fault under "Fehler", so the hero keeps to the fault line.
 */
@Composable
internal fun BikeHeroPanel(
    vm: AppViewModel,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier,
    diagnosisBeside: Boolean = false,
) {
    val s by vm.bikeState.collectAsStateWithLifecycle()
    val vault by vm.vault.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val tracker = LocalShellCommands.current
    val readiness = LocalShellBle.current
    val bleReady = readiness?.ready != false
    val c = S3.colors

    val preferred = remember(vault) { vm.preferredBike() }
    val bike = s.bike ?: preferred
    val needsChoice = bike == null && vault.bikes.size > 1
    // Before the first connect the stored bike's family decides what the lever offers (an S2 locks with the foot);
    // the values, the link and busy stay the live state's.
    val s2TestMode by vm.bike.s2Prefs.testMode.collectAsStateWithLifecycle()
    val gate = FamilyGate.beforeConnect(s, preferred, s2TestMode)
    // The Wache owns the bike while it is active (the guard design 7.2 to 7.4): tube, header and lever are its. S3 only.
    val guardState by vm.guardState.collectAsStateWithLifecycle()
    val guard = guardState.takeIf { it.active && FamilyGate.guard(gate) }

    // The top tube shows the state; now and then it calls out a Dutch word first (see rememberHeroMoments).
    val res = LocalContext.current.resources
    val config = LocalConfiguration.current
    val view = remember(s.link, s.speedKmh, s.charging, s.battery, s.faults, s.lock, s.upload, s.busy, bleReady, config, guard?.phase) {
        tubeView(s, bleReady, res, guard?.phase)
    }
    val unlockClock = remember { UnlockClock() }
    // Theft is a serious moment: while the Wache is active the tube calls nothing out and the bike is never "not found".
    val moments = rememberHeroMoments(vm, s, view, bike, bleReady, unlockClock, suppressed = guard != null)
    // After more than 12 seconds without an answer the tube settles on the Bluetooth glyph: "?" for a bike that
    // is simply not found, the search word when the VanMoof app probably holds the connection.
    val shown = if (moments.notFound) notFoundView(res, moments.vanMoofApp, view) else view
    val stage = rememberOutburstStage(calm = shown.frame, cue = moments.cue, blocked = !bleReady || guard != null)
    GuardAlarmHaptics(guard)
    val openCockpit = rememberCockpitOpener(vm)
    val heroOffer = rememberHeroCockpitOffer(vm)
    val offerDismissed by CockpitRuntime.suggestDismissedState.collectAsStateWithLifecycle()

    // The last moment the bike was connected: the facts below say from when their values are once it is not.
    var lastReadyAt by rememberSaveable { mutableLongStateOf(0L) }
    val ready = s.isReady
    DisposableEffect(ready) {
        if (ready) lastReadyAt = System.currentTimeMillis()
        onDispose { if (ready) lastReadyAt = System.currentTimeMillis() }
    }

    // Faults count only from a bike that is connected; a list kept from earlier is one quiet line.
    val faults = if (ready) s.faults else emptyList()
    // Fault 42 explains itself once per session, after the call on the tube and only while the bike stands (the full
    // explanation after the stop); later episodes keep to the fault line. With Diagnose beside, its paragraph stands
    // there once, and the line here only leads to it.
    val fault42Ended = rememberEndedEpisodes(42 in faults)
    val explain42 = faults.isNotEmpty() && faults.min() == 42 && fault42Ended == 0 && !s.moving && !diagnosisBeside

    Column(modifier.fillMaxWidth().skipOnTouch(stage)) {
        HeroHeader(s, guard?.phase)
        Spacer(Modifier.height(S3.m))

        if (bike != null) {
            ShellFitText(bike.name, S3.type.display, c.paint)
            Spacer(Modifier.height(S3.xs))
            Text(bikeCaption(bike, if (s.bike != null) s.family else bike.family), style = S3.type.caption, color = c.fog)
        } else {
            ShellFitText(
                stringResource(if (needsChoice) R.string.shell_hero_which_bike else R.string.shell_hero_no_bike),
                S3.type.display,
                c.paint,
            )
            Spacer(Modifier.height(S3.xs))
            Text(
                if (needsChoice) pluralStringResource(R.plurals.shell_hero_bikes_saved, vault.bikes.size, vault.bikes.size)
                else stringResource(R.string.shell_hero_add_bike),
                style = S3.type.caption,
                color = c.fog,
            )
        }

        Spacer(Modifier.height(S3.l))
        HeroTube(shown, stage, lightsOn = s.light == LightMode.ON)

        // What it takes to get connected stands right under the tube, with its one action.
        when {
            bike == null -> {
                Spacer(Modifier.height(S3.l))
                BigButton(stringResource(R.string.shell_hero_choose_bike), onClick = nav.openGarage)
            }
            // Bluetooth itself is the blocker: fix that here, where the bike lives.
            readiness != null && !readiness.ready -> {
                Spacer(Modifier.height(S3.l))
                HeroBleFix(readiness)
            }
            s.link == Link.LOST || (s.link == Link.SEARCHING && s.linkMessage != null) || moments.notFound -> {
                Spacer(Modifier.height(S3.l))
                if (moments.notFound) {
                    // More than 12 seconds without an answer, the bike stands: title and button from the start,
                    // the explaining sentence after BALEN (or GEDOE, when the VanMoof app is installed).
                    ShellNotice(
                        title = stringResource(R.string.shell_notfound_title),
                        text = stringResource(R.string.shell_notfound_text, bike.name),
                        textModifier = Modifier.coverUntilSentence(stage, Moment.NotFound),
                    )
                } else if (guard == null) {
                    Hint(s.linkMessage ?: stringResource(R.string.shell_hero_link_lost))
                }
                // While the Wache is active the controller's advice ("move the bike or press the bell to wake it") would
                // set off the bike's own watch: the Wache section explains the link instead, the search button stays.
                Spacer(Modifier.height(S3.m))
                BigButton(stringResource(R.string.shell_hero_search_again), onClick = { vm.bike.retryNow() })
                Spacer(Modifier.height(S3.l))
                OfficialAppNotice(vm, explainModifier = Modifier.coverUntilSentence(stage, Moment.NotFound))
            }
            s.link == Link.OFFLINE -> {
                Spacer(Modifier.height(S3.l))
                BigButton(stringResource(R.string.shell_hero_connect), onClick = { vm.connect(bike) })
                Spacer(Modifier.height(S3.s))
                OfficialAppNotice(vm, fallback = { Hint(stringResource(R.string.shell_hero_close_vanmoof)) })
            }
        }

        Spacer(Modifier.height(S3.l))
        HeroFacts(s, controlsVisible, staleSince = lastReadyAt.takeIf { !ready && bike != null && it > 0L })
        if (ready && guard == null) {
            // The quiet way into the cockpit while the bike stands. The slot keeps its height while riding (the
            // offer then takes the lever's place), so nothing above the lever moves when the bike starts or stops.
            // After "Nicht jetzt" the offer stays away for the ride, and the quiet link comes back here.
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (!s.moving || offerDismissed) TextLink(stringResource(R.string.cockpit_open), openCockpit)
            }
        }

        if (faults.isNotEmpty()) {
            Spacer(Modifier.height(S3.m))
            FaultLine(faults, gate, explained = explain42 || diagnosisBeside) { nav.go(Destination.DIAGNOSIS) }
            // Fault 42 has its sentence right here; it waits for the call on the tube, the title and the link do not.
            if (explain42) {
                Spacer(Modifier.height(S3.m))
                Text(
                    s.describeFault(42).hint,
                    style = S3.type.body,
                    color = c.paint,
                    modifier = Modifier.coverUntilSentence(stage, Moment.Fault42),
                )
            }
        } else if (!ready && bike != null && s.faults.isNotEmpty()) {
            // Not connected: what the bike said last, without signal and without a way in.
            Spacer(Modifier.height(S3.m))
            Hint(stringResource(R.string.shell_fault_last, s.faults.min()))
        }

        if (bike != null) {
            // Right under the cockpit line its own 48 dp already give room; after anything else the full gap.
            val underLink = ready && faults.isEmpty() && guard == null
            Spacer(Modifier.height(if (underLink) S3.l else S3.xl))
            if (guard != null) {
                // A bike that moves while the Wache is active is not a ride: the lever stays and is the way out.
                GuardLever(vm, guard, ready)
            } else if (heroOffer) {
                // Riding: the lever has nothing to do (locking waits for the stop), so the cockpit takes its place.
                CockpitOffer(vm)
            } else {
                UnlockSlider(
                    locked = s.lock?.let { it != LockState.UNLOCKED },
                    busy = Op.UNLOCK in s.busy || Op.LOCK in s.busy,
                    onUnlock = {
                        unlockClock.startedAt = SystemClock.elapsedRealtime()
                        tracker.mark(Op.UNLOCK); vm.bike.clearCommandError(); vm.bike.unlock()
                    },
                    // Locking only while the bike stands (every family); an S2 locks with the foot at the kick lock, its
                    // remote lock exists only in S2 test mode. Unlocking stays offered while rolling.
                    onLock = if (FamilyGate.remoteLock(gate)) ({ tracker.mark(Op.LOCK); vm.bike.clearCommandError(); vm.bike.lock() }) else null,
                    enabled = s.isReady,
                    // The lever has no disabled look of its own; offline it steps back instead of glowing red.
                    modifier = Modifier.alpha(if (s.isReady) 1f else 0.45f),
                )
                val error = s.lastCommandError?.takeIf { tracker.op in LockOps }
                when {
                    error != null -> {
                        Spacer(Modifier.height(S3.s))
                        ShellCommandError(error, onExpire = { vm.bike.clearCommandError(); tracker.clear() })
                    }
                    // Not connected: the action to connect stands under the tube, the lever needs no sentence.
                    !s.isReady -> Unit
                    s.lock == LockState.PIN_ENTRY -> {
                        Spacer(Modifier.height(S3.s))
                        Hint(stringResource(R.string.shell_hero_pin_entry), color = c.lamp)
                    }
                    // S2: the kick lock opens once the bike rolls; locking is done with the foot.
                    FamilyGate.rollToUnlock(gate) && s.lock == LockState.LOCKED -> {
                        Spacer(Modifier.height(S3.s))
                        Hint(stringResource(R.string.s2ui_roll_to_unlock))
                    }
                    // Not while rolling: the slider has no lock then (FamilyGate.remoteLock), and the foot is busy.
                    FamilyGate.rollToUnlock(gate) && s.lock == LockState.UNLOCKED && !FamilyGate.remoteLock(gate) && !s.moving -> {
                        Spacer(Modifier.height(S3.s))
                        Hint(stringResource(R.string.s2ui_lock_by_foot))
                    }
                    // Rolling and open: the lever says why it does not lock now.
                    s.moving && s.lock == LockState.UNLOCKED && !FamilyGate.rollToUnlock(gate) -> {
                        Spacer(Modifier.height(S3.s))
                        Hint(stringResource(R.string.shell_hero_lock_parked))
                    }
                }
            }
            // After the person's own unlock: how long it took and what the battery says. HÈ HÈ comes before this sentence.
            moments.unlockNote?.takeIf { s.isReady && s.lock == LockState.UNLOCKED }?.let { note ->
                Spacer(Modifier.height(S3.l))
                ShellNotice(
                    title = stringResource(R.string.shell_lock_unlocked),
                    text = unlockText(note),
                    textModifier = Modifier.coverUntilSentence(stage, Moment.Relief),
                )
            }
        }
    }
}

// --- Rare calls on the top tube -------------------------------------------------------------------

/** The bike has to be silent this long before YABA says it was not found (the text says "more than 12 seconds"). */
private const val NotFoundAfterMs = 12_000L

/** When the person started the unlock; read once when the bike reports the lock open. */
private class UnlockClock {
    var startedAt = 0L
}

/** [relief]: a bike was not found earlier in this session, so this unlock may be answered with HÈ HÈ. Fixed when the note is made. */
private class UnlockNote(val seconds: Float, val battery: Int, val first: Boolean, val relief: Boolean, val token: Int)

private class HeroMoments(val cue: OutburstCue?, val notFound: Boolean, val vanMoofApp: Boolean, val unlockNote: UnlockNote?)

/** Counts up each time [active] ends, so that the next time it starts it is a new occurrence with a new key. */
@Composable
private fun rememberEpisode(active: Boolean): Int {
    var episode by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(active) { if (!active) episode++ }
    return episode
}

/** How many times [active] has gone from true to false in this session: 0 during (and before) the first episode. */
@Composable
private fun rememberEndedEpisodes(active: Boolean): Int {
    var ended by rememberSaveable { mutableIntStateOf(0) }
    var was by rememberSaveable { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (was && !active) ended++
        was = active
    }
    return ended
}

/**
 * Which of the hero's moments is on right now: the bike is not found (no answer for more than 12 seconds while
 * it stands: BALEN, or GEDOE with the VanMoof app installed), fault 42 stands on the tube (VERDORIE), or the
 * person's unlock has just come through after one of those (HÈ HÈ). At most one cue at a time.
 */
@Composable
private fun rememberHeroMoments(
    vm: AppViewModel,
    s: BikeState,
    view: RideTubeView,
    bike: BikeCredentials?,
    bleReady: Boolean,
    unlockClock: UnlockClock,
    suppressed: Boolean = false,
): HeroMoments {
    val context = LocalContext.current
    val policy = remember { Outbursts.policy(context) }

    // [suppressed]: the Wache is active; its section says what the link does, and the tube calls nothing out.
    val searching = !suppressed && bike != null && bleReady && (s.link == Link.SEARCHING || s.link == Link.LOST)
    var notFound by remember { mutableStateOf(false) }
    LaunchedEffect(searching, bike?.mac) {
        notFound = false
        if (searching) {
            delay(NotFoundAfterMs)
            // A link that dropped during a ride is not a bike that was not found: it rides on.
            notFound = !Outbursts.driveLock(context).locked()
        }
    }
    val notFoundEpisode = rememberEpisode(notFound)
    val vanMoofApp = remember(notFound) { notFound && vm.officialAppInstalled() }

    // E42 on the tube means the bike stands (speed under 1 km/h) and is not charging.
    val fault42 = s.isReady && view.fault == 42
    val faultEpisode = rememberEpisode(fault42)

    var note by remember { mutableStateOf<UnlockNote?>(null) }
    var notes by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.lock, s.isReady) {
        if (s.isReady && s.lock == LockState.UNLOCKED) {
            val seconds = (SystemClock.elapsedRealtime() - unlockClock.startedAt) / 1000f
            val battery = s.battery
            // Only the person's own unlock counts here; a stale start (the unlock failed once) is ignored.
            if (unlockClock.startedAt != 0L && seconds in 0f..30f && battery != null) {
                val first = !policy.firstRideDone
                if (first) policy.firstRideDone = true
                notes++
                note = UnlockNote(seconds, battery, first, policy.sighPending, notes)
            }
            unlockClock.startedAt = 0L
        } else {
            note = null
        }
    }

    val shown = note
    val cue = when {
        suppressed -> null
        // The flag is the one from when the unlock came through: the count that the strike frame raises must not change the cue mid-call.
        shown != null && shown.relief -> OutburstCue(Moment.Relief, outburstFor(Moment.Relief), shown.token)
        fault42 -> OutburstCue(Moment.Fault42, outburstFor(Moment.Fault42), faultEpisode)
        notFound -> OutburstCue(Moment.NotFound, outburstFor(Moment.NotFound, vanMoofApp), notFoundEpisode)
        else -> null
    }
    return HeroMoments(cue, notFound, vanMoofApp, shown)
}

private fun notFoundView(res: Resources, vanMoofApp: Boolean, view: RideTubeView) = RideTubeView(
    bleWordFrame(if (vanMoofApp) res.getString(R.string.onb_matrix_scan) else "?"),
    view.tone,
    res.getString(R.string.shell_notfound_title),
)

@Composable
private fun unlockText(note: UnlockNote): String {
    val locale = LocalConfiguration.current.locales[0]
    val done = stringResource(R.string.shell_unlock_done_text, String.format(locale, "%.1f", note.seconds), note.battery)
    return if (note.first) done + " " + stringResource(R.string.shell_unlock_first_ride) else done
}

/**
 * What blocks Bluetooth and the one action that fixes it. Replaces "Verbinden" while Bluetooth
 * is not ready; the other destinations show their own short prompt with the same verb.
 */
@Composable
private fun HeroBleFix(readiness: BleReadiness) {
    val c = S3.colors
    val context = LocalContext.current
    val prompt = LocalShellBlePrompt.current
    val needsPermission = !readiness.permissionsGranted
    val needsLocation = !needsPermission && readiness.bluetoothOn && !readiness.locationOn
    val message = stringResource(
        when {
            needsLocation -> R.string.shell_ble_location_off
            !needsPermission -> R.string.shell_ble_off
            Build.VERSION.SDK_INT >= 31 -> R.string.shell_ble_needs_nearby
            else -> R.string.shell_ble_needs_location
        },
    )
    val action = stringResource(
        when {
            needsLocation -> R.string.shell_ble_action_location
            needsPermission -> R.string.shell_ble_action_allow
            else -> R.string.shell_ble_action_enable
        },
    )

    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(S3.xs),
    ) {
        ShellLed(lit = true, color = c.lamp, glow = Color.Transparent, modifier = Modifier.padding(top = 3.dp))
        Text(message, style = S3.type.body, color = c.paint, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(S3.m))
    BigButton(
        action,
        onClick = {
            when {
                needsLocation -> readiness.openLocationSettings()
                needsPermission -> {
                    prompt.asked = true
                    readiness.requestPermissions()
                }
                else -> readiness.requestEnable()
            }
        },
    )
    // Android stops showing the prompt after two refusals; then only the settings page helps.
    if (needsPermission && prompt.asked) {
        Spacer(Modifier.height(S3.m))
        Hint(stringResource(R.string.shell_ble_settings_hint))
        Spacer(Modifier.height(S3.s))
        QuietButton(
            stringResource(R.string.shell_ble_open_settings),
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** [guardPhase]: the Wache is active, so the note on the right is its own (5561 may read 2 while plainly watching). */
@Composable
private fun HeroHeader(s: BikeState, guardPhase: GuardPhase? = null) {
    val c = S3.colors
    val reduce = rememberReducedMotion()
    val (textRes, color) = when (s.link) {
        Link.SEARCHING -> R.string.shell_link_searching to c.lamp
        Link.CONNECTING -> R.string.shell_link_connecting to c.lamp
        Link.AUTHENTICATING -> R.string.shell_link_authenticating to c.lamp
        Link.READY -> R.string.shell_link_ready to c.led
        Link.LOST -> R.string.shell_link_lost to c.signal
        Link.OFFLINE -> R.string.shell_link_offline to c.fog
    }
    val pulsing = s.link != Link.READY && s.link != Link.OFFLINE && !reduce
    // What the bike is doing, when it is worth knowing.
    val status = s.status
    val noteColor: Color? = if (s.isReady) {
        when (status) {
            BikeStatus.SLEEPING, BikeStatus.UPDATING -> c.lamp
            BikeStatus.SHIPPING, BikeStatus.ALARM -> c.signal
            else -> null
        }
    } else null
    // Lost and ending: no note, the link indicator says it.
    val guardNote: Pair<String, Color>? = when (guardPhase) {
        GuardPhase.ARMING, GuardPhase.WATCHING, GuardPhase.REARMING -> stringResource(R.string.guard_header_armed) to c.led
        GuardPhase.ALARM -> stringResource(R.string.guard_header_alarm) to c.signal
        else -> null
    }

    // The first line of Fahren on the cover screen: the shell's corner (the language) stands at its end, beside the status.
    ShellCornerLine {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkIndicator(
                text = stringResource(textRes),
                color = color,
                pulsing = pulsing,
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            )
            when {
                guardPhase != null -> guardNote?.let { (text, color) -> Text(text, style = S3.type.label, color = color) }
                status != null && noteColor != null -> Text(status.label, style = S3.type.label, color = noteColor)
            }
        }
    }
}

@Composable
private fun bikeCaption(bike: BikeCredentials, family: BikeFamily): String {
    val fallback = if (family == BikeFamily.S2) DefaultModelS2 else DefaultModel
    val model = bike.modelName?.takeIf { it.isNotBlank() } ?: fallback
    val owner = bike.ownerName?.takeIf { it.isNotBlank() }
    return if (owner != null) stringResource(R.string.shell_bike_caption_owner, model, owner) else model
}

// --- Top tube -------------------------------------------------------------------------------------

private enum class RideTubeTone { LED, SIGNAL, DIM }

/** [blink]: the Wache's alarm, 500 ms on and 500 ms off with hard cuts (BRAND: blinking only for the alarm, never above 3 Hz). */
private class RideTubeView(
    val frame: MatrixFrame,
    val tone: RideTubeTone,
    val description: String,
    val fault: Int? = null,
    val blink: Boolean = false,
)

/** The hero tube's width in columns ([HeroTube]). */
internal const val HeroTubeColumns = 40

/**
 * The Bluetooth glyph, two columns of air and [word]: the frame onboarding draws while it searches (FindStep), with the same
 * word (onb_matrix_scan: SUCHE, ZOEK, SCAN) for the same state. Every word of the searching and Bluetooth-off frames is at
 * most 5 letters, so the frame keeps to 36 columns (SUCHE) in every language and never runs as a marquee (TubeFrameTest
 * reads the words from the resources).
 */
internal fun bleWordFrame(word: String): MatrixFrame =
    MatrixFrame.text(MatrixFont.BLE.toString()) + MatrixFrame.gap(2) + MatrixFrame.text(word)

/**
 * The armed Wache on the tube: the closed lock, two columns of air and the Wache's word (guard_matrix_armed: WACHE, WAAK,
 * GARDE, GUARD). The lock glyph is 7 columns, so a 5-letter word makes 38 of the hero's 40 (TubeFrameTest).
 */
internal fun guardArmedFrame(word: String): MatrixFrame =
    MatrixFrame.text(MatrixFont.LOCKED.toString()) + MatrixFrame.gap(2) + MatrixFrame.text(word)

/**
 * The matrix shows at most 40 columns (5 per letter, 3 for I and 1, 1 between), so every frame
 * here fits without a marquee: short words from the matrix_ resources, glyphs and numbers.
 */
private fun tubeView(s: BikeState, bleReady: Boolean, res: Resources, guardPhase: GuardPhase? = null): RideTubeView {
    val battery = s.battery
    val percent = if (battery != null) "$battery%" else "--%"
    if (!bleReady && s.link != Link.READY) {
        return RideTubeView(
            bleWordFrame(res.getString(R.string.shell_matrix_ble_off)),
            RideTubeTone.DIM,
            res.getString(R.string.shell_tube_ble_off_cd),
        )
    }
    if (guardPhase != null && guardPhase != GuardPhase.OFF) return guardTubeView(s, guardPhase, res)
    return when (s.link) {
        Link.READY -> {
            val speed = s.speedKmh ?: 0
            val faults = s.faults
            val upload = s.upload?.takeIf { Op.UPLOAD in s.busy }
            when {
                speed >= 1 -> speedView(speed, res)
                // A sound on its way to the bike: the bell and how far it is (29 columns move at most 29 times).
                upload != null -> {
                    val fraction = upload.coerceIn(0f, 1f)
                    RideTubeView(
                        MatrixFrame.text(MatrixFont.BELL.toString()) + MatrixFrame.gap(2) + MatrixFrame.bar(fraction, 29),
                        RideTubeTone.LED,
                        res.getString(R.string.shell_tube_upload_cd, (fraction * 100).roundToInt()),
                    )
                }
                s.charging == true -> RideTubeView(
                    MatrixFrame.text("${MatrixFont.BOLT} $percent"),
                    RideTubeTone.LED,
                    if (battery != null) res.getString(R.string.shell_tube_charging_cd, battery)
                    else res.getString(R.string.shell_tube_charging_unknown_cd),
                )
                faults.isNotEmpty() -> {
                    val code = faults.min()
                    RideTubeView(
                        MatrixFrame.text(res.getString(R.string.shell_matrix_fault, code)),
                        RideTubeTone.SIGNAL,
                        res.getString(R.string.shell_fault_title, code, s.describeFault(code).name),
                        fault = code,
                    )
                }
                else -> {
                    val glyph = if (s.lock == LockState.UNLOCKED) MatrixFont.UNLOCKED else MatrixFont.LOCKED
                    var frame = MatrixFrame.text(glyph.toString()) + MatrixFrame.gap(2) + MatrixFrame.text(percent)
                    if (battery != null) frame = frame + MatrixFrame.gap(2) + MatrixFrame.bar(battery / 100f, 8)
                    val lock = res.getString(
                        when (s.lock) {
                            LockState.UNLOCKED -> R.string.shell_lock_unlocked
                            LockState.LOCKED -> R.string.shell_lock_locked
                            LockState.PIN_ENTRY -> R.string.shell_lock_pin_entry
                            null -> R.string.shell_lock_unknown
                        },
                    )
                    RideTubeView(
                        frame,
                        RideTubeTone.LED,
                        if (battery != null) res.getString(R.string.shell_tube_lock_battery_cd, lock, battery) else lock,
                    )
                }
            }
        }
        Link.SEARCHING -> RideTubeView(
            bleWordFrame(res.getString(R.string.onb_matrix_scan)),
            RideTubeTone.LED,
            res.getString(R.string.shell_link_searching),
        )
        // The Bluetooth glyph and three dots: no word, so it fits in every language.
        Link.CONNECTING, Link.AUTHENTICATING -> RideTubeView(
            MatrixFrame.text("${MatrixFont.BLE} ..."),
            RideTubeTone.LED,
            res.getString(R.string.shell_tube_connecting_cd),
        )
        Link.LOST, Link.OFFLINE -> RideTubeView(
            MatrixFrame.text(res.getString(R.string.shell_matrix_offline)),
            RideTubeTone.DIM,
            res.getString(R.string.shell_link_offline),
        )
    }
}

private fun speedView(speed: Int, res: Resources) = RideTubeView(
    MatrixFrame.text(res.getString(R.string.shell_matrix_speed, speed)),
    RideTubeTone.LED,
    res.getString(R.string.shell_tube_riding_cd, speed),
)

/**
 * The tube while the Wache is active (the guard design 7.2), after the Bluetooth-off frame: ALARM blinks in signal; a bike
 * that rolls shows its speed (someone is moving it, the trigger follows within seconds); otherwise the lock and the Wache's
 * word, led while it watches over a live link, dim while it arms, is lost or ends. It replaces the fault, charging, upload,
 * searching and offline frames: the fault stays in the fault line, the link in the header.
 */
private fun guardTubeView(s: BikeState, phase: GuardPhase, res: Resources): RideTubeView {
    val speed = s.speedKmh ?: 0
    val live = s.link == Link.READY
    return when {
        phase == GuardPhase.ALARM -> RideTubeView(
            MatrixFrame.text(res.getString(R.string.guard_matrix_alarm)),
            RideTubeTone.SIGNAL,
            res.getString(R.string.guard_tube_alarm_cd),
            blink = true,
        )
        live && speed >= 1 -> speedView(speed, res)
        else -> {
            val watching = phase == GuardPhase.WATCHING || phase == GuardPhase.REARMING
            val battery = s.battery
            RideTubeView(
                guardArmedFrame(res.getString(R.string.guard_matrix_armed)),
                if (live && watching) RideTubeTone.LED else RideTubeTone.DIM,
                when {
                    phase == GuardPhase.ARMING -> res.getString(R.string.guard_tube_arming_cd)
                    phase == GuardPhase.DISARMING -> res.getString(R.string.guard_disarming)
                    phase == GuardPhase.LOST || !live -> res.getString(R.string.guard_tube_lost_cd)
                    battery != null -> res.getString(R.string.guard_tube_armed_cd, battery)
                    else -> res.getString(R.string.guard_tube_armed_nobattery_cd)
                },
            )
        }
    }
}

@Composable
private fun HeroTube(view: RideTubeView, stage: OutburstStage, lightsOn: Boolean) {
    val c = S3.colors
    // A call on the tube is always led. Only when the state is back does a fault turn the matrix signal red
    // and an unreachable bike dim it; BikeTube draws its matrix in the palette's led colour.
    val tone = if (stage.showingState) view.tone else RideTubeTone.LED
    val palette: S3Palette = remember(c, tone) {
        when (tone) {
            RideTubeTone.LED -> c
            RideTubeTone.SIGNAL -> c.copy(led = c.signal, ledGlow = if (c.isDark) c.signal.copy(alpha = 0.33f) else Color.Transparent)
            RideTubeTone.DIM -> c.copy(led = c.fog, ledGlow = Color.Transparent)
        }
    }
    // The Wache's alarm blinks with hard cuts, 500 ms on and 500 ms off; under reduced motion it stands.
    val reduce = rememberReducedMotion()
    var dark by remember { mutableStateOf(false) }
    LaunchedEffect(view.blink, reduce) {
        dark = false
        if (view.blink && !reduce) {
            while (true) {
                delay(500)
                dark = !dark
            }
        }
    }
    CompositionLocalProvider(LocalS3Palette provides palette) {
        if (view.blink) {
            BikeTube(
                frame = if (dark) MatrixFrame.Empty else stage.frame,
                lightsOn = lightsOn,
                columns = HeroTubeColumns,
                description = view.description,
                sweepMillis = 0,
            )
        } else {
            OutburstTube(stage, lightsOn = lightsOn, description = view.description, columns = HeroTubeColumns)
        }
    }
}

/**
 * The lock lever while the Wache is active (the guard design 7.4): sliding it open ends the Wache and unlocks the bike. It is
 * never the cockpit's place then. Offered in every active phase (5.1: "Wache beenden" works in all of them, during arming it
 * cancels); full strength also while the link is down, with the sentence that says what sliding then does. While the Wache
 * ends it is busy ("Entsperren läuft", true: the disarm unlocks); while that end waits for a link that is down, the track
 * says "Offline" instead, since nothing is being unlocked yet and maybe nothing will be.
 */
@Composable
private fun GuardLever(vm: AppViewModel, guard: GuardState, ready: Boolean) {
    val ending = guard.phase == GuardPhase.DISARMING
    UnlockSlider(
        locked = true,
        busy = ending && ready,
        onUnlock = { vm.guard.disarm() },
        onLock = null,
        enabled = !(ending && !ready),
    )
    Spacer(Modifier.height(S3.s))
    Hint(
        if (guard.phase == GuardPhase.LOST || !ready) {
            stringResource(R.string.guard_lever_hint_offline, (GuardRules.DISARM_LINK_WAIT_MS / 1000).toInt())
        } else {
            stringResource(R.string.guard_lever_hint)
        },
    )
}

/** When the Wache turns to ALARM while Fahren is on screen: two hard pulses, 300 ms apart. Not again after a fold. */
@Composable
private fun GuardAlarmHaptics(guard: GuardState?) {
    val haptics = rememberHaptics()
    val alarming = guard?.alarming == true
    val at = guard?.lastAlarm?.atMillis
    LaunchedEffect(alarming) {
        if (alarming && at != null && System.currentTimeMillis() - at <= 3_000) {
            haptics.reject()
            delay(300)
            haptics.reject()
        }
    }
}

// --- Facts ----------------------------------------------------------------------------------------

/**
 * Battery is the live number a rider checks, so it is the big one; the odometer sits next to it.
 * Assist level and limit only show while RideControls are off screen ([controlsVisible] false).
 * [staleSince]: not connected, the values are the last ones seen; they step back to fog and say from when.
 */
@Composable
private fun HeroFacts(s: BikeState, controlsVisible: Boolean, staleSince: Long? = null) {
    val c = S3.colors
    val battery = s.battery
    val charging = s.charging == true
    val low = battery != null && battery <= 15
    val locale = LocalConfiguration.current.locales[0]
    val dash = "–"
    val stale = !s.isReady
    val valueColor = if (stale) c.fog else c.paint

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.m)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(S3.m),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(
                        battery?.let { stringResource(R.string.shell_fact_battery_value, it) } ?: dash,
                        style = S3.type.dataLarge,
                        color = when {
                            stale -> c.fog
                            charging || low -> c.lamp
                            else -> c.paint
                        },
                        maxLines = 1,
                    )
                    Text(
                        stringResource(if (charging && !stale) R.string.shell_fact_battery_charging else R.string.shell_fact_battery),
                        style = S3.type.caption,
                        color = c.fog,
                        maxLines = 1,
                    )
                }
                Fact(
                    label = stringResource(R.string.shell_fact_odometer),
                    value = s.distanceKm?.let { stringResource(R.string.shell_fact_odometer_value, formatKm(it, locale)) } ?: dash,
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                    valueColor = valueColor,
                )
            }
            if (staleSince != null && (battery != null || s.distanceKm != null)) {
                val time = remember(staleSince, locale) {
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
                        .format(Instant.ofEpochMilli(staleSince).atZone(ZoneId.systemDefault()))
                }
                Spacer(Modifier.height(S3.xs))
                Hint(stringResource(R.string.shell_facts_stale, time))
            }
        }
        if (!controlsVisible) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.m)) {
                Fact(
                    label = stringResource(R.string.shell_fact_level),
                    value = s.powerLevel?.let { if (it == 0) stringResource(R.string.shell_power_off) else it.toString() } ?: dash,
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                    valueColor = valueColor,
                )
                Fact(
                    label = stringResource(R.string.shell_fact_limit),
                    value = s.speedLimit?.let { stringResource(R.string.shell_fact_limit_value, it.kmh) } ?: dash,
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                    valueColor = valueColor,
                )
            }
        }
    }
}

private fun formatKm(km: Double, locale: java.util.Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(km)

/**
 * The lowest fault, tappable into Diagnose. [explained]: fault 42's own paragraph stands under this line or in the
 * Diagnose pane beside it; otherwise a lone 42 names the first thing to check itself.
 */
@Composable
private fun FaultLine(faults: List<Int>, s: BikeState, explained: Boolean, onOpen: () -> Unit) {
    val c = S3.colors
    val code = faults.min()
    // The family's own table: an S2's 16 is a hall-sensor fault, an S3's the battery.
    val fault = s.describeFault(code)
    val more = faults.size - 1
    val openLabel = stringResource(R.string.shell_fault_open_cd)
    val title = S3.type.bodyStrong
    // The dot centred on the first line of the title, whatever the font size.
    val dot = 8.dp
    val dotTop = with(LocalDensity.current) { ((title.lineHeight.toDp() - dot) / 2).coerceAtLeast(0.dp) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .padding(vertical = S3.s),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = dotTop).size(dot).clip(CircleShape).background(c.signal))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.shell_fault_title, code, fault.name), style = title, color = c.signal)
            Text(
                when {
                    more > 0 -> pluralStringResource(R.plurals.shell_fault_more, more, more)
                    code == 42 && !explained && s.family != BikeFamily.S2 -> stringResource(R.string.shell_fault_42_tap)
                    else -> stringResource(R.string.shell_fault_tap)
                },
                style = S3.type.caption,
                color = c.fog,
            )
        }
    }
}

// --- Controls -------------------------------------------------------------------------------------

private val Sending: @Composable RowScope.() -> Unit = { ShellSending() }

/**
 * Everything you set before riding: speed limit, assist, light, alarm, and the shifting last (its profiles are
 * the longest block). With [wide] it scrolls and pads itself; otherwise it sits inside RideScreen.
 */
@Composable
fun RideControls(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val s by vm.bikeState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Before the first connect the stored bike's family decides which controls stand here (an S2: levels 0 to 4,
    // the alarm read-only, no shifting). Values, link and busy come from the live state.
    val vault by vm.vault.collectAsStateWithLifecycle()
    val s2TestMode by vm.bike.s2Prefs.testMode.collectAsStateWithLifecycle()
    val preferred = remember(vault) { vm.preferredBike() }
    val gate = FamilyGate.beforeConnect(s, preferred, s2TestMode)
    // The Wache (the guard design 7.1): S3 only. While OFF its section stands after the alarm; while active it is first.
    val guardState by vm.guardState.collectAsStateWithLifecycle()
    val guardShown = FamilyGate.guard(gate)
    val guardOn = guardShown && guardState.active
    val tracker = LocalShellCommands.current
    val c = S3.colors
    val ready = s.isReady
    val owner = tracker.op
    val error = s.lastCommandError
    val expire = { vm.bike.clearCommandError(); tracker.clear() }
    fun errorFor(op: Op): String? = error?.takeIf { owner == op }
    fun send(op: Op, action: () -> Unit) {
        tracker.mark(op)
        vm.bike.clearCommandError()
        action()
    }

    val applyLimit: (SpeedLimit) -> Unit = { limit -> send(Op.SPEED) { vm.bike.setSpeedLimit(limit) } }

    val lightOptions = LightMode.entries.map { it to it.label }
    val alarmOptions = listOf(false to stringResource(R.string.shell_alarm_off), true to stringResource(R.string.shell_alarm_on))
    // S2: off, manual, automatic (the bike's own three stages).
    val alarmStages = listOf(
        AlarmStage.DISARMED to stringResource(R.string.shell_alarm_off),
        AlarmStage.ARMED to stringResource(R.string.s2ui_alarm_manual),
        AlarmStage.AUTOMATIC to stringResource(R.string.core_alarm_stage_automatic),
    )
    // Speed++: a 28-inch bike set to 24 inches. Its own matrix then reads low, the app shows the real speed.
    // Exactly when YABA scales the speed: it follows a stock answered on the Bike tab at once.
    val speedPlus = s.speedFactor > 1f
    // The bike reads 24″ and nothing says S3 or X3 (the question on the Bike tab): the speed is the bike's own, and this says so.
    val stockOpen = s.wheelStock?.let { SpeedPlus.view(s.isReady, s.wheelSize, it) } == SpeedPlusView.ASK

    val scroll = rememberScrollState()
    val frame = if (wide) {
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = S3.l, end = S3.l, top = S3.l, bottom = S3.xl)
    } else {
        Modifier.fillMaxWidth()
    }

    Column(modifier.then(frame)) {
        // Not connected: the hero carries the action to connect; while it connects, one line says the wait is short.
        if (!ready && (s.link == Link.SEARCHING || s.link == Link.CONNECTING || s.link == Link.AUTHENTICATING)) {
            Hint(stringResource(R.string.shell_controls_almost))
            Spacer(Modifier.height(S3.l))
        }
        // An error nobody here asked for (Touch-Unlock, another screen) still gets shown, up top.
        val orphan = error?.takeIf { owner == null || (owner !in ControlOps && owner !in LockOps) }
        if (orphan != null) {
            ShellCommandError(orphan, onExpire = expire)
            Spacer(Modifier.height(S3.l))
        }

        // The active Wache owns the bike: its section is the first thing under the hero (cover) or in the right pane.
        if (guardOn) {
            GuardSection(vm, s, guardState)
            Spacer(Modifier.height(S3.xl))
        }

        // Speed limit
        SectionTitle(stringResource(R.string.shell_section_speed), trailing = if (Op.SPEED in s.busy) Sending else null)
        Spacer(Modifier.height(S3.m))
        val limits = FamilyGate.speedLimits(gate)
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(S3.m, Alignment.CenterHorizontally),
        ) {
            for (limit in limits) {
                SpeedSign(
                    kmh = limit.kmh,
                    region = limit.region,
                    selected = s.speedLimit == limit,
                    onClick = {
                        when {
                            limit == s.speedLimit -> Unit
                            else -> applyLimit(limit)
                        }
                    },
                    enabled = ready,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 110.dp),
                )
            }
        }
        if (speedPlus) {
            Spacer(Modifier.height(S3.m))
            SpeedPlusNote(stringResource(R.string.shell_speedplus_note))
        } else if (stockOpen) {
            Spacer(Modifier.height(S3.m))
            SpeedPlusNote(stringResource(R.string.speedplus_stock_open_note, stringResource(R.string.shell_dest_bike), stringResource(R.string.bike_exp_title)))
        }
        errorFor(Op.SPEED)?.let {
            Spacer(Modifier.height(S3.s))
            ShellCommandError(it, onExpire = expire)
        }

        // Assist
        Spacer(Modifier.height(S3.xl))
        SectionTitle(stringResource(R.string.shell_section_assist), trailing = if (Op.POWER in s.busy) Sending else null)
        Spacer(Modifier.height(S3.m))
        PowerLadder(
            level = s.powerLevel,
            maxLevel = FamilyGate.maxPowerLevel(gate),
            onSelect = { level -> if (level != s.powerLevel) send(Op.POWER) { vm.bike.setPowerLevel(level) } },
            enabled = ready,
        )
        errorFor(Op.POWER)?.let {
            Spacer(Modifier.height(S3.s))
            ShellCommandError(it, onExpire = expire)
        }

        // Light: the bar stays led like every choice; a lamp dot marks a light that is on (lamp is never a surface).
        Spacer(Modifier.height(S3.xl))
        SectionTitle(stringResource(R.string.shell_section_light), trailing = if (Op.LIGHT in s.busy) Sending else null)
        Spacer(Modifier.height(S3.m))
        ChoiceBar(
            options = lightOptions,
            selected = s.light,
            onSelect = { mode -> if (mode != s.light) send(Op.LIGHT) { vm.bike.setLight(mode) } },
            enabled = ready,
            accent = c.led,
            onAccent = c.onLed,
            selectedDot = if (s.light == LightMode.ON || s.light == LightMode.AUTO) c.lamp else null,
        )
        errorFor(Op.LIGHT)?.let {
            Spacer(Modifier.height(S3.s))
            ShellCommandError(it, onExpire = expire)
        }

        // Alarm: led like every choice, a signal dot while armed. A triggered alarm has its own signal line below.
        Spacer(Modifier.height(S3.xl))
        SectionTitle(stringResource(R.string.shell_section_alarm), trailing = if (Op.ALARM in s.busy) Sending else null)
        Spacer(Modifier.height(S3.m))
        val armedDot = if (s.alarmArmed == true) c.signal else null
        when (FamilyGate.alarmControl(gate)) {
            // While the Wache is active the alarm stays on: shown, not changeable, with the reason.
            FamilyGate.AlarmControl.ON_OFF -> {
                ChoiceBar(
                    options = alarmOptions,
                    selected = s.alarmArmed,
                    onSelect = { on -> if (!guardOn && on != s.alarmArmed) send(Op.ALARM) { vm.bike.setAlarm(on) } },
                    enabled = ready,
                    accent = c.led,
                    onAccent = c.onLed,
                    selectedDot = armedDot,
                    readOnly = guardOn,
                )
                if (guardOn) {
                    Spacer(Modifier.height(S3.s))
                    Hint(stringResource(R.string.guard_alarm_choice_locked))
                }
            }
            FamilyGate.AlarmControl.THREE_WAY -> ChoiceBar(
                options = alarmStages,
                selected = s.alarmStage,
                onSelect = { stage -> if (stage != s.alarmStage) send(Op.ALARM) { vm.bike.setAlarmStage(stage) } },
                enabled = ready,
                accent = c.led,
                onAccent = c.onLed,
                selectedDot = armedDot,
            )
            // S2 outside test mode: the bike's stage, shown and not changeable, with the reason.
            FamilyGate.AlarmControl.READ_ONLY -> {
                // Read-only keeps the armed stage in led with its signal dot; offline greys it like every control.
                ChoiceBar(
                    options = alarmStages,
                    selected = s.alarmStage,
                    onSelect = {},
                    enabled = ready,
                    accent = c.led,
                    onAccent = c.onLed,
                    selectedDot = armedDot,
                    readOnly = true,
                )
                if (gate.family == BikeFamily.S2) {
                    Spacer(Modifier.height(S3.s))
                    Hint(stringResource(R.string.s2ui_alarm_read_only))
                }
            }
        }
        // The defence state only matters here while the alarm is actually going off. While the Wache is active 5523 = 2 is its
        // normal watch and its section owns the alarm; the line and the stop come back by themselves after it ends.
        s.defence?.takeIf { it.alarming && !guardOn }?.let {
            Spacer(Modifier.height(S3.m))
            ShellSignalLine(stringResource(R.string.shell_alarm_running, it.label))
            // Stop the running alarm from here (5523 = 0; standstill only, it cuts assist). S3 only.
            if (FamilyGate.alarmStop(gate)) {
                Spacer(Modifier.height(S3.s))
                AlarmStopRow(vm, s)
            }
        }
        // Touch Unlock is bypassed while the Wache is active (bell and boost are triggers then).
        if (settings.touchUnlock && FamilyGate.touchUnlock(gate) && !guardOn) {
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.shell_touch_unlock_hint))
        }
        errorFor(Op.ALARM)?.let {
            Spacer(Modifier.height(S3.s))
            ShellCommandError(it, onExpire = expire)
        }

        // The Wache while it is off: after the alarm, before the shifting.
        if (guardShown && !guardOn) {
            Spacer(Modifier.height(S3.xl))
            GuardSection(vm, s, guardState)
        }

        // Shifting (ShiftingBits.kt); only on bikes that offer 5537 and 5538
        if (FamilyGate.shifting(gate) && (!ready || s.shiftSupported)) {
            Spacer(Modifier.height(S3.xl))
            ShiftSection(vm, s, error = errorFor(Op.SHIFT), onExpire = expire, send = { action -> send(Op.SHIFT, action) })
        }
    }
}

/**
 * A lamp dot and a line in paint under the speed limit: Speed++ is on, so the bike's own speed reading is low; or the factory
 * wheel size is open, so YABA shows the bike's own reading and says where to answer.
 */
@Composable
private fun SpeedPlusNote(text: String) {
    val c = S3.colors
    val line = S3.type.label
    val dot = 6.dp
    val dotTop = with(LocalDensity.current) { ((line.lineHeight.toDp() - dot) / 2).coerceAtLeast(0.dp) }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = dotTop).size(dot).clip(CircleShape).background(c.lamp))
        Text(text, style = line, color = c.paint, modifier = Modifier.weight(1f))
    }
}

