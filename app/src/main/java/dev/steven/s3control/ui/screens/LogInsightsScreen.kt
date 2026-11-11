package dev.steven.s3control.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.LabOp
import dev.steven.s3control.ble.LabState
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ride.BikeLogArchive
import dev.steven.s3control.ride.BikeLogParser.ShifterEventKind
import dev.steven.s3control.ride.LogInsights
import dev.steven.s3control.ride.LogInsights.DerateCause
import dev.steven.s3control.ride.LogInsights.Ride
import dev.steven.s3control.ride.LogInsights.Verdict
import dev.steven.s3control.ride.LogInsights.WakeSource
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.BikeSectionTitle
import dev.steven.s3control.ui.components.CheckRow
import dev.steven.s3control.ui.components.DASH
import dev.steven.s3control.ui.components.DayLane
import dev.steven.s3control.ui.components.DayStrip
import dev.steven.s3control.ui.components.DerateGrid
import dev.steven.s3control.ui.components.Fact
import dev.steven.s3control.ui.components.GearBars
import dev.steven.s3control.ui.components.Hairline
import dev.steven.s3control.ui.components.Health
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.LedDot
import dev.steven.s3control.ui.components.LegendKey
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.ShellFitText
import dev.steven.s3control.ui.components.Sparkline
import dev.steven.s3control.ui.components.TabularDigits
import dev.steven.s3control.ui.components.TraceChart
import dev.steven.s3control.ui.components.TraceMark
import dev.steven.s3control.ui.components.TracePoint
import dev.steven.s3control.ui.components.TraceRef
import dev.steven.s3control.ui.components.TraceSeries
import dev.steven.s3control.ui.components.appLocale
import dev.steven.s3control.ui.onboarding.TextLink
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Fahrtenschreiber: the bike's own log (30-second telemetry plus event lines) as rides with charts,
 * the firmware's current limit explained, the shifter's health, the wake timeline, and the guarded
 * log seek. Data: every download saved on the phone (ride/BikeLogArchive.kt), analysed by
 * ride/LogInsights.kt. Entry from Diagnose: [LogInsightsEntry] → [LogInsightsNav.open].
 */

// --- Navigation -----------------------------------------------------------------------------------

/**
 * Whether the Fahrtenschreiber is open. AppRoot shows [LogInsightsScreen] in place of the destination
 * content while [open] is true; the screen closes itself on back and with its close button.
 */
object LogInsightsNav {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()
    fun open() { _open.value = true }
    fun close() { _open.value = false }
}

/** Opens the Fahrtenschreiber (see [LogInsightsNav]). */
fun openLogInsights() = LogInsightsNav.open()

// --- Data -----------------------------------------------------------------------------------------

/** Where the merged log text comes from. The real screen reads the archive; the debug demo passes a sample. */
fun interface LogTextSource {
    suspend fun load(latest: AppViewModel.BikeLog?): BikeLogArchive.Snapshot
}

private class ArchiveSource(private val archive: BikeLogArchive) : LogTextSource {
    override suspend fun load(latest: AppViewModel.BikeLog?): BikeLogArchive.Snapshot = withContext(Dispatchers.IO) {
        archive.load(latest?.takeIf { !it.failed }?.text)
    }
}

@Composable
private fun rememberArchive(): BikeLogArchive {
    val context = LocalContext.current
    return remember(context) { BikeLogArchive(File(context.filesDir, "logs")) }
}

@Immutable
internal class Loaded(val snapshot: BikeLogArchive.Snapshot, val insights: LogInsights.Insights)

/** The last analysis, shared by the Diagnose entry and the screen so opening it does not parse twice. */
private object InsightsCache {
    @Volatile private var last: Loaded? = null

    suspend fun load(source: LogTextSource, latest: AppViewModel.BikeLog?): Loaded = withContext(Dispatchers.Default) {
        val snap = source.load(latest)
        val prev = last
        if (prev != null && prev.snapshot.text == snap.text) return@withContext Loaded(snap, prev.insights)
        Loaded(snap, LogInsights.analyze(snap.text)).also { last = it }
    }
}

@Composable
private fun rememberLoaded(vm: AppViewModel, source: LogTextSource): Loaded? {
    val log by vm.bikeLog.collectAsStateWithLifecycle()
    val loaded by produceState<Loaded?>(null, source, log?.atMillis) {
        value = runCatching { InsightsCache.load(source, log) }.getOrNull() ?: value
    }
    return loaded
}

// --- Formatting -----------------------------------------------------------------------------------

/** Numbers, units and bike-clock times in the app language. Bike epochs are shown in the phone's zone. */
@Stable
private class Fmt(
    val locale: Locale,
    val zone: ZoneId,
    private val timeF: DateTimeFormatter,
    private val timeSecondsF: DateTimeFormatter,
    private val rideF: DateTimeFormatter,
    private val dayF: DateTimeFormatter,
    private val dayShortF: DateTimeFormatter,
    private val dateF: DateTimeFormatter,
    private val atTimeP: String,
    private val kmhP: String,
    private val kmP: String,
    private val pctP: String,
    private val ampP: String,
    private val celsiusP: String,
    private val minP: String,
    private val hourMinP: String,
) {
    private fun at(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(zone)
    fun time(epoch: Long): String = timeF.format(at(epoch))
    fun timeSeconds(epoch: Long): String = timeSecondsF.format(at(epoch))
    fun atTime(epoch: Long): String = String.format(locale, atTimeP, time(epoch))
    fun ride(epoch: Long): String = rideF.format(at(epoch))
    fun day(d: LocalDate): String = dayF.format(d)
    fun dayShort(d: LocalDate): String = dayShortF.format(d)
    fun date(epoch: Long): String = dateF.format(at(epoch))
    fun localDate(epoch: Long): LocalDate = at(epoch).toLocalDate()
    fun secondOfDay(epoch: Long): Int = at(epoch).toLocalTime().toSecondOfDay()

    fun one(v: Double): String = String.format(locale, "%.1f", v)
    fun whole(v: Double): String = String.format(locale, "%.0f", v)
    fun count(n: Int): String = String.format(locale, "%,d", n)
    fun kmh(v: Double): String = String.format(locale, kmhP, one(v))
    fun km(v: Double): String = String.format(locale, kmP, one(v))
    fun pct(n: Int): String = String.format(locale, pctP, n.toString())
    fun amps(v: Double): String = String.format(locale, ampP, if (v % 1.0 == 0.0) whole(v) else one(v))
    fun ampsOf(n: Int): String = String.format(locale, ampP, n.toString())
    fun celsius(v: Double): String = String.format(locale, celsiusP, if (v % 1.0 == 0.0) whole(v) else one(v))
    fun celsiusOf(n: Int): String = String.format(locale, celsiusP, n.toString())
    fun duration(seconds: Long): String {
        val min = ((seconds + 30) / 60).toInt()
        return if (min < 60) String.format(locale, minP, min) else String.format(locale, hourMinP, min / 60, min % 60)
    }
}

@Composable
private fun rememberFmt(): Fmt {
    val locale = appLocale()
    val patterns = listOf(
        stringResource(R.string.loginsights_fmt_time),
        stringResource(R.string.loginsights_fmt_time_seconds),
        stringResource(R.string.loginsights_fmt_ride),
        stringResource(R.string.loginsights_fmt_day),
        stringResource(R.string.loginsights_fmt_day_short),
        stringResource(R.string.loginsights_fmt_date),
    )
    val units = listOf(
        stringResource(R.string.loginsights_at_time),
        stringResource(R.string.loginsights_unit_kmh),
        stringResource(R.string.loginsights_unit_km),
        stringResource(R.string.loginsights_unit_pct),
        stringResource(R.string.loginsights_unit_amp),
        stringResource(R.string.loginsights_unit_celsius),
        stringResource(R.string.loginsights_unit_min),
        stringResource(R.string.loginsights_unit_h_min),
    )
    return remember(locale, patterns, units) {
        val f = patterns.map { DateTimeFormatter.ofPattern(it, locale) }
        Fmt(
            locale, ZoneId.systemDefault(), f[0], f[1], f[2], f[3], f[4], f[5],
            units[0], units[1], units[2], units[3], units[4], units[5], units[6], units[7],
        )
    }
}

// --- Entry for Diagnose ---------------------------------------------------------------------------

/**
 * What the phone holds of the bike's log, loaded once for the section "Protokoll des Bikes": its read button and
 * [LogInsightsEntry] trade the main button on it.
 */
@Immutable
class LogArchiveView internal constructor(internal val loaded: Loaded?) {
    /** True once a log is saved on the phone (or read in this session), false while none is, null while it loads. */
    val saved: Boolean? get() = loaded?.let { it.snapshot.lines > 0 }
}

@Composable
fun rememberLogArchiveView(vm: AppViewModel): LogArchiveView {
    val archive = rememberArchive()
    val source = remember(archive) { ArchiveSource(archive) }
    val loaded = rememberLoaded(vm, source)
    return remember(loaded) { LogArchiveView(loaded) }
}

/**
 * The Fahrtenschreiber's place in Diagnose, inside the section "Protokoll des Bikes" (which reads the log): what the
 * archive holds (rides, kilometres, the last one) and "Fahrtenschreiber öffnen", that section's main button once a log
 * is saved. Before, reading the log is the main button and the Fahrtenschreiber only a link (it would open empty).
 */
@Composable
fun LogInsightsEntry(vm: AppViewModel, modifier: Modifier = Modifier, view: LogArchiveView = rememberLogArchiveView(vm)) {
    val loaded = view.loaded
    val fmt = rememberFmt()
    Column(modifier.fillMaxWidth()) {
        val ins = loaded?.insights
        when {
            loaded == null -> Unit
            loaded.snapshot.lines == 0 -> Hint(stringResource(R.string.loginsights_entry_empty))
            ins == null || ins.rides.isEmpty() -> Hint(stringResource(R.string.loginsights_entry_no_rides))
            else -> Hint(
                pluralStringResource(
                    R.plurals.loginsights_entry_summary, ins.rides.size,
                    ins.rides.size, fmt.km(ins.totalDistanceKm), fmt.ride(ins.rides.last().startEpoch),
                ),
            )
        }
        if (view.saved == false) {
            TextLink(stringResource(R.string.loginsights_entry_open), { LogInsightsNav.open() })
        } else {
            Spacer(Modifier.height(S3.s))
            BigButton(stringResource(R.string.loginsights_entry_open), { LogInsightsNav.open() })
        }
    }
}

// --- Screen ---------------------------------------------------------------------------------------

/**
 * The Fahrtenschreiber. Same contract as the other screens plus [onClose] (back and the close button).
 * [source] is for the debug demo only; the app reads the archive on the phone.
 */
@Composable
fun LogInsightsScreen(
    vm: AppViewModel,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = LogInsightsNav::close,
    source: LogTextSource? = null,
) {
    BackHandler(onBack = onClose)
    val archive = rememberArchive()
    val src = source ?: remember(archive) { ArchiveSource(archive) }
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val lab by vm.labState.collectAsStateWithLifecycle()
    val bikeLog by vm.bikeLog.collectAsStateWithLifecycle()
    val loaded = rememberLoaded(vm, src)
    val fmt = rememberFmt()
    val gutter = if (wide) S3.l else S3.gutter
    val ins = loaded?.insights

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = gutter)
            .padding(top = S3.l, bottom = S3.xl),
    ) {
        TitleRow(onClose)
        Spacer(Modifier.height(S3.s))
        Text(stringResource(R.string.loginsights_intro), style = S3.type.body, color = S3.colors.fog)

        Spacer(Modifier.height(S3.xl))
        ArchiveSection(vm, state, bikeLog, loaded, fmt)

        if (ins != null && loaded.snapshot.lines > 0) {
            Spacer(Modifier.height(S3.xl))
            RidesSection(ins, fmt)
        }
        if (ins != null) {
            Spacer(Modifier.height(S3.xl))
            DerateSection(state.battery.takeIf { state.isReady }, ins, fmt)
        }
        if (ins != null && loaded.snapshot.lines > 0) {
            Spacer(Modifier.height(S3.xl))
            ShifterSection(ins, fmt)
            Spacer(Modifier.height(S3.xl))
            WakeSection(ins, lab, fmt)
        }
        Spacer(Modifier.height(S3.xl))
        SeekSection(vm, state, lab, bikeLog, archive, fmt)
    }
}

@Composable
private fun TitleRow(onClose: () -> Unit) {
    val title = stringResource(R.string.loginsights_title)
    val close = stringResource(R.string.loginsights_close)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 460.dp) {
            // Cover screen: the button above, so the one long word keeps its size instead of an ellipsis.
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { QuietButton(close, onClose) }
                Spacer(Modifier.height(S3.s))
                ShellFitText(
                    title, S3.type.display, S3.colors.paint, maxLines = 1, minFontSize = 26.sp,
                    modifier = Modifier.semantics { heading() },
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.s), verticalAlignment = Alignment.CenterVertically) {
                ShellFitText(
                    title, S3.type.display, S3.colors.paint, maxLines = 1, minFontSize = 26.sp,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                QuietButton(close, onClose)
            }
        }
    }
}

// --- Archive --------------------------------------------------------------------------------------

@Composable
private fun ArchiveSection(vm: AppViewModel, state: BikeState, bikeLog: AppViewModel.BikeLog?, loaded: Loaded?, fmt: Fmt) {
    val c = S3.colors
    val progress = state.logProgress
    val reading = progress != null || Op.BIKE_LOG in state.busy
    BikeSectionTitle(stringResource(R.string.loginsights_archive_title))
    Spacer(Modifier.height(S3.s))
    when {
        loaded == null -> Hint(stringResource(R.string.loginsights_loading))
        loaded.snapshot.lines == 0 -> Text(stringResource(R.string.loginsights_archive_empty), style = S3.type.body, color = c.paint)
        else -> {
            val snap = loaded.snapshot
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt.count(snap.lines), style = S3.type.dataLarge, color = c.paint)
                Spacer(Modifier.width(S3.s))
                Text(
                    pluralStringResource(R.plurals.loginsights_archive_lines_unit, snap.lines),
                    style = S3.type.body,
                    color = c.fog,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Hint(pluralStringResource(R.plurals.loginsights_archive_downloads, snap.downloads, snap.downloads))
            val ins = loaded.insights
            if (ins.firstEpoch != null && ins.lastEpoch != null) {
                Hint(stringResource(R.string.loginsights_archive_span, fmt.date(ins.firstEpoch), fmt.date(ins.lastEpoch)))
            }
        }
    }
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.loginsights_archive_hint))
    Spacer(Modifier.height(S3.m))
    if (reading) {
        Text(
            progress?.let { stringResource(R.string.loginsights_archive_reading_percent, (it * 100).roundToInt()) }
                ?: stringResource(R.string.loginsights_archive_reading),
            style = S3.type.data,
            color = c.paint,
        )
        Spacer(Modifier.height(S3.s))
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = c.led, trackColor = c.rib)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.led, trackColor = c.rib)
        }
    } else {
        // One read, in one place: Diagnose, "Protokoll des Bikes". This page shows its progress while it runs.
        Hint(stringResource(R.string.loginsights_archive_read_where))
    }
    if (!reading && bikeLog != null) {
        when {
            bikeLog.failed -> Text(
                stringResource(R.string.loginsights_archive_failed),
                style = S3.type.label, color = c.signal, modifier = Modifier.padding(top = S3.s),
            )
            !bikeLog.complete -> Text(
                stringResource(R.string.loginsights_archive_partial),
                style = S3.type.label, color = c.lamp, modifier = Modifier.padding(top = S3.s),
            )
        }
    }
}

// --- Rides ----------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RidesSection(ins: LogInsights.Insights, fmt: Fmt) {
    val c = S3.colors
    val rides = ins.rides
    BikeSectionTitle(
        stringResource(R.string.loginsights_rides_title),
        trailing = if (rides.isNotEmpty()) {
            { Text(pluralStringResource(R.plurals.loginsights_rides_count, rides.size, rides.size), style = S3.type.label, color = c.fog) }
        } else null,
    )
    Spacer(Modifier.height(S3.s))
    if (rides.isEmpty()) {
        Text(stringResource(R.string.loginsights_rides_none), style = S3.type.body, color = c.paint)
        return
    }
    var selected by rememberSaveable { mutableLongStateOf(rides.last().startEpoch) }
    val ride = rides.firstOrNull { it.startEpoch == selected } ?: rides.last()
    val detail = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    RideDetail(ride, fmt, titleModifier = Modifier.bringIntoViewRequester(detail))

    Spacer(Modifier.height(S3.l))
    Text(stringResource(R.string.loginsights_rides_all), style = S3.type.bodyStrong, color = c.paint)
    Spacer(Modifier.height(S3.xs))
    var shown by rememberSaveable { mutableIntStateOf(6) }
    val newestFirst = remember(rides) { rides.asReversed() }
    newestFirst.take(shown).forEach { r ->
        RideRow(r, r.startEpoch == ride.startEpoch, fmt) {
            selected = r.startEpoch
            scope.launch { delay(50); detail.bringIntoView() }
        }
    }
    if (rides.size > shown) {
        Spacer(Modifier.height(S3.m))
        QuietButton(stringResource(R.string.loginsights_rides_more), { shown += 10 })
    }
}

@Composable
private fun RideRow(r: Ride, selected: Boolean, fmt: Fmt, onSelect: () -> Unit) {
    val c = S3.colors
    val facts = stringResource(
        R.string.loginsights_ride_row,
        r.distanceKm?.let { fmt.km(it) } ?: DASH,
        fmt.duration(r.durationSeconds),
        r.maxSpeedKmh?.let { fmt.kmh(it) } ?: DASH,
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedDot(if (selected) c.led else c.rib)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(fmt.ride(r.startEpoch), style = S3.type.bodyStrong, color = c.paint)
                Text(facts, style = S3.type.caption, color = c.fog)
            }
            Spacer(Modifier.width(S3.s))
            Sparkline(r.rows.map { it.speedKmh ?: Double.NaN }, if (selected) c.led else c.fog)
        }
        Hairline()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideDetail(ride: Ride, fmt: Fmt, titleModifier: Modifier = Modifier) {
    val c = S3.colors
    var scrub by remember(ride.startEpoch) { mutableStateOf<Long?>(null) }
    val onScrub: (Long) -> Unit = { scrub = it }
    val row = scrub?.let { ride.rowAt(it) }
    val at = scrub?.let { fmt.atTime(row?.epoch ?: it) }
    val tick: (Double) -> String = { if (it % 1.0 == 0.0) fmt.whole(it) else fmt.one(it) }

    Text(fmt.ride(ride.startEpoch), style = S3.type.title, color = c.paint, modifier = titleModifier.semantics { heading() })
    Spacer(Modifier.height(S3.m))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(S3.l),
        verticalArrangement = Arrangement.spacedBy(S3.m),
    ) {
        val w = Modifier.widthIn(min = 92.dp)
        Fact(stringResource(R.string.loginsights_fact_distance), ride.distanceKm?.let { fmt.km(it) } ?: DASH, w)
        Fact(stringResource(R.string.loginsights_fact_duration), fmt.duration(ride.durationSeconds), w)
        Fact(stringResource(R.string.loginsights_fact_avg), ride.avgSpeedKmh?.let { fmt.kmh(it) } ?: DASH, w)
        Fact(stringResource(R.string.loginsights_fact_max), ride.maxSpeedKmh?.let { fmt.kmh(it) } ?: DASH, w)
        Fact(stringResource(R.string.loginsights_fact_battery), ride.socUsed?.let { fmt.pct(it) } ?: DASH, w)
        Fact(stringResource(R.string.loginsights_fact_motor_temp), ride.maxMotorTempC?.let { fmt.celsiusOf(it) } ?: DASH, w)
        Fact(stringResource(R.string.loginsights_fact_boost), ride.boostShare?.let { fmt.pct(LogInsights.percent(it)) } ?: DASH, w)
    }
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.loginsights_ride_sampling))
    Spacer(Modifier.height(S3.l))
    Hint(stringResource(R.string.loginsights_chart_scrub_hint))

    val start = ride.startEpoch
    val end = ride.endEpoch
    val rows = ride.rows
    fun pts(f: (dev.steven.s3control.ride.BikeLogParser.Telemetry) -> Double?) = rows.map { TracePoint(it.epoch, f(it) ?: Double.NaN) }

    // Speed, with shift retries and position faults on the line.
    val speedLabel = stringResource(R.string.loginsights_series_speed)
    val speed = remember(ride, c, speedLabel) { TraceSeries(speedLabel, c.led, pts { it.speedKmh }, wash = true) }
    val shiftMarks = remember(ride, c) {
        ride.shifter.filter { it.kind == ShifterEventKind.GEAR_RETRY || it.kind == ShifterEventKind.POSITION_ERROR }
            .map { e -> TraceMark(e.epoch, ride.rowAt(e.epoch)?.speedKmh ?: 0.0, c.signal) }
    }
    Spacer(Modifier.height(S3.l))
    ChartBlock(
        readout = at?.let { stringResource(R.string.loginsights_readout, it, row?.speedKmh?.let(fmt::kmh) ?: DASH) },
    ) {
        TraceChart(
            title = stringResource(R.string.loginsights_chart_speed),
            series = listOf(speed), xStart = start, xEnd = end, scrub = scrub, onScrub = onScrub,
            formatTime = fmt::time, formatTick = tick, marks = shiftMarks,
            markLegend = if (shiftMarks.isNotEmpty()) stringResource(R.string.loginsights_series_shift_retry) else null,
            description = chartDescription(stringResource(R.string.loginsights_chart_speed), ride, fmt, speed.points, fmt::kmh),
        )
    }

    // Battery: main pack and the module's own cell, with the first derate threshold.
    val mainLabel = stringResource(R.string.loginsights_series_main_battery)
    val moduleLabel = stringResource(R.string.loginsights_series_module_battery)
    val battery = remember(ride, c, mainLabel, moduleLabel) {
        listOf(
            TraceSeries(mainLabel, c.led, pts { LogInsights.soc(it)?.toDouble() }),
            TraceSeries(moduleLabel, c.fog, pts { it.liPoSoc }),
        )
    }
    Spacer(Modifier.height(S3.l))
    ChartBlock(
        readout = at?.let {
            stringResource(
                R.string.loginsights_readout_two, it,
                mainLabel, row?.let { r -> LogInsights.soc(r) }?.let(fmt::pct) ?: DASH,
                moduleLabel, row?.liPoSoc?.let { v -> fmt.pct(v.roundToInt()) } ?: DASH,
            )
        },
    ) {
        TraceChart(
            title = stringResource(R.string.loginsights_chart_battery),
            series = battery, xStart = start, xEnd = end, scrub = scrub, onScrub = onScrub,
            formatTime = fmt::time, formatTick = tick, yTop = 100.0,
            refs = listOf(TraceRef(22.0, fmt.pct(22))),
            description = chartDescription(stringResource(R.string.loginsights_chart_battery), ride, fmt, battery[0].points) { fmt.pct(it.roundToInt()) },
        )
    }
    Hint(stringResource(R.string.loginsights_chart_battery_note), Modifier.padding(top = S3.s))

    // Motor current against the firmware's limit (a step line from the log's power-state lines).
    val currentLabel = stringResource(R.string.loginsights_series_motor_current)
    val limitLabel = stringResource(R.string.loginsights_series_limit)
    val current = remember(ride, c, currentLabel, limitLabel) {
        val limitPts = ArrayList<TracePoint>()
        ride.limitBefore?.let { limitPts.add(TracePoint(start, it.currentLimitA)) }
        ride.limits.forEach { limitPts.add(TracePoint(it.epoch, it.currentLimitA)) }
        limitPts.lastOrNull()?.let { limitPts.add(TracePoint(end, it.value)) }
        listOfNotNull(
            TraceSeries(currentLabel, c.led, pts { it.motorCurrent }),
            if (limitPts.isNotEmpty()) TraceSeries(limitLabel, c.signal, limitPts, step = true) else null,
        )
    }
    Spacer(Modifier.height(S3.l))
    ChartBlock(
        readout = at?.let {
            stringResource(
                R.string.loginsights_readout_two, it,
                currentLabel, row?.motorCurrent?.let(fmt::amps) ?: DASH,
                limitLabel, scrub?.let { s -> ride.limitAt(s) }?.let(fmt::amps) ?: DASH,
            )
        },
    ) {
        TraceChart(
            title = stringResource(R.string.loginsights_chart_current),
            series = current, xStart = start, xEnd = end, scrub = scrub, onScrub = onScrub,
            formatTime = fmt::time, formatTick = tick,
            description = chartDescription(stringResource(R.string.loginsights_chart_current), ride, fmt, current[0].points, fmt::amps),
        )
    }
    if (current.size < 2) Hint(stringResource(R.string.loginsights_chart_current_no_limit), Modifier.padding(top = S3.s))

    // Temperatures: battery (drives the limit) in led; motor in fog, the motor controller as its dashed twin.
    val batLabel = stringResource(R.string.loginsights_series_temp_battery)
    val motorLabel = stringResource(R.string.loginsights_series_temp_motor)
    val driverLabel = stringResource(R.string.loginsights_series_temp_driver)
    val temps = remember(ride, c, batLabel, motorLabel, driverLabel) {
        listOf(
            TraceSeries(batLabel, c.led, pts { it.batTempC }),
            TraceSeries(motorLabel, c.fog, pts { LogInsights.motorTemp(it)?.toDouble() }),
            TraceSeries(driverLabel, c.fog, pts { it.driverTempC?.toDouble() }, dashed = true),
        )
    }
    Spacer(Modifier.height(S3.l))
    ChartBlock(
        readout = at?.let {
            stringResource(
                R.string.loginsights_readout_three, it,
                batLabel, row?.batTempC?.let(fmt::celsius) ?: DASH,
                motorLabel, row?.let { r -> LogInsights.motorTemp(r) }?.let(fmt::celsiusOf) ?: DASH,
                driverLabel, row?.driverTempC?.let(fmt::celsiusOf) ?: DASH,
            )
        },
    ) {
        TraceChart(
            title = stringResource(R.string.loginsights_chart_temps),
            series = temps, xStart = start, xEnd = end, scrub = scrub, onScrub = onScrub,
            formatTime = fmt::time, formatTick = tick,
            description = chartDescription(stringResource(R.string.loginsights_chart_temps), ride, fmt, temps[0].points, fmt::celsius),
        )
    }

    Spacer(Modifier.height(S3.l))
    RowsTable(ride, fmt)
}

/** A chart with the value line for the scrub position above it (never only inside the chart). */
@Composable
private fun ChartBlock(readout: String?, chart: @Composable () -> Unit) {
    val c = S3.colors
    Column(Modifier.fillMaxWidth()) {
        chart()
        Text(
            readout ?: " ",
            style = S3.type.caption.merge(TabularDigits),
            color = c.paint,
            modifier = Modifier.padding(top = S3.xs),
        )
    }
}

@Composable
private fun chartDescription(title: String, ride: Ride, fmt: Fmt, points: List<TracePoint>, unit: (Double) -> String): String {
    val values = points.map { it.value }.filter { !it.isNaN() }
    return stringResource(
        R.string.loginsights_chart_cd,
        title,
        fmt.time(ride.startEpoch),
        fmt.time(ride.endEpoch),
        values.minOrNull()?.let(unit) ?: DASH,
        values.maxOrNull()?.let(unit) ?: DASH,
    )
}

/** The chart's table twin: every 30-second row of the ride. */
@Composable
private fun RowsTable(ride: Ride, fmt: Fmt) {
    val c = S3.colors
    var open by rememberSaveable(ride.startEpoch) { mutableStateOf(false) }
    QuietButton(
        stringResource(if (open) R.string.loginsights_rows_hide else R.string.loginsights_rows_show),
        { open = !open },
    )
    if (!open) return
    Spacer(Modifier.height(S3.m))
    val heads = listOf(
        R.string.loginsights_col_time, R.string.loginsights_col_speed, R.string.loginsights_col_soc,
        R.string.loginsights_col_current, R.string.loginsights_col_battemp, R.string.loginsights_col_motortemp,
    ).map { stringResource(it) }
    val style = S3.type.caption.merge(TabularDigits)
    @Composable
    fun line(cells: List<String>, color: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
            cells.forEachIndexed { i, s ->
                Text(
                    s,
                    style = if (bold) style.copy(fontWeight = S3.type.label.fontWeight) else style,
                    color = color,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.weight(if (i == 0) 1.2f else 1f),
                )
            }
        }
    }
    line(heads, c.fog, bold = true)
    Hairline()
    ride.rows.forEach { r ->
        line(
            listOf(
                fmt.timeSeconds(r.epoch),
                r.speedKmh?.let(fmt::one) ?: DASH,
                LogInsights.soc(r)?.toString() ?: DASH,
                r.motorCurrent?.let(fmt::one) ?: DASH,
                r.batTempC?.let(fmt::one) ?: DASH,
                LogInsights.motorTemp(r)?.toString() ?: DASH,
            ),
            c.paint,
        )
    }
    Hairline()
}

// --- Derate ---------------------------------------------------------------------------------------

@Composable
private fun DerateSection(liveSoc: Int?, ins: LogInsights.Insights, fmt: Fmt) {
    val c = S3.colors
    BikeSectionTitle(stringResource(R.string.loginsights_derate_title))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.loginsights_derate_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.l))

    val temp = ins.latestBatTemp
    val soc = liveSoc ?: ins.latestSoc?.second
    val ex = if (soc != null && temp != null) LogInsights.explainDerate(soc, temp.second) else null
    if (ex == null) {
        Text(stringResource(R.string.loginsights_derate_unknown), style = S3.type.body, color = c.paint)
    } else {
        val limit = ex.limitAmps
        val color = when {
            limit == null -> c.fog
            limit == 0 -> c.signal
            limit < 30 -> c.lamp
            else -> c.led
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(limit?.let { fmt.ampsOf(it) } ?: DASH, style = S3.type.display, color = color)
            Spacer(Modifier.width(S3.m))
            Text(
                stringResource(R.string.loginsights_derate_of_max, fmt.ampsOf(30)),
                style = S3.type.body,
                color = c.fog,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(S3.s))
        val socS = fmt.pct(ex.socPercent)
        val tempS = fmt.celsius(ex.tempC)
        val limitS = limit?.let { fmt.ampsOf(it) } ?: DASH
        val sentence = when (ex.cause) {
            DerateCause.FULL -> stringResource(R.string.loginsights_derate_full, socS, tempS, fmt.ampsOf(30))
            DerateCause.EMPTY -> stringResource(R.string.loginsights_derate_empty, socS) +
                (ex.nextSocPercent?.let { " " + stringResource(R.string.loginsights_derate_next_soc, fmt.pct(it), fmt.ampsOf(ex.nextLimitAmps ?: 0)) } ?: "")
            DerateCause.BATTERY -> stringResource(R.string.loginsights_derate_battery, socS, limitS, fmt.ampsOf(30)) +
                (ex.nextSocPercent?.let { " " + stringResource(R.string.loginsights_derate_next_soc, fmt.pct(it), fmt.ampsOf(ex.nextLimitAmps ?: 0)) } ?: "")
            DerateCause.COLD -> stringResource(R.string.loginsights_derate_cold, tempS, limitS, fmt.ampsOf(30)) +
                (ex.tempEdgeC?.let { " " + stringResource(R.string.loginsights_derate_next_warmer, fmt.celsius(it), fmt.ampsOf(ex.tempEdgeLimitAmps ?: 0)) } ?: "")
            DerateCause.HEAT -> stringResource(R.string.loginsights_derate_heat, tempS, limitS, fmt.ampsOf(30)) +
                (ex.tempEdgeC?.let { " " + stringResource(R.string.loginsights_derate_next_cooler, fmt.celsius(it), fmt.ampsOf(ex.tempEdgeLimitAmps ?: 0)) } ?: "")
            DerateCause.BOTH -> stringResource(R.string.loginsights_derate_both, socS, tempS, limitS, fmt.ampsOf(30))
            DerateCause.OUTSIDE -> stringResource(R.string.loginsights_derate_unknown)
        }
        Text(sentence, style = S3.type.body, color = c.paint)
        Spacer(Modifier.height(S3.xs))
        Hint(
            if (liveSoc != null) stringResource(R.string.loginsights_derate_basis_live, fmt.atTime(temp!!.first) + ", " + fmt.date(temp.first))
            else stringResource(R.string.loginsights_derate_basis_log, fmt.atTime(temp!!.first) + ", " + fmt.date(temp.first)),
        )
    }

    Spacer(Modifier.height(S3.l))
    val rowLabels = listOf(
        "≤ " + fmt.celsiusOf(0), "≤ " + fmt.celsiusOf(5), "≤ " + fmt.celsiusOf(60), "> " + fmt.celsiusOf(60),
    )
    val colLabels = listOf(
        "≤ " + fmt.pct(5), "≤ " + fmt.pct(13), "≤ " + fmt.pct(18), "≤ " + fmt.pct(22), "> " + fmt.pct(22),
    )
    val cells = (0..3).map { r -> (0..4).map { col -> LogInsights.derateCell(r, col)?.let { fmt.ampsOf(it) } ?: DASH } }
    Text(stringResource(R.string.loginsights_derate_grid_title), style = S3.type.bodyStrong, color = c.paint)
    Spacer(Modifier.height(S3.s))
    DerateGrid(
        rowLabels = rowLabels,
        columnLabels = colLabels,
        cells = cells,
        current = ex?.let { it.row to it.column },
        currentState = stringResource(R.string.loginsights_derate_now),
        description = stringResource(R.string.loginsights_derate_grid_cd),
    )
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.loginsights_derate_grid_note))

    // What the log recorded: every change of the limit, newest first.
    Spacer(Modifier.height(S3.l))
    Text(stringResource(R.string.loginsights_derate_log_title), style = S3.type.bodyStrong, color = c.paint)
    Spacer(Modifier.height(S3.xs))
    // Only real changes: the firmware also repeats the limit in force at every boot.
    val events = remember(ins) {
        ins.log.derate.filterIndexed { i, e -> i == 0 || e.currentLimitA != ins.log.derate[i - 1].currentLimitA }.asReversed()
    }
    if (events.isEmpty()) {
        Hint(stringResource(R.string.loginsights_derate_log_none))
    } else {
        var shown by rememberSaveable { mutableIntStateOf(6) }
        events.take(shown).forEach { e ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.Top,
            ) {
                Text(fmt.time(e.epoch), style = S3.type.data, color = c.fog, modifier = Modifier.widthIn(min = 76.dp))
                Spacer(Modifier.width(12.dp))
                // The battery temperature from the row nearest this change: a cold battery is often the real
                // cause, so showing it next to the limit stops the derate reading as a contradiction.
                val nearTemp = remember(ins, e.epoch) {
                    ins.log.validTelemetry.filter { it.batTempC != null }.minByOrNull { abs(it.epoch - e.epoch) }?.batTempC
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (e.socPercent >= 0) stringResource(R.string.loginsights_derate_log_row, fmt.amps(e.currentLimitA), fmt.pct(e.socPercent))
                        else stringResource(R.string.loginsights_derate_log_row_nosoc, fmt.amps(e.currentLimitA)),
                        style = S3.type.body,
                        color = if (e.currentLimitA <= 0.0) c.signal else c.paint,
                    )
                    Hint(fmt.date(e.epoch))
                    nearTemp?.let { Hint(stringResource(R.string.labx_derate_bat_temp, fmt.celsius(it))) }
                    // The raw firmware state token on its own, quieter line.
                    Hint(stringResource(R.string.labx_firmware_text, e.powerState))
                }
            }
            Hairline()
        }
        if (events.size > shown) {
            Spacer(Modifier.height(S3.m))
            QuietButton(stringResource(R.string.loginsights_more), { shown += 10 })
        }
    }
}

// --- Shifter --------------------------------------------------------------------------------------

@Composable
private fun ShifterSection(ins: LogInsights.Insights, fmt: Fmt) {
    val c = S3.colors
    val h = ins.shifter
    BikeSectionTitle(stringResource(R.string.loginsights_shift_title))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.loginsights_shift_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.m))
    val rideTime = fmt.duration(h.rideSeconds)
    Text(
        when (h.verdict) {
            Verdict.OK -> stringResource(R.string.loginsights_shift_summary_ok, rideTime)
            Verdict.ATTENTION -> pluralStringResource(R.plurals.loginsights_shift_summary_retries, h.retries, rideTime, h.retries)
            Verdict.PROBLEM -> pluralStringResource(R.plurals.loginsights_shift_summary_position, h.positionErrors, h.positionErrors)
        },
        style = S3.type.bodyStrong,
        color = when (h.verdict) {
            Verdict.OK -> c.paint
            Verdict.ATTENTION -> c.paint
            Verdict.PROBLEM -> c.signal
        },
    )
    Spacer(Modifier.height(S3.s))
    CheckRow(
        label = stringResource(R.string.loginsights_shift_retries),
        value = h.retries.toString(),
        health = if (h.retries > 0) Health.ATTENTION else Health.OK,
        hint = stringResource(R.string.loginsights_shift_retries_hint),
        note = h.retriesPerHour?.takeIf { h.retries > 0 }?.let { stringResource(R.string.loginsights_shift_per_hour, fmt.one(it)) },
    )
    CheckRow(
        label = stringResource(R.string.loginsights_shift_position),
        value = h.positionErrors.toString(),
        health = if (h.positionErrors > 0) Health.PROBLEM else Health.OK,
        hint = stringResource(R.string.loginsights_shift_position_hint),
    )
    CheckRow(
        label = stringResource(R.string.loginsights_shift_off),
        value = h.shifterOff.toString(),
        health = Health.OK,
        hint = stringResource(R.string.loginsights_shift_off_hint),
    )
    CheckRow(
        label = stringResource(R.string.loginsights_shift_zero),
        value = h.zeroCal.toString(),
        health = Health.OK,
        hint = stringResource(R.string.loginsights_shift_zero_hint),
    )
    if (h.retries > 0) {
        Spacer(Modifier.height(S3.l))
        Text(stringResource(R.string.loginsights_shift_by_gear), style = S3.type.bodyStrong, color = c.paint)
        Spacer(Modifier.height(S3.s))
        val gear = stringResource(R.string.loginsights_shift_gear)
        GearBars(
            counts = h.retriesByGear,
            gearLabel = { String.format(fmt.locale, gear, it) },
            color = c.lamp,
            description = stringResource(
                R.string.loginsights_shift_by_gear_cd, h.retriesByGear[0], h.retriesByGear[1], h.retriesByGear[2], h.retriesByGear[3],
            ),
        )
    }
    if (h.entries.isNotEmpty()) {
        Spacer(Modifier.height(S3.l))
        Text(stringResource(R.string.loginsights_shift_events), style = S3.type.bodyStrong, color = c.paint)
        Spacer(Modifier.height(S3.xs))
        var shown by rememberSaveable { mutableIntStateOf(8) }
        val list = remember(h) { h.entries.asReversed() }
        list.take(shown).forEach { entry ->
            val e = entry.event
            val text = when (e.kind) {
                ShifterEventKind.GEAR_RETRY -> stringResource(
                    R.string.loginsights_shift_event_retry,
                    e.gear?.toString() ?: DASH,
                    e.reported?.toString() ?: DASH,
                )
                ShifterEventKind.POSITION_ERROR -> stringResource(R.string.loginsights_shift_event_position)
                ShifterEventKind.SHIFTER_OFF -> stringResource(R.string.loginsights_shift_event_off)
                ShifterEventKind.ZERO_CAL -> stringResource(R.string.loginsights_shift_event_zero)
            }
            val where = entry.speedKmh?.let {
                if (it < LogInsights.MOVING_KMH) stringResource(R.string.loginsights_shift_event_parked)
                else stringResource(R.string.loginsights_shift_event_speed, fmt.kmh(it))
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.Top,
            ) {
                Text(fmt.time(e.epoch), style = S3.type.data, color = c.fog, modifier = Modifier.widthIn(min = 76.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text,
                        style = S3.type.body,
                        color = if (e.kind == ShifterEventKind.POSITION_ERROR) c.signal else c.paint,
                    )
                    Hint(listOfNotNull(fmt.date(e.epoch), where).joinToString(", "))
                }
            }
            Hairline()
        }
        if (list.size > shown) {
            Spacer(Modifier.height(S3.m))
            QuietButton(stringResource(R.string.loginsights_more), { shown += 20 })
        }
    } else {
        Spacer(Modifier.height(S3.s))
        Hint(stringResource(R.string.loginsights_shift_none))
    }
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.loginsights_shift_caveat))
}

// --- Wake timeline --------------------------------------------------------------------------------

@Composable
private fun wakeLabel(source: WakeSource): String = stringResource(
    when (source) {
        WakeSource.NONE -> R.string.loginsights_wake_none
        WakeSource.RTC -> R.string.loginsights_wake_rtc
        WakeSource.BUTTON_1 -> R.string.loginsights_wake_button1
        WakeSource.BUTTON_2 -> R.string.loginsights_wake_button2
        WakeSource.BLE -> R.string.loginsights_wake_ble
        WakeSource.MEMS -> R.string.loginsights_wake_mems
        WakeSource.CHARGER -> R.string.loginsights_wake_charger
        WakeSource.WHEEL -> R.string.loginsights_wake_wheel
        WakeSource.KEY_IN -> R.string.loginsights_wake_key
        WakeSource.KICKLOCK -> R.string.loginsights_wake_kicklock
        WakeSource.COLD_BOOT -> R.string.loginsights_wake_cold
        WakeSource.COUNTER -> R.string.loginsights_wake_counter_only
        WakeSource.OTHER -> R.string.loginsights_wake_other
    },
)

/** One line of the timeline: a wake, or a deep-sleep report. */
private class TimelineItem(val epoch: Long, val wake: LogInsights.Wake?, val sleep: LogInsights.DeepSleep?)

@Composable
private fun WakeSection(ins: LogInsights.Insights, lab: LabState, fmt: Fmt) {
    val c = S3.colors
    val tl = ins.wakes
    BikeSectionTitle(stringResource(R.string.loginsights_wake_title))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.loginsights_wake_intro), style = S3.type.body, color = c.paint)
    lab.clockSkewSeconds?.takeIf { abs(it) >= 120 }?.let { skew ->
        Spacer(Modifier.height(S3.s))
        Text(
            stringResource(R.string.loginsights_wake_clock_skew, fmt.duration(abs(skew))),
            style = S3.type.label,
            color = c.lamp,
        )
    }
    Spacer(Modifier.height(S3.m))
    if (tl.wakes.isEmpty() && tl.deepSleeps.isEmpty()) {
        Hint(stringResource(R.string.loginsights_wake_none_found))
        Spacer(Modifier.height(S3.s))
        Hint(stringResource(R.string.loginsights_wake_caveat))
        return
    }

    // The last seven days with anything on them, newest on top.
    val dayCd = stringResource(R.string.loginsights_wake_day_cd)
    val lanes = remember(ins, fmt) {
        val days = (tl.wakes.map { fmt.localDate(it.epoch) } + ins.rides.map { fmt.localDate(it.startEpoch) })
            .distinct().sortedDescending().take(7)
        days.map { d ->
            val rides = ins.rides.filter { fmt.localDate(it.startEpoch) == d }
            val wakes = tl.wakes.filter { fmt.localDate(it.epoch) == d }
            val moved = wakes.filter { it.movedWithoutRide }
            DayLane(
                label = fmt.dayShort(d),
                rides = rides.map { r ->
                    val a = fmt.secondOfDay(r.startEpoch)
                    a to minOf(86_399, a + r.durationSeconds.toInt())
                },
                wakes = wakes.filterNot { it.movedWithoutRide }.map { fmt.secondOfDay(it.epoch) },
                moved = moved.map { fmt.secondOfDay(it.epoch) },
                description = String.format(fmt.locale, dayCd, fmt.day(d), rides.size, wakes.size, moved.size),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(S3.m)) {
        LegendKey(stringResource(R.string.loginsights_wake_legend_ride), c.led, bar = true)
        LegendKey(stringResource(R.string.loginsights_wake_legend_wake), c.fog, dot = true)
    }
    Spacer(Modifier.height(S3.xs))
    LegendKey(stringResource(R.string.loginsights_wake_legend_moved), c.signal, dot = true)
    Spacer(Modifier.height(S3.s))
    DayStrip(
        lanes = lanes,
        hourLabels = listOf("0", "6", "12", "18", "24"),
        rideColor = c.led,
        wakeColor = c.fog,
        movedColor = c.signal,
    )

    // The list, by day, newest first.
    val items = remember(tl) {
        (tl.wakes.map { TimelineItem(it.epoch, it, null) } + tl.deepSleeps.map { TimelineItem(it.epoch, null, it) })
            .sortedByDescending { it.epoch }
    }
    val byDay = remember(items, fmt) { items.groupBy { fmt.localDate(it.epoch) } }
    var days by rememberSaveable { mutableIntStateOf(3) }
    byDay.entries.take(days).forEach { (day, list) ->
        Spacer(Modifier.height(S3.l))
        Text(fmt.day(day), style = S3.type.bodyStrong, color = c.paint, modifier = Modifier.semantics { heading() })
        list.forEach { item -> TimelineRow(item, fmt) }
    }
    if (byDay.size > days) {
        Spacer(Modifier.height(S3.m))
        QuietButton(stringResource(R.string.loginsights_wake_more), { days += 5 })
    }
    Spacer(Modifier.height(S3.m))
    Hint(stringResource(R.string.loginsights_wake_caveat))
}

@Composable
private fun TimelineRow(item: TimelineItem, fmt: Fmt) {
    val c = S3.colors
    val w = item.wake
    val s = item.sleep
    val (title, color) = when {
        w != null && w.movedWithoutRide -> stringResource(R.string.loginsights_wake_moved, wakeLabel(w.source)) to c.signal
        w != null -> wakeLabel(w.source) to c.paint
        else -> stringResource(R.string.loginsights_wake_deepsleep) to c.lamp
    }
    val details = buildList {
        if (w != null) {
            // A shake without a ride already says so in its red title.
            w.ride?.let { ride -> add(stringResource(R.string.loginsights_wake_ride_after, ride.distanceKm?.let(fmt::km) ?: DASH)) }
            if (w.counter != null && w.counterOf != null) add(stringResource(R.string.loginsights_wake_counter, w.counter, w.counterOf))
        }
        if (s != null) {
            add(
                stringResource(
                    R.string.loginsights_wake_deepsleep_detail,
                    s.soc?.takeIf { it >= 0 }?.let(fmt::pct) ?: DASH,
                    s.wakeups?.toString() ?: DASH,
                ),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Text(fmt.time(item.epoch), style = S3.type.data, color = c.fog, modifier = Modifier.widthIn(min = 76.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = S3.type.body, color = color)
            if (details.isNotEmpty()) Hint(details.joinToString(", "))
            // The raw firmware token on its own, quieter line, so it reads as a reference, not a fact.
            w?.token?.takeIf { it.isNotBlank() }?.let { Hint(stringResource(R.string.labx_firmware_text, it)) }
            if (s != null) Hint(stringResource(R.string.loginsights_wake_deepsleep_hint))
        }
    }
    Hairline()
}

// --- Log seek -------------------------------------------------------------------------------------

private sealed interface SeekResult {
    data class Done(val newest: Long) : SeekResult
    data class Failed(val message: String) : SeekResult
}

@Composable
private fun SeekSection(
    vm: AppViewModel,
    state: BikeState,
    lab: LabState,
    bikeLog: AppViewModel.BikeLog?,
    archive: BikeLogArchive,
    fmt: Fmt,
) {
    val c = S3.colors
    BikeSectionTitle(stringResource(R.string.loginsights_seek_title))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.loginsights_seek_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.xs))
    Text(stringResource(R.string.loginsights_seek_warning), style = S3.type.body, color = c.signal)
    Spacer(Modifier.height(S3.m))

    // A clock for the age check: every 5 s is enough for a 10-minute window.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(5_000) }
    }
    val text = bikeLog?.takeIf { !it.failed }?.text
    // The file is written in the background after the download: look again until it is there.
    val savedAt = bikeLog?.atMillis
    val saved by produceState(false, text, savedAt) {
        value = false
        if (text.isNullOrEmpty() || savedAt == null) return@produceState
        repeat(20) {
            val ok = withContext(Dispatchers.IO) { archive.holds(text, savedAt) }
            if (ok) { value = true; return@produceState }
            delay(1_500)
        }
    }
    val newest = remember(text) { text?.let { LogInsights.newestEpoch(it) } }
    val guard = LogInsights.SeekGuard(
        linkReady = state.isReady,
        complete = bikeLog?.complete == true && !text.isNullOrEmpty(),
        saved = saved,
        ageSeconds = bikeLog?.let { (now - it.atMillis) / 1000 },
        sameConnection = bikeLog != null && state.connectedAtMillis != null && state.connectedAtMillis <= bikeLog.atMillis,
        newestEpoch = newest,
    )

    fun ok(b: Boolean) = if (b) Health.OK else Health.PROBLEM
    val yes = stringResource(R.string.loginsights_yes)
    val no = stringResource(R.string.loginsights_no)
    CheckRow(
        label = stringResource(R.string.loginsights_seek_check_link),
        value = if (guard.linkReady) yes else no,
        health = ok(guard.linkReady),
    )
    CheckRow(
        label = stringResource(R.string.loginsights_seek_check_complete),
        value = if (guard.complete) yes else no,
        health = ok(guard.complete),
        hint = if (!guard.complete) stringResource(R.string.loginsights_seek_check_complete_hint) else null,
    )
    CheckRow(
        label = stringResource(R.string.loginsights_seek_check_saved),
        value = if (guard.saved) yes else no,
        health = ok(guard.saved),
        hint = stringResource(R.string.loginsights_seek_check_saved_hint),
    )
    CheckRow(
        label = stringResource(R.string.loginsights_seek_check_fresh),
        value = guard.ageSeconds?.let { fmt.duration(it.coerceAtLeast(0)) } ?: DASH,
        health = ok(guard.fresh),
        hint = stringResource(R.string.loginsights_seek_check_fresh_hint, LogInsights.SEEK_MAX_AGE_SECONDS / 60),
    )
    CheckRow(
        label = stringResource(R.string.loginsights_seek_check_newest),
        value = newest?.let { fmt.timeSeconds(it) } ?: DASH,
        health = ok(guard.clockSet),
        hint = if (newest != null && !guard.clockSet) stringResource(R.string.loginsights_seek_check_clock_hint)
        else stringResource(R.string.loginsights_seek_check_newest_hint),
    )

    var confirm by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf<Long?>(null) }
    var sawBusy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SeekResult?>(null) }
    val busy = LabOp.LOGSEEK in lab.busy
    LaunchedEffect(busy, lab.error, requested) {
        val target = requested ?: return@LaunchedEffect
        if (busy) { sawBusy = true; return@LaunchedEffect }
        if (lab.error != null) {
            result = SeekResult.Failed(lab.error)
            requested = null
        } else if (sawBusy) {
            result = SeekResult.Done(target + 1)
            requested = null
        }
    }

    Spacer(Modifier.height(S3.l))
    val target = guard.target
    BigButton(
        text = when {
            busy -> stringResource(R.string.loginsights_seek_busy)
            newest != null -> stringResource(R.string.loginsights_seek_button, fmt.timeSeconds(newest))
            else -> stringResource(R.string.loginsights_seek_button_plain)
        },
        onClick = { result = null; confirm = true },
        enabled = target != null && !busy && result !is SeekResult.Done,
        color = c.signal,
        onColor = c.onSignal,
    )
    if (target == null && !busy) Hint(stringResource(R.string.loginsights_seek_blocked), Modifier.padding(top = S3.s))
    when (val r = result) {
        is SeekResult.Done -> Text(
            stringResource(R.string.loginsights_seek_done, fmt.timeSeconds(r.newest)),
            style = S3.type.label, color = c.led, modifier = Modifier.padding(top = S3.s),
        )
        is SeekResult.Failed -> Text(r.message, style = S3.type.label, color = c.signal, modifier = Modifier.padding(top = S3.s))
        null -> Unit
    }

    if (confirm && target != null && newest != null) {
        val lines = remember(text) { text?.count { it == '\n' } ?: 0 }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.loginsights_seek_confirm_title), style = S3.type.section, color = c.paint) },
            text = {
                Text(
                    stringResource(R.string.loginsights_seek_confirm_text, fmt.timeSeconds(newest), fmt.date(newest), fmt.count(lines)),
                    style = S3.type.body,
                    color = c.paint,
                )
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    sawBusy = false
                    vm.lab.clearError()
                    requested = target
                    vm.lab.seekLog(target)
                }) { Text(stringResource(R.string.loginsights_seek_confirm), style = S3.type.bodyStrong, color = c.signal) }
            },
            dismissButton = {
                TextButton({ confirm = false }) {
                    Text(stringResource(R.string.loginsights_seek_cancel), style = S3.type.bodyStrong, color = c.paint)
                }
            },
        )
    }
}
