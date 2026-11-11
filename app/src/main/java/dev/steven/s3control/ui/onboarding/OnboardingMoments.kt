package dev.steven.s3control.ui.onboarding

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.OutburstCue
import dev.steven.s3control.ui.components.Outbursts
import dev.steven.s3control.ui.components.outburstFor
import kotlinx.coroutines.delay

/*
 * The rare calls of the find and unlock steps. This is RideScreen.rememberHeroMoments, cut to what the onboarding needs
 * (RideScreen itself is left alone): the bike that is not found, the VanMoof app that holds the connection, and the unlock
 * that follows after one of those. Fault 42 and the server moments have no place here.
 */

/** The bike has to be silent this long before YABA says it was not found (the text says "more than 12 seconds"). */
internal const val NOT_FOUND_AFTER_MS = 12_000L

internal class FindMoments(val cue: OutburstCue?, val notFound: Boolean, val vanMoofApp: Boolean)

/** Counts up each time [active] ends, so that the next time it starts it is a new occurrence with a new key. */
@Composable
private fun rememberEpisode(active: Boolean): Int {
    var episode by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(active) { if (!active) episode++ }
    return episode
}

/** Whether the official VanMoof app is installed; looked up again whenever the app comes back to the front. */
@Composable
internal fun rememberOfficialAppInstalled(vm: AppViewModel): Boolean {
    var installed by remember { mutableStateOf(vm.officialAppInstalled()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) installed = vm.officialAppInstalled() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return installed
}

/**
 * The bike is not found when it stays silent for more than 12 seconds after the tap on "Search now" (the link has left
 * OFFLINE: searching, connecting, signing in to the bike), counted again after every retry ([attempt]). A link that dropped
 * during a ride is no bike that was not found, so the drive lock vetoes it. With the VanMoof app installed the call is GEDOE
 * instead of BALEN, never both.
 */
@Composable
internal fun rememberFindMoments(vm: AppViewModel, link: Link, bikeMac: String?, attempt: Int): FindMoments {
    val context = LocalContext.current
    val searching = bikeMac != null && link != Link.OFFLINE && link != Link.READY
    var notFound by remember { mutableStateOf(false) }
    LaunchedEffect(searching, bikeMac, attempt) {
        notFound = false
        if (searching) {
            delay(NOT_FOUND_AFTER_MS)
            notFound = !Outbursts.driveLock(context).locked()
        }
    }
    val episode = rememberEpisode(notFound)
    val installed = rememberOfficialAppInstalled(vm)
    val vanMoofApp = notFound && installed
    val cue = if (notFound) OutburstCue(Moment.NotFound, outburstFor(Moment.NotFound, vanMoofApp), episode) else null
    return FindMoments(cue, notFound, vanMoofApp)
}

/** When the person started the unlock; read once when the bike reports the lock open. */
internal class UnlockClock {
    var startedAt = 0L

    fun start() { startedAt = SystemClock.elapsedRealtime() }
}

/**
 * What the unlock took and what the battery says. [first] is the very first unlock of this installation ("Gute Fahrt." is said
 * exactly once, so the flag is set as the note is made). [relief] is fixed at that moment too: a bike was not found earlier in
 * this session, so HÈ HÈ may answer.
 */
internal class UnlockNote(val seconds: Float, val battery: Int, val first: Boolean, val relief: Boolean, val token: Int)

internal class UnlockMoments(val cue: OutburstCue?, val note: UnlockNote?)

@Composable
internal fun rememberUnlockMoments(s: BikeState, clock: UnlockClock): UnlockMoments {
    val context = LocalContext.current
    val policy = remember { Outbursts.policy(context) }
    var note by remember { mutableStateOf<UnlockNote?>(null) }
    var notes by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.lock, s.isReady, s.battery) {
        if (s.isReady && s.lock == LockState.UNLOCKED) {
            val seconds = (SystemClock.elapsedRealtime() - clock.startedAt) / 1000f
            val battery = s.battery
            when {
                // Only the person's own unlock counts here; a stale start (the unlock failed once) is ignored.
                clock.startedAt == 0L || seconds !in 0f..30f -> clock.startedAt = 0L
                battery != null -> {
                    val first = !policy.firstRideDone
                    if (first) policy.firstRideDone = true
                    notes++
                    note = UnlockNote(seconds, battery, first, policy.sighPending, notes)
                    clock.startedAt = 0L
                }
            }
        } else {
            note = null
        }
    }
    val shown = note
    // The flag is the one from when the unlock came through: the count that the strike frame raises must not change the cue mid-call.
    val cue = shown?.takeIf { it.relief }?.let { OutburstCue(Moment.Relief, outburstFor(Moment.Relief), it.token) }
    return UnlockMoments(cue, shown)
}
