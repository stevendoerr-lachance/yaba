package dev.steven.s3control.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.ManualBikeFields
import dev.steven.s3control.ui.components.ManualBikeState
import dev.steven.s3control.ui.components.manualMacHint

/**
 * Enter the key by hand (step 1 of 4, the third way to a key): the model (S3/X3 or S2/X2, so an S2 gets its own driver from
 * the first connect on), the MAC address and the 32 hex characters of the key on one page, with the live count of what is
 * missing. The name ("Mein S3" or "Mein S2") and the user key ID (1) have defaults and can be changed later in the Garage.
 * The key is only held in memory, never in the saved state.
 */
@Composable
internal fun ManualStep(vm: AppViewModel, state: ManualBikeState, env: OnboardingEnv) {
    val account by vm.account.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val title = stringResource(R.string.account_garage_add_manual)
    val add = {
        if (state.canAdd) {
            focus.clearFocus()
            vm.addManualBike(state.toCredentials())
            state.clearSecrets()
        }
    }
    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = listOf(
            Drop.Rail, Drop.BodyToSemantics, Drop.StatusWhenNonEmpty, Drop.TitleCompact, Drop.PrimaryUnderIme, Drop.TitleToSemantics,
        ),
        rail = 1,
        // The S3 hint reads the MAC from the Bluetooth name; an S2's name has only 6 of its digits.
        body = BodySpec(stringResource(manualMacHint(state.model))),
        fields = { ManualBikeFields(state, compact = true, onDone = add) },
        status = { StatusLine(account.error) },
        statusEmpty = account.error == null,
        primary = { BigButton(stringResource(R.string.account_manual_add), onClick = add, enabled = state.canAdd) },
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize())
}
