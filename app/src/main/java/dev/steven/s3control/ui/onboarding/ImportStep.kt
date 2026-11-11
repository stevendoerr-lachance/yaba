package dev.steven.s3control.ui.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.ImportPasteField
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.rememberKeyFileImport

/**
 * Import a key file (step 1 of 4, the second way to a key): the system file picker is the primary action, pasting its text and
 * adding the key by hand are the two quiet ones. A file that cannot be a key file, or a key the import does not accept, is
 * one line of red text under the buttons. No call on this screen either. The sentences are plain: no format names.
 */
@Composable
internal fun ImportChooseStep(vm: AppViewModel, env: OnboardingEnv, onPaste: () -> Unit, onManual: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val file = rememberKeyFileImport(vm)
    val title = stringResource(R.string.account_import_title)
    val error = file.error ?: account.error
    val pasteLabel = stringResource(R.string.account_import_paste_button)
    val manualLabel = stringResource(R.string.account_garage_add_manual)
    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = listOf(Drop.Rail, Drop.BodyToSemantics, Drop.TitleCompact, Drop.TitleToSemantics),
        rail = 1,
        body = BodySpec(stringResource(R.string.account_import_hint)),
        status = { StatusLine(error) },
        statusEmpty = error == null,
        primary = { BigButton(stringResource(R.string.account_import_pick_file), onClick = { file.pick() }) },
        secondaries = listOf(
            FrameAction("paste") { QuietButton(pasteLabel, onPaste, Modifier.fillMaxWidth()) },
            FrameAction("manual") { QuietButton(manualLabel, onManual, Modifier.fillMaxWidth()) },
        ),
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize())
}

/**
 * Paste the text of the key file (step 1 of 4): one field of five lines that scrolls inside itself, and the import. Whatever the import says
 * against it (no valid key, a file that is not JSON) is the plain red line of the status slot.
 */
@Composable
internal fun ImportPasteStep(vm: AppViewModel, text: String, onText: (String) -> Unit, env: OnboardingEnv) {
    val account by vm.account.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val title = stringResource(R.string.account_import_title)
    val submit = {
        if (text.isNotBlank()) {
            focus.clearFocus()
            vm.importKeys(text)
        }
    }
    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = listOf(
            Drop.Rail, Drop.BodyToSemantics, Drop.StatusWhenNonEmpty, Drop.TitleCompact, Drop.PrimaryUnderIme, Drop.TitleToSemantics,
        ),
        rail = 1,
        body = BodySpec(stringResource(R.string.account_import_paste_hint)),
        fields = { ImportPasteField(text, onTextChange = { onText(it); vm.clearAccountMessage() }) },
        status = { StatusLine(account.error) },
        statusEmpty = account.error == null,
        primary = { BigButton(stringResource(R.string.account_import_submit), onClick = submit, enabled = text.isNotBlank()) },
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize())
}
