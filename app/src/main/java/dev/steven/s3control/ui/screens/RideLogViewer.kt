package dev.steven.s3control.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.Faults
import dev.steven.s3control.ride.BikeLogArchive
import dev.steven.s3control.ride.RideLogReader
import dev.steven.s3control.ride.RideLogReader.RideLog
import dev.steven.s3control.ride.RideLogReader.Signal
import dev.steven.s3control.ui.components.BikeSectionTitle
import dev.steven.s3control.ui.components.ChartButton
import dev.steven.s3control.ui.components.OutburstBlockWhile
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.DASH
import dev.steven.s3control.ui.components.Fact
import dev.steven.s3control.ui.components.Hairline
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.LedDot
import dev.steven.s3control.ui.components.LegendKey
import dev.steven.s3control.ui.components.LogGlyphs
import dev.steven.s3control.ui.components.MIN_WINDOW_MS
import dev.steven.s3control.ui.components.MatrixDisplay
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.MatrixGlyphButton
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.RideTimeline
import dev.steven.s3control.ui.components.ShellFitText
import dev.steven.s3control.ui.components.ShellLed
import dev.steven.s3control.ui.components.shellFitLabels
import dev.steven.s3control.ui.components.TimelineClock
import dev.steven.s3control.ui.components.TimelineText
import dev.steven.s3control.ui.components.appLocale
import dev.steven.s3control.ui.components.bikeFieldColors
import dev.steven.s3control.ui.components.windowShowing
import dev.steven.s3control.ui.components.zoomWindow
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The log viewer: one ride log (the CSV of ride/RideRecorder.kt, read by ride/RideLogReader.kt) with a
 * summary, a timeline of speed, power, the shipping-rule signals and the link, and its events; and the
 * bike's own log as plain text with line numbers, local times and search. Not everyone can open a CSV or
 * TXT file, so both are readable here. Opens from Diagnose ("Ansehen") through [LogViewNav]; AppRoot
 * shows it over the whole window, and on the inner screen it splits into two panes at the fold.
 */

// --- Navigation -----------------------------------------------------------------------------------

/** Which log is open. AppRoot shows [LogViewer] over everything while [target] is set; back closes it. */
object LogViewNav {
    sealed interface Target {
        /** A ride log file (`filesDir/logs/ride-….csv`). */
        data class Ride(val path: String) : Target

        /** The bike's own log: a saved download, or (null) the one read in this session. */
        data class BikeLog(val path: String?) : Target
    }

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    fun openRide(file: File) { _target.value = Target.Ride(file.absolutePath) }
    fun openBikeLog(file: File? = null) { _target.value = Target.BikeLog(file?.absolutePath) }
    fun close() { _target.value = null }

    private val _changes = MutableStateFlow(0)

    /** Counts the recordings deleted from the viewer, so the list in Diagnose reads its files again. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    internal fun deleted() { _changes.value = _changes.value + 1 }

    /**
     * What was read last, kept for the life of the process: switching Night and Daylight recreates the
     * activity, and the page must come back at once (and at its scroll position) instead of reading a
     * 12 MB file again. Keyed by path, length and time of change, so a file that grew is read anew.
     */
    private val rides = Lru<RideLog>(3)
    private val texts = Lru<TextDoc>(2)

    internal fun fileKey(file: File): String = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    internal fun cachedRide(key: String): RideLog? = rides[key]
    internal fun keepRide(key: String, log: RideLog) = rides.put(key, log)
    internal fun cachedText(key: String): TextDoc? = texts[key]
    internal fun keepText(key: String, doc: TextDoc) = texts.put(key, doc)

    private class Lru<V>(private val max: Int) {
        private val map = LinkedHashMap<String, V>(8, 0.75f, true)
        @Synchronized operator fun get(key: String): V? = map[key]
        @Synchronized fun put(key: String, value: V) {
            map[key] = value
            while (map.size > max) map.remove(map.keys.first())
        }
    }
}

/**
 * The viewer for [target]. [wide] is the window at 600 dp or more; [layoutInfo] places the split on a
 * vertical fold. The screen draws edge to edge inside the insets AppRoot gives it.
 */
@Composable
fun LogViewer(
    vm: AppViewModel,
    target: LogViewNav.Target,
    wide: Boolean,
    layoutInfo: WindowLayoutInfo?,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = LogViewNav::close,
) {
    BackHandler(onBack = onClose)
    key(target) {
        when (target) {
            is LogViewNav.Target.Ride -> RideLogViewer(vm, File(target.path), wide, layoutInfo, modifier, onClose)
            is LogViewNav.Target.BikeLog -> BikeLogViewer(vm, target.path?.let(::File), wide, layoutInfo, modifier, onClose)
        }
    }
}

// --- Formatting -----------------------------------------------------------------------------------

/**
 * Times, numbers and units in the app language. [days]: the log runs over more than one date, so every
 * moment carries its day ("Do. 15.10., 17:21:02 Uhr") and the axis marks the midnights.
 */
@Stable
private data class LogFmt(
    val locale: Locale,
    val zone: ZoneId,
    private val timeF: DateTimeFormatter,
    private val timeShortF: DateTimeFormatter,
    private val dateTimeF: DateTimeFormatter,
    private val dayTimeF: DateTimeFormatter,
    private val dayTimeSecF: DateTimeFormatter,
    private val dayF: DateTimeFormatter,
    private val dayHeaderF: DateTimeFormatter,
    private val headingF: DateTimeFormatter,
    private val atP: String,
    private val atMomentP: String,
    private val atDayMomentP: String,
    private val kmhP: String,
    private val kmP: String,
    private val pctP: String,
    private val secP: String,
    private val minSecP: String,
    private val hourMinP: String,
    val days: Boolean = false,
) {
    private fun zdt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    fun localDate(ms: Long): LocalDate = zdt(ms).toLocalDate()
    fun time(ms: Long): String = timeF.format(zdt(ms))
    fun timeShort(ms: Long): String = timeShortF.format(zdt(ms))
    /** "07:52:14", or with the day across dates. */
    fun clock(ms: Long): String = if (days) dayTimeSecF.format(zdt(ms)) else time(ms)
    /** "07:52:14 Uhr" (a moment standing alone, as in the readout). */
    fun atTime(ms: Long): String = String.format(locale, atP, clock(ms))
    /** "um 07:52:14 Uhr", or "am Do. 15.10. um 07:52:14 Uhr" across dates (inside a sentence). */
    fun at(ms: Long, withDay: Boolean = days): String =
        if (withDay) String.format(locale, atDayMomentP, dayF.format(zdt(ms)), time(ms)) else String.format(locale, atMomentP, time(ms))
    fun dateTime(ms: Long): String = dateTimeF.format(zdt(ms)).replaceFirstChar { it.titlecase(locale) }
    fun dayTime(ms: Long): String = dayTimeF.format(zdt(ms))
    fun day(ms: Long): String = dayF.format(zdt(ms))
    fun dayHeader(ms: Long): String = dayHeaderF.format(zdt(ms)).replaceFirstChar { it.titlecase(locale) }
    /** The heading of a recording: "Sa. 17.10., 15:37 Uhr". */
    fun heading(ms: Long): String = headingF.format(zdt(ms)).replaceFirstChar { it.titlecase(locale) }
    fun kmh(k: Int): String = String.format(locale, kmhP, k.toString())
    fun kmh(v: Double): String = String.format(locale, kmhP, String.format(locale, "%.1f", v))
    fun km(v: Double): String = String.format(locale, kmP, String.format(locale, if (v < 1.0) "%.2f" else "%,.1f", v))
    fun pct(n: Int): String = String.format(locale, pctP, n.toString())
    fun count(n: Int): String = String.format(locale, "%,d", n)
    /** "34 s", "22 min 16 s", "1 h 12 min": a duration never reads like a clock time. */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        if (s < 60) return String.format(locale, secP, s)
        if (s < 3600) return String.format(locale, minSecP, s / 60, s % 60)
        val min = (s + 30) / 60
        return String.format(locale, hourMinP, min / 60, min % 60)
    }

    /** This format for a log from [startMs] to [endMs]: with days when they fall on different dates. */
    fun forSpan(startMs: Long?, endMs: Long?): LogFmt {
        val d = startMs != null && endMs != null && localDate(startMs) != localDate(endMs)
        return if (d == days) this else copy(days = d)
    }
}

@Composable
private fun rememberLogFmt(): LogFmt {
    val locale = appLocale()
    val patterns = listOf(
        stringResource(R.string.logview_fmt_time),
        stringResource(R.string.logview_fmt_time_short),
        stringResource(R.string.logview_fmt_date_time),
        stringResource(R.string.logview_fmt_day_time),
        stringResource(R.string.logview_fmt_day_time_sec),
        stringResource(R.string.logview_fmt_day),
        stringResource(R.string.logview_fmt_day_header),
        stringResource(R.string.logview_fmt_heading),
    )
    val units = listOf(
        stringResource(R.string.logview_at_time),
        stringResource(R.string.logview_at_moment),
        stringResource(R.string.logview_at_day_moment),
        stringResource(R.string.logview_unit_kmh),
        stringResource(R.string.logview_unit_km),
        stringResource(R.string.logview_unit_pct),
        stringResource(R.string.logview_unit_sec),
        stringResource(R.string.logview_unit_min_sec),
        stringResource(R.string.logview_unit_h_min),
    )
    return remember(locale, patterns, units) {
        val f = patterns.map { DateTimeFormatter.ofPattern(it, locale) }
        LogFmt(
            locale, ZoneId.systemDefault(), f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7],
            units[0], units[1], units[2], units[3], units[4], units[5], units[6], units[7], units[8],
        )
    }
}

private val FILE_STAMP = Regex("""(\d{8})-(\d{6})""")
private val FILE_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/** The moment in a log file name ("ride-20261017-075214.csv", "bikelog-…"), or null. */
internal fun logFileStamp(name: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
    FILE_STAMP.find(name)?.let { m ->
        runCatching {
            LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], FILE_STAMP_FORMAT).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

// --- Shared pieces --------------------------------------------------------------------------------

/**
 * Title with its buttons. Narrow: the buttons above, so the one long word keeps its size; wide: in
 * one row. [buttons] null leaves them out (the two-pane ride view puts them over the right pane).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerTitle(title: String, buttons: (@Composable RowScope.() -> Unit)?, style: TextStyle = S3.type.display) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (buttons == null || maxWidth < 460.dp) {
            Column(Modifier.fillMaxWidth()) {
                if (buttons != null) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(S3.s, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(S3.s),
                    ) { buttons() }
                    Spacer(Modifier.height(S3.s))
                }
                ShellFitText(
                    title, style, S3.colors.paint, maxLines = 2, minFontSize = 26.sp,
                    modifier = Modifier.semantics { heading() },
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.s), verticalAlignment = Alignment.CenterVertically) {
                ShellFitText(
                    title, style, S3.colors.paint, maxLines = 2, minFontSize = 26.sp,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                buttons()
            }
        }
    }
}

/** Left and right pane widths and the hinge gap: split at a vertical fold, else in the middle. */
private fun viewerSplit(info: WindowLayoutInfo?, originXPx: Float, total: Dp, density: Density): Pair<Dp, Dp> {
    val fallback = total / 2 to 0.dp
    val fold = info?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }
        ?: return fallback
    val start = with(density) { (fold.bounds.left - originXPx).toDp() }
    val end = with(density) { (fold.bounds.right - originXPx).toDp() }
    if (end < start || start < total * 0.25f || end > total * 0.75f) return fallback
    return start to (end - start)
}

/** Two panes split like the shell's: [left] and [right] each pad S3.l on the hinge side. */
@Composable
private fun TwoPanes(
    layoutInfo: WindowLayoutInfo?,
    modifier: Modifier,
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    var originX by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { originX = it.positionInWindow().x }) {
        val (leftWidth, hinge) = viewerSplit(layoutInfo, originX, maxWidth, density)
        Row(Modifier.fillMaxSize()) {
            left(Modifier.width(leftWidth).fillMaxHeight())
            Spacer(Modifier.width(hinge))
            right(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

private fun shareFile(context: Context, file: File, mime: String, subject: String, chooserTitle: String): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}.isSuccess

// --- Ride log -------------------------------------------------------------------------------------

private sealed interface RideLoad {
    data object Loading : RideLoad
    data object Failed : RideLoad
    class Done(val log: RideLog) : RideLoad
}

/** What the event list shows. A Vorfall is an alert or an error; the rider's marks are counted on their own. */
private enum class ListFilter { ALL, INCIDENTS, MARKS }

/**
 * What the person chose: the cursor moment, the event (by file line), the list filter and the visible
 * window of the timeline (null: the whole log).
 */
@Stable
private class RideViewState(cursor: Long?, chosen: Int?, filter: ListFilter, viewStart: Long?, viewEnd: Long?) {
    var cursor by mutableStateOf(cursor)
    var chosen by mutableStateOf(chosen)
    var filter by mutableStateOf(filter)
    var viewStart by mutableStateOf(viewStart)
    var viewEnd by mutableStateOf(viewEnd)

    /** The visible window within [log], the whole log when none is set (or the log changed under it). */
    fun window(log: RideLog): Pair<Long, Long> {
        val s = log.startMillis ?: return 0L to 1L
        val e = log.endMillis ?: return 0L to 1L
        val a = viewStart
        val b = viewEnd
        if (a == null || b == null || b <= a || a >= e || b <= s) return s to e
        return a.coerceAtLeast(s) to b.coerceAtMost(e)
    }

    val zoomed: Boolean get() = viewStart != null

    fun setWindow(log: RideLog, a: Long, b: Long) {
        val s = log.startMillis ?: return
        val e = log.endMillis ?: return
        if (a <= s && b >= e) {
            viewStart = null
            viewEnd = null
        } else {
            viewStart = a
            viewEnd = b
        }
    }

    /** Puts the cursor (and the chosen event) on [t], moving the window so that it shows it. */
    fun pick(log: RideLog, t: Long, line: Int?) {
        cursor = t
        chosen = line
        if (zoomed) {
            val (a, b) = window(log)
            val (s, e) = windowShowing(log.startMillis ?: return, log.endMillis ?: return, a, b, t)
            setWindow(log, s, e)
        }
    }

    companion object {
        val Saver = listSaver<RideViewState, Any?>(
            save = { listOf(it.cursor, it.chosen, it.filter.ordinal, it.viewStart, it.viewEnd) },
            restore = {
                RideViewState(it[0] as Long?, it[1] as Int?, ListFilter.entries[it[2] as Int], it[3] as Long?, it[4] as Long?)
            },
        )
    }
}

/** How often a file that is still being written is read again: less often the larger it is. */
private fun liveReloadMs(bytes: Long): Long = when {
    bytes < 2L * 1024 * 1024 -> 10_000L
    bytes < 8L * 1024 * 1024 -> 30_000L
    else -> 60_000L
}

@Composable
private fun RideLogViewer(
    vm: AppViewModel,
    file: File,
    wide: Boolean,
    layoutInfo: WindowLayoutInfo?,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val rec by vm.recorder.collectAsStateWithLifecycle()
    val live = rec.active && rec.file?.absolutePath == file.absolutePath
    // After a recreation (Night/Daylight, rotation) the page comes back from the cache at once.
    val initial = remember(file) { LogViewNav.cachedRide(LogViewNav.fileKey(file))?.let { RideLoad.Done(it) } ?: RideLoad.Loading }
    val coldStart = remember(file) { initial is RideLoad.Loading }
    var reloadMs by remember { mutableStateOf(10_000L) }
    val load by produceState<RideLoad>(initial, file, live) {
        while (true) {
            val next = withContext(Dispatchers.IO) {
                try {
                    val k = LogViewNav.fileKey(file)
                    val cached = LogViewNav.cachedRide(k)
                    if (cached != null) {
                        RideLoad.Done(cached)
                    } else {
                        RideLogReader.read(file).also { LogViewNav.keepRide(k, it) }.let { RideLoad.Done(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RideLoad.Failed
                } catch (e: OutOfMemoryError) {
                    RideLoad.Failed
                }
            }
            // While the file grows, a failed re-read keeps what is already shown; an unchanged file changes nothing.
            val cur = value
            val same = next is RideLoad.Done && cur is RideLoad.Done && cur.log === next.log
            if (!same && (next is RideLoad.Done || cur !is RideLoad.Done)) value = next
            if (!live) break
            reloadMs = liveReloadMs(withContext(Dispatchers.IO) { file.length() })
            delay(reloadMs)
        }
    }
    val baseFmt = rememberLogFmt()
    val log = (load as? RideLoad.Done)?.log
    val fmt = remember(baseFmt, log) { baseFmt.forSpan(log?.startMillis, log?.endMillis) }
    val st = rememberSaveable(file.path, saver = RideViewState.Saver) { RideViewState(null, null, ListFilter.ALL, null, null) }
    val shareTitle = stringResource(R.string.logview_ride_share_title)
    val share: () -> Unit = { runCatching { context.startActivity(Intent.createChooser(vm.shareRideLog(file), shareTitle)) } }
    var askDelete by remember { mutableStateOf(false) }
    val buttons: @Composable RowScope.() -> Unit = {
        QuietButton(stringResource(R.string.logview_share), share)
        // The file being written cannot go; stop the logger first.
        QuietButton(stringResource(R.string.logview_delete), { askDelete = true }, enabled = !live)
        QuietButton(stringResource(R.string.logview_close), onClose)
    }
    val startedAt = log?.startMillis ?: logFileStamp(file.name) ?: file.lastModified()
    val head = RideHeadInfo(file, load, startedAt, live, (reloadMs / 1000).toInt())

    if (wide) {
        RideTwoPanes(head, log, fmt, st, coldStart, layoutInfo, modifier, buttons)
    } else {
        RideOneColumn(head, log, fmt, st, coldStart, modifier, buttons)
    }

    OutburstBlockWhile(askDelete)
    if (askDelete) {
        val c = S3.colors
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.bike_logger_delete_title), style = S3.type.section, color = c.paint) },
            text = { Text(stringResource(R.string.bike_logger_delete_text, fmt.heading(startedAt)), style = S3.type.body, color = c.paint) },
            confirmButton = {
                TextButton({
                    askDelete = false
                    vm.deleteRideLog(file)
                    LogViewNav.deleted()
                    onClose()
                }) { Text(stringResource(R.string.bike_delete), style = S3.type.bodyStrong, color = c.signal) }
            },
            dismissButton = {
                TextButton({ askDelete = false }) { Text(stringResource(R.string.bike_keep), style = S3.type.bodyStrong, color = c.paint) }
            },
        )
    }
}

@Immutable
private class RideHeadInfo(val file: File, val load: RideLoad, val startedAt: Long, val live: Boolean, val reloadSeconds: Int)

/**
 * The date of the ride as the heading ("Sa. 17.10., 15:37 Uhr"), then the notes about the file itself (live, cut,
 * folded, skipped). The file name stays in the share subject.
 */
@Composable
private fun RideHead(h: RideHeadInfo, log: RideLog?, fmt: LogFmt, buttons: (@Composable RowScope.() -> Unit)?) {
    val c = S3.colors
    ViewerTitle(fmt.heading(h.startedAt), buttons, S3.type.title)
    if (h.live) {
        Spacer(Modifier.height(S3.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LedDot(c.signal, dot = 8.dp)
            Spacer(Modifier.width(S3.xs))
            Text(
                stringResource(R.string.logview_ride_live, h.reloadSeconds),
                style = S3.type.caption,
                color = c.paint,
                modifier = Modifier.weight(1f),
            )
        }
    }
    when (h.load) {
        RideLoad.Loading -> {
            Spacer(Modifier.height(S3.m))
            Hint(stringResource(R.string.logview_loading))
        }
        RideLoad.Failed -> {
            Spacer(Modifier.height(S3.m))
            Text(stringResource(R.string.logview_read_failed), style = S3.type.body, color = c.paint)
        }
        is RideLoad.Done -> if (log != null) {
            val notes = buildList {
                if (log.partialTail && !h.live) add(stringResource(R.string.logview_ride_partial))
                if (log.skippedLines > 0) add(pluralStringResource(R.plurals.logview_ride_skipped, log.skippedLines, log.skippedLines))
                if (log.sampleStride > 1) add(stringResource(R.string.logview_ride_folded, log.sampleStride))
            }
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(S3.s))
                notes.forEach { Hint(it) }
            }
        }
    }
}

/** The kinds of [sig] that count as a finding: a lock that was shut only while parked does not. */
private fun findings(log: RideLog, sig: Signal): List<RideLogReader.Span> =
    log.spans(sig).let { if (sig == Signal.LOCKED) it.filter { s -> s.moving } else it }

/**
 * The verdict under the tube, one line from the [findings]: the three signals the shipping rule needs, each as a count
 * ("Schloss 1× in Fahrt · Spule 2× · Akku 0×", the lock named as its lane in the timeline). [spoken] is the same as sentences, with "never" for a count of 0: what
 * TalkBack reads. Signal red when one of the rule's signals held while the bike rolled, otherwise led. Null for a log
 * without a connection or of an S2 (its rows raise none of these). [firstAtMillis]: where a tap puts the cursor.
 * When red, the moment that made it red: the first rolling span of the first signal in the verdict's order (the lock
 * while riding goes before a coil that flickered earlier); otherwise the earliest span of the three.
 */
@Immutable
private class Verdict(val text: String, val spoken: String, val alarm: Boolean, val firstAtMillis: Long?)

/** Between the counts of the verdict. */
private const val VERDICT_JOIN = " · "

/**
 * The verdict shrinks down to this before it is cut; it never wraps. The caption's size: at a font scale of 1.3 it still draws
 * larger than bodyStrong at 1.0, and every language's counts fit the cover's width there.
 */
private val VERDICT_MIN_SIZE = 13.sp

@Composable
private fun verdictOf(log: RideLog): Verdict? {
    if (!log.everConnected || log.isS2) return null
    val lock = findings(log, Signal.LOCKED)
    val coil = findings(log, Signal.FAULT_42)
    val battery = findings(log, Signal.FAULT_18)
    val counts = listOf(
        stringResource(R.string.logview_verdict_short_lock, lock.size),
        stringResource(R.string.logview_verdict_short_coil, coil.size),
        stringResource(R.string.logview_verdict_short_battery, battery.size),
    )
    val sentences = listOf(
        if (lock.isEmpty()) stringResource(R.string.logview_verdict_lock_none) else stringResource(R.string.logview_verdict_lock, lock.size),
        if (coil.isEmpty()) stringResource(R.string.logview_verdict_coil_none) else stringResource(R.string.logview_verdict_coil, coil.size),
        if (battery.isEmpty()) stringResource(R.string.logview_verdict_battery_none) else stringResource(R.string.logview_verdict_battery, battery.size),
    )
    // In the verdict's order: lock, coil, battery, then the rule's other signals.
    val rolled = TIMELINE_ORDER.filter { it != Signal.SUPPLY_LOW }
        .firstNotNullOfOrNull { sig -> findings(log, sig).filter { it.moving }.minOfOrNull { it.startMillis } }
    return Verdict(
        text = counts.joinToString(VERDICT_JOIN),
        spoken = sentences.joinToString(", "),
        alarm = rolled != null,
        firstAtMillis = rolled ?: (lock + coil + battery).minOfOrNull { it.startMillis },
    )
}

/**
 * The one loud thing on the page: the top tube with what it showed last in this log, the lowest fault
 * code of the last fault episode in signal red (as the bike shows codes), or the top speed in led. Under it
 * the verdict ([verdictOf]; a tap puts the cursor on [Verdict.firstAtMillis] through [onPick]), then six facts in
 * two rows. A log without any connection says so instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideSummary(log: RideLog, fmt: LogFmt, onPick: (Long) -> Unit) {
    val c = S3.colors
    val connected = log.everConnected
    val fault = log.tubeFault
    val top = log.topKmh
    val frameText = when {
        !connected -> MatrixFont.BLE.toString()
        fault != null -> "E$fault"
        top != null -> top.toString()
        else -> ""
    }
    val frame = remember(frameText) { MatrixFrame.text(frameText) }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        MatrixDisplay(
            frame = frame,
            columns = 21,
            lit = when {
                !connected -> c.fog
                fault != null -> c.signal
                else -> c.led
            },
            modifier = Modifier.width(150.dp),
        )
        Spacer(Modifier.width(S3.m))
        Column(Modifier.weight(1f)) {
            when {
                !connected -> {
                    Text(stringResource(R.string.logview_never_connected), style = S3.type.bodyStrong, color = c.paint)
                    Text(stringResource(R.string.logview_never_connected_note), style = S3.type.caption, color = c.fog)
                }
                fault != null -> {
                    val at = log.tubeFaultAtMillis
                    Text(stringResource(R.string.logview_tube_fault, fault), style = S3.type.bodyStrong, color = c.paint)
                    if (at != null) {
                        Text(
                            stringResource(
                                if (log.tubeFromShipping) R.string.logview_tube_fault_note_shipping else R.string.logview_tube_fault_note,
                                fmt.at(at),
                            ),
                            style = S3.type.caption,
                            color = c.fog,
                        )
                    }
                }
                top != null -> {
                    Text(fmt.kmh(top), style = S3.type.bodyStrong, color = c.paint)
                    Text(stringResource(R.string.logview_tube_top_note), style = S3.type.caption, color = c.fog)
                }
                else -> Text(stringResource(R.string.logview_tube_none_note), style = S3.type.caption, color = c.fog)
            }
        }
    }
    verdictOf(log)?.let { v ->
        Spacer(Modifier.height(S3.m))
        VerdictLine(v, onPick)
    }
    Spacer(Modifier.height(S3.m))
    val distance = if (connected && log.sampleCount > 1) fmt.km(log.distanceKm) else DASH
    val battery = run {
        val a = log.batteryStart
        val b = log.batteryEnd
        when {
            a != null && b != null -> stringResource(R.string.logview_fact_battery_value, a, b)
            else -> (a ?: b)?.let(fmt::pct) ?: DASH
        }
    }
    val distanceNote = stringResource(R.string.logview_distance_note)
    val facts = buildList {
        add(Triple(stringResource(R.string.logview_fact_duration), fmt.duration(log.durationMillis), null))
        // How the distance and the average come about is said to TalkBack with the distance.
        add(Triple(stringResource(R.string.logview_fact_distance), distance, distanceNote))
        add(Triple(stringResource(R.string.logview_fact_top), top?.let(fmt::kmh) ?: DASH, null))
        add(Triple(stringResource(R.string.logview_fact_avg), log.avgMovingKmh?.let(fmt::kmh) ?: DASH, null))
        add(Triple(stringResource(R.string.logview_fact_battery), battery, null))
        add(Triple(stringResource(R.string.logview_fact_marks), fmt.count(log.markCount), null))
        // S2 drive diagnosis: the phone's top speed and the dropouts against it, a third row.
        if (log.hasGps) {
            add(Triple(stringResource(R.string.s2diag_view_fact_gps_top), log.gpsTopKmh?.let(fmt::kmh) ?: DASH, null))
            add(Triple(stringResource(R.string.s2diag_view_fact_dropouts), fmt.count(log.dropouts.size), null))
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S3.m)) {
        facts.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.m)) {
                row.forEach { (label, value, note) -> SummaryFact(label, value, note, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    log.dropouts.firstOrNull()?.let { first ->
        Spacer(Modifier.height(S3.m))
        Text(
            pluralStringResource(R.plurals.s2diag_view_summary_dropouts, log.dropouts.size, fmt.count(log.dropouts.size), fmt.at(first.startMillis)),
            style = S3.type.bodyStrong,
            color = c.signal,
        )
    }
}

/**
 * The verdict as one line: the counts in bodyStrong, shrinking in 1 sp steps down to [VERDICT_MIN_SIZE] until they fit the
 * width, never wrapping (what is still too long is cut with an ellipsis). TalkBack reads the sentences instead ([Verdict.spoken]);
 * a tap, with at least 48 dp to hit, puts the cursor on [Verdict.firstAtMillis].
 */
@Composable
private fun VerdictLine(v: Verdict, onPick: (Long) -> Unit) {
    val c = S3.colors
    val measurer = rememberTextMeasurer()
    val style = S3.type.bodyStrong
    val first = v.firstAtMillis
    val showLabel = stringResource(R.string.logview_event_show)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (first != null) Modifier.clickable(onClickLabel = showLabel) { onPick(first) } else Modifier)
            .clearAndSetSemantics {
                contentDescription = v.spoken
                if (first != null) onClick(showLabel) { onPick(first); true }
            }
            .heightIn(min = 48.dp)
            .padding(vertical = S3.s),
        contentAlignment = Alignment.CenterStart,
    ) {
        val maxWidth = constraints.maxWidth
        val fitted = remember(v.text, style, maxWidth, measurer) {
            shellFitLabels(measurer, listOf(v.text), style, VERDICT_MIN_SIZE) { widths -> widths[0] <= maxWidth }
        }
        Text(
            v.text,
            style = fitted,
            color = if (v.alarm) c.signal else c.led,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One fact of the summary: one TalkBack stop, label first, and [note] after the value where it explains it. */
@Composable
private fun SummaryFact(label: String, value: String, note: String?, modifier: Modifier = Modifier) =
    Fact(
        label,
        value,
        modifier.clearAndSetSemantics { contentDescription = listOfNotNull("$label: $value", note).joinToString(". ") },
    )

/** Section title, the hint and the legend above the timeline (they scroll; the timeline may stick). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineIntro(log: RideLog) {
    val c = S3.colors
    BikeSectionTitle(stringResource(R.string.logview_chart_title))
    Spacer(Modifier.height(S3.s))
    Hint(stringResource(R.string.logview_chart_hint))
    Spacer(Modifier.height(S3.s))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.m), verticalArrangement = Arrangement.spacedBy(S3.xs)) {
        LegendKey(stringResource(R.string.logview_legend_speed), c.led)
        LegendKey(stringResource(R.string.logview_legend_battery), c.lamp)
        LegendKey(stringResource(R.string.logview_legend_supply), c.lamp, bar = true)
        LegendKey(stringResource(R.string.logview_legend_mark), c.led, dot = true)
        LegendKey(stringResource(R.string.logview_legend_rule), c.signal, bar = true)
        LegendKey(stringResource(R.string.logview_legend_link_down), c.fog, bar = true)
        // S2 drive diagnosis: the phone's GPS speed and where the bike's speed signal dropped out.
        if (log.hasGps) {
            LegendKey(stringResource(R.string.s2diag_view_legend_gps), c.paint, dashed = true)
            LegendKey(stringResource(R.string.s2diag_view_legend_dropout), c.signal, bar = true)
        }
    }
}

/**
 * The fault signals are the S3 shipping rule's fault bits by definition: S2 rows never raise them (their codes
 * are not rule faults, RideLogReader.Row.ruleFaults) and no LOCKED either (Row.ruleLocked), so the S3 table is
 * the right one here, not the log's family table.
 */
@Composable
private fun signalName(sig: Signal): String = when (sig) {
    Signal.LOCKED -> stringResource(R.string.logview_signal_locked)
    Signal.FAULT_42 -> stringResource(R.string.logview_signal_fault, 42, Faults.describe(42).name)
    Signal.FAULT_18 -> stringResource(R.string.logview_signal_fault, 18, Faults.describe(18).name)
    // The shared fault table names 8 by its charging meaning; in the shipping context it is the BMS
    // switching its output off (the shipping-mode notes), so the viewer says that.
    Signal.FAULT_8 -> stringResource(R.string.logview_signal_fault_8)
    Signal.SHIPPING -> stringResource(R.string.logview_signal_shipping)
    Signal.SUPPLY_LOW -> stringResource(R.string.logview_signal_supply)
}

/** The short name of a signal, for the line under the timeline. */
@Composable
private fun signalShort(sig: Signal): String = when (sig) {
    Signal.LOCKED -> stringResource(R.string.logview_short_locked)
    Signal.FAULT_42 -> stringResource(R.string.logview_tube_fault, 42)
    Signal.FAULT_18 -> stringResource(R.string.logview_tube_fault, 18)
    Signal.FAULT_8 -> stringResource(R.string.logview_tube_fault, 8)
    Signal.SHIPPING -> stringResource(R.string.logview_short_shipping)
    Signal.SUPPLY_LOW -> stringResource(R.string.logview_signal_supply)
}

/**
 * The values at the cursor, as one line that may wrap to two (a Kick Lock that reads locked while rolling marked in
 * signal red inside it, a supply below 20 V in lamp), and the other signals that held then, if any. On an S3 the
 * shipping rule's signals lead and speed and battery follow, so a narrow screen cuts the least telling value first.
 */
@Immutable
private class Readout(val line: AnnotatedString, val signals: String?, val signalsArePower: Boolean) {
    val spoken: String get() = if (signals == null) line.text else "${line.text}. $signals"
}

/** One value of the readout and the piece of it marked in [color] (none without [mark]). */
private class ReadoutPart(val text: String, val mark: String? = null, val color: Color = Color.Unspecified)

private const val READOUT_SEPARATOR = ", "

/** One moment's values: a chosen event's own row, or the stored sample nearest the cursor. */
private class Moment(
    val ready: Boolean,
    val kmh: Int?,
    val battery: Int?,
    val supplyOk: Boolean?,
    val lock: String?,
    val moving: Boolean,
    val signals: Set<Signal>,
    /** The phone's GPS speed (S2 drive diagnosis); null without GPS. */
    val gpsKmh: Double? = null,
)

/**
 * The values at the cursor; null until a moment is chosen (the line under the chart stays away until then).
 * [withSignals]: the other signals that held go into the line, right after the lock, instead of [Readout.signals]
 * (the chart's short form on the cover screen has no row for them).
 */
@Composable
private fun readoutAt(log: RideLog, st: RideViewState, fmt: LogFmt, withSignals: Boolean = false): Readout? {
    val t = st.cursor ?: return null
    val chosenLine = st.chosen
    val event = remember(log, chosenLine) { chosenLine?.let { l -> log.events.firstOrNull { it.line == l } } }
    val m: Moment? = if (event != null && event.atMillis == t) {
        Moment(event.ready, event.kmh, event.battery, event.supplyOk, event.lock, event.moving, event.signals, event.gpsKmh)
    } else {
        log.sampleAt(t)?.let { s -> Moment(s.ready, s.kmh, s.battery, s.supplyOk, s.lock, s.moving, s.signals, s.gpsKmh) }
    }
    val at = fmt.atTime(t)
    val lockedWord = stringResource(R.string.logview_lock_locked)
    val c = S3.colors
    if (m == null || !m.ready || (event == null && log.linkDownAt(t))) {
        return Readout(AnnotatedString(stringResource(R.string.logview_readout_offline, at)), null, false)
    }
    val lockText = when (m.lock) {
        RideLogReader.LOCKED -> lockedWord
        RideLogReader.UNLOCKED -> stringResource(R.string.logview_lock_unlocked)
        RideLogReader.PIN_ENTRY -> stringResource(R.string.logview_lock_pin)
        else -> DASH
    }
    val supplyText = when (m.supplyOk) {
        true -> stringResource(R.string.logview_supply_ok)
        false -> stringResource(R.string.logview_supply_low)
        null -> DASH
    }
    // Locked while standing is how every ride starts and ends; only locked while rolling is red.
    val lockedRolling = m.lock == RideLogReader.LOCKED && m.moving
    val lock = ReadoutPart(stringResource(R.string.logview_readout_lock, lockText), lockedWord.takeIf { lockedRolling }, c.signal)
    val supply = ReadoutPart(stringResource(R.string.logview_readout_supply, supplyText), supplyText.takeIf { m.supplyOk == false }, c.lamp)
    val speed = ReadoutPart(m.kmh?.let(fmt::kmh) ?: DASH)
    // With GPS (S2 drive diagnosis) the phone's speed stands right after the bike's.
    val gps = if (log.hasGps) ReadoutPart(stringResource(R.string.s2diag_view_readout_gps, m.gpsKmh?.let(fmt::kmh) ?: DASH)) else null
    val battery = ReadoutPart(stringResource(R.string.logview_readout_battery, m.battery?.let(fmt::pct) ?: DASH))
    // The lock is in the line already and a low supply is the supply's own value; the rest of what held then.
    val held = TIMELINE_ORDER.filter { it in m.signals && it != Signal.LOCKED }
    val dropout = log.dropoutAt(t)
    val dropoutName = stringResource(R.string.s2diag_view_legend_dropout)
    val names = held.map { signalShort(it) } + if (dropout) listOf(dropoutName) else emptyList()
    val inline = if (withSignals) {
        (held.filter { it != Signal.SUPPLY_LOW }.map { signalShort(it) } + if (dropout) listOf(dropoutName) else emptyList())
            .map { ReadoutPart(it, it, c.signal) }
    } else emptyList()
    // An S3: the shipping rule's signals first. An S2 (drive diagnosis): the two speeds first; lock and supply only
    // when its rows have them.
    val parts = if (log.isS2) {
        listOfNotNull(speed, gps) + inline + battery + listOfNotNull(lock.takeIf { m.lock != null }, supply.takeIf { m.supplyOk != null })
    } else {
        listOf(lock) + inline + listOfNotNull(supply, speed, gps, battery)
    }
    val body = parts.joinToString(READOUT_SEPARATOR) { it.text }
    val plain = stringResource(R.string.logview_readout_at, at, body)
    val line = buildAnnotatedString {
        append(plain)
        var from = plain.lastIndexOf(body).coerceAtLeast(0)
        for (part in parts) {
            val mark = part.mark
            val i = if (mark != null) part.text.lastIndexOf(mark) else -1
            if (mark != null && i >= 0) addStyle(SpanStyle(color = part.color), from + i, from + i + mark.length)
            from += part.text.length + READOUT_SEPARATOR.length
        }
    }
    return Readout(
        line,
        if (withSignals) null else names.joinToString(", ").ifEmpty { null },
        held.all { it == Signal.SUPPLY_LOW } && !dropout,
    )
}

/**
 * The timeline with the values at the cursor under it (two lines, the time first, once a moment is chosen) and the
 * zoom row. Choosing a moment on the chart clears the chosen event; the TalkBack actions step through the events and
 * zoom. [compact]: the short form while the chart sticks over the list on the cover screen, without the zoom row;
 * [onExpand] asks for the full lanes. [onLane]: a lane label was tapped (null: the link lane).
 */
@Composable
private fun TimelineBlock(
    log: RideLog,
    fmt: LogFmt,
    st: RideViewState,
    speedHeight: Dp,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onExpand: (() -> Unit)? = null,
    onLane: ((Signal?) -> Unit)? = null,
) {
    val c = S3.colors
    val start = log.startMillis ?: return
    val end = log.endMillis ?: return
    val (viewStart, viewEnd) = st.window(log)
    // The short form has no row for the other signals: they go into the line.
    val readout = readoutAt(log, st, fmt, withSignals = compact)
    val text = TimelineText(
        battery = stringResource(R.string.logview_lane_battery),
        supply = stringResource(R.string.logview_lane_supply),
        shipping = stringResource(R.string.logview_lane_shipping),
        lock = stringResource(R.string.logview_lane_lock),
        fault42 = stringResource(R.string.logview_lane_f42),
        fault18 = stringResource(R.string.logview_lane_f18),
        fault8 = stringResource(R.string.logview_lane_f8),
        link = stringResource(R.string.logview_lane_link),
        signals = stringResource(R.string.logview_lane_signals),
    )
    val seen = TIMELINE_ORDER.filter { findings(log, it).isNotEmpty() }.map { signalName(it) } +
        if (log.dropouts.isNotEmpty()) listOf(stringResource(R.string.s2diag_view_legend_dropout)) else emptyList()
    val signalsSentence = when {
        !log.everConnected -> stringResource(R.string.logview_never_connected)
        seen.isEmpty() -> stringResource(R.string.logview_signals_none)
        else -> stringResource(R.string.logview_signals_seen_cd, seen.joinToString(", "))
    }
    val description = stringResource(
        R.string.logview_chart_cd,
        fmt.atTime(start),
        fmt.atTime(end),
        log.topKmh?.let(fmt::kmh) ?: DASH,
        log.batteryStart?.let(fmt::pct) ?: DASH,
        log.batteryEnd?.let(fmt::pct) ?: DASH,
        signalsSentence,
    )
    val canZoomIn = viewEnd - viewStart > MIN_WINDOW_MS && end - start > MIN_WINDOW_MS
    // "Vergrößern" zooms in around the cursor, centred (so both sides of a moment show), else the middle;
    // "Ganze Fahrt" shows the whole recording again.
    fun zoom(factor: Double) {
        val cur = st.cursor?.takeIf { it in viewStart..viewEnd }
        val a = cur ?: (viewStart + (viewEnd - viewStart) / 2)
        val frac = if (factor < 1.0) 0.5f else (a - viewStart).toFloat() / (viewEnd - viewStart).coerceAtLeast(1L)
        val (s, e) = zoomWindow(start, end, viewStart, viewEnd, a, frac, factor)
        st.setWindow(log, s, e)
    }
    val zoomIn: () -> Unit = { zoom(0.25) }
    val whole: () -> Unit = { st.setWindow(log, start, end) }
    val nextLabel = stringResource(R.string.logview_action_next_event)
    val prevLabel = stringResource(R.string.logview_action_prev_event)
    val zoomInLabel = stringResource(R.string.logview_zoom_in)
    val wholeLabel = stringResource(R.string.logview_zoom_whole)
    val expandLabel = stringResource(R.string.logview_action_expand)
    val actions = remember(log, nextLabel, prevLabel, zoomInLabel, wholeLabel, expandLabel, viewStart, viewEnd, st.cursor, compact, onExpand) {
        buildList {
            add(
                CustomAccessibilityAction(nextLabel) {
                    val from = st.cursor ?: Long.MIN_VALUE
                    val e = log.events.firstOrNull { it.atMillis > from } ?: return@CustomAccessibilityAction false
                    st.pick(log, e.atMillis, e.line)
                    true
                },
            )
            add(
                CustomAccessibilityAction(prevLabel) {
                    val from = st.cursor ?: return@CustomAccessibilityAction false
                    val e = log.events.lastOrNull { it.atMillis < from } ?: return@CustomAccessibilityAction false
                    st.pick(log, e.atMillis, e.line)
                    true
                },
            )
            if (canZoomIn) add(CustomAccessibilityAction(zoomInLabel) { zoomIn(); true })
            if (st.zoomed) add(CustomAccessibilityAction(wholeLabel) { whole(); true })
            if (compact && onExpand != null) add(CustomAccessibilityAction(expandLabel) { onExpand(); true })
        }
    }
    val clock = remember(fmt) { TimelineClock(fmt.zone, fmt::time, fmt::timeShort, fmt::dayTime, fmt::day) }
    Column(modifier.fillMaxWidth()) {
        RideTimeline(
            log = log,
            cursor = st.cursor,
            onCursor = { st.cursor = it; st.chosen = null },
            viewStart = viewStart,
            viewEnd = viewEnd,
            onView = { a, b -> st.setWindow(log, a, b) },
            text = text,
            clock = clock,
            description = description,
            state = readout?.spoken.orEmpty(),
            actions = actions,
            speedHeight = speedHeight,
            compact = compact,
            onExpand = onExpand,
            onLane = onLane,
        )
        // The chart's state already speaks the readout; this line is for the eye, and only once there is a moment.
        // Two lines, held even for a short one, so the chart does not jump while the cursor moves.
        if (readout != null) {
            Text(
                readout.line,
                style = S3.type.caption.copy(fontFeatureSettings = "tnum"),
                color = c.paint,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = S3.xs).clearAndSetSemantics { },
            )
        }
        if (compact) return@Column
        // The signals that held at the cursor, else the window; the zoom buttons at the end.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = S3.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(S3.s),
        ) {
            val signals = readout?.signals
            when {
                signals != null -> Text(
                    signals,
                    style = S3.type.label,
                    color = if (readout?.signalsArePower == true) c.lamp else c.signal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clearAndSetSemantics { },
                )
                st.zoomed -> Text(
                    stringResource(R.string.logview_zoom_window, fmt.duration(viewEnd - viewStart)),
                    style = S3.type.caption,
                    color = c.fog,
                    modifier = Modifier.weight(1f),
                )
                else -> Spacer(Modifier.weight(1f))
            }
            ChartButton(wholeLabel, whole, enabled = st.zoomed)
            ChartButton(zoomInLabel, zoomIn, enabled = canZoomIn)
        }
    }
}

private val TIMELINE_ORDER = listOf(Signal.LOCKED, Signal.FAULT_42, Signal.FAULT_18, Signal.FAULT_8, Signal.SHIPPING, Signal.SUPPLY_LOW)

/**
 * Where the rows of "Signale der Versandregel" sit, so a tap on a lane label of the chart can bring its row into view:
 * a [BringIntoViewRequester] per row for the scrolling column of the inner screen, and each row's offset within the
 * section's list item for the cover screen's list (whose sticky chart would cover a row brought in from above).
 * Keys: a [Signal] by name, [UNSEEN] for the line of the signals not seen, [HEAD] for the section's title.
 */
@Stable
private class SignalTargets {
    private val requesters = HashMap<String, BringIntoViewRequester>()
    private val coords = HashMap<String, LayoutCoordinates>()
    private val offsets = HashMap<String, Float>()
    var root: LayoutCoordinates? = null

    fun requester(key: String): BringIntoViewRequester = requesters.getOrPut(key) { BringIntoViewRequester() }

    fun place(key: String, c: LayoutCoordinates) { coords[key] = c }

    /** The row's top within the section's item, in px; the last one measured while the item was on screen. */
    fun offsetOf(key: String): Float? {
        val r = root
        val c = coords[key]
        if (r != null && c != null && r.isAttached && c.isAttached) offsets[key] = r.localPositionOf(c, Offset.Zero).y
        return offsets[key]
    }

    /** Which row stands for [sig] on the chart (null: the link lane), given what [log] saw. */
    fun keyFor(log: RideLog, sig: Signal?): String = when {
        sig == null -> HEAD
        !log.everConnected -> HEAD
        log.spans(sig).isEmpty() -> UNSEEN
        else -> sig.name
    }

    companion object {
        const val UNSEEN = "unseen"
        const val HEAD = "head"
    }
}

/** Marks a row of the signals section for [SignalTargets]. */
private fun Modifier.signalTarget(targets: SignalTargets?, key: String): Modifier =
    if (targets == null) this
    else this.bringIntoViewRequester(targets.requester(key)).onGloballyPositioned { targets.place(key, it) }

/**
 * One row per signal that this recording saw: how often, the first moment, and a tap puts the cursor there. The
 * signals not seen fold into one line in fog.
 */
@Composable
private fun SignalsSection(log: RideLog, fmt: LogFmt, onPick: (Long) -> Unit, targets: SignalTargets? = null) {
    val c = S3.colors
    val connected = log.everConnected
    BikeSectionTitle(stringResource(R.string.logview_signals_title), Modifier.signalTarget(targets, SignalTargets.HEAD))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.logview_signals_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.s))
    val showLabel = stringResource(R.string.logview_event_show)
    val unseen = ArrayList<String>()
    if (connected) TIMELINE_ORDER.forEach { sig ->
        val spans = log.spans(sig)
        val first = spans.firstOrNull()
        if (first == null) {
            unseen.add(signalName(sig))
            return@forEach
        }
        // Locked while parked is normal; locked while rolling is the finding, so a tap goes there.
        val riding = if (sig == Signal.LOCKED) spans.filter { it.moving } else emptyList()
        val parkedOnly = sig == Signal.LOCKED && riding.isEmpty()
        val color = when {
            parkedOnly -> c.fog
            sig == Signal.SUPPLY_LOW -> c.lamp
            else -> c.signal
        }
        val target = riding.firstOrNull() ?: first
        Row(
            Modifier
                .fillMaxWidth()
                .signalTarget(targets, sig.name)
                .heightIn(min = 52.dp)
                .clickable(onClickLabel = showLabel) { onPick(target.startMillis) }
                .semantics(mergeDescendants = true) {}
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedDot(color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(signalName(sig), style = S3.type.label, color = c.paint)
                Text(
                    pluralStringResource(R.plurals.logview_signal_seen, spans.size, fmt.count(spans.size), fmt.at(first.startMillis)),
                    style = S3.type.caption,
                    color = if (parkedOnly) c.fog else c.paint,
                )
                if (riding.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.logview_signal_riding, riding.size, fmt.count(riding.size), fmt.at(riding.first().startMillis),
                        ),
                        style = S3.type.caption,
                        color = c.signal,
                    )
                } else if (parkedOnly) {
                    Text(stringResource(R.string.logview_signal_parked_only), style = S3.type.caption, color = c.fog)
                }
            }
        }
    }
    if (unseen.isNotEmpty()) {
        Text(
            stringResource(R.string.logview_signals_unseen, unseen.joinToString(", ")),
            style = S3.type.caption,
            color = c.fog,
            modifier = Modifier.fillMaxWidth().signalTarget(targets, SignalTargets.UNSEEN).padding(vertical = S3.s),
        )
    }
    val anyFinding = TIMELINE_ORDER.any { findings(log, it).isNotEmpty() }
    when {
        !connected -> {
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.logview_never_connected))
        }
        !anyFinding && log.spans(Signal.LOCKED).isNotEmpty() -> {
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.logview_signals_none_riding))
        }
        !anyFinding -> {
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.logview_signals_none))
        }
    }
    if (log.hasGps) {
        Spacer(Modifier.height(S3.m))
        DropoutRow(log, fmt, onPick)
    }
}

/** S2 drive diagnosis: the signal dropouts against GPS, one row like a signal's; a tap puts the cursor on the first. */
@Composable
private fun DropoutRow(log: RideLog, fmt: LogFmt, onPick: (Long) -> Unit) {
    val c = S3.colors
    val drops = log.dropouts
    val first = drops.firstOrNull()
    val showLabel = stringResource(R.string.logview_event_show)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (first != null) Modifier.clickable(onClickLabel = showLabel) { onPick(first.startMillis) } else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedDot(if (first == null) c.rib else c.signal)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.s2diag_view_signal), style = S3.type.label, color = c.paint)
            if (first == null) {
                Text(stringResource(R.string.s2diag_view_dropout_none), style = S3.type.caption, color = c.fog)
            } else {
                Text(
                    pluralStringResource(R.plurals.logview_signal_seen, drops.size, fmt.count(drops.size), fmt.at(first.startMillis)),
                    style = S3.type.caption,
                    color = c.paint,
                )
                Text(
                    stringResource(R.string.s2diag_view_dropout_total, fmt.duration(log.dropoutMillis), fmt.kmh(drops.maxOf { it.peakGpsKmh })),
                    style = S3.type.caption,
                    color = c.signal,
                )
            }
        }
    }
}

@Composable
private fun EventsHead(log: RideLog, fmt: LogFmt, st: RideViewState) {
    BikeSectionTitle(stringResource(R.string.logview_events_title))
    Spacer(Modifier.height(S3.m))
    ChoiceBar(
        options = listOf(
            ListFilter.ALL to stringResource(R.string.logview_events_all),
            ListFilter.INCIDENTS to pluralStringResource(R.plurals.logview_events_incidents_count, log.incidentCount, fmt.count(log.incidentCount)),
            ListFilter.MARKS to pluralStringResource(R.plurals.logview_events_marks_count, log.markCount, fmt.count(log.markCount)),
        ),
        selected = st.filter,
        onSelect = { st.filter = it },
    )
    Spacer(Modifier.height(S3.s))
    // Events and marks are counted apart; every incident and mark is kept unless there were thousands.
    val keptIncidents = remember(log) { log.events.count { it.incident } }
    val notes = buildList {
        if (log.eventsLeftOut > 0) {
            val shown = log.events.count { !it.mark }
            add(
                pluralStringResource(
                    R.plurals.logview_ride_events_cut, log.eventsLeftOut,
                    fmt.count(shown), fmt.count(log.eventCount), fmt.count(log.eventsLeftOut),
                ),
            )
        }
        if (log.marksLeftOut > 0) add(pluralStringResource(R.plurals.logview_ride_marks_cut, log.marksLeftOut, fmt.count(log.marksLeftOut)))
        if (log.eventsLeftOut > 0 && keptIncidents == log.incidentCount && log.marksLeftOut == 0) {
            add(stringResource(R.string.logview_ride_incidents_kept))
        }
    }
    if (notes.isNotEmpty()) Hint(notes.joinToString(" "), Modifier.padding(bottom = S3.s))
}

/** The empty-list line under the events, when there is nothing to show. */
@Composable
private fun EventsEmpty(log: RideLog, filter: ListFilter, shown: Int) {
    if (shown > 0) return
    Spacer(Modifier.height(S3.s))
    Hint(
        stringResource(
            when {
                log.events.isEmpty() -> R.string.logview_events_empty
                filter == ListFilter.INCIDENTS -> R.string.logview_events_no_incidents
                filter == ListFilter.MARKS -> R.string.logview_events_no_marks
                else -> R.string.logview_events_empty
            },
        ),
    )
}

/**
 * One event: the time, the text in the colour of its meaning ([RideLogReader.Tone]: lamp for power and the link,
 * signal for the shipping rule and safety, marks in bold), and its detail on one line; the chosen one shows it whole.
 */
@Composable
private fun EventRow(e: RideLogReader.Event, chosen: Boolean, fmt: LogFmt, chosenState: String, onPick: () -> Unit) {
    val c = S3.colors
    val tone = e.tone
    val color = when (tone) {
        RideLogReader.Tone.SIGNAL -> c.signal
        RideLogReader.Tone.LAMP -> c.lamp
        RideLogReader.Tone.MARK, RideLogReader.Tone.PLAIN -> c.paint
    }
    // Colour is not the only carrier: TalkBack hears "Vorfall" (or "Marke") first.
    val kindWord = when {
        e.mark -> stringResource(R.string.logview_legend_mark)
        e.incident -> stringResource(R.string.logview_event_incident)
        else -> null
    }
    val spoken = listOfNotNull(kindWord, fmt.atTime(e.atMillis), e.text, e.detail).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.logview_event_show), onClick = onPick)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                if (chosen) stateDescription = chosenState
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The chosen event is lit like a matrix dot.
        ShellLed(lit = chosen, dot = 6.dp)
        Spacer(Modifier.width(S3.xs))
        Text(
            fmt.time(e.atMillis),
            style = S3.type.data.copy(fontFeatureSettings = "tnum"),
            color = if (chosen) c.paint else c.fog,
            modifier = Modifier.widthIn(min = 76.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.text, style = if (e.mark) S3.type.bodyStrong else S3.type.body, color = color)
            e.detail?.let {
                Text(
                    it,
                    style = S3.type.caption,
                    color = c.fog,
                    maxLines = if (chosen) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A row of the event list: an event, or the day that starts with the next one (logs over several dates). */
private sealed interface EventItem {
    val key: String

    class Day(val atMillis: Long, val date: LocalDate) : EventItem {
        override val key: String get() = "d${date.toEpochDay()}"
    }

    class Ev(val e: RideLogReader.Event) : EventItem {
        override val key: String get() = "e${e.line}"
    }
}

private fun eventList(log: RideLog, filter: ListFilter, fmt: LogFmt): List<EventItem> {
    val shown = when (filter) {
        ListFilter.ALL -> log.events
        ListFilter.INCIDENTS -> log.events.filter { it.incident }
        ListFilter.MARKS -> log.events.filter { it.mark }
    }
    if (!fmt.days) return shown.map { EventItem.Ev(it) }
    val out = ArrayList<EventItem>(shown.size + 8)
    var day: LocalDate? = null
    for (e in shown) {
        val d = fmt.localDate(e.atMillis)
        if (d != day) {
            out.add(EventItem.Day(e.atMillis, d))
            day = d
        }
        out.add(EventItem.Ev(e))
    }
    return out
}

private fun LazyListScope.eventItems(
    items: List<EventItem>,
    st: RideViewState,
    fmt: LogFmt,
    gutter: Dp,
    chosenLabel: String,
    readout: Readout?,
    onPick: (RideLogReader.Event) -> Unit,
) {
    items(items, key = { it.key }, contentType = { if (it is EventItem.Day) 0 else 1 }) { item ->
        Box(Modifier.padding(horizontal = gutter)) {
            when (item) {
                is EventItem.Day -> Text(
                    fmt.dayHeader(item.atMillis),
                    style = S3.type.bodyStrong,
                    color = S3.colors.paint,
                    modifier = Modifier.padding(top = S3.m, bottom = S3.xs).semantics { heading() },
                )
                is EventItem.Ev -> {
                    val chosen = st.chosen == item.e.line
                    EventRow(item.e, chosen, fmt, readout?.let { "$chosenLabel. ${it.spoken}" } ?: chosenLabel) { onPick(item.e) }
                }
            }
        }
    }
}

/** Index of the chosen event in [items], or null. */
private fun chosenIndex(items: List<EventItem>, line: Int?): Int? {
    line ?: return null
    return items.indexOfFirst { it is EventItem.Ev && it.e.line == line }.takeIf { it >= 0 }
}

/**
 * Once a log read after a recreation has arrived (it was not in the cache), the list goes back to the
 * chosen event: the saved scroll position was clamped while the page was still empty.
 */
@Composable
private fun RestoreChosen(coldStart: Boolean, log: RideLog?, listState: LazyListState, index: () -> Int?) {
    LaunchedEffect(coldStart, log != null) {
        if (!coldStart || log == null) return@LaunchedEffect
        if (listState.firstVisibleItemIndex > 0) return@LaunchedEffect
        index()?.let { listState.scrollToItem(it) }
    }
}

/** The list item that holds the signals section in [RideOneColumn] (head, chart, signals, then the events). */
private const val SIGNALS_ITEM = 2

/**
 * Cover screen: one list; the timeline sticks to the top while the events scroll under it, in its short form
 * (about 120 dp) so the list keeps the room. A tap on its strip, or the list back at the top, brings the full
 * lanes back; a tap on a lane label scrolls to that signal's row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RideOneColumn(
    h: RideHeadInfo,
    log: RideLog?,
    fmt: LogFmt,
    st: RideViewState,
    coldStart: Boolean,
    modifier: Modifier,
    buttons: @Composable RowScope.() -> Unit,
) {
    val c = S3.colors
    val gutter = S3.gutter
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val targets = remember { SignalTargets() }
    val density = LocalDensity.current
    var chartPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // The timeline only sticks where it leaves room for the list (not on a landscape cover screen).
        val sticky = maxHeight >= 600.dp
        val stuck by remember(sticky) {
            derivedStateOf {
                sticky && (listState.firstVisibleItemIndex > 1 || (listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset > 0))
            }
        }
        val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
        // A tap on the compact strip asks for the full lanes; they stay until the list is back at the top.
        var expanded by remember { mutableStateOf(false) }
        LaunchedEffect(atTop) { if (atTop) expanded = false }
        val compact = stuck && !expanded
        val items = remember(log, st.filter, fmt) { log?.let { eventList(it, st.filter, fmt) }.orEmpty() }
        val chosenLabel = stringResource(R.string.logview_event_chosen)
        val readout = log?.let { readoutAt(it, st, fmt) }
        val pick: (Long, Int?) -> Unit = { t, line ->
            if (log != null) st.pick(log, t, line)
            if (!sticky) scope.launch { listState.animateScrollToItem(1) }
        }
        val toLane: (Signal?) -> Unit = { sig ->
            if (log != null) {
                val key = targets.keyFor(log, sig)
                scope.launch {
                    // Down to the signals, then the row just under the chart that sticks over them. The item is laid
                    // out (and the chart in its short form) one frame after the jump; the row's place is read then.
                    listState.scrollToItem(SIGNALS_ITEM)
                    withFrameNanos { }
                    val y = targets.offsetOf(key) ?: 0f
                    val margin = with(density) { S3.s.toPx() }
                    listState.animateScrollBy(y - (if (sticky) chartPx else 0) - margin)
                }
            }
        }
        // head, chart, signals, then the events.
        RestoreChosen(coldStart, log, listState) { chosenIndex(items, st.chosen)?.let { it + 3 } }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = S3.xl)) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = S3.l)) {
                    RideHead(h, log, fmt, buttons)
                    if (log != null && !log.isEmpty) {
                        Spacer(Modifier.height(S3.xl))
                        RideSummary(log, fmt) { t -> pick(t, null) }
                        Spacer(Modifier.height(S3.xl))
                        TimelineIntro(log)
                        Spacer(Modifier.height(S3.m))
                    } else if (log != null) {
                        Spacer(Modifier.height(S3.m))
                        Text(stringResource(R.string.logview_ride_empty), style = S3.type.body, color = c.paint)
                    }
                }
            }
            if (log == null || log.isEmpty) return@LazyColumn
            val chart: @Composable () -> Unit = {
                // While it sticks, a short fade from asphalt hangs under the hairline, over the list: a line scrolling
                // under the chart goes soft instead of showing as a sliced half line. It is drawn outside the chart's
                // bounds, so the chart's measured height (chartPx, which toLane uses) and the list do not move.
                val fade by animateFloatAsState(if (stuck) 1f else 0f, tween(120), label = "chartFade")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            if (fade > 0f) {
                                val h = STICKY_FADE.toPx()
                                drawRect(
                                    // held dense over the first half: a sliver of a line right under the hairline all but goes
                                    Brush.verticalGradient(
                                        0f to c.asphalt,
                                        0.5f to c.asphalt.copy(alpha = 0.8f),
                                        1f to c.asphalt.copy(alpha = 0f),
                                        startY = size.height,
                                        endY = size.height + h,
                                    ),
                                    topLeft = Offset(0f, size.height),
                                    size = Size(size.width, h),
                                    alpha = fade,
                                )
                            }
                        }
                        .background(c.asphalt)
                        .onSizeChanged { chartPx = it.height },
                ) {
                    TimelineBlock(
                        log, fmt, st,
                        speedHeight = if (compact) 56.dp else 88.dp,
                        modifier = Modifier.padding(horizontal = gutter).padding(top = S3.s),
                        compact = compact,
                        onExpand = { expanded = true },
                        onLane = toLane,
                    )
                    if (compact) Spacer(Modifier.height(S3.xs))
                    Hairline()
                }
            }
            if (sticky) stickyHeader(key = "chart") { chart() } else item(key = "chart") { chart() }
            item(key = "signals") {
                Column(Modifier.fillMaxWidth().onGloballyPositioned { targets.root = it }.padding(horizontal = gutter).padding(top = S3.xl)) {
                    SignalsSection(log, fmt, { t -> pick(t, null) }, targets)
                    Spacer(Modifier.height(S3.xl))
                    EventsHead(log, fmt, st)
                }
            }
            eventItems(items, st, fmt, gutter, chosenLabel, readout) { e -> pick(e.atMillis, e.line) }
            item(key = "events-empty") {
                Column(Modifier.padding(horizontal = gutter)) { EventsEmpty(log, st.filter, items.count { it is EventItem.Ev }) }
            }
        }
    }
}

/** The fade under the sticky chart of the cover screen (asphalt to clear), drawn over the list. */
private val STICKY_FADE = 16.dp

/** Inner screen: summary, timeline and signals on the left of the fold, the events on the right. */
@Composable
private fun RideTwoPanes(
    h: RideHeadInfo,
    log: RideLog?,
    fmt: LogFmt,
    st: RideViewState,
    coldStart: Boolean,
    layoutInfo: WindowLayoutInfo?,
    modifier: Modifier,
    buttons: @Composable RowScope.() -> Unit,
) {
    val c = S3.colors
    val scope = rememberCoroutineScope()
    val chartRequester = remember { BringIntoViewRequester() }
    val targets = remember { SignalTargets() }
    val leftScroll = rememberScrollState()
    val listState = rememberLazyListState()
    val pick: (Long, Int?) -> Unit = { t, line ->
        if (log != null) st.pick(log, t, line)
        scope.launch { chartRequester.bringIntoView() }
    }
    val toLane: (Signal?) -> Unit = { sig ->
        if (log != null) scope.launch { targets.requester(targets.keyFor(log, sig)).bringIntoView() }
    }
    val items = remember(log, st.filter, fmt) { log?.let { eventList(it, st.filter, fmt) }.orEmpty() }
    // events-head, then the events.
    RestoreChosen(coldStart, log, listState) { chosenIndex(items, st.chosen)?.let { it + 1 } }
    TwoPanes(
        layoutInfo,
        modifier,
        left = { m ->
            Column(m.verticalScroll(leftScroll).padding(start = S3.l, end = S3.l, top = S3.l, bottom = S3.xl)) {
                RideHead(h, log, fmt, buttons = null)
                if (log != null && !log.isEmpty) {
                    Spacer(Modifier.height(S3.xl))
                    RideSummary(log, fmt) { t -> pick(t, null) }
                    Spacer(Modifier.height(S3.xl))
                    TimelineIntro(log)
                    Spacer(Modifier.height(S3.m))
                    TimelineBlock(log, fmt, st, speedHeight = 140.dp, modifier = Modifier.bringIntoViewRequester(chartRequester), onLane = toLane)
                    Spacer(Modifier.height(S3.xl))
                    SignalsSection(log, fmt, { t -> pick(t, null) }, targets)
                } else if (log != null) {
                    Spacer(Modifier.height(S3.m))
                    Text(stringResource(R.string.logview_ride_empty), style = S3.type.body, color = c.paint)
                }
            }
        },
        right = { m ->
            Column(m) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = S3.l).padding(top = S3.l),
                    horizontalArrangement = Arrangement.spacedBy(S3.s, Alignment.End),
                    content = buttons,
                )
                if (log != null && !log.isEmpty) {
                    val chosenLabel = stringResource(R.string.logview_event_chosen)
                    val readout = readoutAt(log, st, fmt)
                    LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(top = S3.l, bottom = S3.xl)) {
                        item(key = "events-head") {
                            Column(Modifier.padding(horizontal = S3.l)) { EventsHead(log, fmt, st) }
                        }
                        eventItems(items, st, fmt, S3.l, chosenLabel, readout) { e -> pick(e.atMillis, e.line) }
                        item(key = "events-empty") {
                            Column(Modifier.padding(horizontal = S3.l)) { EventsEmpty(log, st.filter, items.count { it is EventItem.Ev }) }
                        }
                    }
                }
            }
        },
    )
}

// --- The bike's own log as text ---------------------------------------------------------------------

/** Lines shown at most; a larger file shows its newest ones (the end is where a shutdown sits). */
private const val MAX_LINES = 100_000

/** Bytes read at most from a saved download (the bike's ring holds 128 KB, so this is only a guard). */
private const val MAX_BYTES = 8L * 1024 * 1024

/** Matches marked at most. */
private const val MAX_MATCHES = 20_000

/** The longest line the no-wrap view makes room for (characters). */
private const val MAX_LINE_CHARS = 1_000

@Immutable
internal class TextDoc(
    val lines: List<String>,
    /** Line number of [lines] index 0 in the whole file (1 unless the start was left out). */
    val firstNumber: Int,
    val totalLines: Int,
    val readAt: Long,
    val file: File?,
    /** The text itself when it is not on disk (the download held in memory), for sharing. */
    val text: String?,
    /** The stamped lines fall on more than one date, so their times carry the day. */
    val days: Boolean,
    /** The longest line, in characters (capped), for the no-wrap view. */
    val longest: Int,
)

private sealed interface TextLoad {
    data object Loading : TextLoad
    data object Failed : TextLoad
    class Done(val doc: TextDoc) : TextLoad
}

/** The Unix seconds a stamped line starts with and the length of that prefix with its space, or null. */
private fun stampOf(line: String): Pair<Long, Int>? {
    if (!BikeLogArchive.isStamped(line)) return null
    var n = 0
    while (n < line.length && line[n].isDigit()) n++
    val sec = line.substring(0, n).toLongOrNull() ?: return null
    return sec to (n + 1).coerceAtMost(line.length)
}

/** Splits [text] into lines, dropping the empty piece after a final newline; keeps the newest [MAX_LINES]. */
private fun toDoc(text: String, skippedLines: Int, readAt: Long, file: File?, keepText: Boolean, zone: ZoneId = ZoneId.systemDefault()): TextDoc {
    val all = text.split('\n').map { it.trimEnd('\r') }.let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
    val dropped = (all.size - MAX_LINES).coerceAtLeast(0)
    val lines = if (dropped > 0) all.subList(dropped, all.size).toList() else all
    val first = lines.firstNotNullOfOrNull { stampOf(it)?.first }
    val last = lines.asReversed().firstNotNullOfOrNull { stampOf(it)?.first }
    fun date(sec: Long) = Instant.ofEpochSecond(sec).atZone(zone).toLocalDate()
    val days = first != null && last != null && date(first) != date(last)
    val longest = lines.maxOfOrNull { it.length }?.coerceAtMost(MAX_LINE_CHARS) ?: 0
    return TextDoc(lines, skippedLines + dropped + 1, skippedLines + all.size, readAt, file, if (keepText) text else null, days, longest)
}

/**
 * Reads [file], or only its last [MAX_BYTES] for a larger one: then the cut first line is dropped and
 * the lines before are counted, so the numbers stay those of the whole file.
 */
private fun readLogFile(file: File, readAt: Long): TextDoc {
    val length = file.length()
    if (length <= MAX_BYTES) return toDoc(file.readText(), 0, readAt, file, keepText = false)
    val from = length - MAX_BYTES
    var newlines = 0
    var lastBefore = '\n'.code
    file.inputStream().buffered().use { input ->
        var left = from
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) newlines++
            if (n > 0) lastBefore = buf[n - 1].toInt()
            left -= n
        }
    }
    val bytes = ByteArray(MAX_BYTES.toInt())
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(from)
        raf.readFully(bytes)
    }
    var text = String(bytes, Charsets.UTF_8)
    var skipped = newlines
    if (lastBefore != '\n'.code) {
        // We start inside a line: drop its rest, it is line newlines + 1.
        val cut = text.indexOf('\n')
        text = if (cut >= 0) text.substring(cut + 1) else ""
        skipped = newlines + 1
    }
    return toDoc(text, skipped, readAt, file, keepText = false)
}

/** One line as shown: the stamp as local time (null when the line has none) and the message after it. */
private class ShownLine(val time: String?, val message: String)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BikeLogViewer(
    vm: AppViewModel,
    file: File?,
    wide: Boolean,
    layoutInfo: WindowLayoutInfo?,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val c = S3.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memory by vm.bikeLog.collectAsStateWithLifecycle()
    val cacheKey = remember(file, memory?.atMillis) { file?.let(LogViewNav::fileKey) ?: memory?.let { "memory|${it.atMillis}" } }
    val initial = remember(cacheKey) { cacheKey?.let(LogViewNav::cachedText)?.let { TextLoad.Done(it) } ?: TextLoad.Loading }
    val load by produceState<TextLoad>(initial, cacheKey) {
        val cached = cacheKey?.let(LogViewNav::cachedText)
        if (cached != null) {
            value = TextLoad.Done(cached)
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                if (file != null) {
                    TextLoad.Done(readLogFile(file, logFileStamp(file.name) ?: file.lastModified()).also { LogViewNav.keepText(LogViewNav.fileKey(file), it) })
                } else {
                    val m = memory
                    val t = m?.text
                    if (m == null || t == null) TextLoad.Failed
                    else TextLoad.Done(toDoc(t, 0, m.atMillis, null, keepText = true).also { LogViewNav.keepText("memory|${m.atMillis}", it) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TextLoad.Failed
            } catch (e: OutOfMemoryError) {
                TextLoad.Failed
            }
        }
    }
    val baseFmt = rememberLogFmt()
    val doc = (load as? TextLoad.Done)?.doc
    val fmt = remember(baseFmt, doc) { if (doc?.days == true) baseFmt.copy(days = true) else baseFmt }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    var query by rememberSaveable { mutableStateOf("") }
    var current by rememberSaveable { mutableIntStateOf(0) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var shareFailed by remember { mutableStateOf(false) }
    val matches by produceState(emptyList<Int>(), doc, query) {
        val q = query
        if (doc == null || q.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(180)
        value = withContext(Dispatchers.Default) {
            val out = ArrayList<Int>()
            for (i in doc.lines.indices) {
                if (doc.lines[i].contains(q, ignoreCase = true)) {
                    out.add(i)
                    if (out.size >= MAX_MATCHES) break
                }
            }
            out
        }
    }
    val compact = !wide
    // Compact: the head and the search row are the list's first two items.
    val lineOffset = if (compact) 2 else 0
    val density = LocalDensity.current
    val numberStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
    var stickyPx by remember { mutableIntStateOf(0) }
    fun show(index: Int) {
        scope.launch {
            listState.scrollToItem(lineOffset + index)
            // Below the search row, with up to two lines of context above (less when the keyboard is up).
            val linePx = with(density) { numberStyle.lineHeight.toPx() }
            val free = listState.layoutInfo.viewportSize.height - stickyPx
            val context = minOf(linePx * 2, free / 3f).coerceAtLeast(0f)
            listState.scrollBy(-(stickyPx + context))
        }
    }
    val shownMatch by rememberUpdatedState(matches.getOrNull(current))
    // A new search jumps to the first match at or after the line in view.
    LaunchedEffect(matches) {
        if (matches.isEmpty()) return@LaunchedEffect
        val top = (listState.firstVisibleItemIndex - lineOffset).coerceAtLeast(0)
        current = matches.indexOfFirst { it >= top }.takeIf { it >= 0 } ?: 0
        show(matches[current])
    }
    // When the visible part shrinks (the keyboard comes up), the current match is brought back into view.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .drop(1)
            .collect { shownMatch?.let { show(it) } }
    }
    val step: (Int) -> Unit = { d ->
        if (matches.isNotEmpty()) {
            current = ((current + d) % matches.size + matches.size) % matches.size
            show(matches[current])
        }
    }
    val jumpNewest: () -> Unit = { doc?.let { d -> scope.launch { listState.scrollToItem(lineOffset + d.lines.lastIndex.coerceAtLeast(0)) } } }
    val shareTitle = stringResource(R.string.logview_bike_share_title)
    val share: () -> Unit = {
        val d = doc
        if (d != null) {
            shareFailed = false
            scope.launch {
                val f = d.file ?: withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.ofEpochMilli(d.readAt).atZone(ZoneId.systemDefault()))
                        File(dir, "bike-log-$stamp.txt").apply { writeText(d.text.orEmpty()) }
                    }.getOrNull()
                }
                if (f == null) {
                    shareFailed = true
                    return@launch
                }
                val ok = shareFile(context, f, "text/plain", context.getString(R.string.logview_bike_share_subject, f.nameWithoutExtension), shareTitle)
                if (!ok) shareFailed = true
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val numberWidth = remember(doc, numberStyle, density) {
        val digits = ((doc?.let { it.firstNumber + it.lines.size } ?: 1)).toString().length
        with(density) { measurer.measure(AnnotatedString("0".repeat(digits)), numberStyle).size.width.toDp() }
    }
    val textWidth = remember(doc, numberStyle, density) {
        val ch = with(density) { measurer.measure(AnnotatedString("0"), numberStyle).size.width.toDp() }
        ch * ((doc?.longest ?: 0) + 14)
    }
    val q = query
    val currentLine = matches.getOrNull(current)
    fun shown(raw: String): ShownLine {
        val s = stampOf(raw) ?: return ShownLine(null, raw)
        return ShownLine(fmt.clock(s.first * 1000), raw.substring(s.second))
    }
    val countText = when {
        q.isBlank() -> null
        matches.isEmpty() -> stringResource(R.string.logview_search_none)
        else -> stringResource(R.string.logview_search_count, current + 1, matches.size)
    }
    // Read out when it changes, with the line it points at.
    val countSpoken = when {
        q.isBlank() -> null
        matches.isEmpty() || doc == null -> countText
        else -> {
            val i = matches[current.coerceIn(0, matches.lastIndex)]
            val l = shown(doc.lines[i])
            stringResource(
                R.string.logview_search_count_cd, current + 1, matches.size, doc.firstNumber + i,
                listOfNotNull(l.time, l.message).joinToString(", "),
            )
        }
    }
    val countModifier = Modifier.semantics {
        liveRegion = LiveRegionMode.Polite
        if (countSpoken != null) contentDescription = countSpoken
    }

    val buttons: @Composable RowScope.() -> Unit = {
        QuietButton(stringResource(R.string.logview_share), share, enabled = doc != null && doc.lines.isNotEmpty())
        QuietButton(stringResource(R.string.logview_close), onClose)
    }
    /** Read time, line count, notes; then (wide only) the search with its buttons. */
    val meta: @Composable () -> Unit = {
        Spacer(Modifier.height(S3.m))
        when (load) {
            TextLoad.Loading -> Hint(stringResource(R.string.logview_loading))
            TextLoad.Failed -> Text(stringResource(R.string.logview_read_failed), style = S3.type.body, color = c.paint)
            is TextLoad.Done -> if (doc != null) {
                Text(stringResource(R.string.logview_bike_read_at, fmt.dateTime(doc.readAt)), style = S3.type.bodyStrong, color = c.paint)
                Text(
                    pluralStringResource(R.plurals.logview_bike_lines, doc.totalLines, fmt.count(doc.totalLines)),
                    style = S3.type.caption,
                    color = c.fog,
                )
                if (doc.firstNumber > 1) Hint(stringResource(R.string.logview_bike_tail, fmt.count(doc.lines.size)))
                if (doc.lines.isEmpty()) {
                    Spacer(Modifier.height(S3.m))
                    Text(stringResource(R.string.logview_bike_empty), style = S3.type.body, color = c.paint)
                } else {
                    Spacer(Modifier.height(S3.m))
                    Text(stringResource(R.string.logview_wrap_title), style = S3.type.caption, color = c.fog)
                    Spacer(Modifier.height(S3.xs))
                    ChoiceBar(
                        options = listOf(true to stringResource(R.string.logview_wrap_on), false to stringResource(R.string.logview_wrap_off)),
                        selected = wrap,
                        onSelect = { wrap = it },
                    )
                    Spacer(Modifier.height(S3.s))
                    QuietButton(stringResource(R.string.logview_jump_newest), jumpNewest)
                }
                if (shareFailed) {
                    Text(
                        stringResource(R.string.logview_share_failed),
                        style = S3.type.label,
                        color = c.signal,
                        modifier = Modifier.padding(top = S3.s),
                    )
                }
            }
        }
    }
    val searchField: @Composable (Modifier, Boolean) -> Unit = { m, withCount ->
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(80) },
            modifier = m,
            label = { Text(stringResource(R.string.logview_search)) },
            singleLine = true,
            trailingIcon = if (withCount && countText != null) {
                {
                    Text(
                        countText,
                        style = S3.type.caption.copy(fontFeatureSettings = "tnum"),
                        color = c.paint,
                        modifier = countModifier.padding(end = 12.dp),
                    )
                }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { step(1) }),
            colors = bikeFieldColors(),
        )
    }
    val hasLines = doc != null && doc.lines.isNotEmpty()
    val lineItems: LazyListScope.(Dp) -> Unit = { side ->
        if (doc != null) {
            itemsIndexed(doc.lines) { i, line ->
                LogLine(
                    doc.firstNumber + i, shown(line), q, i == currentLine, numberWidth, numberStyle,
                    wrap, hScroll, textWidth, side,
                )
            }
        }
    }

    if (wide) {
        TwoPanes(
            layoutInfo,
            modifier,
            left = { m ->
                Column(m.verticalScroll(rememberScrollState()).padding(start = S3.l, end = S3.l, top = S3.l, bottom = S3.xl)) {
                    ViewerTitle(stringResource(R.string.logview_bike_title), buttons)
                    meta()
                    if (hasLines) {
                        Spacer(Modifier.height(S3.m))
                        searchField(Modifier.fillMaxWidth(), false)
                        countText?.let {
                            Text(
                                it,
                                style = S3.type.data.copy(fontFeatureSettings = "tnum"),
                                color = c.paint,
                                modifier = countModifier.padding(top = S3.s),
                            )
                        }
                        if (q.isNotBlank()) {
                            Spacer(Modifier.height(S3.s))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
                                QuietButton(stringResource(R.string.logview_search_prev), { step(-1) }, enabled = matches.isNotEmpty())
                                QuietButton(stringResource(R.string.logview_search_next), { step(1) }, enabled = matches.isNotEmpty())
                            }
                        }
                    }
                }
            },
            right = { m ->
                Column(m) {
                    Spacer(Modifier.height(S3.l))
                    Hairline()
                    if (hasLines) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(top = S3.s, bottom = S3.xl),
                        ) { lineItems(S3.l) }
                    }
                }
            },
        )
    } else {
        // Cover: the head scrolls away with the lines; only the search row stays.
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(bottom = S3.xl),
        ) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(horizontal = S3.gutter).padding(top = S3.l, bottom = S3.m)) {
                    // A tool page: the compact title leaves the lines more room.
                    ViewerTitle(stringResource(R.string.logview_bike_title), buttons, S3.type.title)
                    meta()
                }
            }
            if (hasLines) {
                stickyHeader(key = "search") {
                    Column(Modifier.fillMaxWidth().background(c.asphalt).onSizeChanged { stickyPx = it.height }) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = S3.gutter, end = S3.gutter - 4.dp, top = S3.xs, bottom = S3.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            searchField(Modifier.weight(1f), true)
                            Spacer(Modifier.width(S3.xs))
                            MatrixGlyphButton(LogGlyphs.UP, stringResource(R.string.logview_search_prev), { step(-1) }, enabled = matches.isNotEmpty())
                            MatrixGlyphButton(LogGlyphs.DOWN, stringResource(R.string.logview_search_next), { step(1) }, enabled = matches.isNotEmpty())
                        }
                        Hairline()
                    }
                }
                item(key = "top") { Spacer(Modifier.height(S3.s)) }
                lineItems(S3.gutter)
            }
        }
    }
}

/**
 * One line: its number in fog, a rib rule, the bike's stamp as local time in fog, then the message
 * with the search matches marked. [wrap] false keeps it on one line; all lines then scroll sideways
 * together ([hScroll]) inside [textWidth].
 */
@Composable
private fun LogLine(
    number: Int,
    line: ShownLine,
    query: String,
    current: Boolean,
    numberWidth: Dp,
    style: TextStyle,
    wrap: Boolean,
    hScroll: ScrollState,
    textWidth: Dp,
    side: Dp,
) {
    val c = S3.colors
    val marked = remember(line, query, current, c) {
        buildAnnotatedString {
            line.time?.let {
                withStyle(SpanStyle(color = c.fog, fontFeatureSettings = "tnum")) { append(it) }
                append("  ")
            }
            if (query.isBlank()) append(line.message) else append(
                highlight(line.message, query, if (current) SpanStyle(background = c.led, color = c.onLed) else SpanStyle(background = c.rib)),
            )
        }
    }
    val spoken = stringResource(R.string.logview_line_cd, number, listOfNotNull(line.time, line.message).joinToString(", "))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = side)
            .clearAndSetSemantics { contentDescription = spoken }
            .drawBehind {
                val x = numberWidth.toPx() + 6.dp.toPx()
                drawLine(c.rib, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            },
    ) {
        Text(number.toString(), style = style.copy(color = c.fog, textAlign = TextAlign.End), modifier = Modifier.width(numberWidth))
        Spacer(Modifier.width(12.dp))
        if (wrap) {
            Text(marked, style = style.copy(color = c.paint), modifier = Modifier.weight(1f).padding(vertical = 1.dp))
        } else {
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                Text(
                    marked,
                    style = style.copy(color = c.paint),
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.width(textWidth).padding(vertical = 1.dp),
                )
            }
        }
    }
}

private fun highlight(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    var from = 0
    while (from <= text.length) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        append(text.substring(from, at))
        withStyle(style) { append(text.substring(at, at + query.length)) }
        from = at + query.length
    }
    if (from < text.length) append(text.substring(from))
}
