package dev.steven.s3control.ui.onboarding

import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.steven.s3control.R
import dev.steven.s3control.ui.BlePermissions
import dev.steven.s3control.ui.BleReadiness
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.LocalShellBlePrompt
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.rememberOutburstStage

/**
 * Bluetooth access (step 2 of 4). The tube shows the Bluetooth glyph and stands still; there is no call in front of a system
 * dialog. One screen for everything that can stand between the phone and the bike: the permission itself (asked once, then
 * refused once, then only the app settings help), Bluetooth switched off, and, below Android 12, the location that Android
 * wants for scanning. The text is replaced for each case, never added to, so it stays at two sentences.
 */
@Composable
internal fun PermissionStep(env: OnboardingEnv, readiness: BleReadiness) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val prompt = LocalShellBlePrompt.current
    val askedAtStart = remember { prompt.asked }

    val needsPermission = !readiness.permissionsGranted
    // The rationale is false before the first answer as well, so it is only asked for after an answer (or after a restart that
    // came back to this screen with the prompt already shown once).
    val answered = readiness.permissionResults > 0 || askedAtStart
    val permanent = remember(readiness.permissionResults, readiness.permissionsGranted, answered) {
        needsPermission && answered && activity != null && BlePermissions.permanentlyDenied(activity, prompt.asked)
    }
    val denied = needsPermission && answered && prompt.asked

    val title = stringResource(R.string.onb_perm_title)
    val body = stringResource(
        when {
            // After one denial the system dialog can be asked again, and the sentence says so; only when Android will not
            // show it any more (permanent) are the app settings the way, and the button says "Open app settings".
            needsPermission && permanent -> R.string.onb_perm_denied
            needsPermission && denied -> R.string.onb_perm_denied_again
            // Below Android 12 the permission is the location, and the sentence about "Nearby devices" would be wrong.
            needsPermission && Build.VERSION.SDK_INT < 31 -> R.string.bike_prompt_permission_location
            needsPermission -> R.string.onb_perm_text
            !readiness.bluetoothOn -> R.string.bike_prompt_bluetooth_off
            else -> R.string.bike_prompt_permission_location
        },
    )
    val primaryText = stringResource(
        when {
            needsPermission && permanent -> R.string.shell_ble_open_settings
            needsPermission -> R.string.onb_perm_button
            !readiness.bluetoothOn -> R.string.bike_prompt_bluetooth_on
            else -> R.string.shell_ble_action_location
        },
    )
    val primaryAction: () -> Unit = {
        when {
            needsPermission && permanent -> openAppSettings(context)
            needsPermission -> {
                prompt.asked = true
                readiness.requestPermissions()
            }
            !readiness.bluetoothOn -> readiness.requestEnable()
            else -> readiness.openLocationSettings()
        }
    }

    val bleFrame = remember { MatrixFrame.text(MatrixFont.BLE.toString()) }
    val stage = rememberOutburstStage(calm = bleFrame)
    val spec = FrameSpec(
        paneTitle = title,
        title = TitleSpec(title),
        ladder = listOf(Drop.Rail, Drop.Hero60, Drop.HeroNone, Drop.BodyToSemantics, Drop.TitleCompact, Drop.TitleToSemantics),
        rail = 2,
        hero = HeroSpec { modifier -> OnboardingTube(stage, TubeTone.Led, title, modifier) },
        body = BodySpec(body),
        primary = { BigButton(primaryText, primaryAction) },
    )
    OnboardingFrame(spec, env, Modifier.fillMaxSize())
}
