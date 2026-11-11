package dev.steven.s3control.ui.cockpit

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ui.components.ConnectPrompt
import dev.steven.s3control.ui.components.LocalShellBle
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Theme
import kotlin.math.min

/*
 * The handlebar cockpit: a full-screen overlay without a list and without scrolling. Ranked by
 * importance: the speed matrix, one alert line, battery and the four lamps of the shipping rule, the
 * mark capsule, the exit slot. A shipping or kick-lock event replaces all of it (CockpitTakeover.kt).
 */

/**
 * Hosted by AppRoot in place of both shells while [AppViewModel.cockpitOpen]. [layoutInfo] carries
 * the fold; [onClose] closes the cockpit (the app returns to where it was opened).
 */
@Composable
fun CockpitScreen(vm: AppViewModel, layoutInfo: WindowLayoutInfo?, onClose: () -> Unit) {
    val view by vm.cockpitView.collectAsStateWithLifecycle()
    val prefs by vm.cockpitPrefs.collectAsStateWithLifecycle()
    val appDark = S3.colors.isDark
    val v = view ?: return
    val dark = when (prefs.theme) {
        CockpitTheme.APP -> appDark
        CockpitTheme.DAY -> false
        CockpitTheme.NIGHT -> true
    }
    S3Theme(darkTheme = dark) {
        // Values are sized in dp; text grows with the user's font scale only up to 1.3.
        val d = LocalDensity.current
        val clamped = remember(d.density, d.fontScale) { Density(d.density, min(d.fontScale, 1.3f)) }
        CompositionLocalProvider(LocalDensity provides clamped) {
            CockpitRoot(vm, v, layoutInfo, appDark, onClose)
        }
    }
}

/** What every layout hands to its pieces. */
internal class CockpitActions(
    val close: () -> Unit,
    val mark: () -> Unit,
    val alert: (AlertAction) -> Unit,
    val loggerError: String?,
    val showPrompt: Boolean,
) {
    /** The status row's "Fahrtprotokoll aus" starts the logger (never asking while riding). */
    val startLogger: () -> Unit = { alert(AlertAction.START_LOGGER) }
}

@Composable
private fun CockpitRoot(vm: AppViewModel, v: CockpitView, layoutInfo: WindowLayoutInfo?, appDark: Boolean, onClose: () -> Unit) {
    val c = S3.colors
    val t = v.takeover
    val close by rememberUpdatedState(onClose)

    CockpitKeepScreenOn()
    CockpitOrientationLock(v.movingRecently)
    // On the signal field the icons follow the red: dark on NightRide's light red, light on Daylight's.
    CockpitSystemBars(lightField = if (t != null) c.isDark else !c.isDark, appDark = appDark)
    CockpitVolumeKeys { vm.markMoment(viaKey = true) }
    CockpitHaptics(v.haptic)
    BackHandler { if (vm.cockpitBack() == BackResult.CLOSE) close() }

    val starter = rememberRideLoggerStarter(vm)
    val loggerError by vm.cockpitLoggerError.collectAsStateWithLifecycle()
    val ble = LocalShellBle.current
    val moving by rememberUpdatedState(v.movingRecently)
    val actions = CockpitActions(
        close = { close() },
        mark = { vm.markMoment() },
        alert = { a ->
            when (a) {
                AlertAction.RETRY -> vm.bike.retryNow()
                // Never a permission dialog while the bike moves; the logger runs without it.
                AlertAction.START_LOGGER -> starter.start(mayAsk = !moving)
                AlertAction.REOPEN -> vm.cockpitReopenTakeover()
                AlertAction.NONE -> Unit
            }
        },
        loggerError = loggerError,
        showPrompt = !v.ready && (v.rawLink == Link.OFFLINE || ble?.ready == false),
    )

    val pane = stringResource(if (t != null) R.string.cockpit_to_pane else R.string.cockpit_pane)
    val closeLabel = stringResource(R.string.cockpit_close)
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val safe = rememberCockpitSafeArea()
    Box(
        Modifier
            .fillMaxSize()
            .background(if (t != null && t.phase == 1) c.signal else c.asphalt)
            .semantics {
                paneTitle = pane
                customActions = listOf(CustomAccessibilityAction(closeLabel) { close(); true })
            },
    ) {
        if (t != null && t.phase == 2) TakeoverTopInset(safe.calculateTopPadding())
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(safe)
                .onGloballyPositioned { origin = it.positionInWindow() },
        ) {
            val w = maxWidth.value
            val h = maxHeight.value
            val fold = remember(layoutInfo, origin, density.density) { foldOf(layoutInfo, origin, density.density) }
            val window = cockpitWindow(w, h, fold)
            val plan = remember(window, w, h, density.fontScale) { cockpitPlan(window, w, h, density.fontScale) }
            LaunchedEffect(window, w, h, plan) {
                Log.i(
                    "Cockpit",
                    "window=$window size=${w}x$h fold=$fold pitch=${plan.pitch} capsule=${plan.capsuleW}x${plan.capsuleH} " +
                        "used=${plan.usedH} dropped=${plan.dropped}",
                )
            }
            if (t == null) {
                Instrument(vm, v, plan, actions)
            } else {
                CockpitTakeover(vm, v, t, plan, close)
            }
        }
    }
}

/** The first fold of the window, relative to the cockpit root, in dp. */
private fun foldOf(info: WindowLayoutInfo?, origin: Offset, density: Float): CockpitFold? {
    val f = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull() ?: return null
    val b = f.bounds
    return if (f.orientation == FoldingFeature.Orientation.VERTICAL) {
        CockpitFold(true, (b.left - origin.x) / density, (b.right - origin.x) / density)
    } else {
        CockpitFold(false, (b.top - origin.y) / density, (b.bottom - origin.y) / density)
    }
}

/**
 * Long enough for "Halten zum Schließen" on one line at 12 sp or more; a little longer with a large
 * font ([text] is the plan's clamped font scale), so the label keeps its size before it shrinks.
 */
internal fun exitLength(columnW: Float, text: Float = 1f): Dp =
    ((columnW * 0.55f).coerceIn(170f, 230f) * (1f + (text - 1f) * 0.4f)).coerceAtMost(maxOf(columnW * 0.62f, 170f)).dp

@Composable
private fun Instrument(vm: AppViewModel, v: CockpitView, plan: CockpitPlan, a: CockpitActions) {
    when (plan.window) {
        CockpitWindow.CoverPortrait -> CoverPortrait(vm, v, plan, a)
        CockpitWindow.CoverLandscape -> CoverLandscape(vm, v, plan, a)
        is CockpitWindow.Book -> Book(vm, v, plan, a)
        is CockpitWindow.Table -> Table(vm, v, plan, a)
    }
}

// --- Shared blocks ----------------------------------------------------------------------------------

/** Spule, Schalter, Akku-Erkennung under their dimension line, plus Versorgung as a 4th cell if asked. */
@Composable
private fun RuleLamps(
    v: CockpitView,
    values: Boolean,
    measure: Boolean,
    withSupply: Boolean,
    modifier: Modifier = Modifier,
    measureH: Dp = 24.dp,
) {
    // An S2 has none of the rule's signals: its error code takes the lamps' place.
    if (!v.s3Lamps) {
        ErrorCodeLamp(v, values, modifier)
        return
    }
    val gap = 8.dp
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        Column(Modifier.weight(if (withSupply) 3f else 1f)) {
            // The battery-detection lamp has the longest name in every language: it gets more room.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                CockpitLamp(stringResource(R.string.cockpit_lamp_coil), v.coil, values, Modifier.weight(0.85f))
                CockpitLamp(stringResource(R.string.cockpit_lamp_switch), v.switch, values, Modifier.weight(0.85f))
                CockpitLamp(stringResource(R.string.cockpit_lamp_key), v.key, values, Modifier.weight(1.3f))
            }
            if (measure) RuleMeasure(measureH)
        }
        if (withSupply) {
            CockpitLamp(stringResource(R.string.cockpit_lamp_supply), v.supply, values, Modifier.weight(1f))
        }
    }
}

/** Battery figure left, Versorgung lamp right: one 72 dp row. An S2 has no supply signal: the battery alone. */
@Composable
private fun BatteryRow(v: CockpitView, height: Dp, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        BatteryFigure(v, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (v.s3Lamps) {
            CockpitLamp(stringResource(R.string.cockpit_lamp_supply), v.supply, true, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** S2: its one error code, the code in the signal capsule and the fault's name under it, or "none". */
@Composable
private fun ErrorCodeLamp(v: CockpitView, values: Boolean, modifier: Modifier = Modifier) {
    val lamp = v.errorCode
    val value = when (lamp.state) {
        LampState.TRIPPED -> faultLine(lamp.code, BikeFamily.S2)
        LampState.OK -> stringResource(R.string.s2ui_cockpit_val_no_error)
        else -> "–"
    }
    CockpitLamp(stringResource(R.string.s2ui_cockpit_lamp_error), lamp, values, modifier.fillMaxWidth(), valueText = value)
}

/** No link: the one step that fixes it, in place of battery and lamps. */
@Composable
private fun CockpitConnect(vm: AppViewModel, compact: Boolean, modifier: Modifier = Modifier) {
    val state by vm.bikeState.collectAsStateWithLifecycle()
    Box(modifier.clipToBounds()) {
        if (!compact) {
            ConnectPrompt(vm, state)
        } else {
            val ble = LocalShellBle.current
            val bike = state.bike ?: vm.preferredBike()
            when {
                bike == null -> Unit
                ble != null && !ble.permissionsGranted ->
                    QuietButton(stringResource(R.string.bike_prompt_grant_permission), ble.requestPermissions, Modifier.fillMaxWidth())
                ble != null && !ble.bluetoothOn ->
                    QuietButton(stringResource(R.string.bike_prompt_bluetooth_on), ble.requestEnable, Modifier.fillMaxWidth())
                else -> QuietButton(stringResource(R.string.bike_prompt_connect), { vm.connect(bike) }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The instrument block under the alert line: battery row, lamps and the rule, or the connect step. */
@Composable
private fun ColumnScope.LampsBlock(vm: AppViewModel, v: CockpitView, plan: CockpitPlan, a: CockpitActions, gap: Dp) {
    if (a.showPrompt) {
        var h = plan.lampH + (if (plan.measure) plan.measureH else 0f)
        if (plan.batteryRow) h += plan.batteryH + gap.value
        CockpitConnect(vm, compact = false, modifier = Modifier.fillMaxWidth().height(h.dp))
        return
    }
    if (plan.batteryRow) {
        BatteryRow(v, plan.batteryH.dp)
        Spacer(Modifier.height(gap))
    }
    RuleLamps(v, values = plan.lampValues, measure = plan.measure, withSupply = !plan.batteryRow, measureH = plan.measureH.dp)
}

// --- Cover portrait ---------------------------------------------------------------------------------

@Composable
private fun CoverPortrait(vm: AppViewModel, v: CockpitView, plan: CockpitPlan, a: CockpitActions) {
    val gap = plan.gap.dp
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = plan.gutter.dp)
            .padding(top = plan.top.dp, bottom = plan.bottom.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(plan.statusH.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkStatus(v, Modifier.weight(1f), onStartLogger = a.startLogger)
            if (!plan.batteryRow && !a.showPrompt) BatteryInline(v, Modifier.padding(horizontal = 8.dp))
            ExitSlot(exitLength(plan.columnW, plan.text), a.close)
        }
        Spacer(Modifier.height(gap))
        CockpitMatrix(v, plan.pitch.dp, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(CockpitSizes.UNIT_GAP.dp))
        UnitRow(v, plan.unitH.dp)
        Spacer(Modifier.height(gap))
        AlertLine(v.alert, plan.alertH.dp, plan.alertLines, inner = false, a.loggerError, a.alert)
        Spacer(Modifier.height(gap))
        LampsBlock(vm, v, plan, a, gap)
        Spacer(Modifier.height(gap))
        MarkCapsule(v, plan.capsuleW.dp, plan.capsuleH.dp, a.mark, Modifier.fillMaxWidth().weight(1f))
    }
}

// --- Cover landscape --------------------------------------------------------------------------------

@Composable
private fun CoverLandscape(vm: AppViewModel, v: CockpitView, plan: CockpitPlan, a: CockpitActions) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = plan.gutter.dp)
            .padding(top = plan.top.dp, bottom = plan.bottom.dp),
    ) {
        Column(Modifier.width(plan.columnW.dp)) {
            Row(Modifier.fillMaxWidth().height(plan.statusH.dp), verticalAlignment = Alignment.CenterVertically) {
                // Too short for the unit row: the mode word takes the link word's place while live.
                LinkStatus(v, Modifier.weight(1f), compact = true, modeInstead = plan.unitInStatus, onStartLogger = a.startLogger)
                BatteryInline(v)
            }
            Spacer(Modifier.height(4.dp))
            CockpitMatrix(v, plan.pitch.dp)
            if (!plan.unitInStatus) {
                Spacer(Modifier.height(4.dp))
                UnitRow(v, plan.unitH.dp)
            }
        }
        Spacer(Modifier.width(plan.gap.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (plan.exitInAlert) {
                Row(Modifier.fillMaxWidth().height(plan.alertH.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlertLine(v.alert, plan.alertH.dp, plan.alertLines, inner = false, a.loggerError, a.alert, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    ExitSlot(exitLength(plan.sideW, plan.text), a.close)
                }
            } else {
                Row(Modifier.fillMaxWidth().height(CockpitSizes.EXIT_ROW.dp), horizontalArrangement = Arrangement.End) {
                    ExitSlot(exitLength(plan.sideW, plan.text), a.close)
                }
                Spacer(Modifier.height(8.dp))
                AlertLine(v.alert, plan.alertH.dp, plan.alertLines, inner = false, a.loggerError, a.alert)
            }
            Spacer(Modifier.height(8.dp))
            if (plan.lamps) {
                if (a.showPrompt) {
                    CockpitConnect(vm, compact = true, modifier = Modifier.fillMaxWidth().height(plan.lampH.dp))
                } else {
                    RuleLamps(v, values = false, measure = false, withSupply = true, modifier = Modifier.height(plan.lampH.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            MarkCapsule(v, plan.capsuleW.dp, plan.capsuleH.dp, a.mark, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

// --- Inner screen: book (vertical fold) -------------------------------------------------------------

@Composable
private fun Book(vm: AppViewModel, v: CockpitView, plan: CockpitPlan, a: CockpitActions) {
    val window = plan.window as CockpitWindow.Book
    val pad = plan.gutter.dp
    val gap = plan.gap.dp
    val events by vm.events.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(window.splitX.dp).fillMaxHeight().padding(pad)) {
            Row(Modifier.fillMaxWidth().height(plan.statusH.dp), verticalAlignment = Alignment.CenterVertically) {
                LinkStatus(v, Modifier.weight(1f), onStartLogger = a.startLogger)
            }
            Spacer(Modifier.height(16.dp))
            CockpitMatrix(v, plan.pitch.dp, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(CockpitSizes.UNIT_GAP.dp))
            UnitRow(v, plan.unitH.dp)
            Spacer(Modifier.height(gap))
            AlertLine(v.alert, plan.alertH.dp, plan.alertLines, inner = true, a.loggerError, a.alert)
            Spacer(Modifier.height(gap))
            LampsBlock(vm, v, plan, a, gap)
            if (plan.facts) {
                Spacer(Modifier.height(gap))
                FactsRow(v)
            }
        }
        Spacer(Modifier.width(window.hinge.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(pad)) {
            Row(Modifier.fillMaxWidth().height(CockpitSizes.EXIT_ROW.dp), horizontalArrangement = Arrangement.End) {
                ExitSlot(exitLength(plan.sideW, plan.text), a.close)
            }
            Spacer(Modifier.height(gap))
            if (plan.recentRows > 0) RecentList(events, plan.recentRows, plan.recentRowH.dp)
            Spacer(Modifier.weight(1f))
            MarkCapsule(v, plan.capsuleW.dp, plan.capsuleH.dp, a.mark, Modifier.fillMaxWidth().height(plan.capsuleH.dp))
        }
    }
}

// --- Inner screen: table (horizontal fold) ----------------------------------------------------------

@Composable
private fun Table(vm: AppViewModel, v: CockpitView, plan: CockpitPlan, a: CockpitActions) {
    val window = plan.window as CockpitWindow.Table
    val pad = plan.gutter.dp
    val gap = plan.gap.dp
    val events by vm.events.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(window.splitY.dp).padding(pad)) {
            Column(Modifier.width(plan.columnW.dp)) {
                Row(Modifier.fillMaxWidth().height(plan.statusH.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinkStatus(v, Modifier.weight(1f), onStartLogger = a.startLogger)
                }
                Spacer(Modifier.height(4.dp))
                CockpitMatrix(v, plan.pitch.dp)
                Spacer(Modifier.height(4.dp))
                UnitRow(v, plan.unitH.dp)
            }
            Spacer(Modifier.width(gap))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().height(CockpitSizes.EXIT_ROW.dp), horizontalArrangement = Arrangement.End) {
                    ExitSlot(exitLength(plan.sideW, plan.text), a.close)
                }
                Spacer(Modifier.height(16.dp))
                AlertLine(v.alert, plan.alertH.dp, plan.alertLines, inner = true, a.loggerError, a.alert)
                if (plan.batteryRow && !a.showPrompt) {
                    Spacer(Modifier.height(16.dp))
                    BatteryRow(v, plan.batteryH.dp)
                }
            }
        }
        Spacer(Modifier.height(window.hinge.dp))
        Row(Modifier.fillMaxWidth().weight(1f).padding(pad)) {
            Column(Modifier.width(plan.columnW.dp)) {
                if (a.showPrompt) {
                    CockpitConnect(vm, compact = false, modifier = Modifier.fillMaxWidth().height((plan.lampH + plan.measureH).dp))
                } else {
                    RuleLamps(v, values = plan.lampValues, measure = plan.measure, withSupply = !plan.batteryRow, measureH = plan.measureH.dp)
                }
                if (plan.recentRows > 0) {
                    Spacer(Modifier.height(gap))
                    RecentList(events, plan.recentRows, plan.recentRowH.dp)
                }
            }
            Spacer(Modifier.width(gap))
            // The capsule keeps its capped height at the bottom; the room above holds the facts.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (plan.facts) FactsRow(v)
                Spacer(Modifier.weight(1f))
                MarkCapsule(v, plan.capsuleW.dp, plan.capsuleH.dp, a.mark, Modifier.fillMaxWidth().height(plan.capsuleH.dp))
            }
        }
    }
}
