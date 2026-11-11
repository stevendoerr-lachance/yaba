package dev.steven.s3control.ui.cockpit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.SwitchRow
import dev.steven.s3control.ui.onboarding.TextLink
import dev.steven.s3control.ui.theme.S3
import androidx.lifecycle.viewModelScope
import java.util.WeakHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/*
 * The ways into the cockpit: the entry in Diagnose (and in the S2 block) with its two settings folded away, the
 * quiet line under the ride hero while the bike stands, and the offer that takes the lock lever's place once the bike
 * has been moving for a few seconds.
 */

/**
 * What every "Cockpit öffnen" does: with the logger switch on and the logger stopped, the logger starts first, so its
 * permission dialog comes up while the phone is still in the hand. The cockpit opens either way. Reads the settings
 * when tapped, so the caller does not recompose with every logger sample.
 */
@Composable
fun rememberCockpitOpener(vm: AppViewModel): () -> Unit {
    val starter = rememberRideLoggerStarter(vm)
    return remember(vm, starter) {
        {
            if (vm.cockpitPrefs.value.startsLogger && !vm.recorder.value.active) {
                starter.start(mayAsk = true) { vm.openCockpit() }
            } else {
                vm.openCockpit()
            }
        }
    }
}

/**
 * "Cockpit öffnen" with one sentence, and under it "Cockpit-Einstellungen", which folds open the logger switch and
 * the colour choice. [quiet]: an outlined button, for a page whose one main action is something else (the S2 block,
 * or Diagnose while no bike is connected).
 */
@Composable
fun CockpitEntry(vm: AppViewModel, modifier: Modifier = Modifier, quiet: Boolean = false) {
    val prefs by vm.cockpitPrefs.collectAsStateWithLifecycle()
    val open = rememberCockpitOpener(vm)
    var settings by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (settings) R.string.shift_expanded else R.string.shift_collapsed)
    Column(modifier.fillMaxWidth()) {
        if (quiet) {
            QuietButton(stringResource(R.string.cockpit_open), open, Modifier.fillMaxWidth())
        } else {
            BigButton(stringResource(R.string.cockpit_open), open)
        }
        Hint(stringResource(R.string.cockpit_open_hint), Modifier.fillMaxWidth().padding(top = S3.s))
        TextLink(
            stringResource(R.string.cockpit_settings_link),
            { settings = !settings },
            Modifier.semantics { stateDescription = state },
        )
        if (settings) {
            SwitchRow(
                label = stringResource(R.string.cockpit_with_logger),
                caption = stringResource(R.string.cockpit_with_logger_hint),
                checked = prefs.startsLogger,
                onCheckedChange = { on -> vm.updateCockpitPrefs { it.copy(startsLogger = on) } },
            )
            Spacer(Modifier.height(S3.s))
            Text(stringResource(R.string.cockpit_theme_title), style = S3.type.bodyStrong, color = S3.colors.paint)
            Spacer(Modifier.height(S3.s))
            ChoiceBar(
                options = listOf(
                    CockpitTheme.APP to stringResource(R.string.cockpit_theme_app),
                    CockpitTheme.DAY to stringResource(R.string.cockpit_theme_day),
                    CockpitTheme.NIGHT to stringResource(R.string.cockpit_theme_night),
                ),
                selected = prefs.theme,
                onSelect = { theme -> vm.updateCockpitPrefs { it.copy(theme = theme) } },
            )
            Hint(stringResource(R.string.cockpit_theme_hint), Modifier.fillMaxWidth().padding(top = S3.s))
        }
    }
}

/**
 * True while the ride hero offers the cockpit: the bike has been moving for 5 s without a break, the cockpit is
 * closed, and "Nicht jetzt" was not tapped in this process.
 */
@Composable
fun rememberCockpitOffer(vm: AppViewModel): Boolean {
    val open by vm.cockpitOpen.collectAsStateWithLifecycle()
    val steadyFlow = remember(vm) { steadyRiding(vm) }
    val steady by steadyFlow.collectAsStateWithLifecycle()
    val dismissed by CockpitRuntime.suggestDismissedState.collectAsStateWithLifecycle()
    return !open && !dismissed && steady
}

private fun ridingOpen(s: BikeState): Boolean = s.link == Link.READY && s.moving && s.lock == LockState.UNLOCKED

/** Connected and moving for 5 s without a break, one clock per ViewModel ([steadyRiding]). */
private val steadyByVm = WeakHashMap<AppViewModel, StateFlow<Boolean>>()

/**
 * Every caller of [rememberCockpitOffer] reads the same 5 s: the Diagnose entry that opens beside the hero mid-ride
 * knows the offer is already on, instead of starting a timer of its own. The flow holds the bike state and the
 * ViewModel's scope, never the ViewModel, so the weak key can go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun steadyRiding(vm: AppViewModel): StateFlow<Boolean> = synchronized(steadyByVm) {
    steadyByVm.getOrPut(vm) {
        val states = vm.bikeState
        states.map { it.link == Link.READY && it.moving }
            .distinctUntilChanged()
            .transformLatest { moving ->
                emit(false)
                if (moving) {
                    delay(STEADY_MS)
                    emit(true)
                }
            }
            .stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(), false)
    }
}

private const val STEADY_MS = 5_000L

/**
 * True while the ride hero shows [CockpitOffer] in the lock lever's place: the offer is on ([rememberCockpitOffer]),
 * the bike is connected, moving and open. A "Cockpit öffnen" beside the hero (Diagnose in the right pane) steps back
 * to its quiet form meanwhile, so the screen has one filled button for it.
 */
@Composable
fun rememberHeroCockpitOffer(vm: AppViewModel): Boolean {
    val offer = rememberCockpitOffer(vm)
    val heroFlow = remember(vm) { vm.bikeState.map(::ridingOpen).distinctUntilChanged() }
    // The current state from the first frame, so an entry that opens mid-ride never shows its filled form for a frame.
    val riding by heroFlow.collectAsStateWithLifecycle(initialValue = ridingOpen(vm.bikeState.value))
    return offer && riding
}

/**
 * The riding offer in the lock lever's place: "Cockpit öffnen" over a quiet "Nicht jetzt". Opening from here never
 * asks for a permission ([AppViewModel.openCockpitFromRiding]). "Nicht jetzt" holds for the rest of the process.
 */
@Composable
fun CockpitOffer(vm: AppViewModel, onDismiss: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        BigButton(stringResource(R.string.cockpit_open), { vm.openCockpitFromRiding() })
        TextLink(stringResource(R.string.cockpit_suggest_later), {
            vm.cockpitSuggestDismissed = true
            onDismiss()
        })
    }
}
