package dev.steven.s3control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ui.BleReadiness
import dev.steven.s3control.ui.components.LanguageButton
import dev.steven.s3control.ui.components.LocalShellBlePrompt
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.ServerFailure
import dev.steven.s3control.ui.components.ServerRecord
import dev.steven.s3control.ui.components.ShellBlePrompt
import dev.steven.s3control.ui.components.rememberLoginFieldsState
import dev.steven.s3control.ui.components.rememberManualBikeState
import dev.steven.s3control.ui.onboarding.FindActions
import dev.steven.s3control.ui.onboarding.FindStep
import dev.steven.s3control.ui.onboarding.FindView
import dev.steven.s3control.ui.onboarding.ImportChooseStep
import dev.steven.s3control.ui.onboarding.ImportPasteStep
import dev.steven.s3control.ui.onboarding.LocalOnboardingCorner
import dev.steven.s3control.ui.onboarding.ManualStep
import dev.steven.s3control.ui.onboarding.OnboardingEnv
import dev.steven.s3control.ui.onboarding.PermissionStep
import dev.steven.s3control.ui.onboarding.ServerNoticeView
import dev.steven.s3control.ui.onboarding.SignInStep
import dev.steven.s3control.ui.onboarding.UnlockClock
import dev.steven.s3control.ui.onboarding.UnlockStep
import dev.steven.s3control.ui.onboarding.WelcomeStep
import dev.steven.s3control.ui.onboarding.FrameAction
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only: any onboarding step on its own, with the state a device would not give on demand (Bluetooth access, the
 * search, the lock) made up. The window decides cover or inner screen, the keyboard and the font scale are the system's.
 *
 * adb shell am start -n dev.steven.yaba/dev.steven.s3control.OnboardingGalleryActivity --es step welcome [--ez dark false]
 * steps: welcome, signin, import, paste, manual, perm, perm_denied, perm_bt_off, find (stopped), find_searching, find_connecting, find_notfound,
 *        find_notfound_app (the VanMoof app is installed), find_notfound_app_quit (and Quit was tapped once), unlock, unlock_unknown, server_http, server_timeout, server_dead
 */
class OnboardingGalleryActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val step = intent.getStringExtra("step") ?: "welcome"
        val dark = intent.getBooleanExtra("dark", true)
        setContent {
            S3Theme(dark) {
                BoxWithConstraints(Modifier.fillMaxSize().background(S3.colors.asphalt)) {
                    val wide = maxWidth >= 600.dp
                    val env = remember(wide) { OnboardingEnv(wide, null, 0f) }
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        // the language in the corner, as the host hands it to every step (named on the first)
                        val corner: @Composable () -> Unit = remember { { LanguageButton(showName = step == "welcome") } }
                        CompositionLocalProvider(LocalOnboardingCorner provides corner) { Gallery(step, env) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Gallery(step: String, env: OnboardingEnv) {
        val bike = remember {
            BikeCredentials(
                id = 1, name = "My S3", ownerName = "Alex", mac = "74:D2:85:AB:CD:EF",
                encryptionKey = "00000000000000000000000000000000", userKeyId = 1,
            )
        }
        val noReadiness = BleReadiness(false, true, {}, {})
        when (step) {
            "welcome" -> WelcomeStep(env, {}, {})
            "signin" -> SignInStep(vm, rememberLoginFieldsState(), env) {}
            "import" -> ImportChooseStep(vm, env, {}, {})
            "paste" -> {
                var text by remember { mutableStateOf("") }
                ImportPasteStep(vm, text, { text = it }, env)
            }
            "manual" -> ManualStep(vm, rememberManualBikeState(), env)
            "perm" -> PermissionStep(env, noReadiness)
            "perm_denied" -> CompositionLocalProvider(LocalShellBlePrompt provides ShellBlePrompt(asked = true)) {
                PermissionStep(env, BleReadiness(false, true, {}, {}, permissionResults = 1))
            }
            "perm_bt_off" -> PermissionStep(env, BleReadiness(true, false, {}, {}))
            // FindStep starts a real search when it appears; the gallery shows the states without one
            "find", "find_searching", "find_connecting" -> FindView(
                env, bike.name,
                BikeState(
                    bike = bike,
                    link = when (step) { "find" -> Link.OFFLINE; "find_connecting" -> Link.CONNECTING; else -> Link.SEARCHING },
                ),
                notFound = false, vanMoofApp = false, cue = null, actions = FindActions({}, {}, {}, {}, {}, {}),
            )
            // the link's own message is made up here: the step must not show it next to its own paragraph (find_notfound_app_quit:
            // the person has already tapped "Quit VanMoof app" once, so the revoke line is offered)
            "find_notfound", "find_notfound_app", "find_notfound_app_quit" -> FindView(
                env, bike.name,
                BikeState(bike = bike, link = Link.SEARCHING, linkMessage = "No answer from the bike. Usually the bike is asleep, or the VanMoof app is connected to it right now."),
                notFound = true, vanMoofApp = step != "find_notfound", cue = null, actions = FindActions({}, {}, {}, {}, {}, {}),
                quitTried = step == "find_notfound_app_quit",
            )
            "unlock", "unlock_unknown" -> {
                val scope = rememberCoroutineScope()
                val base = BikeState(
                    bike = bike, link = Link.READY, battery = 87,
                    lock = if (step == "unlock") LockState.LOCKED else null,
                )
                var s by remember { mutableStateOf(base) }
                val clock = remember { UnlockClock() }
                UnlockStep(
                    vm, env, s, clock,
                    onUnlockStarted = { scope.launch { delay(900); s = s.copy(lock = LockState.UNLOCKED) } },
                    onDone = {},
                )
            }
            "server_http", "server_timeout", "server_dead" -> {
                val failure = if (step == "server_http") ServerFailure.Http(503) else ServerFailure.Timeout
                val record = remember { ServerRecord(failure, System.currentTimeMillis(), if (step == "server_dead") Moment.ServerDead else null, 7) }
                ServerNoticeView(record, env, retry = {}, importAction = FrameAction("import") {})
            }
        }
    }
}
