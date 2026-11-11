package dev.steven.s3control.ui.s2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ui.cockpit.CockpitEntry
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.BikeSectionTitle
import dev.steven.s3control.ui.components.ConnectPrompt
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.theme.S3

/**
 * The S2/X2 block of the Diagnose page (plan B6): DiagnosisScreen calls it in place of the S3 live checks, only
 * while the bike is an S2 (`Feature.LIVE_SUPPLY_BUTTONS_COIL` missing). It draws the whole block, its own section
 * title included, and leads into [S2DiagnosisScreen] (over the whole window). Below it the cockpit entry, which
 * is family-neutral: the GPS ride runs there, and a volume key marks the moment the motor cuts out.
 *
 * The line under the intro is the error catcher's state ([S2DriveWatch]) for this bike, which runs from the app's start.
 */
@Composable
fun S2DiagnosisEntry(vm: AppViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { S2DriveWatch.attach(context) }
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val watch by S2DriveWatch.state.collectAsStateWithLifecycle()
    val mac = state.bike?.normalizedMac
    val hits = remember(watch.hits, mac) { S2DriveWatch.hitsFor(watch.hits, mac) }
    var open by rememberSaveable { mutableStateOf(false) }
    val clock = rememberClock()
    val c = S3.colors

    Column(Modifier.fillMaxWidth()) {
        BikeSectionTitle(stringResource(R.string.s2diag_title))
        Spacer(Modifier.height(S3.s))
        Text(stringResource(R.string.s2diag_entry_intro), style = S3.type.body, color = c.paint)
        Spacer(Modifier.height(S3.xs))
        val last = hits.lastOrNull()
        val since = watch.sinceMillis
        when {
            last != null -> Text(
                pluralStringResource(R.plurals.s2diag_entry_caught, hits.size, hits.size, last.code, clock.time(last.firstAtMillis)),
                style = S3.type.caption,
                color = if (S2Parts.drive(last.code)) c.signal else c.fog,
            )
            since != null -> Hint(stringResource(R.string.s2diag_entry_quiet, clock.shortTime(since)))
            else -> Hint(stringResource(R.string.s2diag_entry_waiting))
        }
        Spacer(Modifier.height(S3.m))
        if (!state.isReady) {
            ConnectPrompt(vm, state, reason = stringResource(R.string.s2diag_connect_reason))
            Spacer(Modifier.height(S3.m))
        }
        BigButton(stringResource(R.string.s2diag_entry_open), { open = true })
        Spacer(Modifier.height(S3.xl))
        Text(stringResource(R.string.s2fix_cockpit_intro), style = S3.type.body, color = c.paint)
        Spacer(Modifier.height(S3.m))
        // "Antrieb prüfen" is this block's one main action; the cockpit stays outlined beside it.
        CockpitEntry(vm, quiet = true)
    }
    if (open) S2DiagnosisDialog(vm) { open = false }
}
