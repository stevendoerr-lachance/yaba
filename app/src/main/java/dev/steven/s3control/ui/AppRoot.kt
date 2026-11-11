package dev.steven.s3control.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ui.cockpit.CockpitScreen
import dev.steven.s3control.ui.components.LocalShellBle
import dev.steven.s3control.ui.components.LocalShellBlePrompt
import dev.steven.s3control.ui.components.LocalShellCommands
import dev.steven.s3control.ui.components.ShellBlePrompt
import dev.steven.s3control.ui.components.InnerTabsStep
import dev.steven.s3control.ui.components.LanguageButton
import dev.steven.s3control.ui.components.LanguageTubeSize
import dev.steven.s3control.ui.components.LocalShellCorner
import dev.steven.s3control.ui.components.ShellCommandTracker
import dev.steven.s3control.ui.components.ShellTabGlyph
import dev.steven.s3control.ui.components.TabGlyphs
import dev.steven.s3control.ui.components.planInnerTabs
import dev.steven.s3control.ui.components.rememberHaptics
import dev.steven.s3control.ui.components.shellFitLabels
import dev.steven.s3control.ui.components.shellLabelStyle
import dev.steven.s3control.ui.components.tabGlyph
import dev.steven.s3control.ui.components.tabGlyphWidth
import dev.steven.s3control.ui.screens.BikeHeroPanel
import dev.steven.s3control.ui.screens.BikeScreen
import dev.steven.s3control.ui.screens.DiagnosisScreen
import dev.steven.s3control.ui.screens.GarageScreen
import dev.steven.s3control.ui.screens.LogInsightsNav
import dev.steven.s3control.ui.screens.LogInsightsScreen
import dev.steven.s3control.ui.screens.LogViewNav
import dev.steven.s3control.ui.screens.LogViewer
import dev.steven.s3control.ui.screens.RideScreen
import dev.steven.s3control.ui.screens.SoundScreen
import dev.steven.s3control.ui.screens.WelcomeScreen
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * The adaptive shell. No bike key yet, or the first unlock not through: the onboarding (WelcomeScreen). Otherwise the cover screen gets one
 * column with a bottom destination bar, and the inner screen gets two panes split at the fold:
 * the bike on the left, always; the chosen destination on the right. The language stands in both shells, without a bike
 * ([ShellLanguageCorner]).
 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val vault by vm.vault.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Only a connected bike's faults light the Diagnose tab; a list kept from earlier is no alarm.
    val faultsFlow = remember(vm) { vm.bikeState.map { it.isReady && it.faults.isNotEmpty() }.distinctUntilChanged() }
    val hasFaults by faultsFlow.collectAsStateWithLifecycle(
        initialValue = vm.bikeState.value.let { it.isReady && it.faults.isNotEmpty() },
    )
    val readiness = rememberBleReadiness()
    val blePrompt = rememberSaveable(saver = ShellBlePrompt.Saver) { ShellBlePrompt() }
    val tracker = remember { ShellCommandTracker() }
    val holder = rememberSaveableStateHolder()

    // Collected for the whole life of the shell, so the fold is known before the inner screen
    // composes and the panes don't start at the fallback split and then jump.
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val layoutInfo by produceState<WindowLayoutInfo?>(initialValue = null, activity) {
        val a = activity ?: return@produceState
        WindowInfoTracker.getOrCreate(a).windowLayoutInfo(a).collect { value = it }
    }

    var destinationName by rememberSaveable { mutableStateOf(Destination.RIDE.name) }
    var garageOpen by rememberSaveable { mutableStateOf(false) }
    val hasBikes = vault.bikes.isNotEmpty()

    // Klang only for a bike that can play something (an S2 has no bell, sound board or custom bell): before the first
    // connect the preferred bike's family decides, as on the Diagnose page.
    val preferred = remember(vault) { vm.preferredBike() }
    val s2TestMode by vm.bike.s2Prefs.testMode.collectAsStateWithLifecycle()
    val soundFlow = remember(vm, preferred, s2TestMode) {
        vm.bikeState.map { FamilyGate.anySound(FamilyGate.beforeConnect(it, preferred, s2TestMode)) }.distinctUntilChanged()
    }
    val hasSound by soundFlow.collectAsStateWithLifecycle(
        initialValue = FamilyGate.anySound(FamilyGate.beforeConnect(vm.bikeState.value, preferred, s2TestMode)),
    )
    val destinations = remember(hasSound) { Destination.entries.filter { it != Destination.SOUND || hasSound } }
    val destination = destinations.firstOrNull { it.name == destinationName } ?: Destination.RIDE
    LaunchedEffect(destinations, destinationName) {
        if (destinations.none { it.name == destinationName }) destinationName = Destination.RIDE.name
    }

    // The onboarding runs from the welcome until the first unlock is through. An install from before the onboarding existed never
    // decided: it counts as done when it has a bike (the owner), and that is written down once. A person who removes every bike
    // later starts again with the welcome and, once a key is back, with Bluetooth access, the search and the unlock.
    val onboardingDone = settings.onboardingDone ?: hasBikes
    LaunchedEffect(Unit) {
        if (settings.onboardingDone == null) vm.updateSettings { it.copy(onboardingDone = hasBikes) }
    }
    LaunchedEffect(hasBikes, settings.onboardingDone) {
        if (!hasBikes && settings.onboardingDone == true) vm.updateSettings { it.copy(onboardingDone = false) }
    }
    val inOnboarding = !hasBikes || !onboardingDone

    val navigator = remember {
        Navigator(
            go = { d -> LogInsightsNav.close(); garageOpen = false; destinationName = d.name },
            openGarage = { LogInsightsNav.close(); garageOpen = true },
            closeOverlay = { garageOpen = false },
        )
    }

    // Auto-connect once per readiness change and preferred bike. A manual disconnect stays a
    // disconnect: the marker lives in the ViewModel, so it survives recreation, but a restart
    // after process death (the controller is back at OFFLINE) connects again. It resets when
    // Bluetooth stops being ready or auto-connect is switched off.
    LaunchedEffect(readiness.ready, settings.autoConnect, preferred?.normalizedMac, onboardingDone) {
        // During the onboarding the search step connects, on the tap on "Search now". The marker is left alone until then.
        if (!onboardingDone) return@LaunchedEffect
        if (!readiness.ready || !settings.autoConnect) {
            vm.autoConnectTriedMac = null
            return@LaunchedEffect
        }
        val bike = preferred ?: return@LaunchedEffect
        if (vm.autoConnectTriedMac == bike.normalizedMac) return@LaunchedEffect
        vm.autoConnectTriedMac = bike.normalizedMac
        if (vm.bikeState.value.link == Link.OFFLINE) vm.connect(bike)
    }

    // Forget the tracked command once it went through, so a later error from elsewhere
    // isn't pinned to a control that worked.
    LaunchedEffect(vm, tracker) {
        var previousBusy = emptySet<Op>()
        vm.bikeState.collect { s ->
            val op = tracker.op
            if (op != null && op in previousBusy && op !in s.busy && s.lastCommandError == null) tracker.clear()
            previousBusy = s.busy
        }
    }

    LaunchedEffect(hasBikes) {
        if (!hasBikes) {
            garageOpen = false
            destinationName = Destination.RIDE.name
        }
    }

    // The handlebar cockpit replaces both shells while it is open (it handles back itself).
    val cockpitOpen by vm.cockpitOpen.collectAsStateWithLifecycle()
    // The Fahrtenschreiber (log insights) opens over the destination content; back closes it first.
    val logInsightsOpen by LogInsightsNav.open.collectAsStateWithLifecycle()
    // A ride log or the bike's own log, opened from Diagnose: over the whole window (it handles back itself).
    val logView by LogViewNav.target.collectAsStateWithLifecycle()

    BackHandler(enabled = !cockpitOpen && hasBikes && (logInsightsOpen || garageOpen || destination != Destination.RIDE)) {
        when {
            logInsightsOpen -> LogInsightsNav.close()
            garageOpen -> garageOpen = false
            else -> destinationName = Destination.RIDE.name
        }
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalShellCommands provides tracker,
        LocalShellBle provides readiness,
        LocalShellBlePrompt provides blePrompt,
    ) {
        if (cockpitOpen) {
            CockpitScreen(vm, layoutInfo, onClose = { vm.closeCockpit() })
            return@CompositionLocalProvider
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(S3.colors.asphalt)) {
            val wide = maxWidth >= 600.dp
            // Every destination and the garage is composed once and moved between the two
            // layouts, so folding, unfolding or rotating keeps scroll positions, typed text and
            // open dialogs. (Two SaveableStateProviders with one key swapped in a single pass
            // would drop the saved state.)
            // Every destination has its content; only the offered ones (see destinations) are ever shown.
            val screens = remember(vm, holder) {
                Destination.entries.associateWith { d ->
                    movableContentOf { w: Boolean ->
                        holder.SaveableStateProvider(d.name) { DestinationContent(vm, d, w) }
                    }
                }
            }
            val garage = remember(vm, navigator) {
                movableContentOf { w: Boolean ->
                    GarageScreen(vm, wide = w, onClose = navigator.closeOverlay, modifier = Modifier.fillMaxSize())
                }
            }
            val content: @Composable (Boolean) -> Unit = { w ->
                when {
                    logInsightsOpen -> LogInsightsScreen(vm, w, Modifier.fillMaxSize())
                    garageOpen -> garage(w)
                    else -> screens.getValue(destination)(w)
                }
            }
            val viewing = logView
            when {
                inOnboarding -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    WelcomeScreen(vm, wide, layoutInfo, readiness, onboardingDone, Modifier.fillMaxSize())
                }
                viewing != null -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    LogViewer(vm, viewing, wide, layoutInfo, Modifier.fillMaxSize())
                }
                wide -> WideShell(vm, destinations, destination, garageOpen, hasFaults, layoutInfo, navigator, content)
                else -> CompactShell(destinations, destination, garageOpen, hasFaults, navigator, content)
            }
        }
    }
}

@Composable
private fun DestinationContent(vm: AppViewModel, destination: Destination, wide: Boolean) {
    val m = Modifier.fillMaxSize()
    when (destination) {
        Destination.RIDE -> RideScreen(vm, wide, m)
        Destination.SOUND -> SoundScreen(vm, wide, m)
        Destination.DIAGNOSIS -> DiagnosisScreen(vm, wide, m)
        Destination.BIKE -> BikeScreen(vm, wide, m)
    }
}

// --- Cover screen ---------------------------------------------------------------------------------

/**
 * The app language, reachable from every tab without a bike and before the first connect: it is the app's, not the bike's,
 * so it is not in the Bike tab. On the cover screen it stands at the end of each tab's first line, on the inner screen at the
 * end of the tab row ([DestinationTabs]). Not in the cockpit, the opening film or the Garage.
 */
private val ShellLanguageCorner: @Composable () -> Unit = { LanguageButton() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactShell(
    destinations: List<Destination>,
    destination: Destination,
    garageOpen: Boolean,
    hasFaults: Boolean,
    nav: Navigator,
    content: @Composable (wide: Boolean) -> Unit,
) {
    if (garageOpen) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            content(false)
        }
        return
    }
    // While typing, the bar steps aside and the content takes the keyboard inset itself.
    val imeVisible = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(
                    if (imeVisible) WindowInsets.safeDrawing
                    else WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
        ) {
            // The language in the top right corner of every tab, at the end of its first line (ShellCornerLine).
            CompositionLocalProvider(LocalShellCorner provides ShellLanguageCorner) {
                content(false)
            }
        }
        if (!imeVisible) DestinationBar(destinations, destination, hasFaults, nav.go)
    }
}

/**
 * The destinations as labels, each under its own dot glyph on the top tube's grid (speedometer, bell, pulse, bike; see
 * [ShellTabGlyph]): every glyph in fog, the active one lit in led. All glyphs take one box of the same size, so the
 * labels stand on one line; the bar grows with a large font. A fault the bike reports is its own small signal dot after
 * the Diagnose label, at cap height. Not a Material NavigationBar.
 */
@Composable
private fun DestinationBar(destinations: List<Destination>, current: Destination, hasFaults: Boolean, onGo: (Destination) -> Unit) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val labels = destinations.map { stringResource(it.labelRes) }
    val alertLabel = stringResource(R.string.shell_tab_alert_cd, stringResource(Destination.DIAGNOSIS.labelRes))
    val measurer = rememberTextMeasurer()
    val baseStyle = S3.type.bodyStrong
    val density = LocalDensity.current
    val glyphHeight = TabGlyphs.CoverPitch * TabGlyphs.ROWS
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.asphalt)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rib))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // One size for all labels, shrunk together until the longest fits its share (the Diagnose label with
            // room for its fault dot), so a long word (or a large font) never gets cut to "Diagnos".
            val width = constraints.maxWidth
            val fit = remember(destinations, labels, width, measurer, baseStyle, density) {
                with(density) {
                    fitBar(
                        measurer = measurer,
                        labels = labels,
                        style = baseStyle,
                        width = width,
                        pad = (S3.xs * 2).roundToPx(),
                        alertAt = destinations.indexOf(Destination.DIAGNOSIS),
                        // The dot's room on both sides, so the label stays centred when the dot comes.
                        dotRoom = ((AlertDotGap + AlertDot) * 2).roundToPx(),
                        glyph = tabGlyphWidth(TabGlyphs.BIKE, TabGlyphs.CoverPitch, trim = false).roundToPx(),
                    )
                }
            }
            val strong = fit.style
            val regular = strong.copy(fontWeight = S3.type.body.fontWeight)
            // 64 dp at font scale 1.0; a larger font grows the bar instead of pushing glyph or label out of it.
            val lineHeight = with(density) { (if (strong.lineHeight.isSp) strong.lineHeight else strong.fontSize).toDp() }
            val barHeight = maxOf(64.dp, glyphHeight + GlyphLabelGap + lineHeight + S3.s * 2)
            Row(Modifier.fillMaxWidth().height(barHeight).selectableGroup()) {
                destinations.forEachIndexed { index, d ->
                    key(d) {
                        val active = d == current
                        val alert = d == Destination.DIAGNOSIS && hasFaults
                        Column(
                            Modifier
                                .weight(fit.weights?.get(index) ?: 1f)
                                .fillMaxHeight()
                                .selectable(
                                    selected = active,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = {
                                        if (!active) haptics.tick()
                                        onGo(d)
                                    },
                                )
                                .semantics { if (alert) contentDescription = alertLabel }
                                .padding(horizontal = S3.xs),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            ShellTabGlyph(tabGlyph(d), active, pitch = TabGlyphs.CoverPitch)
                            Spacer(Modifier.height(GlyphLabelGap))
                            TabLabel(labels[index], if (active) strong else regular, if (active) c.paint else c.fog, alert, centred = true)
                        }
                    }
                }
            }
        }
    }
}

/** The cover bar's label [style] and, when the labels need unequal room, each tab's share of the width ([weights]). */
private class BarFit(val style: TextStyle, val weights: List<Float>?)

/**
 * Equal slots while every label fits its quarter (third) at 14 sp or more. Below that (Russian, a large font) each tab
 * takes the room it needs, the label (with the Diagnose fault dot's room on both sides) or the glyph box, whichever is
 * wider, plus an equal share of what is left, if that lets the labels stand larger. All sizes in px; [pad] is a slot's
 * own padding.
 */
private fun fitBar(
    measurer: TextMeasurer,
    labels: List<String>,
    style: TextStyle,
    width: Int,
    pad: Int,
    alertAt: Int,
    dotRoom: Int,
    glyph: Int,
): BarFit {
    val slot = width / labels.size.coerceAtLeast(1) - pad
    var equalFits = false
    val equal = shellFitLabels(measurer, labels, style, min = 12.sp) { widths ->
        widths.withIndex().all { (i, w) -> w + (if (i == alertAt) dotRoom else 0) <= slot }.also { equalFits = it }
    }
    if (equalFits && equal.fontSize.value >= 14f) return BarFit(equal, null)
    var needs = emptyList<Int>()
    var sizedFits = false
    val sized = shellFitLabels(measurer, labels, style, min = 12.sp) { widths ->
        needs = widths.mapIndexed { i, w -> maxOf(w + (if (i == alertAt) dotRoom else 0), glyph) + pad }
        (needs.sum() <= width).also { sizedFits = it }
    }
    if (equalFits && (!sizedFits || sized.fontSize.value <= equal.fontSize.value)) return BarFit(equal, null)
    val spare = (width - needs.sum()).coerceAtLeast(0).toFloat() / needs.size.coerceAtLeast(1)
    return BarFit(sized, needs.map { it + spare })
}

/** The fault dot after a tab label: 6 dp, signal, its top on the label's cap height. */
private val AlertDot = 6.dp
private val AlertDotGap = 3.dp

/** Between the cover bar's glyph and its label. */
private val GlyphLabelGap = 2.dp

/** Between the inner screen's last tab and the language at the row's end: at least the row's own spacing between two tabs. */
private val LanguageGap = TabGlyphs.InnerSpacing

/** Archivo's cap height, as a share of the font size (BRAND: 0.686 em). */
private const val CapHeightEm = 0.686f

@Composable
private fun TabLabel(text: String, style: TextStyle, color: Color, alert: Boolean, centred: Boolean = false) {
    val c = S3.colors
    val density = LocalDensity.current
    Row {
        // Centred under its glyph, the label keeps its place: the dot's room mirrored in front of it.
        if (alert && centred) Spacer(Modifier.width(AlertDotGap + AlertDot))
        Text(
            text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.alignByBaseline(),
        )
        if (alert) {
            val cap = with(density) { (style.fontSize.toPx() * CapHeightEm).roundToInt() }
            Spacer(Modifier.width(AlertDotGap))
            // Aligned so that the dot's top sits a cap height above the label's baseline.
            Box(
                Modifier
                    .alignBy { cap }
                    .size(AlertDot)
                    .clip(CircleShape)
                    .background(c.signal),
            )
        }
    }
}

// --- Inner screen ---------------------------------------------------------------------------------

@Composable
private fun WideShell(
    vm: AppViewModel,
    destinations: List<Destination>,
    destination: Destination,
    garageOpen: Boolean,
    hasFaults: Boolean,
    layoutInfo: WindowLayoutInfo?,
    nav: Navigator,
    content: @Composable (wide: Boolean) -> Unit,
) {
    val fold = layoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
    val density = LocalDensity.current
    var originX by remember { mutableFloatStateOf(0f) }
    val heroScroll = rememberScrollState()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onGloballyPositioned { originX = it.positionInWindow().x },
    ) {
        val total = maxWidth
        val split = paneSplit(fold, originX, total, density)
        val rightWidth = total - split.left - split.hinge
        Row(Modifier.fillMaxSize()) {
            // Each pane pads S3.l on its hinge side, so the gap straddles the fold evenly.
            Column(
                Modifier
                    .width(split.left)
                    .fillMaxHeight()
                    .verticalScroll(heroScroll)
                    .padding(start = S3.l, end = S3.l, top = S3.l, bottom = S3.xl),
            ) {
                // On Fahren the right pane shows the controls, so the hero leaves level and limit to them; on Diagnose
                // the fault's paragraph stands there, so the hero keeps to the fault line.
                BikeHeroPanel(
                    vm,
                    controlsVisible = !garageOpen && destination == Destination.RIDE,
                    diagnosisBeside = !garageOpen && destination == Destination.DIAGNOSIS,
                )
            }
            Spacer(Modifier.width(split.hinge))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!garageOpen) DestinationTabs(destinations, destination, hasFaults, nav.go, tight = rightWidth < 380.dp)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content(true)
                }
            }
        }
    }
}

/**
 * Text tabs over the right pane, each label after its dot glyph like on the cover screen's bar (fog, the active one lit
 * in led). Here a glyph takes only its own columns, so every label stands the same gap after its symbol; a fault the
 * bike reports is a signal dot after the Diagnose label.
 *
 * The labels never stand smaller than they did beside the old single LED: when the glyphs take that room, the row
 * first closes up (8 dp between tabs, 4 dp to the label), then each glyph goes over its label as on the cover screen
 * and the tabs spread over the width; if even that does not fit at 12 sp, the row scrolls rather than cut a tab off.
 * See [planInnerTabs].
 *
 * The language ([LanguageButton]) stands at the row's end, at least [LanguageGap] after the last tab, and the tabs plan
 * their row in the width that is left: on the Find N6's right pane (about 360 dp) every glyph goes over its label, and
 * the labels keep their 16 sp (Russian, the longest, 13 sp).
 */
@Composable
private fun DestinationTabs(
    destinations: List<Destination>,
    current: Destination,
    hasFaults: Boolean,
    onGo: (Destination) -> Unit,
    tight: Boolean,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val labels = destinations.map { stringResource(it.labelRes) }
    val alertLabel = stringResource(R.string.shell_tab_alert_cd, stringResource(Destination.DIAGNOSIS.labelRes))
    val measurer = rememberTextMeasurer()
    val baseStyle = S3.type.bodyStrong
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = S3.l)) {
        // The language stands at the row's end; the tabs plan their labels in what is left.
        val available = (constraints.maxWidth - with(density) { (LanguageTubeSize.width + LanguageGap).roundToPx() }).coerceAtLeast(0)
        val plan = remember(destinations, labels, available, tight, measurer, baseStyle, density) {
            with(density) {
                val widths = HashMap<Float, List<Int>>()
                val compact = InnerTabsStep(TabGlyphs.InnerSpacingCompact.roundToPx(), TabGlyphs.InnerGapCompact.roundToPx())
                val n = labels.size
                val legacySpacing = if (tight) TabGlyphs.LegacySpacingTight else TabGlyphs.LegacySpacing
                planInnerTabs(
                    widthsAt = { size ->
                        widths.getOrPut(size) {
                            val style = shellLabelStyle(baseStyle, size)
                            labels.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
                        }
                    },
                    base = baseStyle.fontSize.value,
                    min = 12f,
                    available = available,
                    glyphs = destinations.map { tabGlyphWidth(tabGlyph(it), TabGlyphs.InnerPitch, trim = true).roundToPx() },
                    alertAt = destinations.indexOf(Destination.DIAGNOSIS),
                    dotRoom = (AlertDotGap + AlertDot).roundToPx(),
                    minTab = 48.dp.roundToPx(),
                    steps = if (tight) {
                        listOf(compact)
                    } else {
                        listOf(InnerTabsStep(TabGlyphs.InnerSpacing.roundToPx(), TabGlyphs.InnerGap.roundToPx()), compact)
                    },
                    stackedSpacing = compact.spacing,
                    legacyFixed = (TabGlyphs.LegacyLed * n + legacySpacing * (n - 1).coerceAtLeast(0)).roundToPx(),
                )
            }
        }
        val style = shellLabelStyle(baseStyle, plan.size)
        val spacing = with(density) { plan.spacing.toDp() }
        val gap = with(density) { plan.gap.toDp() }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Stacked, the tabs spread over the whole width before the language like the cover screen's bar.
                Row(
                    Modifier
                        .weight(1f)
                        .selectableGroup()
                        .then(if (!plan.fits) Modifier.horizontalScroll(scroll) else Modifier),
                    horizontalArrangement = if (plan.stacked && plan.fits) Arrangement.SpaceBetween else Arrangement.spacedBy(spacing),
                ) {
                    destinations.forEachIndexed { index, d ->
                        key(d) {
                            val active = d == current
                            val alert = d == Destination.DIAGNOSIS && hasFaults
                            val tab = Modifier
                                .heightIn(min = 52.dp)
                                .widthIn(min = 48.dp)
                                .selectable(
                                    selected = active,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = {
                                        if (!active) haptics.tick()
                                        onGo(d)
                                    },
                                )
                                .semantics { if (alert) contentDescription = alertLabel }
                            val color = if (active) c.paint else c.fog
                            if (plan.stacked) {
                                Column(tab, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    ShellTabGlyph(tabGlyph(d), active, pitch = TabGlyphs.InnerPitch, trim = true)
                                    Spacer(Modifier.height(GlyphLabelGap))
                                    TabLabel(labels[index], style, color, alert, centred = true)
                                }
                            } else {
                                Row(tab, verticalAlignment = Alignment.CenterVertically) {
                                    ShellTabGlyph(tabGlyph(d), active, pitch = TabGlyphs.InnerPitch, trim = true)
                                    Spacer(Modifier.width(gap))
                                    TabLabel(labels[index], style, color, alert)
                                }
                            }
                        }
                    }
                }
                // The language, the app's and not the bike's, at the row's end: reachable from every tab.
                Spacer(Modifier.width(LanguageGap))
                LanguageButton()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.rib))
        }
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
