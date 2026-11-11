package dev.steven.s3control.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.Link
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ui.FamilyGate
import dev.steven.s3control.ui.components.AccountMessages
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.DisclosureSection
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.ImportKeys
import dev.steven.s3control.ui.components.LinkIndicator
import dev.steven.s3control.ui.components.LoginForm
import dev.steven.s3control.ui.components.ManualBikeForm
import dev.steven.s3control.ui.components.OutburstBlockWhile
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.SectionTitle
import dev.steven.s3control.ui.components.ShellFitText
import dev.steven.s3control.ui.components.UnderlinedAction
import dev.steven.s3control.ui.components.s3FieldColors
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.delay

/** Garage screens currently composed (main thread only); see the DisposableEffect in [GarageScreen]. */
private var garagesShown = 0
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Bike list and everything around the keys: connect, rename, delete, back up, and the account.
 * Opened as an overlay from the Bike screen. Leaves the account status clean on the way out.
 * No BigButton here: the Garage has no single main action, every bike row and section is equal.
 */
@Composable
fun GarageScreen(vm: AppViewModel, wide: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val vault by vm.vault.collectAsStateWithLifecycle()
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    // While the Wache is active YABA stays with the armed bike: no connect to another bike, the armed one is neither removed
    // nor switched to another model (both would drop the link). the guard design 7.6.
    val guard by vm.guardState.collectAsStateWithLifecycle()
    val armedMac = guard.armedMac?.takeIf { guard.active }
    val context = LocalContext.current

    val loggedIn = vault.session != null
    val connectedMac = if (state.isReady) state.bike?.normalizedMac else null
    // The bike the link is busy with (searching, connecting, signing in), told on its row.
    val linkingMac = state.bike?.normalizedMac?.takeIf {
        state.link == Link.SEARCHING || state.link == Link.CONNECTING || state.link == Link.AUTHENTICATING
    }
    // Connected bike first, then the last one used, then the rest in the vault's order.
    val bikes = vault.bikes.sortedBy { bike ->
        when (bike.normalizedMac) {
            connectedMac -> 0
            vault.lastBikeMac -> 1
            else -> 2
        }
    }

    var renaming by remember { mutableStateOf<BikeCredentials?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<BikeCredentials?>(null) }
    var copied by remember { mutableStateOf(false) }

    // A fold moves the Garage from one shell to the other: this instance is disposed and the
    // other shell's is composed in the same frame. That isn't leaving, so check once the frame
    // is applied and only clear the account message when no Garage took over.
    DisposableEffect(vm) {
        garagesShown++
        onDispose {
            garagesShown--
            mainHandler.post { if (garagesShown == 0) vm.clearAccountMessage() }
        }
    }

    // A model change for the connected bike reconnects it with the other protocol: asked first.
    var switching by remember { mutableStateOf<Pair<BikeCredentials, BikeFamily>?>(null) }

    // Nothing is called out on the top tube while a dialog of this screen is open.
    OutburstBlockWhile(renaming != null || deleting != null || switching != null)

    val exportSubject = stringResource(R.string.account_garage_export_subject)
    val exportChooser = stringResource(R.string.account_garage_export_chooser)
    val exportKeys: () -> Unit = {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, exportSubject)
            putExtra(Intent.EXTRA_TEXT, vm.exportKeys())
        }
        context.startActivity(Intent.createChooser(send, exportChooser))
    }

    val hPad = if (wide) S3.l else S3.gutter

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = if (wide) 640.dp else Dp.Unspecified)
                .padding(horizontal = hPad)
                .padding(top = S3.l, bottom = S3.xl),
            verticalArrangement = Arrangement.spacedBy(S3.xl),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(S3.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Steps down instead of wrapping as "Garag/e" next to the button on a 320 dp cover.
                ShellFitText(
                    text = stringResource(R.string.account_garage_title),
                    style = S3.type.display,
                    color = S3.colors.paint,
                    maxLines = 1,
                    minFontSize = 28.sp,
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
                )
                QuietButton(stringResource(R.string.account_garage_done), onClick = onClose)
            }

            // Bikes
            Column(verticalArrangement = Arrangement.spacedBy(S3.m)) {
                if (guard.active && bikes.isNotEmpty()) Hint(stringResource(R.string.guard_link_locked))
                if (bikes.isEmpty()) {
                    Hint(stringResource(R.string.account_garage_empty))
                } else {
                    bikes.forEachIndexed { index, bike ->
                        key(bike.normalizedMac) {
                            val connected = bike.normalizedMac == connectedMac
                            val armed = bike.normalizedMac == armedMac
                            BikeRow(
                                bike = bike,
                                connected = connected,
                                linking = bike.normalizedMac == linkingMac,
                                liveFamily = if (connected) state.family else null,
                                // Connecting the armed bike itself only helps the Wache; any other bike waits.
                                connectLocked = guard.active && !armed,
                                guarded = armed,
                                onConnect = { vm.connect(bike); onClose() },
                                onRename = { renaming = bike; renameText = bike.name },
                                onDelete = { deleting = bike },
                                onModel = { model -> if (connected) switching = bike to model else vm.setBikeModel(bike.mac, model) },
                            )
                            if (index != bikes.lastIndex) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(S3.colors.rib))
                            }
                        }
                    }
                }
            }

            // Back up the keys
            Column(verticalArrangement = Arrangement.spacedBy(S3.m)) {
                SectionTitle(stringResource(R.string.account_garage_backup_title))
                Hint(stringResource(R.string.account_garage_backup_hint))
                QuietButton(
                    text = stringResource(R.string.account_garage_export),
                    onClick = exportKeys,
                    enabled = vault.bikes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                QuietButton(
                    text = stringResource(R.string.account_garage_copy),
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(exportSubject, vm.exportKeys())
                        // The keys unlock the bike: keep them out of the clipboard preview (Android 13+).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            clip.description.extras = PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                        cm.setPrimaryClip(clip)
                        copied = true
                    },
                    enabled = vault.bikes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (copied) {
                    Hint(stringResource(R.string.account_garage_copied), color = S3.colors.led)
                    LaunchedEffect(copied) { delay(4000); copied = false }
                }
            }

            // Account
            Column(verticalArrangement = Arrangement.spacedBy(S3.m)) {
                SectionTitle(stringResource(R.string.account_garage_account_title))
                if (loggedIn) {
                    Text(
                        stringResource(R.string.account_garage_signed_in_as, vault.session?.email.orEmpty()),
                        style = S3.type.body,
                        color = S3.colors.paint,
                    )
                    QuietButton(
                        text = stringResource(
                            if (account.running) R.string.account_garage_refreshing else R.string.account_garage_refresh,
                        ),
                        onClick = { vm.refreshBikes() },
                        enabled = !account.running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    QuietButton(
                        text = stringResource(R.string.account_garage_logout),
                        onClick = { vm.logout() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AccountMessages(vm, onRetry = { vm.refreshBikes() })
                } else {
                    Hint(stringResource(R.string.account_garage_login_hint))
                    LoginForm(vm, primary = false)
                }
            }

            // Secondary paths, folded away
            DisclosureSection(stringResource(R.string.account_garage_add_manual)) { ManualBikeForm(vm) }
            DisclosureSection(stringResource(R.string.account_import_title)) { ImportKeys(vm) }
        }
    }

    renaming?.let { bike ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = S3.colors.frame,
            title = { Text(stringResource(R.string.account_garage_rename_title), style = S3.type.section, color = S3.colors.paint) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.account_field_name)) },
                    singleLine = true,
                    colors = s3FieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.account_garage_save),
                    onClick = {
                        if (renameText.isNotBlank()) vm.renameBike(bike.mac, renameText.trim())
                        renaming = null
                    },
                    enabled = renameText.isNotBlank(),
                    color = S3.colors.led,
                )
            },
            dismissButton = {
                QuietButton(stringResource(R.string.account_garage_cancel), onClick = { renaming = null }, color = S3.colors.fog)
            },
        )
    }

    switching?.let { (bike, model) ->
        val name = if (model == BikeFamily.S2) MODEL_S2 else MODEL_S3
        AlertDialog(
            onDismissRequest = { switching = null },
            containerColor = S3.colors.frame,
            title = { Text(stringResource(R.string.s2fix_garage_switch_title, name), style = S3.type.section, color = S3.colors.paint) },
            text = { Text(stringResource(R.string.s2fix_garage_switch_text, bike.name, name), style = S3.type.body, color = S3.colors.fog) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.s2fix_garage_switch_confirm),
                    onClick = { vm.setBikeModel(bike.mac, model); switching = null },
                    color = S3.colors.led,
                )
            },
            dismissButton = {
                QuietButton(stringResource(R.string.account_garage_cancel), onClick = { switching = null }, color = S3.colors.fog)
            },
        )
    }

    deleting?.let { bike ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = S3.colors.frame,
            title = {
                Text(stringResource(R.string.account_garage_delete_title, bike.name), style = S3.type.section, color = S3.colors.paint)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(S3.m)) {
                    Text(
                        stringResource(R.string.account_garage_delete_body),
                        style = S3.type.body,
                        color = S3.colors.fog,
                    )
                    // Without the account the key file is the only way back to the bike: offer it right here.
                    if (!loggedIn) {
                        QuietButton(
                            text = stringResource(R.string.account_garage_backup_first),
                            onClick = exportKeys,
                            color = S3.colors.led,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.account_garage_delete),
                    onClick = { vm.removeBike(bike.mac); deleting = null },
                    color = S3.colors.signal,
                )
            },
            dismissButton = {
                QuietButton(stringResource(R.string.account_garage_cancel), onClick = { deleting = null }, color = S3.colors.fog)
            },
        )
    }
}

@Composable
private fun BikeRow(
    bike: BikeCredentials,
    connected: Boolean,
    /** The link is searching, connecting or signing in to this bike. */
    linking: Boolean,
    /** The family the live link showed for this bike, while it is the connected one. */
    liveFamily: BikeFamily?,
    /** The Wache is active for another bike: connecting this one would end its link. */
    connectLocked: Boolean,
    /** The Wache is active for this bike: no delete, no model change. */
    guarded: Boolean,
    onConnect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onModel: (BikeFamily) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = S3.colors
    val swatch = parseHexColor(bike.colorPrimary)
    val family = bike.family
    // With several bikes TalkBack hears a list of equal buttons; name the bike on each.
    val connectSpoken = stringResource(R.string.account_garage_connect_description, bike.name)
    val renameSpoken = stringResource(R.string.account_garage_rename_description, bike.name)
    val deleteSpoken = stringResource(R.string.account_garage_delete_title, bike.name)
    val editSpoken = stringResource(R.string.account_garage_edit_description, bike.name)
    // A human model name only: the account's, else what YABA treats the bike as. An S2 (support in testing) and
    // an older model (no support) say so instead, since that is what the owner needs to know about them.
    val modelLine = when (family) {
        BikeFamily.S2 -> stringResource(R.string.s2ui_garage_family_s2)
        BikeFamily.LEGACY_UNSUPPORTED -> stringResource(R.string.s2ui_garage_family_legacy)
        BikeFamily.S3, BikeFamily.UNKNOWN ->
            bike.modelName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.s2ui_garage_family_s3)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row {
                    if (swatch != null) PaintSwatch(swatch, Modifier.alignBy { it.measuredHeight })
                    Text(
                        bike.name,
                        style = S3.type.title,
                        color = c.paint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignBy(FirstBaseline),
                    )
                }
                when {
                    connected -> LinkIndicator(stringResource(R.string.account_garage_connected), color = c.led, pulsing = false)
                    linking -> LinkIndicator(stringResource(R.string.sound_connect_connecting), color = c.fog, pulsing = true)
                }
            }
            if (!connected && !linking && FamilyGate.canConnect(bike)) {
                LedPill(
                    text = stringResource(R.string.account_garage_connect),
                    onClick = onConnect,
                    enabled = !connectLocked,
                    modifier = Modifier.padding(start = S3.s).semantics { contentDescription = connectSpoken },
                )
            }
        }
        // One line of details: the address in the data cut, then where the key came from.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(bike.normalizedMac, style = S3.type.data.copy(fontSize = 15.sp, lineHeight = 18.sp), color = c.fog, maxLines = 1)
            Text(
                stringResource(if (bike.fromAccount) R.string.account_garage_source_account else R.string.account_garage_source_manual),
                style = S3.type.caption,
                color = c.fog,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = S3.s),
            )
        }
        Text(
            modelLine,
            style = S3.type.caption,
            color = if (family == BikeFamily.LEGACY_UNSUPPORTED) c.signal else c.fog,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // A bike the account does not describe and nothing has settled yet: the model has to be chosen, so the
        // choice stands open on the row. Every other change waits behind "Bearbeiten".
        val choiceNeeded = FamilyGate.modelChoice(bike) && FamilyGate.modelChoiceOpen(bike, liveFamily)
        val modelChoice: @Composable () -> Unit = {
            Text(stringResource(R.string.s2ui_garage_model_title), style = S3.type.bodyStrong, color = c.paint)
            ChoiceBar(
                options = listOf(BikeFamily.S3 to MODEL_S3, BikeFamily.S2 to MODEL_S2),
                selected = FamilyGate.shownModel(bike),
                onSelect = { model -> if (!guarded && model != FamilyGate.shownModel(bike)) onModel(model) },
                readOnly = guarded,
            )
            Hint(stringResource(R.string.s2ui_garage_model_hint))
        }
        if (choiceNeeded) {
            Column(Modifier.padding(top = S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) { modelChoice() }
        }
        DisclosureSection(
            label = stringResource(R.string.account_garage_edit),
            compact = true,
            description = editSpoken,
        ) {
            QuietButton(
                stringResource(R.string.account_garage_rename),
                onClick = onRename,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = renameSpoken },
            )
            if (FamilyGate.modelChoice(bike) && !choiceNeeded) {
                Column(verticalArrangement = Arrangement.spacedBy(S3.s)) { modelChoice() }
            }
            UnderlinedAction(
                stringResource(R.string.account_garage_delete),
                onClick = onDelete,
                enabled = !guarded,
                // The text, not its touch padding, lines up with the row.
                modifier = Modifier.offset(x = -S3.s),
                color = c.signal,
                description = deleteSpoken,
            )
        }
    }
}

/**
 * The frame paint before the bike's name: a capsule as tall as the name's capital letters (cap height of the title cut,
 * 0.686 of its size, so it grows with the font scale) and 1.7 times as wide, standing on the name's baseline. The ring
 * is fog, which holds on asphalt and on the light ground alike, so a dark paint does not sink into Night and a light one
 * does not vanish by Day. Decoration: the name says which bike it is.
 */
@Composable
private fun PaintSwatch(color: Color, modifier: Modifier = Modifier) {
    val cap = with(LocalDensity.current) { (S3.type.title.fontSize * CAP_HEIGHT).toDp() }
    Box(
        modifier
            .padding(end = S3.s)
            .size(width = cap * 1.7f, height = cap)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, S3.colors.fog, CircleShape),
    )
}

/** Cap height of Archivo as a share of the font size. */
private const val CAP_HEIGHT = 0.686f

/** The one action of a bike that is not connected: a small filled capsule in led, 48 dp to touch. */
@Composable
private fun LedPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = S3.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (enabled) c.led else c.rib)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = S3.type.bodyStrong, color = if (enabled) c.onLed else c.fog, maxLines = 1)
    }
}

/** Model names on the choice (here and on the form for a bike added by hand): proper names, the same in every language. */
internal const val MODEL_S3 = "S3/X3"
internal const val MODEL_S2 = "S2/X2"

/** Parses "#RRGGBB" (or "#AARRGGBB") into a Color, or null when it is missing or malformed. */
private fun parseHexColor(raw: String?): Color? {
    val hex = raw?.trim()?.removePrefix("#") ?: return null
    val value = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> null
    }
}
