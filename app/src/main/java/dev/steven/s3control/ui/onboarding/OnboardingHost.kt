package dev.steven.s3control.ui.onboarding

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ui.BleReadiness
import dev.steven.s3control.ui.components.LanguageButton
import dev.steven.s3control.ui.components.LoginFieldsState
import dev.steven.s3control.ui.components.ManualBikeState
import dev.steven.s3control.ui.components.Outbursts
import dev.steven.s3control.ui.components.rememberReducedMotion

/** The screens of the onboarding. The server notice is not a step of its own: it takes the place of the sign-in form. */
internal enum class Step { Welcome, SignIn, ImportChoose, ImportPaste, Manual, Permission, Find, Wheel, Unlock }

/** Where the person is on the way to a key: the three ways in, saved over a restart. */
internal enum class Side {
    SignIn, ImportChoose, ImportPaste, Manual;

    fun toStep(): Step = when (this) {
        SignIn -> Step.SignIn
        ImportChoose -> Step.ImportChoose
        ImportPaste -> Step.ImportPaste
        Manual -> Step.Manual
    }
}

/**
 * Where the onboarding is, derived from what is true and never stored as a position:
 *
 *  - no key yet (or the person asked to change the key): the Welcome, or the way to a key they chose
 *  - Bluetooth not ready: the access
 *  - the bike not connected: the search (and, a few seconds at most, an S3 whose wheel size is not read yet, [wheelPending])
 *  - connected, reading 24″, and nothing says S3 or X3 ([wheelOpen], [wheelQuestionOpen]): the factory wheel size
 *  - connected, but not yet through the first unlock: the slider
 *  - through: the onboarding is done ([onboardingDone] in the settings), and the app takes over
 *
 * What is saved is only the side the person is on, whether the celebration was seen, "change key" and the typed e-mail
 * address. The password and the key live in plain memory ([OnboardingSecrets]), which outlasts a change of the language.
 */
internal fun stepFor(
    hasBikes: Boolean,
    changingKey: Boolean,
    side: Side?,
    bleReady: Boolean,
    linkReady: Boolean,
    celebrated: Boolean,
    wheelOpen: Boolean = false,
): Step? = when {
    !hasBikes || changingKey -> side?.toStep() ?: Step.Welcome
    !bleReady -> Step.Permission
    !linkReady -> Step.Find
    !celebrated && wheelOpen -> Step.Wheel
    !celebrated -> Step.Unlock
    else -> null
}

@Composable
internal fun OnboardingHost(vm: AppViewModel, env: OnboardingEnv, readiness: BleReadiness, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vault by vm.vault.collectAsStateWithLifecycle()
    val s by vm.bikeState.collectAsStateWithLifecycle()
    val hasBikes = vault.bikes.isNotEmpty()

    var sideName by rememberSaveable { mutableStateOf<String?>(null) }
    var celebrated by rememberSaveable { mutableStateOf(false) }
    var changingKey by rememberSaveable { mutableStateOf(false) }
    var keySignature by rememberSaveable { mutableStateOf(0) }
    var unlockStarted by rememberSaveable { mutableStateOf(false) }
    val side = sideName?.let { n -> Side.entries.firstOrNull { it.name == n } }

    // What the vault holds of keys and session: "change key" ends when this changes (a key arrived), and it never logs out.
    val signature = remember(vault) {
        var h = 17
        vault.bikes.forEach { h = 31 * h + (it.normalizedMac + it.encryptionKey + it.userKeyId).hashCode() }
        31 * h + (vault.session?.obtainedAtMillis ?: 0L).hashCode()
    }
    LaunchedEffect(signature) {
        if (changingKey && signature != keySignature) {
            changingKey = false
            sideName = null
        }
    }
    LaunchedEffect(hasBikes) { if (hasBikes && !changingKey) sideName = null }
    // A key that the vault already held changes nothing in it: the message that says an import, a sign-in or an entry went
    // through is what ends "change key" then (the messages are cleared whenever a way to a key is entered).
    val account by vm.account.collectAsStateWithLifecycle()
    LaunchedEffect(account.info) {
        if (changingKey && account.info != null) {
            changingKey = false
            sideName = null
        }
    }

    // A fresh token from the server (sign-in) proves that it answered: "no answer for days" starts over.
    val tokenAt = vault.session?.obtainedAtMillis
    LaunchedEffect(tokenAt) {
        if (tokenAt != null && System.currentTimeMillis() - tokenAt < 60_000L) Outbursts.recordServerAnswer(context)
    }

    // Not once the slider has been used: an unlock in flight is not interrupted by the question (the Bike tab asks it too).
    val wheelOpen = wheelQuestionOpen(s) && !unlockStarted
    // The search step stays until an S3 has reported its wheel size (a few seconds after the link is up), so the unlock is not
    // shown first and then replaced by the question. The clock ticks once, when the wait is over.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connectedAt = s.connectedAtMillis.takeIf { s.isReady }
    LaunchedEffect(connectedAt) {
        now = System.currentTimeMillis()
        if (connectedAt != null) {
            delay((connectedAt + WHEEL_WAIT_MS - System.currentTimeMillis()).coerceAtLeast(0L))
            now = System.currentTimeMillis()
        }
    }
    val holding = wheelPending(s, now) && !unlockStarted
    val step = stepFor(hasBikes, changingKey, side, readiness.ready, s.link == Link.READY && !holding, celebrated, wheelOpen)
    // The step of the last frame before the end: it stays on screen while the settings catch up.
    var lastStep by remember { mutableStateOf(Step.Welcome) }
    if (step != null) lastStep = step
    val shownStep = step ?: lastStep

    // The end: the settings say so, and AppRoot takes the screen away. A bike that is already unlocked when the unlock would
    // start has nothing to celebrate: the onboarding ends in silence, without "Gute Fahrt.".
    val silentEnd = step == Step.Unlock && s.lock == LockState.UNLOCKED && !unlockStarted
    val finishing = step == null || silentEnd
    LaunchedEffect(finishing) {
        if (finishing) vm.updateSettings { it.copy(onboardingDone = true) }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val autofill = LocalAutofillManager.current
    fun toSide(next: Side?) {
        keyboard?.hide()
        vm.clearAccountMessage()
        sideName = next?.name
    }

    BackHandler(enabled = shownStep != Step.Welcome || changingKey) {
        when (shownStep) {
            Step.SignIn -> { autofill?.cancel(); toSide(null) }
            Step.ImportChoose -> toSide(null)
            Step.ImportPaste, Step.Manual -> toSide(Side.ImportChoose)
            Step.Welcome -> { changingKey = false; toSide(null) }
            // The key is stored already: there is nothing to go back to. The app goes to the background, as it would at home.
            Step.Permission, Step.Find, Step.Wheel, Step.Unlock -> (context as? Activity)?.moveTaskToBack(true)
        }
    }

    // What is typed survives every layout change and a change of the language (the activity is recreated then): the fields
    // are never rebuilt out of other state, and the secrets wait in memory for the new activity (see OnboardingSecrets).
    val secrets: OnboardingSecrets = viewModel()
    DisposableEffect(secrets) {
        onDispose { if (context.findComponentActivity()?.isChangingConfigurations != true) secrets.clear() }
    }
    val login = rememberOnboardingLogin(secrets)
    val manual = rememberOnboardingManual(secrets)
    var paste by secrets.paste
    val clock = remember { UnlockClock() }
    val bike = vm.preferredBike() ?: vault.bikes.firstOrNull()

    // The language in the corner of every step; the first one also names it, for someone who does not read it.
    val namedCorner: @Composable () -> Unit = remember { { LanguageButton(showName = true) } }
    val plainCorner: @Composable () -> Unit = remember { { LanguageButton() } }

    StepSweep(shownStep, rememberReducedMotion(), modifier) { current ->
        CompositionLocalProvider(LocalOnboardingCorner provides if (current == Step.Welcome) namedCorner else plainCorner) {
            when (current) {
                Step.Welcome -> WelcomeStep(env, onSignIn = { toSide(Side.SignIn) }, onImport = { toSide(Side.ImportChoose) })
                Step.SignIn -> SignInStep(vm, login, env, onImport = { toSide(Side.ImportChoose) })
                Step.ImportChoose -> ImportChooseStep(vm, env, onPaste = { toSide(Side.ImportPaste) }, onManual = { toSide(Side.Manual) })
                Step.ImportPaste -> ImportPasteStep(vm, paste, onText = { paste = it }, env = env)
                Step.Manual -> ManualStep(vm, manual, env)
                Step.Permission -> PermissionStep(env, readiness)
                Step.Find -> if (bike != null) {
                    FindStep(
                        vm, env, bike, s,
                        onChangeKey = {
                            keySignature = signature
                            changingKey = true
                            toSide(null)
                        },
                    )
                }
                Step.Wheel -> WheelStep(vm, env, s)
                Step.Unlock -> UnlockStep(
                    vm, env, s, clock,
                    onUnlockStarted = { unlockStarted = true },
                    onDone = { celebrated = true },
                )
            }
        }
    }
}

/**
 * What is typed and must never go into the saved state (the password, the key by hand, a pasted key file), held for the
 * activity's whole life rather than the composition's: a change of the language recreates the activity, and the person
 * finds the fields as they left them. Cleared when the onboarding leaves the screen for good (not for a recreation).
 */
internal class OnboardingSecrets : ViewModel() {
    val password: MutableState<String> = mutableStateOf("")
    val manualKey: MutableState<String> = mutableStateOf("")
    val paste: MutableState<String> = mutableStateOf("")

    fun clear() {
        password.value = ""
        manualKey.value = ""
        paste.value = ""
    }

    override fun onCleared() = clear()
}

/** The sign-in fields: the e-mail address and the switch in the saved state, the password in [secrets]. */
@Composable
private fun rememberOnboardingLogin(secrets: OnboardingSecrets): LoginFieldsState {
    val email = rememberSaveable { mutableStateOf("") }
    val show = rememberSaveable { mutableStateOf(false) }
    return remember(secrets) { LoginFieldsState(email, secrets.password, show) }
}

/**
 * The fields for a key by hand, as [dev.steven.s3control.ui.components.rememberManualBikeState] keeps them, with the key in
 * [secrets]. A name still at its default ("Mein S3") follows a change of the language, so the bike is not stored under the
 * default of the language before.
 */
@Composable
private fun rememberOnboardingManual(secrets: OnboardingSecrets): ManualBikeState {
    val defaultName = stringResource(R.string.account_default_bike_name)
    val defaultNameS2 = stringResource(R.string.account_default_bike_name_s2)
    val name = rememberSaveable { mutableStateOf(defaultName) }
    val mac = rememberSaveable { mutableStateOf("") }
    val userKeyId = rememberSaveable { mutableStateOf("1") }
    val showKey = rememberSaveable { mutableStateOf(false) }
    val model = rememberSaveable { mutableStateOf(BikeFamily.S3.name) }
    val shownDefault = rememberSaveable { mutableStateOf(defaultName) }
    val shownDefaultS2 = rememberSaveable { mutableStateOf(defaultNameS2) }
    return remember(defaultName, defaultNameS2) {
        // the first composition after a language change: the old defaults are in the saved state
        when (name.value.trim()) {
            shownDefault.value -> name.value = defaultName
            shownDefaultS2.value -> name.value = defaultNameS2
        }
        shownDefault.value = defaultName
        shownDefaultS2.value = defaultNameS2
        ManualBikeState(name, mac, secrets.manualKey, userKeyId, showKey, model, defaultName, defaultNameS2)
    }
}
