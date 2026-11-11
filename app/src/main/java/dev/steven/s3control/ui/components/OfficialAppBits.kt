package dev.steven.s3control.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ui.theme.S3

/**
 * Shown where the bike cannot be reached and the official VanMoof app is installed:
 * one tap to push it out of the background, one to its settings to mute it for good.
 * Renders [fallback] instead when the app is not installed.
 *
 * When the tube above calls out GEDOE for this very situation, the explaining paragraph waits for the
 * call: pass `Modifier.coverUntilSentence(stage)` as [explainModifier]. Title and buttons stand from the start.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfficialAppNotice(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    explainModifier: Modifier = Modifier,
    fallback: @Composable () -> Unit = {},
) {
    var installed by remember { mutableStateOf(vm.officialAppInstalled()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) installed = vm.officialAppInstalled() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (!installed) {
        fallback()
        return
    }
    val c = S3.colors
    var note by remember { mutableStateOf<Int?>(null) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        Text(stringResource(R.string.official_title), style = S3.type.bodyStrong, color = c.paint, modifier = Modifier.semantics { heading() })
        Hint(stringResource(R.string.official_explain), explainModifier)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
            QuietButton(stringResource(R.string.official_quit), onClick = {
                vm.quitOfficialApp()
                note = R.string.official_quit_done
            })
            QuietButton(stringResource(R.string.official_mute), onClick = {
                showSteps = true
                if (!vm.openOfficialAppInfo()) note = R.string.official_open_settings_failed
            })
        }
        if (showSteps) Hint(stringResource(R.string.official_mute_steps), color = c.lamp)
        note?.let { Hint(stringResource(it)) }
    }
}
