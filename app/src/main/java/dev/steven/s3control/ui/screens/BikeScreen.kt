package dev.steven.s3control.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.LabProtocol
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.SpeedPlus
import dev.steven.s3control.ble.SpeedPlusView
import dev.steven.s3control.ble.UnitSystem
import dev.steven.s3control.ble.WheelStock
import dev.steven.s3control.data.AppSettings
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.data.ThemeChoice
import dev.steven.s3control.ui.FamilyGate
import dev.steven.s3control.ui.LocalNavigator
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.BikeFeedback
import dev.steven.s3control.ui.components.BikeFeedbackNote
import dev.steven.s3control.ui.components.BikeLabSection
import dev.steven.s3control.ui.components.BikeSectionTitle
import dev.steven.s3control.ui.components.BikeTube
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.ConnectPrompt
import dev.steven.s3control.ui.components.DASH
import dev.steven.s3control.ui.components.DisclosureSection
import dev.steven.s3control.ui.components.Hairline
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.LampNote
import dev.steven.s3control.ui.components.LinkLine
import dev.steven.s3control.ui.components.OfficialAppNotice
import dev.steven.s3control.ui.components.OutburstBlockWhile
import dev.steven.s3control.ui.components.OutburstSettings
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.ScreenTitle
import dev.steven.s3control.ui.components.SwitchRow
import dev.steven.s3control.ui.components.ValueRow
import dev.steven.s3control.ui.components.WorkingQuietButton
import dev.steven.s3control.ui.components.YabaWordmark
import dev.steven.s3control.ui.components.appLocale
import dev.steven.s3control.ui.components.bikeFieldColors
import dev.steven.s3control.ui.components.formatDistanceKm
import dev.steven.s3control.ui.components.rememberBikeFeedback
import dev.steven.s3control.ui.onboarding.TextLink
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * The Bike tab, in the order it is used: who the bike is (facts), the link, the settings, the backup code, sharing and
 * the experiments; then, folded, what is looked up rarely (versions and identifiers, the Bike-Lab); and "Über" at the end.
 * The language is not here: it is the app's, not the bike's, and stands in the shell's corner (LanguageButton, ui/AppRoot.kt),
 * reachable without a bike and before the first connect.
 */

@Composable
fun BikeScreen(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val vault by vm.vault.collectAsStateWithLifecycle()
    val feedback = rememberBikeFeedback(vm)
    val preferred = remember(vault) { vm.preferredBike() }
    val bike = state.bike ?: preferred
    // Before the first connect the stored bike's family decides what the tab offers (as on the Diagnose page): an S2 never
    // sees the S3 sections in the meantime. Only the gating reads it; every value still comes from [state].
    val s2TestMode by vm.bike.s2Prefs.testMode.collectAsStateWithLifecycle()
    val gate = FamilyGate.beforeConnect(state, preferred, s2TestMode)
    val gutter = if (wide) S3.l else S3.gutter
    val nav = LocalNavigator.current
    // Speed++: the bike's factory wheel size, live once 5564 is read, else from the model name and what YABA stored.
    val wheelStock = remember(bike, state.wheelStock, state.versionValues) { vm.bike.wheelStock(bike) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = gutter)
            .padding(top = S3.l, bottom = S3.xl),
    ) {
        // On the inner screen the left pane already shows name, model, link, battery and distance.
        if (!wide) {
            Header(bike, state)
            Spacer(Modifier.height(S3.l))
        }

        if (bike == null) {
            // Wide: the left pane carries the loud "pick a bike" button, so this one stays quiet.
            NoBike(loud = !wide)
        } else {
            FactsGrid(bike, state, gate, withRideFacts = !wide)
            // The S2 has no Klang tab: say why here, once.
            if (gate.family == BikeFamily.S2) {
                Spacer(Modifier.height(S3.m))
                Hint(stringResource(R.string.s2ui_sound_none))
            }
            if (wide) {
                Spacer(Modifier.height(S3.l))
                QuietButton(stringResource(R.string.bike_manage_bikes), nav.openGarage)
            }

            Spacer(Modifier.height(S3.xl))
            ConnectionSection(vm, state, feedback)
        }

        Spacer(Modifier.height(S3.xl))
        SettingsSection(vm, state, gate, settings, feedback, hasBike = bike != null)

        if (bike != null) {
            // Each section only where the bike's family has it (plan A1): an S3 keeps every one of them.
            if (FamilyGate.backupCode(gate)) {
                Spacer(Modifier.height(S3.xl))
                BackupCodeSection(vm, state, gate, feedback)
            }

            if (FamilyGate.sharing(gate)) {
                Spacer(Modifier.height(S3.xl))
                ShareSection(vm)
            }

            // Speed++, 37 km/h and Stufe 5 are S3 firmware experiments.
            if (FamilyGate.s3Experiments(gate)) {
                Spacer(Modifier.height(S3.xl))
                ExperimentsSection(vm, state, bike, feedback, wheelStock)
            }
        }

        // The folds stand together as one list of fold rows under one rule, as the groups on Klang: rarely needed, one tap
        // each. No rule between them: an open fold's own rows would stand on a second one.
        Spacer(Modifier.height(S3.xl))
        Hairline()
        if (bike != null) VersionsSection(bike, state)
        if (bike != null && FamilyGate.s3Experiments(gate)) BikeLabSection(vm)

        Spacer(Modifier.height(S3.xl))
        AboutSection(wide, onReplayIntro = vm::replayIntro)
    }
}

// --- Kopf & Fakten -------------------------------------------------------------------------------

private fun BikeState.version(label: String): String? = versions.firstOrNull { it.first == label }?.second

/** Cover screen only: the inner screen's left pane shows all of this already. */
@Composable
private fun Header(bike: BikeCredentials?, state: BikeState) {
    val c = S3.colors
    val nav = LocalNavigator.current
    ScreenTitle(bike?.name ?: stringResource(R.string.bike_no_bike_title))
    if (bike != null) {
        val model = bike.modelName ?: state.version(Gatt.MODEL.label)
        val owner = bike.ownerName?.takeIf { it.isNotBlank() }
        val line = when {
            model != null && owner != null -> stringResource(R.string.bike_model_of_owner, model, owner)
            model != null -> model
            owner != null -> stringResource(R.string.bike_owned_by, owner)
            else -> null
        }
        if (line != null) {
            Spacer(Modifier.height(S3.xs))
            Text(line, style = S3.type.label, color = c.fog)
        }
        Spacer(Modifier.height(S3.m))
        LinkLine(state)
        Spacer(Modifier.height(S3.m))
        QuietButton(stringResource(R.string.bike_manage_bikes), nav.openGarage)
    }
}

@Composable
private fun NoBike(loud: Boolean) {
    val nav = LocalNavigator.current
    Text(stringResource(R.string.bike_no_bike_text), style = S3.type.body, color = S3.colors.paint)
    Spacer(Modifier.height(S3.m))
    if (loud) {
        BigButton(stringResource(R.string.bike_add_bike), nav.openGarage)
    } else {
        QuietButton(stringResource(R.string.bike_add_bike), nav.openGarage)
    }
}

private class BikeFact(val label: String, val value: String, val color: Color)

/** Speed++ is on: a 28″ bike set to 24″ ([SpeedPlus]), exactly when YABA scales the bike's speed (as on the Fahren page). */
private fun speedPlusOn(state: BikeState): Boolean = state.speedFactor > 1f

/**
 * The bike at a glance: mileage and battery ([withRideFacts], the cover screen; the inner screen's left pane has
 * them), model, firmware, and an S2's module battery. Two across on the cover, four from 480 dp. With Speed++ on,
 * the wheel comes first in lamp, and the mileage says it counts short. MAC and frame number are under
 * "Versionen und Kennungen".
 */
@Composable
private fun FactsGrid(bike: BikeCredentials, state: BikeState, gate: BikeState, withRideFacts: Boolean) {
    val c = S3.colors
    val locale = appLocale()
    val charging = state.charging == true
    val speedPlus = speedPlusOn(state)
    val distanceLabel = stringResource(if (speedPlus) R.string.bike_fact_distance_speedplus else R.string.bike_fact_distance)
    val batteryLabel = stringResource(if (charging) R.string.bike_fact_battery_charging else R.string.bike_fact_battery)
    val batteryValue = state.battery?.let { stringResource(R.string.bike_value_percent, it) } ?: DASH
    val firmwareLabel = stringResource(R.string.bike_fact_firmware)
    val modelLabel = stringResource(R.string.bike_fact_model)
    val moduleLabel = stringResource(R.string.s2ui_fact_module_battery)
    // An S2 names its model in Device Information (2A24), if at all; otherwise the family's proper name.
    val s2Model = if (gate.family == BikeFamily.S2) state.version(stringResource(R.string.core_char_device_model)) ?: "VanMoof S2" else null

    val facts = buildList {
        if (speedPlus) add(BikeFact(stringResource(R.string.bike_fact_wheel), stringResource(R.string.bike_fact_wheel_speedplus), c.lamp))
        if (withRideFacts) {
            add(BikeFact(distanceLabel, state.distanceKm?.let { formatDistanceKm(it, locale) } ?: DASH, c.paint))
            add(
                BikeFact(
                    batteryLabel,
                    batteryValue,
                    when {
                        charging -> c.lamp
                        (state.battery ?: 100) <= 15 -> c.signal
                        else -> c.paint
                    },
                ),
            )
        }
        add(BikeFact(modelLabel, bike.modelName ?: state.version(Gatt.MODEL.label) ?: s2Model ?: DASH, c.paint))
        add(BikeFact(firmwareLabel, state.firmware ?: state.version(Gatt.BIKE_FIRMWARE.label) ?: DASH, c.paint))
        // S2: the smart module's own battery, which no other page shows.
        if (FamilyGate.moduleBattery(gate)) {
            val module = state.moduleBattery
            add(
                BikeFact(
                    moduleLabel,
                    module?.let { stringResource(R.string.bike_value_percent, it) } ?: DASH,
                    if ((module ?: 100) <= 15) c.signal else c.paint,
                ),
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp) 4 else 2
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.m)) {
            facts.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.m)) {
                    row.forEach { f ->
                        FactCell(f.label, f.value, if (f.value == DASH) c.fog else f.color, Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Like Fact, but values and labels wrap instead of ending in "…". */
@Composable
private fun FactCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Hairline()
        Spacer(Modifier.height(10.dp))
        Text(value, style = S3.type.data, color = valueColor, maxLines = 2)
        Text(label, style = S3.type.caption, color = S3.colors.fog, maxLines = 3)
    }
}

// --- Verbindung ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionSection(vm: AppViewModel, state: BikeState, fb: BikeFeedback) {
    // While the Wache is active YABA stays with the armed bike: "Trennen" waits until it has ended (the guard design 7.6).
    val guard by vm.guardState.collectAsStateWithLifecycle()
    BikeSectionTitle(stringResource(R.string.bike_connection_title))
    Spacer(Modifier.height(S3.m))
    if (state.isReady) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
            WorkingQuietButton(
                stringResource(R.string.bike_connection_wake),
                stringResource(R.string.bike_connection_waking),
                fb.isBusy("wake"),
                { fb.run("wake", Op.WAKE) { vm.bike.wakeUp() } },
                // The controller refuses WAKE while the Wache is active (the guard design 2.4).
                enabled = !guard.active,
            )
            WorkingQuietButton(
                stringResource(R.string.bike_connection_refresh),
                stringResource(R.string.bike_connection_refreshing),
                fb.isBusy("refresh"),
                { fb.run("refresh", Op.REFRESH) { vm.bike.refresh() } },
            )
            QuietButton(stringResource(R.string.bike_connection_disconnect), { vm.bike.disconnect() }, enabled = !guard.active)
        }
        if (guard.active) {
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.guard_link_locked))
        }
        BikeFeedbackNote(fb, "wake", okText = stringResource(R.string.bike_connection_wake_done))
        BikeFeedbackNote(fb, "refresh", okText = stringResource(R.string.bike_connection_refresh_done))
        if (state.sharedLink) {
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.official_shared_hint))
        }
    } else {
        ConnectPrompt(vm, state)
    }
    Spacer(Modifier.height(S3.l))
    OfficialAppNotice(vm)
}

// --- Versionen und Kennungen ---------------------------------------------------------------------

/** Folded: MAC and frame number, then what the bike reports (firmware is a fact above, the frame number is here once). */
@Composable
private fun VersionsSection(bike: BikeCredentials, state: BikeState) {
    DisclosureSection(stringResource(R.string.bike_versions_title), foldRow = true) {
        Column(Modifier.fillMaxWidth()) {
            ValueRow(stringResource(R.string.bike_fact_mac), bike.normalizedMac)
            ValueRow(stringResource(R.string.bike_fact_frame), state.version(Gatt.FRAME_NUMBER.label) ?: bike.frameNumber ?: DASH)
            val shown = remember(state.versions) {
                state.versions.filter { (label, _) -> label != Gatt.BIKE_FIRMWARE.label && label != Gatt.FRAME_NUMBER.label }
            }
            if (state.versions.isEmpty()) {
                Hint(
                    stringResource(if (state.isReady) R.string.bike_versions_none else R.string.bike_versions_offline),
                    Modifier.padding(top = S3.s),
                )
            } else {
                shown.forEach { (label, value) -> ValueRow(label, value) }
            }
        }
    }
}

// --- Einstellungen -------------------------------------------------------------------------------

@Composable
private fun SettingsSection(
    vm: AppViewModel,
    state: BikeState,
    gate: BikeState,
    settings: AppSettings,
    fb: BikeFeedback,
    hasBike: Boolean,
) {
    val c = S3.colors
    BikeSectionTitle(stringResource(R.string.bike_settings_title))

    if (hasBike) {
        Spacer(Modifier.height(S3.m))
        val unitBusy = fb.isBusy("unit")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bike_units), style = S3.type.bodyStrong, color = c.paint, modifier = Modifier.weight(1f))
            if (unitBusy) CircularProgressIndicator(Modifier.size(16.dp), color = c.led, strokeWidth = 2.dp, trackColor = Color.Transparent)
        }
        Hint(stringResource(R.string.bike_units_hint))
        Spacer(Modifier.height(S3.s))
        ChoiceBar(
            options = listOf(
                UnitSystem.METRIC to stringResource(R.string.bike_unit_metric),
                UnitSystem.IMPERIAL to stringResource(R.string.bike_unit_imperial),
            ),
            selected = state.unit,
            onSelect = { unit -> fb.run("unit", Op.UNIT) { vm.bike.setUnit(unit) } },
            enabled = state.isReady && !unitBusy,
        )
        if (!state.isReady) Hint(stringResource(R.string.bike_needs_link), Modifier.padding(top = S3.s))
        BikeFeedbackNote(fb, "unit")
    }

    // "Farben": Tag is the light scheme, Nacht the dark one, as in the cockpit.
    Spacer(Modifier.height(S3.l))
    Text(stringResource(R.string.bike_theme), style = S3.type.bodyStrong, color = c.paint)
    Spacer(Modifier.height(S3.s))
    ChoiceBar(
        options = listOf(
            ThemeChoice.SYSTEM to stringResource(R.string.bike_theme_system),
            ThemeChoice.LIGHT to stringResource(R.string.bike_theme_light),
            ThemeChoice.DARK to stringResource(R.string.bike_theme_dark),
        ),
        selected = settings.theme,
        onSelect = { choice -> vm.updateSettings { it.copy(theme = choice) } },
    )
    Hint(stringResource(R.string.bike_theme_hint), Modifier.padding(top = S3.s))

    Spacer(Modifier.height(S3.m))
    SwitchRow(
        label = stringResource(R.string.bike_auto_connect),
        caption = stringResource(R.string.bike_auto_connect_caption),
        checked = settings.autoConnect,
        onCheckedChange = { on -> vm.updateSettings { it.copy(autoConnect = on) } },
    )
    Hairline()
    // Bell or boost unlocks (5522): an S3 thing, the S2 has no unlock request.
    if (FamilyGate.touchUnlock(gate)) {
        SwitchRow(
            label = stringResource(R.string.bike_touch_unlock),
            caption = stringResource(R.string.bike_touch_unlock_caption),
            checked = settings.touchUnlock,
            onCheckedChange = { on -> vm.updateSettings { it.copy(touchUnlock = on) } },
        )
        // The catch, said plainly: anyone near the bike can use it while your phone is in range.
        LampNote(stringResource(R.string.bike_touch_unlock_range), Modifier.padding(bottom = S3.s))
        Hairline()
    }
    // The switch for the Dutch calls on the top tube, and the list of the nine words with the rehearsal.
    OutburstSettings()
}

// --- Ersatzcode ----------------------------------------------------------------------------------

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Three digits, set with the keyboard's Done or the one pill beside the field. A set code is confirmed with its time
 * (never its digits: the field is a password field) until the tab is left. Deleting is a link under the notes, with
 * its confirm.
 */
@Composable
private fun BackupCodeSection(vm: AppViewModel, state: BikeState, gate: BikeState, fb: BikeFeedback) {
    val c = S3.colors
    // While the Wache is active the controller refuses the backup code (the guard design 2.4): offered as off, with why.
    val guardOn = vm.guardState.collectAsStateWithLifecycle().value.active
    val ready = state.isReady && !guardOn
    var code by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    // Plain remember: the confirmation stays while the tab is shown and goes with it.
    var setAt by remember { mutableStateOf<Long?>(null) }
    // A typed 0 is dropped; the digit range turns signal for two seconds to say why.
    var zeroAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(zeroAt) {
        if (zeroAt != 0L) {
            delay(2_000)
            zeroAt = 0L
        }
    }
    val canSet = ready && code.length == 3 && fb.running == null
    val set: () -> Unit = {
        if (canSet) {
            val value = code
            setAt = null
            fb.run("code-set", Op.BACKUP_CODE, onResult = { err ->
                if (err == null) {
                    code = ""
                    setAt = System.currentTimeMillis()
                }
            }) { vm.bike.setBackupCode(value) }
        }
    }

    BikeSectionTitle(stringResource(R.string.bike_code_title))
    Spacer(Modifier.height(S3.s))
    // The S2 enters its code another way (unverified): its manual describes it, not the S3's bell button.
    Text(
        stringResource(if (FamilyGate.backupCodeClear(gate)) R.string.bike_code_intro else R.string.s2fix_code_intro_s2),
        style = S3.type.body,
        color = c.paint,
    )
    Spacer(Modifier.height(S3.l))
    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = code,
            onValueChange = { v ->
                if ('0' in v) zeroAt = System.currentTimeMillis()
                code = v.filter { it in '1'..'9' }.take(3)
            },
            modifier = Modifier.width(184.dp),
            enabled = ready,
            textStyle = S3.type.dataLarge.copy(color = if (ready) c.paint else c.fog),
            label = { Text(stringResource(R.string.bike_code_field)) },
            supportingText = {
                Text(stringResource(R.string.bike_code_field_hint), color = if (zeroAt != 0L) c.signal else c.fog)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.length == 3) set() }),
            colors = bikeFieldColors(),
        )
    }
    val button: @Composable (Modifier) -> Unit = { m ->
        WorkingQuietButton(
            text = stringResource(R.string.bike_code_set),
            busyText = stringResource(R.string.bike_code_setting),
            busy = fb.isBusy("code-set"),
            onClick = set,
            modifier = m,
            enabled = canSet,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Field and pill side by side where both fit (the pill needs about 150 dp), else the pill under the field.
        if (maxWidth >= 184.dp + S3.s + 150.dp) {
            Row(verticalAlignment = Alignment.Top) {
                field()
                Spacer(Modifier.width(S3.s))
                button(Modifier.padding(top = 6.dp))
            }
        } else {
            Column {
                field()
                Spacer(Modifier.height(S3.s))
                button(Modifier)
            }
        }
    }
    BikeFeedbackNote(fb, "code-set")
    setAt?.let { at ->
        Text(
            stringResource(R.string.bike_code_set_at, CLOCK.format(Instant.ofEpochMilli(at))),
            style = S3.type.label,
            color = c.led,
            modifier = Modifier.padding(top = S3.s).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    BikeFeedbackNote(fb, "code-clear", okText = stringResource(R.string.bike_code_clear_done))
    Spacer(Modifier.height(S3.s))
    if (!FamilyGate.backupCodeClear(gate)) Hint(stringResource(R.string.s2ui_code_s2), Modifier.padding(bottom = S3.xs))
    if (guardOn) {
        Hint(stringResource(R.string.guard_core_error_active), Modifier.padding(top = S3.xs))
    } else if (!ready) {
        Hint(stringResource(R.string.bike_needs_link), Modifier.padding(top = S3.xs))
    }
    // An S2 can set a code but not clear it.
    if (FamilyGate.backupCodeClear(gate) && ready) {
        val clearing = fb.isBusy("code-clear")
        TextLink(
            stringResource(if (clearing) R.string.bike_code_clearing else R.string.bike_code_clear_link),
            { if (fb.running == null) confirmClear = true },
            Modifier.padding(top = S3.s),
        )
    }

    OutburstBlockWhile(confirmClear)
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.bike_code_clear_title), style = S3.type.section, color = c.paint) },
            text = { Text(stringResource(R.string.bike_code_clear_text), style = S3.type.body, color = c.paint) },
            confirmButton = {
                TextButton({
                    confirmClear = false
                    setAt = null
                    fb.run("code-clear", Op.BACKUP_CODE) { vm.bike.setBackupCode(null) }
                }) { Text(stringResource(R.string.bike_delete), style = S3.type.bodyStrong, color = c.signal) }
            },
            dismissButton = {
                TextButton({ confirmClear = false }) {
                    Text(stringResource(R.string.bike_keep), style = S3.type.bodyStrong, color = c.paint)
                }
            },
        )
    }
}

// --- Experimente ---------------------------------------------------------------------------------

/**
 * The one "Experimente" section: Speed++ first, the one that works on 1.9.3; then the tests of 37 km/h and Stufe 5,
 * shown directly only on a firmware that still knows one of them, and otherwise folded with the reason.
 */
@Composable
private fun ExperimentsSection(vm: AppViewModel, state: BikeState, bike: BikeCredentials, fb: BikeFeedback, wheelStock: WheelStock) {
    BikeSectionTitle(stringResource(R.string.bike_exp_title))
    Spacer(Modifier.height(S3.m))
    SpeedPlusBlock(vm, state, bike, fb, wheelStock)
    Spacer(Modifier.height(S3.l))
    if (state.firmwareHasNoLimit || state.firmwareHasLevel5) {
        FirmwareLine(state)
        Spacer(Modifier.height(S3.m))
        LegacyTests(vm, state, fb)
    } else {
        // A fold row with a noun label: the two "… testen" buttons inside are the only things that act.
        Hairline()
        DisclosureSection(stringResource(R.string.bike_exp_fold), foldRow = true) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bike_exp_intro), style = S3.type.body, color = S3.colors.paint)
                FirmwareLine(state)
                Spacer(Modifier.height(S3.m))
                LegacyTests(vm, state, fb)
            }
        }
    }
}

/**
 * Speed++ (5564): the 24″ setting on a 28″ bike. The bike then computes less speed than it rides, so the motor
 * cut-off moves up; YABA shows the real speed everywhere (see [SpeedPlus]). Parked only.
 *
 * What it shows is [SpeedPlus.view], and an S3 owner always gets something to act on:
 *  - not connected: the switch greyed out (with the size YABA read last, where the bike is a 28″ one) and the sentence that
 *    YABA reads the size on connect; the link section right above has the connect button;
 *  - connected without a size (the read on connect failed): say so and offer "Laufradgröße lesen". That button reads the
 *    bike's values including 5564 ([BikeController.readDiagnostics]); the "Werte neu laden" of the link section reads 5564
 *    only on connect, so the two never share a label;
 *  - the bike reads 24″ and no model name says S3 or X3 (the account names a SKU; YABA's first read may have been taken
 *    with Speed++ already on): the question "Ab Werk: 28″ (S3) | 24″ (X3)" instead of a silent "only on 28″";
 *  - a bike that is 24″ from the factory (an X3 by its name, or by the owner's answer): the honest sentence; while it reads
 *    28″ (a wrong answer, then "28″ ab Werk"), what that does and "Zurück auf 24″".
 * The owner's answer stays on screen as a bar, so a wrong tap can be put right.
 */
@Composable
private fun SpeedPlusBlock(vm: AppViewModel, s: BikeState, bike: BikeCredentials, fb: BikeFeedback, stock: WheelStock) {
    val c = S3.colors
    val parked = remember(s) { LabProtocol.isParked(s) }
    val view = SpeedPlus.view(s.isReady, s.wheelSize, stock)
    Text(stringResource(R.string.speedplus_title), style = S3.type.bodyStrong, color = c.paint, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(S3.s))
    if (SpeedPlus.showsStockChoice(view, stock)) {
        StockChoice(vm, s, stock, asking = view == SpeedPlusView.ASK)
        if (view != SpeedPlusView.ASK) Spacer(Modifier.height(S3.l))
    }
    when (view) {
        // The question above is the whole block until it is answered.
        SpeedPlusView.ASK -> Unit
        SpeedPlusView.NOT_28 -> Hint(stringResource(R.string.speedplus_not_28))
        SpeedPlusView.BACK_TO_24 -> {
            // A 24″ bike set to 28″: no dead end. The way back is the owner's tap, parked only, like the switch.
            Text(stringResource(R.string.speedplus_x3_on_28), style = S3.type.body, color = c.paint)
            Spacer(Modifier.height(S3.s))
            WorkingQuietButton(
                stringResource(R.string.speedplus_back_to_24),
                stringResource(R.string.bike_exp_sending),
                Op.SPEED in s.busy,
                { vm.bike.setWheelSize(SpeedPlus.WHEEL_24) },
                enabled = parked && Op.SPEED !in s.busy,
            )
            s.lastCommandError?.let { Text(it, style = S3.type.label, color = c.signal, modifier = Modifier.padding(top = S3.xs)) }
            if (!parked) LampNote(stringResource(R.string.speedplus_error_rolling), Modifier.padding(top = S3.s))
        }
        SpeedPlusView.UNREAD -> {
            Hint(stringResource(R.string.speedplus_unread))
            Spacer(Modifier.height(S3.s))
            // fb.running clears when the read ends, fails, or never starts (3 s): the button cannot stay grey.
            WorkingQuietButton(
                stringResource(R.string.speedplus_read),
                stringResource(R.string.bike_raw_reading),
                fb.isBusy(SPEEDPLUS_READ),
                { fb.run(SPEEDPLUS_READ, Op.REFRESH) { vm.bike.readDiagnostics() } },
                enabled = fb.running == null,
            )
            // A read that brings the size turns the block into the switch or the question; one that does not says so.
            BikeFeedbackNote(fb, SPEEDPLUS_READ, okText = stringResource(R.string.speedplus_read_none))
        }
        SpeedPlusView.OFFLINE -> {
            // What the bike was set to when YABA read it last; no claim on a bike whose factory size is open.
            val last = remember(bike, s.wheelSize, s.link) { vm.bike.lastWheelSize(bike) }
            WheelSwitch(selected = last.takeIf { stock.wheel == SpeedPlus.WHEEL_28 }, enabled = false, onSelect = {})
            Spacer(Modifier.height(S3.s))
            Text(stringResource(R.string.speedplus_text), style = S3.type.body, color = c.paint)
            Hint(stringResource(R.string.speedplus_unknown), Modifier.padding(top = S3.xs))
        }
        SpeedPlusView.SWITCH -> {
            WheelSwitch(
                selected = s.wheelSize,
                enabled = s.isReady && parked && Op.SPEED !in s.busy,
                onSelect = { wheel -> if (wheel != s.wheelSize) vm.bike.setWheelSize(wheel) },
            )
            Spacer(Modifier.height(S3.s))
            Text(stringResource(R.string.speedplus_text), style = S3.type.body, color = c.paint)
            Hint(stringResource(R.string.speedplus_odometer), Modifier.padding(top = S3.xs))
            s.lastCommandError?.let { Text(it, style = S3.type.label, color = c.signal, modifier = Modifier.padding(top = S3.xs)) }
            if (!parked) LampNote(stringResource(R.string.speedplus_error_rolling), Modifier.padding(top = S3.s))
        }
    }
}

/** The Speed++ switch: "28″ ab Werk | 24″ Speed++". */
@Composable
private fun WheelSwitch(selected: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
    ChoiceBar(
        options = listOf(
            SpeedPlus.WHEEL_28 to stringResource(R.string.speedplus_choice_28),
            SpeedPlus.WHEEL_24 to stringResource(R.string.speedplus_choice_24),
        ),
        selected = selected,
        onSelect = onSelect,
        enabled = enabled,
    )
}

/**
 * "Laufradgröße ab Werk: 28″ (S3) | 24″ (X3)". While [asking], the sentence says why YABA asks; the answer only goes into
 * YABA's memory for this bike (nothing is sent to it), and the speed factor follows at once ([BikeController.setStockWheel]).
 */
@Composable
private fun StockChoice(vm: AppViewModel, s: BikeState, stock: WheelStock, asking: Boolean) {
    val c = S3.colors
    if (asking) {
        Text(stringResource(R.string.speedplus_stock_question), style = S3.type.body, color = c.paint)
        Spacer(Modifier.height(S3.m))
    }
    Text(stringResource(R.string.speedplus_stock_title), style = S3.type.label, color = c.fog)
    Spacer(Modifier.height(S3.xs))
    ChoiceBar(
        options = listOf(
            SpeedPlus.WHEEL_28 to stringResource(R.string.speedplus_stock_28),
            SpeedPlus.WHEEL_24 to stringResource(R.string.speedplus_stock_24),
        ),
        selected = stock.wheel.takeIf { stock.source == WheelStock.Source.OWNER },
        onSelect = { wheel -> vm.bike.setStockWheel(wheel) },
        enabled = s.isReady,
    )
}

/** The feedback key of Speed++'s own "Laufradgröße lesen": its busy state and error stay in the block. */
private const val SPEEDPLUS_READ = "speedplus-read"

/** "Dein Bike läuft mit der Firmware 1.9.3." and, where it still knows them, which of the two. */
@Composable
private fun FirmwareLine(state: BikeState) {
    val fw = state.firmware ?: return
    val known = when {
        state.firmwareHasNoLimit && state.firmwareHasLevel5 -> stringResource(R.string.bike_exp_fw_knows_both)
        state.firmwareHasNoLimit -> stringResource(R.string.bike_exp_fw_knows_37)
        state.firmwareHasLevel5 -> stringResource(R.string.bike_exp_fw_knows_level5)
        else -> null
    }
    val line = stringResource(R.string.bike_exp_fw, fw)
    Hint(if (known != null) "$line $known" else line, Modifier.padding(top = S3.s))
}

/** The two tests that report the bike's answer, and the way back to 25 km/h and Stufe 4. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyTests(vm: AppViewModel, state: BikeState, fb: BikeFeedback) {
    val ready = state.isReady
    // Tried + the bike's error (null = accepted). The sentence for "accepted" is resolved in composition.
    var speedTried by rememberSaveable { mutableStateOf(false) }
    var speedError by rememberSaveable { mutableStateOf<String?>(null) }
    var levelTried by rememberSaveable { mutableStateOf(false) }
    var levelError by rememberSaveable { mutableStateOf<String?>(null) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        WorkingQuietButton(
            text = stringResource(R.string.bike_exp_test_37),
            busyText = stringResource(R.string.bike_exp_sending),
            busy = fb.isBusy("exp-speed"),
            onClick = {
                fb.run("exp-speed", Op.SPEED, onResult = { err ->
                    speedTried = true
                    speedError = err
                }) { vm.bike.setSpeedLimit(SpeedLimit.NO_LIMIT) }
            },
            enabled = ready && fb.running == null,
        )
        WorkingQuietButton(
            text = stringResource(R.string.bike_exp_test_level5),
            busyText = stringResource(R.string.bike_exp_sending),
            busy = fb.isBusy("exp-level"),
            onClick = {
                fb.run("exp-level", Op.POWER, onResult = { err ->
                    levelTried = true
                    levelError = err
                }) { vm.bike.setPowerLevel(5) }
            },
            enabled = ready && fb.running == null,
        )
    }

    if (speedTried) {
        Answer(
            stringResource(R.string.bike_exp_37),
            speedError ?: stringResource(R.string.bike_exp_37_accepted),
            accepted = speedError == null,
        )
    }
    if (levelTried) {
        Answer(
            stringResource(R.string.bike_exp_level5),
            levelError ?: stringResource(R.string.bike_exp_level5_accepted),
            accepted = levelError == null,
        )
    }

    val limit = state.speedLimit
    val level = state.powerLevel
    if (ready && (limit != null || level != null)) {
        Hint(
            when {
                limit != null && level != null -> stringResource(R.string.bike_exp_reports_both, limit.kmh, level)
                limit != null -> stringResource(R.string.bike_exp_reports_speed, limit.kmh)
                else -> stringResource(R.string.bike_exp_reports_level, level ?: 0)
            },
            Modifier.padding(top = S3.m),
        )
    }

    val revertSpeed = ready && limit == SpeedLimit.NO_LIMIT
    val revertLevel = ready && level == 5
    if (revertSpeed || revertLevel) {
        Spacer(Modifier.height(S3.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
            if (revertSpeed) {
                WorkingQuietButton(
                    stringResource(R.string.bike_exp_back_25),
                    stringResource(R.string.bike_exp_setting),
                    fb.isBusy("exp-back-speed"),
                    { fb.run("exp-back-speed", Op.SPEED) { vm.bike.setSpeedLimit(SpeedLimit.EU) } },
                    enabled = fb.running == null,
                )
            }
            if (revertLevel) {
                WorkingQuietButton(
                    stringResource(R.string.bike_exp_back_level4),
                    stringResource(R.string.bike_exp_setting),
                    fb.isBusy("exp-back-level"),
                    { fb.run("exp-back-level", Op.POWER) { vm.bike.setPowerLevel(4) } },
                    enabled = fb.running == null,
                )
            }
        }
        BikeFeedbackNote(fb, "exp-back-speed")
        BikeFeedbackNote(fb, "exp-back-level")
    }

    if (!ready) Hint(stringResource(R.string.bike_needs_link), Modifier.padding(top = S3.m))
}

/** The bike's answer to one experiment; stays until the next test. */
@Composable
private fun Answer(what: String, text: String, accepted: Boolean) {
    val c = S3.colors
    Column(Modifier.fillMaxWidth().padding(top = S3.m)) {
        Text(what, style = S3.type.bodyStrong, color = if (accepted) c.led else c.signal)
        Text(text, style = S3.type.label, color = c.paint)
    }
}

// --- Über ----------------------------------------------------------------------------------------

/**
 * The signature: on the cover the YABA word mark in a still tube (the inner screen has the hero tube on the left),
 * the German lockup under it (it stays German in every language, like a stamp), then version, origin, the
 * mandatory line and the credits.
 */
@Composable
private fun AboutSection(wide: Boolean, onReplayIntro: () -> Unit) {
    val c = S3.colors
    val context = LocalContext.current
    BikeSectionTitle(stringResource(R.string.bike_about_title))
    Spacer(Modifier.height(S3.m))
    if (!wide) {
        BikeTube(frame = YabaWordmark, lightsOn = false, columns = 36, description = "YABA", sweepMillis = 0)
        Spacer(Modifier.height(S3.l))
    }
    Text(stringResource(R.string.bike_about_lockup_1), style = S3.type.title, color = c.paint)
    Spacer(Modifier.height(S3.m))
    Text(stringResource(R.string.bike_about_lockup_2), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.l))
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    Text(stringResource(R.string.bike_about_version, version), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.bike_about_origin), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.bike_about_disclaimer), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.bike_about_credits))
    Spacer(Modifier.height(S3.xs))
    Hint(stringResource(R.string.s2fix_about_credits_s2))
    Spacer(Modifier.height(S3.m))
    QuietButton(stringResource(R.string.intro_replay), onClick = onReplayIntro)
}
