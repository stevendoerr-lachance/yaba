package dev.steven.s3control.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AccountOp
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeCipher
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.data.normalizeMac
import dev.steven.s3control.ui.screens.MODEL_S2
import dev.steven.s3control.ui.screens.MODEL_S3
import dev.steven.s3control.ui.theme.S3
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ------------------------------------------------------------------------------------------------
 * Shared account building blocks. Used by both the first-run onboarding (ui/onboarding) and the Garage.
 * All copy lives in strings_account.xml; errors say what happened and what to do.
 * ---------------------------------------------------------------------------------------------- */

/**
 * Text fields that pick up the S3 palette instead of the stock Material look. The resting outline
 * is fog, not rib: rib on asphalt stays below 3:1 and the field would vanish until focused.
 */
@Composable
internal fun s3FieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = S3.colors.led,
    unfocusedBorderColor = S3.colors.fog,
    errorBorderColor = S3.colors.signal,
    focusedLabelColor = S3.colors.led,
    unfocusedLabelColor = S3.colors.fog,
    errorLabelColor = S3.colors.signal,
    cursorColor = S3.colors.led,
    errorCursorColor = S3.colors.signal,
    focusedTextColor = S3.colors.paint,
    unfocusedTextColor = S3.colors.paint,
    errorTextColor = S3.colors.paint,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
)

/**
 * The single account status line: error in traffic-red, info in matrix-blue.
 *
 * An error that is VanMoof's server's own (a 5xx, a timeout, no connection) becomes a [ErrorOrServerNotice]:
 * the top tube, the explaining paragraph and [onRetry]. Wrong credentials and every other 4xx stay a plain line
 * and never call anything out. [onRetry] is what "Try again" repeats; without it the retry button is left out.
 */
@Composable
fun AccountMessages(vm: AppViewModel, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val account by vm.account.collectAsStateWithLifecycle()
    val vault by vm.vault.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // A fresh token from the server (sign-in, refresh) proves that it answered: "no answer for days" starts over.
    val tokenAt = vault.session?.obtainedAtMillis
    LaunchedEffect(tokenAt) {
        if (tokenAt != null && System.currentTimeMillis() - tokenAt < 60_000L) Outbursts.recordServerAnswer(context)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(S3.xs)) {
        if (onRetry != null) {
            ErrorOrServerNotice(account.error, account, onRetry)
        } else {
            account.error?.let { Hint(it, color = S3.colors.signal) }
        }
        account.info?.let { Hint(it, color = S3.colors.led) }
    }
}

/**
 * A labelled QuietButton that reveals its content when tapped. Collapsed by default.
 * [compact]: the toggle is one underlined line of text with a chevron instead of a button, for a row of a
 * list where a full-width button would compete with the row's own action. [description] names what the
 * toggle opens when the label alone does not (several rows with the same label).
 * [foldRow]: the toggle is a full-width row, the label in bodyStrong and a chevron at the end that turns when it
 * opens, as the folding groups on Klang ([SoundGroupHeader]). For a fold that is a place to look something up
 * (versions, the language, the Bike-Lab), where an outlined button would read as a command.
 */
@Composable
fun DisclosureSection(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    description: String? = null,
    foldRow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.account_disclosure_expanded else R.string.account_disclosure_collapsed)
    if (foldRow) {
        FoldRowSection(label, open, { open = !open }, state, description, modifier, content)
        return
    }
    val color = if (open) S3.colors.led else if (compact) S3.colors.fog else S3.colors.paint
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) S3.s else S3.m)) {
        if (compact) {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { open = !open }
                    .semantics {
                        stateDescription = state
                        if (description != null) contentDescription = description
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = S3.type.label, color = color, textDecoration = TextDecoration.Underline)
                Chevron(if (open) 180f else 0f, color, Modifier.padding(start = S3.xs).size(20.dp))
            }
        } else {
            QuietButton(
                text = label,
                onClick = { open = !open },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        stateDescription = state
                        if (description != null) contentDescription = description
                    },
                color = color,
            )
        }
        if (open) content()
    }
}

/**
 * [DisclosureSection] as a fold row: the whole row (at least 64 dp) toggles with a haptic tick, the chevron turns 180°
 * (at once with reduced motion), TalkBack hears "Aufgeklappt" or "Zugeklappt". The open content follows with the
 * section's usual 16 dp rhythm and leaves 24 dp before whatever comes next.
 */
@Composable
private fun FoldRowSection(
    label: String,
    open: Boolean,
    onToggle: () -> Unit,
    stateText: String,
    description: String?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val reduceMotion = rememberReducedMotion()
    val turn by animateFloatAsState(
        if (open) 180f else 0f,
        if (reduceMotion) snap() else tween(200),
        label = "foldChevron",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button) { haptics.tick(); onToggle() }
                .semantics {
                    stateDescription = stateText
                    if (description != null) contentDescription = description
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = S3.type.bodyStrong, color = c.paint, modifier = Modifier.weight(1f))
            Chevron(turn, c.fog, Modifier.padding(start = S3.s).size(24.dp))
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(top = S3.xs, bottom = S3.l),
                verticalArrangement = Arrangement.spacedBy(S3.m),
            ) { content() }
        }
    }
}

/**
 * The three values of the sign-in fields, hoisted so a screen can keep them while it swaps its layout (the keyboard, a rung
 * of the onboarding ladder, folding, a server notice in their place). The e-mail address and the "show" switch survive a
 * restart of the process; the password never goes into the saved-state Bundle.
 */
@Stable
class LoginFieldsState internal constructor(
    private val emailState: MutableState<String>,
    private val passwordState: MutableState<String>,
    private val showState: MutableState<Boolean>,
) {
    var email: String by emailState
    var password: String by passwordState
    var show: Boolean by showState
}

@Composable
fun rememberLoginFieldsState(): LoginFieldsState {
    val email = rememberSaveable { mutableStateOf("") }
    // Plain remember: rememberSaveable would put the password into the saved-state Bundle.
    val password = remember { mutableStateOf("") }
    val show = rememberSaveable { mutableStateOf(false) }
    return remember { LoginFieldsState(email, password, show) }
}

/**
 * What a submit of the sign-in fields does and whether it may: [run] drops the focus and signs in. The password manager
 * is told how it went only when it is known: [LocalAutofillManager.commit] once the key is in the vault (Bitwarden & co.
 * then offer to save), [LocalAutofillManager.cancel] when the sign-in failed (a wrong password, a 4xx, VanMoof's server
 * failing), so that a password that did not work is never offered for saving.
 */
class LoginSubmit internal constructor(val canSubmit: Boolean, val running: Boolean, val run: () -> Unit)

@Composable
fun rememberLoginSubmit(vm: AppViewModel, state: LoginFieldsState): LoginSubmit {
    val account by vm.account.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val autofill = LocalAutofillManager.current
    val canSubmit = state.email.isNotBlank() && state.password.isNotBlank() && !account.running
    // The state of the account channel when the person pressed the button: the answer is the next one that is not running.
    var asked by remember { mutableStateOf<AccountOp?>(null) }
    LaunchedEffect(account) {
        val before = asked ?: return@LaunchedEffect
        if (account === before || account.running) return@LaunchedEffect
        asked = null
        if (account.error == null && account.info != null) autofill?.commit() else autofill?.cancel()
    }
    return LoginSubmit(canSubmit, account.running) {
        if (canSubmit) {
            asked = account
            focus.clearFocus()
            vm.login(state.email.trim(), state.password)
        }
    }
}

/**
 * The e-mail and password fields, with the semantics a password manager needs (content type username/e-mail and password).
 * The fields stay in one place of the tree whatever surrounds them: moving them would end the autofill session.
 * [onSubmit] is the keyboard's Done action on the password field.
 */
@Composable
fun LoginFields(state: LoginFieldsState, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        OutlinedTextField(
            value = state.email,
            onValueChange = { state.email = it },
            label = { Text(stringResource(R.string.account_field_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            colors = s3FieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = { state.password = it },
            label = { Text(stringResource(R.string.account_field_password)) },
            singleLine = true,
            visualTransformation = if (state.show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            trailingIcon = {
                val toggleLabel = stringResource(if (state.show) R.string.account_password_hide else R.string.account_password_show)
                val toggleSpoken = stringResource(
                    if (state.show) R.string.account_password_hide_description else R.string.account_password_show_description,
                )
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { state.show = !state.show }
                        .semantics { contentDescription = toggleSpoken }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = toggleLabel, style = S3.type.label, color = S3.colors.led, maxLines = 1)
                }
            },
            colors = s3FieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.Password },
        )
    }
}

/**
 * Log in with the VanMoof account. On success the key lands in the vault and the host screen
 * reacts to the now-non-empty bike list. The password is only sent, never stored.
 */
@Composable
fun LoginForm(vm: AppViewModel, modifier: Modifier = Modifier) {
    LoginForm(vm, primary = true, modifier = modifier)
}

/**
 * [LoginForm] for hosts where signing in is a side path: with [primary] false the submit is a
 * QuietButton, so the host keeps its one BigButton for the action it exists for.
 */
@Composable
internal fun LoginForm(vm: AppViewModel, primary: Boolean, modifier: Modifier = Modifier) {
    val state = rememberLoginFieldsState()
    val submit = rememberLoginSubmit(vm, state)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        LoginFields(state, onSubmit = submit.run)
        val submitText = stringResource(if (submit.running) R.string.account_login_running else R.string.account_login_submit)
        if (primary) {
            BigButton(text = submitText, onClick = submit.run, enabled = submit.canSubmit)
        } else {
            QuietButton(
                text = submitText,
                onClick = submit.run,
                enabled = submit.canSubmit,
                color = S3.colors.led,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Hint(stringResource(R.string.account_login_privacy))
        // A failed server request is repeated with what is in the fields; the password is never kept anywhere else.
        AccountMessages(vm, onRetry = { submit.run() })
    }
}

/**
 * The values of a bike entered by hand, hoisted for the same reason as [LoginFieldsState]. The MAC, the name, the model
 * and the user key ID are saved over a restart of the process, the key (it unlocks the bike) is not.
 */
@Stable
class ManualBikeState internal constructor(
    private val nameState: MutableState<String>,
    private val macState: MutableState<String>,
    private val keyState: MutableState<String>,
    private val userKeyIdState: MutableState<String>,
    private val showKeyState: MutableState<Boolean>,
    /** The name of the [BikeFamily] picked: "S3" or "S2". */
    private val modelState: MutableState<String>,
    val defaultName: String,
    /** The default name once S2/X2 is picked ("Mein S2"). */
    val defaultNameS2: String,
) {
    var name: String by nameState

    /**
     * The model picked on the form: [BikeFamily.S3] (S3/X3, shown first, as [dev.steven.s3control.ui.FamilyGate.shownModel]
     * shows a bike nothing describes) or [BikeFamily.S2]. A name still at the default follows the pick.
     */
    var model: BikeFamily
        get() = if (modelState.value == BikeFamily.S2.name) BikeFamily.S2 else BikeFamily.S3
        set(value) {
            val picked = if (value == BikeFamily.S2) BikeFamily.S2 else BikeFamily.S3
            if (isDefaultName(name.trim())) name = defaultNameFor(picked)
            modelState.value = picked.name
        }

    fun defaultNameFor(model: BikeFamily): String = if (model == BikeFamily.S2) defaultNameS2 else defaultName

    private fun isDefaultName(n: String) = n == defaultName || n == defaultNameS2

    /**
     * The MAC as it is typed or pasted: free text (a whole S3 Bluetooth name such as ES3-A1B2C3D4E5F6 is fine; an S2's
     * VANMOOF-A1B2C3 holds only 6 digits, so it never makes a MAC on its own).
     */
    var macInput: String by macState
    var keyInput: String by keyState
    var userKeyIdInput: String by userKeyIdState

    /** Whether the key is shown in clear (it is masked by default: it unlocks the bike). */
    var showKey: Boolean by showKeyState

    /** The 12 hex digits of the MAC, whatever it was typed or pasted as. */
    val macHex: String get() = macHexFrom(macInput)

    val macOk: Boolean get() = macHex.length == 12
    val keyOk: Boolean get() = BikeCipher.isValidKey(keyInput)
    val userKeyId: Int? get() = userKeyIdInput.toIntOrNull()
    val userKeyIdOk: Boolean get() = userKeyId?.let { it in 0..255 } == true
    val keyRemaining: Int get() = 32 - keyInput.length

    /** MAC and key are what the bike needs; the name and the user key ID have defaults (a user key ID of 1 is the usual one). */
    val canAdd: Boolean get() = name.isNotBlank() && macOk && keyOk && userKeyIdOk

    /**
     * The bike as the vault keeps it. The model is stored as picked, S3 too: [BikeCredentials.withModel] would leave an
     * S3 without a profile at null, and the Garage would then ask for the model again until the first connect. A stored
     * S3 is what an unknown bike is treated as anyway (same names, features and driver); only the refusal of the older
     * VanMoofs' service still reads it as unknown ([BikeCredentials.refusalFamily]), as S3 is the form's default. A name
     * left at a default (or blank) becomes the default of the picked model.
     */
    fun toCredentials() = BikeCredentials(
        id = null,
        name = name.trim().let { if (it.isEmpty() || isDefaultName(it)) defaultNameFor(model) else it },
        ownerName = null,
        mac = normalizeMac(macHex),
        encryptionKey = keyInput.lowercase(),
        userKeyId = userKeyId ?: 1,
        chosenFamily = model,
    )

    fun clearSecrets() {
        macInput = ""
        keyInput = ""
    }
}

@Composable
fun rememberManualBikeState(
    defaultName: String = stringResource(R.string.account_default_bike_name),
    defaultNameS2: String = stringResource(R.string.account_default_bike_name_s2),
): ManualBikeState {
    val name = rememberSaveable { mutableStateOf(defaultName) }
    val mac = rememberSaveable { mutableStateOf("") }
    // The key unlocks the bike: keep it out of the saved-state Bundle, like the password.
    val key = remember { mutableStateOf("") }
    val userKeyId = rememberSaveable { mutableStateOf("1") }
    val showKey = rememberSaveable { mutableStateOf(false) }
    // The enum's name, so the Bundle holds a plain string.
    val model = rememberSaveable { mutableStateOf(BikeFamily.S3.name) }
    return remember(defaultName, defaultNameS2) {
        ManualBikeState(name, mac, key, userKeyId, showKey, model, defaultName, defaultNameS2)
    }
}

/**
 * The model choice (S3/X3 or S2/X2), the MAC and the key of a bike entered by hand. The MAC field takes what is typed as it is
 * (so a pasted or typed Bluetooth name ES3-… arrives whole) and shows the colons once it is plain hex. The key is masked, with a
 * show switch. With [compact] only these stand (the name and the user key ID have defaults), the model without its long hint, the
 * counter of the key is its supporting text, and the keyboard's Done action is [onDone]; otherwise the Garage's full set with a
 * hint under each field.
 */
@Composable
fun ManualBikeFields(state: ManualBikeState, modifier: Modifier = Modifier, compact: Boolean = false, onDone: () -> Unit = {}) {
    val hexKeyboard = KeyboardOptions(
        capitalization = KeyboardCapitalization.Characters,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
        imeAction = ImeAction.Next,
    )
    val keyCounter = if (state.keyOk) {
        stringResource(R.string.account_key_complete)
    } else {
        pluralStringResource(R.plurals.account_key_remaining, state.keyRemaining, state.keyRemaining)
    }
    val keyCounterColor = if (state.keyOk) S3.colors.led else S3.colors.fog

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        if (!compact) {
            OutlinedTextField(
                value = state.name,
                onValueChange = { state.name = it },
                label = { Text(stringResource(R.string.account_field_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                colors = s3FieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // The model decides the protocol: picked here, so a hand-added S2 gets its own driver from the first connect on.
        Text(stringResource(R.string.s2ui_garage_model_title), style = S3.type.bodyStrong, color = S3.colors.paint)
        ChoiceBar(
            options = listOf(BikeFamily.S3 to MODEL_S3, BikeFamily.S2 to MODEL_S2),
            selected = state.model,
            onSelect = { state.model = it },
        )
        if (!compact) Hint(stringResource(R.string.s2ui_garage_model_hint))

        OutlinedTextField(
            value = state.macInput,
            onValueChange = { state.macInput = macInputFrom(it) },
            label = { Text(stringResource(R.string.account_field_mac)) },
            singleLine = true,
            isError = state.macInput.isNotEmpty() && !state.macOk,
            visualTransformation = MacColons,
            keyboardOptions = hexKeyboard,
            colors = s3FieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!compact) Hint(stringResource(manualMacHint(state.model)))

        OutlinedTextField(
            value = state.keyInput,
            onValueChange = { state.keyInput = hexOnly(it, 32) },
            label = { Text(stringResource(R.string.account_field_key)) },
            singleLine = true,
            isError = state.keyInput.isNotEmpty() && !state.keyOk,
            visualTransformation = if (state.showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = hexKeyboard.copy(
                capitalization = KeyboardCapitalization.None,
                // a masked key is a secret: no suggestions, nothing learned by the keyboard
                keyboardType = if (state.showKey) KeyboardType.Ascii else KeyboardType.Password,
                imeAction = if (compact) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            trailingIcon = {
                val toggleLabel = stringResource(if (state.showKey) R.string.account_password_hide else R.string.account_password_show)
                val toggleSpoken = stringResource(
                    if (state.showKey) R.string.account_key_hide_description else R.string.account_key_show_description,
                )
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { state.showKey = !state.showKey }
                        .semantics { contentDescription = toggleSpoken }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = toggleLabel, style = S3.type.label, color = S3.colors.led, maxLines = 1)
                }
            },
            supportingText = if (compact) {
                { Text(keyCounter, style = S3.type.caption, color = keyCounterColor) }
            } else null,
            colors = s3FieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!compact) {
            Hint(text = keyCounter, color = keyCounterColor)

            OutlinedTextField(
                value = state.userKeyIdInput,
                onValueChange = { state.userKeyIdInput = digitsOnly(it, 3) },
                label = { Text(stringResource(R.string.account_field_user_key_id)) },
                singleLine = true,
                isError = state.userKeyIdInput.isNotEmpty() && !state.userKeyIdOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                colors = s3FieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(stringResource(R.string.account_user_key_id_hint))
        }
    }
}

/**
 * Add a bike by hand from its MAC and key — the path when the cloud is gone for good or
 * someone pasted their credentials in. The MAC field keeps raw hex and only shows the colons;
 * both values are validated before the button wakes up.
 */
@Composable
fun ManualBikeForm(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    defaultName: String = stringResource(R.string.account_default_bike_name),
) {
    val state = rememberManualBikeState(defaultName)
    val focus = LocalFocusManager.current

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        ManualBikeFields(state)
        QuietButton(
            text = stringResource(R.string.account_manual_add),
            onClick = {
                focus.clearFocus()
                vm.addManualBike(state.toCredentials())
                state.clearSecrets()
            },
            enabled = state.canAdd,
            color = S3.colors.led,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Picking a key file: the system picker, the size cap and the reading, then [AppViewModel.importKeys]. A file that cannot be
 * a key file is turned away before it reaches the import, with [error] to show. [onText] gets the text of a readable file.
 */
@Stable
class KeyFileImport internal constructor() {
    var error by mutableStateOf<String?>(null)
        internal set
    internal var launch: () -> Unit = {}

    fun pick() = launch()
    fun clearError() { error = null }
}

@Composable
fun rememberKeyFileImport(vm: AppViewModel, onText: (String) -> Unit = {}): KeyFileImport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { KeyFileImport() }
    val tooLarge = stringResource(R.string.account_import_file_too_large)
    val unreadable = stringResource(R.string.account_import_file_unreadable)
    val textSink = rememberUpdatedState(onText)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state.error = null
        scope.launch {
            when (val file = withContext(Dispatchers.IO) { readKeyFile(context, uri) }) {
                is KeyFile.Text -> {
                    textSink.value(file.text)
                    vm.importKeys(file.text)
                }
                KeyFile.TooLarge -> {
                    vm.clearAccountMessage()
                    state.error = tooLarge
                }
                KeyFile.Unreadable -> {
                    vm.clearAccountMessage()
                    state.error = unreadable
                }
            }
        }
    }
    state.launch = { picker.launch(arrayOf("application/json", "text/*")) }
    return state
}

/**
 * Import keys from JSON — our own export or a mooovy `vm-bike-credentials` array — by pasting
 * the text or picking a file. [AppViewModel.importKeys] reports the result on the account channel;
 * a file that can't be a key file is turned away here before it reaches the field.
 */
@Composable
fun ImportKeys(vm: AppViewModel, modifier: Modifier = Modifier) {
    // Plain remember: the text holds keys, and a wrongly picked file could blow the saved-state Bundle.
    var text by remember { mutableStateOf("") }
    val file = rememberKeyFileImport(vm) { text = it }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        ImportPasteField(
            text = text,
            onTextChange = { text = it; file.clearError() },
            modifier = Modifier.heightIn(min = 120.dp),
            fixedHeight = false,
        )
        QuietButton(
            text = stringResource(R.string.account_import_submit),
            onClick = {
                file.clearError()
                if (text.isNotBlank()) vm.importKeys(text)
            },
            enabled = text.isNotBlank(),
            color = S3.colors.led,
            modifier = Modifier.fillMaxWidth(),
        )
        QuietButton(
            text = stringResource(R.string.account_import_pick_file),
            onClick = { file.pick() },
            modifier = Modifier.fillMaxWidth(),
        )
        file.error?.let { Hint(it, color = S3.colors.signal) }
        Hint(stringResource(R.string.account_import_hint))
    }
}

/**
 * The text field for pasted key JSON. With [fixedHeight] it is five lines tall (140 dp) whatever is typed, and scrolls
 * inside itself: the onboarding never lets a screen grow past the window.
 */
@Composable
fun ImportPasteField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fixedHeight: Boolean = true,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(stringResource(R.string.account_import_field)) },
        minLines = if (fixedHeight) 5 else 4,
        maxLines = if (fixedHeight) 5 else Int.MAX_VALUE,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        colors = s3FieldColors(),
        modifier = modifier.fillMaxWidth().then(if (fixedHeight) Modifier.height(140.dp) else Modifier),
    )
}

/* --- small input helpers ------------------------------------------------------------------- */

/** Key files are a few hundred bytes; anything past this is the wrong file (a ride log, a photo). */
private const val MAX_KEY_FILE_BYTES = 64 * 1024

private sealed interface KeyFile {
    data class Text(val text: String) : KeyFile
    data object TooLarge : KeyFile
    data object Unreadable : KeyFile
}

/** Reads a picked key file with a size cap. Blocking: call it off the main thread. */
private fun readKeyFile(context: Context, uri: Uri): KeyFile = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
            if (out.size() > MAX_KEY_FILE_BYTES) return KeyFile.TooLarge
        }
        val text = String(out.toByteArray(), Charsets.UTF_8).removePrefix("\uFEFF")
        if (text.isBlank()) KeyFile.Unreadable else KeyFile.Text(text)
    } ?: KeyFile.Unreadable
}.getOrElse { KeyFile.Unreadable }

/** What the MAC field keeps of what is typed: upper case, letters and digits, colons and dashes, at most 24 characters. */
internal fun macInputFrom(input: String): String =
    input.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' || it == ':' || it == '-' }.take(24)

/**
 * The hint under the MAC on the manual form, for the [model] picked. An S3 or X3 names its whole MAC in its Bluetooth name
 * (ES3-A1B2C3D4E5F6); an S2 or X2 advertises VANMOOF- and only the last 6 digits, so its hint points to the key file alone.
 */
@StringRes
internal fun manualMacHint(model: BikeFamily): Int =
    if (model == BikeFamily.S2) R.string.account_mac_hint_s2 else R.string.account_mac_hint

/**
 * The 12 hex digits of a MAC from free input. A BLE name prefix (ES3- or EX3- of an S3/X3, VANMOOF- of an S2/X2, also
 * without the dash) is dropped first, as a whole: its E and 3, or the A and F of VANMOOF, would otherwise count as hex
 * digits. Then colons, dashes and everything else that is not hex. An S2's name keeps only its 6 digits, never a MAC.
 */
internal fun macHexFrom(input: String): String {
    var u = input.uppercase()
    for (prefix in arrayOf("ES3", "EX3", "VANMOOF")) {
        if (u.startsWith(prefix)) { u = u.removePrefix(prefix); break }
    }
    return u.filter { it in '0'..'9' || it in 'A'..'F' }.take(12)
}

/**
 * Shows a MAC that is plain hex as AA:BB:CC:DD:EE:FF. The colons exist only on screen, so the IME, the cursor and the
 * stored text see the same digits. Anything else (a Bluetooth name with its ES3-, colons or dashes typed by hand) is
 * shown as it was typed.
 */
private object MacColons : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.any { it !in '0'..'9' && it !in 'A'..'F' }) return TransformedText(text, OffsetMapping.Identity)
        val shown = raw.chunked(2).joinToString(":")
        return TransformedText(AnnotatedString(shown), object : OffsetMapping {
            // A colon follows every second digit except the last pair: raw offset o has
            // (o - 1) / 2 colons before it. Offset 2 stays in front of the first colon.
            override fun originalToTransformed(offset: Int): Int =
                if (offset <= 0) 0 else (offset + (offset - 1) / 2).coerceAtMost(shown.length)

            // Colons sit at shown positions 2, 5, 8, …, so t / 3 of them lie before position t.
            override fun transformedToOriginal(offset: Int): Int =
                (offset - offset / 3).coerceIn(0, raw.length)
        })
    }
}

// ASCII ranges on purpose: isDigit() would also let through Arabic-Indic and other Unicode digits.
private fun hexOnly(s: String, max: Int): String =
    s.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(max)

private fun digitsOnly(s: String, max: Int): String = s.filter { it in '0'..'9' }.take(max)
