package dev.steven.s3control.ui.s2

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.S3App
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ble.S2Faults
import dev.steven.s3control.ble.S2Frame
import dev.steven.s3control.ble.S2Mute
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ride.GpsSpeed
import dev.steven.s3control.ui.cockpit.rememberRideLoggerStarter
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.BikeSectionTitle
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.ConnectPrompt
import dev.steven.s3control.ui.components.DASH
import dev.steven.s3control.ui.components.Fact
import dev.steven.s3control.ui.components.Hairline
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.LedDot
import dev.steven.s3control.ui.components.LinkLine
import dev.steven.s3control.ui.components.MatrixDisplay
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.ShellFitText
import dev.steven.s3control.ui.components.SwitchRow
import dev.steven.s3control.ui.components.appLocale
import dev.steven.s3control.ui.components.rememberNow
import dev.steven.s3control.ui.onboarding.TextLink
import dev.steven.s3control.ui.screens.LogViewNav
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The S2 drive diagnosis (the S2 plan, the friend's S2 in Zagreb): an S2 whose motor cuts out, with the
 * wiring harness, the speed sensor cable from the motor or the motor cable under suspicion, and a bike that
 * rarely shows an error code. The S2 status block has no motor current, temperature or raw hall data, so the page
 * works with what it has: the speed byte (wiggle test, GPS comparison) and the 5-bit error code (catcher).
 *
 * One loud element: the matrix with the bike's live speed. Everything else is quiet text, rows and switches.
 */

/** While this page is open YABA reads the status block every second (normally every 5 s, 1.5 s while logging). */
private const val PAGE_POLL_OWNER = "s2-drive-page"
private const val PAGE_POLL_MS = 1_000L

/** The wiggle test reads it a little faster still, so a drop to 0 shows between two blocks about 0.7 s apart. */
private const val WIGGLE_POLL_OWNER = "s2-wiggle"
private const val WIGGLE_POLL_MS = 700L

/** A drop flashes the matrix in signal red for this long. */
private const val FLASH_MS = 1_500L

/** The live speed counts as stale once two reads in a row are missing, plus this much slack. */
private const val STALE_SLACK_MS = 500L

/**
 * The diagnosis over the whole window, from [S2DiagnosisEntry]. A full-screen dialog, so it needs nothing from the
 * shell; back closes it.
 */
@Composable
fun S2DiagnosisDialog(vm: AppViewModel, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val dark = S3.colors.isDark
        SideEffect {
            window?.let { w ->
                w.setDimAmount(0f)
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(S3.colors.asphalt).windowInsetsPadding(WindowInsets.safeDrawing)) {
            S2DiagnosisScreen(vm, wide = maxWidth >= 600.dp, onClose = onClose, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The page itself: wiggle test, error catcher, ride with GPS, live status block, S2 test mode. One column on the
 * cover screen; on the inner screen the wiggle test (the hand at the bike) on the left, the rest on the right.
 */
@Composable
fun S2DiagnosisScreen(vm: AppViewModel, wide: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { S2DriveWatch.attach(context) }
    // While the page is open the speed and the byte view stay live (one read a second), not only during the test.
    DisposableEffect(vm) {
        vm.bike.requestPoll(PAGE_POLL_OWNER, PAGE_POLL_MS)
        onDispose { vm.bike.requestPoll(PAGE_POLL_OWNER, null) }
    }
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val watch by S2DriveWatch.state.collectAsStateWithLifecycle()
    val gutter = if (wide) S3.l else S3.gutter
    val ready = state.isReady

    val head: @Composable () -> Unit = {
        // Narrow: the close button above, so the title keeps its size (as the log viewers do).
        val title = stringResource(R.string.s2diag_title)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 460.dp) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        QuietButton(stringResource(R.string.s2diag_close), onClose)
                    }
                    Spacer(Modifier.height(S3.s))
                    ShellFitText(title, S3.type.display, S3.colors.paint, Modifier.semantics { heading() })
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ShellFitText(title, S3.type.display, S3.colors.paint, Modifier.weight(1f).semantics { heading() })
                    Spacer(Modifier.width(S3.s))
                    QuietButton(stringResource(R.string.s2diag_close), onClose)
                }
            }
        }
        Spacer(Modifier.height(S3.s))
        Text(stringResource(R.string.s2diag_intro), style = S3.type.body, color = S3.colors.fog)
        Spacer(Modifier.height(S3.m))
        LinkLine(state)
        if (!ready) {
            Spacer(Modifier.height(S3.m))
            ConnectPrompt(vm, state, reason = stringResource(R.string.s2diag_connect_reason))
        }
    }

    if (wide) {
        Row(modifier.fillMaxSize().padding(horizontal = gutter)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = S3.l, bottom = S3.xl)) {
                head()
                Spacer(Modifier.height(S3.xl))
                WiggleSection(vm, watch, ready)
            }
            Spacer(Modifier.width(S3.l))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = S3.l, bottom = S3.xl)) {
                CatchSection(vm, watch)
                Spacer(Modifier.height(S3.xl))
                GpsSection(vm, onClose)
                Spacer(Modifier.height(S3.xl))
                RawSection(watch, wide = false)
                Spacer(Modifier.height(S3.xl))
                TestModeSection(vm)
            }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = S3.l, bottom = S3.xl)) {
            head()
            Spacer(Modifier.height(S3.xl))
            WiggleSection(vm, watch, ready)
            Spacer(Modifier.height(S3.xl))
            CatchSection(vm, watch)
            Spacer(Modifier.height(S3.xl))
            GpsSection(vm, onClose)
            Spacer(Modifier.height(S3.xl))
            RawSection(watch, wide = false)
            Spacer(Modifier.height(S3.xl))
            TestModeSection(vm)
        }
    }
}

// --- Wiggle test ---------------------------------------------------------------------------------

/** Whether a wiggle test ran in this process: from then on its steps are folded. */
private object WiggleMemory {
    var ran by mutableStateOf(false)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WiggleSection(vm: AppViewModel, watch: S2DriveWatch.State, ready: Boolean) {
    val c = S3.colors
    val view = LocalView.current
    var running by rememberSaveable { mutableStateOf(false) }
    var spot by rememberSaveable { mutableStateOf<CableSpot?>(null) }
    var flashAt by remember { mutableLongStateOf(0L) }
    var flashing by remember { mutableStateOf(false) }
    val marks by S2DriveWatch.marks.collectAsStateWithLifecycle()
    val alarm = rememberDropAlarm()
    val currentSpot by rememberUpdatedState(spot)
    val now = rememberNow(true)
    val interval by vm.bike.pollInterval.collectAsStateWithLifecycle()

    val frame = watch.last?.frame?.takeIf { it.plausible }
    val age = watch.last?.let { now - it.atMillis }
    // Stale from the cadence that applies now: two reads missing in a row, not a fixed number of seconds.
    val stale = ready && age != null && age > 2 * interval + STALE_SLACK_MS
    val kmh = frame?.speed?.takeIf { ready }

    if (running) {
        // Every block, in order: the state alone could skip the one block in which the speed fell to 0.
        LaunchedEffect(Unit) {
            val detector = WiggleDetector()
            vm.bike.s2Blocks.collect { b ->
                if (!b.frame.plausible) return@collect
                val from = detector.offer(b.atMillis, b.frame.speed) ?: return@collect
                val where = currentSpot
                S2DriveWatch.addMark(
                    WiggleMark(b.atMillis, where, 0, from),
                    I18n.get(R.string.s2diag_log_drop, from),
                    where?.let { I18n.get(it.label) },
                )
                alarm.fire()
                flashAt = b.atMillis
            }
        }
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            // A claim, not a write: stopping the logger on this page no longer slows the test down.
            vm.bike.requestPoll(WIGGLE_POLL_OWNER, WIGGLE_POLL_MS)
            onDispose {
                view.keepScreenOn = false
                vm.bike.requestPoll(WIGGLE_POLL_OWNER, null)
            }
        }
    }
    LaunchedEffect(flashAt) {
        if (flashAt == 0L) return@LaunchedEffect
        flashing = true
        delay(FLASH_MS)
        flashing = false
    }

    BikeSectionTitle(stringResource(R.string.s2diag_wiggle_title))
    Spacer(Modifier.height(S3.m))
    val text = kmh?.toString() ?: "--"
    val lit = when {
        flashing -> c.signal
        kmh == null || stale -> c.fog
        else -> c.led
    }
    val description = if (kmh != null) stringResource(R.string.s2diag_wiggle_speed_cd, kmh) else stringResource(R.string.s2diag_wiggle_speed_none_cd)
    MatrixDisplay(
        frame = remember(text) { MatrixFrame.text(text) },
        columns = 13,
        lit = lit,
        description = description,
        sweepMillis = 0,
        modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp),
    )
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.s2diag_wiggle_unit))
    if (stale) {
        val secs = ((age ?: 0L) / 1000).toInt()
        Text(
            pluralStringResource(R.plurals.s2diag_wiggle_stale, secs, secs),
            style = S3.type.label,
            color = c.signal,
            modifier = Modifier.padding(top = S3.xs),
        )
    }
    // The hands are at the bike: where on the cable, start and mark come right under the matrix, the steps after.
    Spacer(Modifier.height(S3.l))
    Text(stringResource(R.string.s2diag_wiggle_spot), style = S3.type.bodyStrong, color = c.paint)
    Spacer(Modifier.height(S3.s))
    ChoiceBar(
        options = CableSpot.entries.map { it to stringResource(it.label) },
        selected = spot,
        onSelect = { spot = if (spot == it) null else it },
    )
    Spacer(Modifier.height(S3.m))
    if (running) {
        BigButton(stringResource(R.string.s2diag_wiggle_stop), {
            running = false
            WiggleMemory.ran = true
        }, color = c.signal, onColor = c.onSignal)
        Hint(stringResource(R.string.s2diag_wiggle_running), Modifier.fillMaxWidth().padding(top = S3.s))
    } else {
        BigButton(stringResource(R.string.s2diag_wiggle_start), { running = true }, enabled = ready)
    }
    Spacer(Modifier.height(S3.s))
    QuietButton(
        stringResource(R.string.s2diag_mark),
        {
            val at = System.currentTimeMillis()
            val speed = kmh ?: 0
            S2DriveWatch.addMark(
                WiggleMark(at, spot, speed, null),
                I18n.get(R.string.s2diag_log_mark, speed),
                spot?.let { I18n.get(it.label) },
            )
        },
        Modifier.fillMaxWidth(),
        enabled = ready,
    )

    // After the first run in this process the steps fold away behind one line.
    var showSteps by rememberSaveable { mutableStateOf(false) }
    if (WiggleMemory.ran && !showSteps) {
        Spacer(Modifier.height(S3.s))
        TextLink(stringResource(R.string.s2diag_wiggle_steps_show), { showSteps = true })
    } else {
        Spacer(Modifier.height(S3.l))
        listOf(
            R.string.s2diag_wiggle_step1,
            R.string.s2diag_wiggle_step2,
            R.string.s2diag_wiggle_step3,
            R.string.s2diag_wiggle_step4,
        ).forEachIndexed { i, res ->
            Row(Modifier.fillMaxWidth().padding(vertical = S3.xs).semantics(mergeDescendants = true) {}) {
                Text("${i + 1}", style = S3.type.data, color = c.led, modifier = Modifier.width(28.dp))
                Text(stringResource(res), style = S3.type.body, color = c.paint, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(S3.s))
        Hint(stringResource(R.string.s2diag_wiggle_zero_hint))
    }

    if (marks.isNotEmpty()) {
        Spacer(Modifier.height(S3.l))
        Text(stringResource(R.string.s2diag_marks_title), style = S3.type.bodyStrong, color = c.paint)
        Spacer(Modifier.height(S3.xs))
        val clock = rememberClock()
        marks.asReversed().forEach { m -> MarkRow(m, clock) }
        Spacer(Modifier.height(S3.s))
        QuietButton(stringResource(R.string.s2diag_marks_clear), { S2DriveWatch.clearMarks() })
    }
}

@Composable
private fun MarkRow(m: WiggleMark, clock: Clock) {
    val c = S3.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.Top,
        ) {
            LedDot(if (m.auto) c.signal else c.led, Modifier.padding(top = 3.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (m.fromKmh != null) stringResource(R.string.s2diag_mark_drop, m.fromKmh) else stringResource(R.string.s2diag_mark_manual, m.kmh),
                    style = S3.type.body,
                    color = c.paint,
                )
                m.spot?.let { Hint(stringResource(it.label)) }
            }
            Spacer(Modifier.width(S3.m))
            Text(
                stringResource(R.string.s2diag_clock, clock.time(m.atMillis)),
                style = S3.type.data.copy(fontFeatureSettings = "tnum"),
                color = c.paint,
                textAlign = TextAlign.End,
            )
        }
        Hairline()
    }
}

// --- Error catcher -------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatchSection(vm: AppViewModel, watch: S2DriveWatch.State) {
    val c = S3.colors
    val context = LocalContext.current
    val testMode by vm.bike.s2Prefs.testMode.collectAsStateWithLifecycle()
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val interval by vm.bike.pollInterval.collectAsStateWithLifecycle()
    val mac = state.bike?.normalizedMac
    // This bike's episodes only: a second bike or the debug demo must not mix into the list.
    val hits = remember(watch.hits, mac) { S2DriveWatch.hitsFor(watch.hits, mac) }
    val clock = rememberClock()
    var copied by remember { mutableIntStateOf(0) }
    LaunchedEffect(copied) {
        if (copied == 0) return@LaunchedEffect
        delay(3_000)
        copied = 0
    }

    BikeSectionTitle(stringResource(R.string.s2diag_catch_title))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.s2diag_catch_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.xs))
    val since = watch.sinceMillis
    if (since != null) {
        val n = watch.blocks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        Hint(pluralStringResource(R.plurals.s2diag_catch_blocks, n, clock.count(watch.blocks), clock.shortTime(since)))
    } else {
        Hint(stringResource(R.string.s2diag_entry_waiting))
    }
    // Whether short codes can slip through: the bike pushes its blocks, or YABA only sees the ones it reads.
    if (watch.pushed + watch.polled > 0) {
        val line = if (watch.pushed > 0) {
            val n = watch.pushed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            pluralStringResource(R.plurals.s2fix_catch_pushed, n, clock.count(watch.pushed))
        } else {
            stringResource(R.string.s2fix_catch_polled, seconds(interval, appLocale()))
        }
        Hint(line, Modifier.padding(top = S3.xs))
    }
    if (watch.implausible > 0) {
        Text(
            pluralStringResource(R.plurals.s2diag_catch_implausible, watch.implausible, watch.implausible),
            style = S3.type.caption,
            color = c.lamp,
            modifier = Modifier.padding(top = S3.xs),
        )
    }
    Spacer(Modifier.height(S3.m))
    if (hits.isEmpty()) {
        Hint(stringResource(R.string.s2diag_catch_none))
    } else {
        Hairline()
        val groups = remember(hits) { HitGroup.of(hits) }
        groups.forEach { g -> key(g.code) { HitGroupRow(g, clock) } }
        if (hits.any { it.code in S2Parts.disputed }) {
            Spacer(Modifier.height(S3.s))
            Text(
                stringResource(if (testMode) R.string.s2diag_note_disputed_on else R.string.s2diag_note_disputed),
                style = S3.type.caption,
                color = c.lamp,
            )
        }
        Spacer(Modifier.height(S3.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
            QuietButton(stringResource(R.string.s2diag_catch_copy), {
                if (copyText(context, S2DriveWatch.copyText(hits, watch, interval))) copied++
            })
            QuietButton(stringResource(R.string.s2diag_catch_clear), {
                if (mac == null) S2DriveWatch.clearHits() else S2DriveWatch.clearHits { it.mac == null || it.mac == mac }
            })
        }
        if (copied > 0) Hint(stringResource(R.string.s2diag_copied), Modifier.padding(top = S3.s))
    }
}

/**
 * One code: how often and when last, its name and the likely cause, once. A tap shows the single moments with the
 * bike's values at each.
 */
@Composable
private fun HitGroupRow(g: HitGroup, clock: Clock) {
    val c = S3.colors
    val drive = S2Parts.drive(g.code)
    val fault = S2Faults.describe(g.code)
    var open by rememberSaveable(g.code) { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.shift_expanded else R.string.shift_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = state }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LedDot(if (drive) c.signal else c.fog, Modifier.padding(top = 3.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.s2diag_hit_group, g.count, g.code, g.count, clock.time(g.lastAtMillis)),
                    style = S3.type.bodyStrong.copy(fontFeatureSettings = "tnum"),
                    color = c.paint,
                )
                Text(stringResource(fault.nameRes), style = S3.type.caption, color = c.fog)
                Text(
                    stringResource(R.string.s2diag_hit_part, stringResource(S2Parts.likely(g.code))),
                    style = S3.type.caption,
                    color = if (drive) c.paint else c.fog,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(S3.s)) {
                g.hits.forEach { h ->
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Text(
                            stringResource(R.string.s2diag_hit_meta, clock.time(h.firstAtMillis), h.kmh, h.motorBattery, h.moduleBattery),
                            style = S3.type.caption.copy(fontFeatureSettings = "tnum"),
                            color = c.paint,
                        )
                        Text(
                            pluralStringResource(R.plurals.s2diag_hit_blocks, h.blocks, h.blocks, clock.time(h.lastAtMillis)),
                            style = S3.type.caption,
                            color = c.fog,
                        )
                    }
                }
            }
        }
        Hairline()
    }
}

// --- Ride with GPS ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpsSection(vm: AppViewModel, onClose: () -> Unit) {
    val c = S3.colors
    val context = LocalContext.current
    val gps = remember { GpsSpeed.get(context) }
    val graph = remember { (context.applicationContext as? S3App)?.graph }
    val enabled by gps.enabled.collectAsStateWithLifecycle()
    val fix by gps.fix.collectAsStateWithLifecycle()
    val listening by gps.listening.collectAsStateWithLifecycle()
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val rec by vm.recorder.collectAsStateWithLifecycle()
    val startError by vm.cockpitLoggerError.collectAsStateWithLifecycle()
    val starter = rememberRideLoggerStarter(vm)
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        // Back from the system settings: the answer may have changed.
        checks++
        gps.update()
        onPauseOrDispose { }
    }
    val permitted = remember(checks) { gps.permitted() }
    val coarseOnly = remember(checks) { gps.coarseOnly() }
    val providerOn = remember(checks, listening) { gps.providerOn() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checks++
        if (gps.permitted()) {
            gps.setEnabled(true)
            graph?.recorder?.onGpsChanged()
        }
    }
    // The live value below needs GPS while this page is open (and the comparison is on).
    DisposableEffect(gps) {
        val owner = Any()
        gps.acquire(owner)
        onDispose { gps.release(owner) }
    }

    BikeSectionTitle(stringResource(R.string.s2diag_gps_title))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.s2diag_gps_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.s))
    SwitchRow(
        label = stringResource(R.string.s2diag_gps_switch),
        caption = stringResource(R.string.s2diag_gps_switch_hint),
        // On while chosen, also when Android still refuses the location: the line below says why, and off clears it.
        checked = enabled,
        onCheckedChange = { on ->
            if (!on) {
                gps.setEnabled(false)
            } else if (gps.permitted()) {
                gps.setEnabled(true)
                graph?.recorder?.onGpsChanged()
            } else {
                gps.setEnabled(true)
                runCatching {
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        },
    )
    when {
        enabled && !permitted -> {
            Text(
                stringResource(if (coarseOnly) R.string.s2diag_gps_coarse else R.string.s2diag_gps_denied),
                style = S3.type.label,
                color = c.signal,
            )
            Spacer(Modifier.height(S3.s))
            QuietButton(stringResource(R.string.s2diag_gps_app_settings), { openAppSettings(context) })
        }
        enabled && !providerOn -> {
            Text(stringResource(R.string.s2diag_gps_off), style = S3.type.label, color = c.signal)
            Spacer(Modifier.height(S3.s))
            QuietButton(stringResource(R.string.s2diag_gps_location_settings), { openLocationSettings(context) })
        }
        enabled -> {
            val gpsKmh = fix?.kmh
            if (listening && gpsKmh == null) Hint(stringResource(R.string.s2diag_gps_waiting))
            Spacer(Modifier.height(S3.s))
            val locale = appLocale()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.xl)) {
                Fact(
                    stringResource(R.string.s2diag_gps_fact_bike),
                    state.speedKmh?.takeIf { state.isReady }?.let { stringResource(R.string.s2diag_kmh, it.toString()) } ?: DASH,
                )
                Fact(
                    stringResource(R.string.s2diag_gps_fact_gps),
                    gpsKmh?.let { stringResource(R.string.s2diag_kmh, String.format(locale, "%.1f", it)) } ?: DASH,
                )
            }
        }
    }
    Spacer(Modifier.height(S3.m))
    Hint(stringResource(R.string.s2diag_gps_steps))
    Spacer(Modifier.height(S3.m))
    // The ride itself runs in the cockpit, which opens from the Diagnose page under this block (the steps say so):
    // the one big button here is the wiggle test's.
    if (rec.active) {
        QuietButton(stringResource(R.string.bike_logger_stop), { vm.stopRideLogger() }, Modifier.fillMaxWidth(), color = c.signal)
    } else {
        QuietButton(stringResource(R.string.bike_logger_start), { starter.start(mayAsk = true) }, Modifier.fillMaxWidth())
    }
    startError?.let {
        Text(stringResource(R.string.bike_logger_start_failed, it), style = S3.type.label, color = c.signal, modifier = Modifier.padding(top = S3.s))
    }
    Spacer(Modifier.height(S3.s))
    var opening by remember { mutableStateOf(false) }
    if (opening) {
        LaunchedEffect(Unit) {
            val latest = withContext(Dispatchers.IO) { vm.rideLogs().firstOrNull() }
            opening = false
            if (latest != null) {
                onClose()
                LogViewNav.openRide(latest)
            }
        }
    }
    QuietButton(stringResource(R.string.s2diag_gps_open_log), { opening = true }, Modifier.fillMaxWidth())
}

// --- Live status block ----------------------------------------------------------------------------

@Composable
private fun RawSection(watch: S2DriveWatch.State, wide: Boolean) {
    val c = S3.colors
    val context = LocalContext.current
    var copied by remember { mutableIntStateOf(0) }
    LaunchedEffect(copied) {
        if (copied == 0) return@LaunchedEffect
        delay(3_000)
        copied = 0
    }
    BikeSectionTitle(stringResource(R.string.s2diag_raw_title))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.s2diag_raw_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.m))
    val frame = watch.last?.frame
    if (frame == null) {
        Hint(stringResource(R.string.s2diag_raw_none))
        return
    }
    val bytes = frame.bytes
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = if (wide || maxWidth >= 600.dp) S2Frame.SIZE else S2Frame.SIZE / 2
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.s)) {
            (0 until S2Frame.SIZE).chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { i ->
                        ByteCell(
                            index = i,
                            hex = String.format(Locale.ROOT, "%02x", bytes[i].toInt() and 0xFF),
                            changed = i in watch.changed,
                            count = watch.changes.getOrElse(i) { 0 },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.s2diag_raw_legend))
    Spacer(Modifier.height(S3.m))
    QuietButton(stringResource(R.string.s2diag_raw_copy), { if (copyText(context, frame.hex)) copied++ })
    if (copied > 0) Hint(stringResource(R.string.s2diag_copied), Modifier.padding(top = S3.s))
}

@Composable
private fun ByteCell(index: Int, hex: String, changed: Boolean, count: Int, modifier: Modifier = Modifier) {
    val c = S3.colors
    val spoken = pluralStringResource(R.plurals.s2diag_raw_byte_cd, count, index, hex, count)
    Column(
        modifier.padding(horizontal = 1.dp).clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$index", style = S3.type.caption.copy(fontFeatureSettings = "tnum"), color = c.fog)
        Text(
            hex,
            style = S3.type.data.copy(fontFeatureSettings = "tnum, zero"),
            color = if (changed) c.led else c.paint,
        )
        Text(if (count > 0) "$count" else "", style = S3.type.caption.copy(fontFeatureSettings = "tnum"), color = if (changed) c.led else c.fog)
    }
}

// --- S2 test mode ---------------------------------------------------------------------------------

/**
 * The switch and, while it is on, the unconfirmed commands that have no place on the other pages: the GATT overview
 * (test step 2), muting the lock sounds (step 13, what does 0x06 do?) and the off-road region (does the S2 take
 * region 2?), the latter behind a confirmation and only at a standstill.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestModeSection(vm: AppViewModel) {
    val c = S3.colors
    val context = LocalContext.current
    val testMode by vm.bike.s2Prefs.testMode.collectAsStateWithLifecycle()
    val state by vm.bikeState.collectAsStateWithLifecycle()
    var copied by remember { mutableIntStateOf(0) }
    // Which of this section's commands went out last, so its error shows here and nowhere else.
    var sent by remember { mutableStateOf<Op?>(null) }
    var confirmOffroad by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied == 0) return@LaunchedEffect
        delay(3_000)
        copied = 0
    }
    BikeSectionTitle(stringResource(R.string.s2diag_test_title))
    Spacer(Modifier.height(S3.s))
    SwitchRow(
        label = stringResource(R.string.s2diag_test_switch),
        caption = stringResource(R.string.s2diag_test_hint),
        checked = testMode,
        onCheckedChange = { vm.bike.s2Prefs.setTestMode(it) },
    )
    if (!testMode) return
    val ready = state.isReady
    val idle = Op.MUTE !in state.busy && Op.SPEED !in state.busy

    // Test step 2 (plan B9): the bike's GATT layout, read-only, for the notes.
    Spacer(Modifier.height(S3.s))
    QuietButton(
        stringResource(R.string.s2diag_test_gatt),
        { vm.bike.describeServices()?.let { if (copyText(context, it)) copied++ } },
        enabled = ready,
    )
    if (copied > 0) Hint(stringResource(R.string.s2diag_copied), Modifier.padding(top = S3.s))

    // Test step 13: MUTE 0x06 with the lock and unlock bits (vanbike-lib calls 0x06 "pair remote").
    Spacer(Modifier.height(S3.l))
    Text(stringResource(R.string.s2fix_mute_title), style = S3.type.bodyStrong, color = c.paint)
    Spacer(Modifier.height(S3.xs))
    Hint(stringResource(R.string.s2fix_mute_hint))
    state.s2MuteMask?.takeIf { ready }?.let { mask ->
        val muted = mask and S2Mute.LOCK_STATE == S2Mute.LOCK_STATE
        Text(
            stringResource(if (muted) R.string.s2fix_mute_reports_on else R.string.s2fix_mute_reports_off),
            style = S3.type.label,
            color = c.paint,
            modifier = Modifier.padding(top = S3.xs),
        )
    }
    Spacer(Modifier.height(S3.s))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        QuietButton(
            stringResource(R.string.s2fix_mute_on),
            { sent = Op.MUTE; vm.bike.clearCommandError(); vm.bike.setMutedSounds(S2Mute.LOCK_STATE) },
            enabled = ready && idle,
        )
        QuietButton(
            stringResource(R.string.s2fix_mute_off),
            { sent = Op.MUTE; vm.bike.clearCommandError(); vm.bike.setMutedSounds(0) },
            enabled = ready && idle,
        )
    }

    // Off-road (region 2, 37 km/h): does the S2 accept it? Only to see the answer, on the stand, then back.
    Spacer(Modifier.height(S3.l))
    Text(stringResource(R.string.s2fix_offroad_title), style = S3.type.bodyStrong, color = c.paint)
    Spacer(Modifier.height(S3.xs))
    Hint(stringResource(R.string.s2fix_offroad_hint))
    state.speedLimit?.takeIf { ready }?.let { limit ->
        Text(
            stringResource(R.string.s2fix_offroad_reports, limit.kmh),
            style = S3.type.label,
            color = if (limit == SpeedLimit.NO_LIMIT) c.lamp else c.paint,
            modifier = Modifier.padding(top = S3.xs),
        )
    }
    Spacer(Modifier.height(S3.s))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        QuietButton(
            stringResource(R.string.s2fix_offroad_send),
            { confirmOffroad = true },
            enabled = ready && idle && !state.moving && state.speedLimit != SpeedLimit.NO_LIMIT,
        )
        if (ready && state.speedLimit == SpeedLimit.NO_LIMIT) {
            QuietButton(
                stringResource(R.string.s2fix_offroad_back),
                { sent = Op.SPEED; vm.bike.clearCommandError(); vm.bike.setSpeedLimit(SpeedLimit.EU) },
                enabled = idle,
            )
        }
    }
    if (ready && state.moving) Hint(stringResource(R.string.s2fix_offroad_standstill), Modifier.padding(top = S3.xs))
    state.lastCommandError?.takeIf { sent != null }?.let {
        Text(it, style = S3.type.label, color = c.signal, modifier = Modifier.padding(top = S3.s))
    }

    if (confirmOffroad) {
        AlertDialog(
            onDismissRequest = { confirmOffroad = false },
            title = { Text(stringResource(R.string.s2fix_offroad_confirm_title), style = S3.type.section, color = c.paint) },
            text = { Text(stringResource(R.string.s2fix_offroad_confirm_text), style = S3.type.body, color = c.paint) },
            confirmButton = {
                TextButton({
                    confirmOffroad = false
                    sent = Op.SPEED
                    vm.bike.clearCommandError()
                    vm.bike.setSpeedLimit(SpeedLimit.NO_LIMIT)
                }) { Text(stringResource(R.string.s2fix_offroad_confirm_send), style = S3.type.bodyStrong, color = c.signal) }
            },
            dismissButton = {
                TextButton({ confirmOffroad = false }) {
                    Text(stringResource(R.string.s2fix_offroad_confirm_cancel), style = S3.type.bodyStrong, color = c.paint)
                }
            },
        )
    }
}

// --- Helpers --------------------------------------------------------------------------------------

/** Times in the app language: today with seconds, another day with its date. */
internal class Clock(private val locale: Locale, private val zone: ZoneId) {
    private val timeF = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
    private val shortF = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    private val dayF = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM).withLocale(locale).withZone(zone)

    fun time(ms: Long): String {
        val i = Instant.ofEpochMilli(ms)
        return if (i.atZone(zone).toLocalDate() == LocalDate.now(zone)) timeF.format(i) else dayF.format(i)
    }

    fun shortTime(ms: Long): String = shortF.format(Instant.ofEpochMilli(ms))

    fun count(n: Long): String = String.format(locale, "%,d", n)
}

@Composable
internal fun rememberClock(): Clock {
    val locale = appLocale()
    return remember(locale) { Clock(locale, ZoneId.systemDefault()) }
}

/** A read interval in seconds as the page says it: "5", "1.5" (or "1,5"). */
internal fun seconds(ms: Long, locale: Locale): String =
    if (ms % 1_000 == 0L) (ms / 1_000).toString() else String.format(locale, "%.1f", ms / 1_000.0)

private fun copyText(context: Context, text: String): Boolean = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
    cm.setPrimaryClip(ClipData.newPlainText("YABA S2", text))
}.isSuccess

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
    }
}

private fun openLocationSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
