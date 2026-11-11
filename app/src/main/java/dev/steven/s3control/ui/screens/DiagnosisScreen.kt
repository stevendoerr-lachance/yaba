package dev.steven.s3control.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.Buttons
import dev.steven.s3control.ble.Faults
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.Op
import dev.steven.s3control.ble.S2Gatt
import dev.steven.s3control.ble.rawKey
import dev.steven.s3control.ble.toHex
import dev.steven.s3control.ride.BikeLogArchive
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ride.RideLogReader
import dev.steven.s3control.ride.RideRecorder
import dev.steven.s3control.ui.FamilyGate
import dev.steven.s3control.ui.cockpit.CockpitEntry
import dev.steven.s3control.ui.cockpit.rememberHeroCockpitOffer
import dev.steven.s3control.ui.cockpit.rememberRideLoggerStarter
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.BikeFeedback
import dev.steven.s3control.ui.components.BikeFeedbackNote
import dev.steven.s3control.ui.components.BikeSectionTitle
import dev.steven.s3control.ui.components.CheckRow
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.ConnectPrompt
import dev.steven.s3control.ui.components.DASH
import dev.steven.s3control.ui.components.GuardJump
import dev.steven.s3control.ui.components.Hairline
import dev.steven.s3control.ui.components.Health
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.LedDot
import dev.steven.s3control.ui.components.LinkIndicator
import dev.steven.s3control.ui.components.LinkLine
import dev.steven.s3control.ui.components.MatrixDisplay
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.OutburstBlockWhile
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.ScreenTitle
import dev.steven.s3control.ui.components.WorkingQuietButton
import dev.steven.s3control.ui.components.appLocale
import dev.steven.s3control.ui.components.bikeFieldColors
import dev.steven.s3control.ui.components.formatClock
import dev.steven.s3control.ui.components.rememberBikeFeedback
import dev.steven.s3control.ui.components.rememberNow
import dev.steven.s3control.ui.components.rememberReducedMotion
import dev.steven.s3control.ui.onboarding.TextLink
import dev.steven.s3control.ui.s2.S2DiagnosisEntry
import dev.steven.s3control.ui.theme.S3
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * BikeState emits on every characteristic read (several times per poll). The sections below take
 * narrow, equality-comparable views instead of the whole state, so a poll that changes nothing they
 * show lets them skip recomposition.
 *
 * The order follows what the owner reaches for: an active fault first, then the cockpit for the ride, the
 * ride log with its recordings, the checks while parked, the code lookup, the events, the bike's own log and
 * the raw values last.
 */

@Composable
fun DiagnosisScreen(vm: AppViewModel, wide: Boolean, modifier: Modifier = Modifier) {
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val feedback = rememberBikeFeedback(vm)
    val gutter = if (wide) S3.l else S3.gutter
    val ready = state.isReady
    // Before the first connect the preferred bike decides which block shows (an S2 gets its drive diagnosis).
    val vault by vm.vault.collectAsStateWithLifecycle()
    val s2TestMode by vm.bike.s2Prefs.testMode.collectAsStateWithLifecycle()
    val preferred = remember(vault) { vm.preferredBike() }
    val gate = FamilyGate.beforeConnect(state, preferred, s2TestMode)
    val s3Checks = FamilyGate.s3LiveChecks(gate)
    val s2Block = !s3Checks && gate.family == BikeFamily.S2
    // The stored bike's family before the first connect too, so an S2's code lookup reads its own table (0 to 31).
    val faults = FaultView(ready, if (ready) state.faults else emptyList(), gate.family)
    // On the inner screen the hero beside offers the cockpit while riding: this entry steps back then.
    val heroOffer = rememberHeroCockpitOffer(vm)
    // An active fault is the first thing to read, right under the link (at the top of the right pane).
    val faultsFirst = faults.ready && faults.faults.isNotEmpty()
    // While the Wache owns the bike, the cockpit (a ride) and the bike-log read (it pauses polling for minutes) are off.
    val guardState by vm.guardState.collectAsStateWithLifecycle()
    val guardOn = guardState.active
    // "Alle Ereignisse unter Diagnose" (Wache) lands on the events, not at the top of the page.
    val scroll = rememberScrollState()
    var eventsTop by remember { mutableIntStateOf(-1) }
    LaunchedEffect(GuardJump.toEvents, eventsTop) {
        if (GuardJump.toEvents && eventsTop >= 0) {
            scroll.animateScrollTo(eventsTop)
            GuardJump.toEvents = false
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = gutter)
            .padding(top = S3.l, bottom = S3.xl),
    ) {
        // On the inner screen the tab row names the page and the left pane shows the link.
        if (!wide) {
            ScreenTitle(stringResource(R.string.bike_diag_title))
            Spacer(Modifier.height(S3.s))
            Text(stringResource(R.string.bike_diag_intro), style = S3.type.body, color = S3.colors.fog)
            Spacer(Modifier.height(S3.m))
            LinkLine(state)
            Spacer(Modifier.height(S3.xl))
        }

        if (faultsFirst) {
            FaultSection(faults)
            Spacer(Modifier.height(S3.xl))
        }

        // Riding is the cockpit's job (handlebar view, ui/cockpit), so it is the page's one big button. Without a
        // bike it steps back: then connecting comes first. An S2 has the cockpit inside its own block.
        if (s3Checks) {
            if (guardOn) {
                Hint(stringResource(R.string.guard_core_error_active))
            } else {
                CockpitEntry(vm, quiet = !ready || (wide && heroOffer))
            }
            Spacer(Modifier.height(S3.xl))
        }

        RideLoggerSection(vm, ready, recorder)

        if (s3Checks) {
            // The short list for tests while parked, the wiggle test at the kick-lock cable for one.
            Spacer(Modifier.height(S3.xl))
            BikeSectionTitle(stringResource(R.string.bike_live_title))
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.cockpit_check_hint))
            if (!ready) {
                Spacer(Modifier.height(S3.m))
                ConnectPrompt(vm, state)
            }
            Spacer(Modifier.height(S3.s))
            LiveCheck(LiveView.from(state), events)
        } else if (s2Block) {
            // S2/X2: none of the S3 live signals exist; its own diagnosis (speed dropouts, error code) instead.
            Spacer(Modifier.height(S3.xl))
            S2DiagnosisEntry(vm)
        }

        Spacer(Modifier.height(S3.xl))
        if (!faultsFirst) {
            FaultSection(faults)
            Spacer(Modifier.height(S3.l))
        }
        CodeLookup(activeFaults = faults.faults, family = faults.family, standalone = faultsFirst)

        Spacer(Modifier.height(S3.xl))
        // An active fault's paragraph stands once, under "Fehler": its event row keeps to the name.
        val faultHints = remember(faults) {
            val table = BikeState(family = faults.family)
            faults.faults.mapTo(HashSet()) { table.describeFault(it).hint }
        }
        Spacer(Modifier.onGloballyPositioned { eventsTop = it.positionInParent().y.toInt() })
        EventSection(vm, events, faultHints)

        // The bike's own log and the Fahrtenschreiber built on it: S3 only (an S2 has no log service).
        if (FamilyGate.bikeLog(gate)) {
            Spacer(Modifier.height(S3.xl))
            BikeLogSection(
                vm,
                LogView(ready, state.logProgress, reading = state.logProgress != null || Op.BIKE_LOG in state.busy),
                feedback,
                guardOn = guardOn,
            )
        }

        Spacer(Modifier.height(S3.xl))
        RawDataSection(vm, ready, state.raw, feedback, s2 = gate.family == BikeFamily.S2)
    }
}

// --- Im Stand prüfen -----------------------------------------------------------------------------

/** Only what the live check shows. Equal views let the section skip a recomposition. */
@Immutable
private data class LiveView(
    val ready: Boolean,
    val status: BikeStatus?,
    val shippingImminent: Boolean,
    val moving: Boolean,
    val wheelMoving: Boolean,
    val supplyOk: Boolean?,
    val lock: LockState?,
    val coilMissing: Boolean,
    val buttons: Buttons?,
    val reconnects: Int,
) {
    companion object {
        fun from(s: BikeState): LiveView {
            val ready = s.isReady
            return LiveView(
                ready = ready,
                status = s.status.takeIf { ready },
                shippingImminent = s.shippingImminent,
                moving = ready && s.moving,
                wheelMoving = s.wheelMoving,
                supplyOk = s.supplyOk.takeIf { ready },
                lock = s.lock.takeIf { ready },
                coilMissing = ready && 42 in s.faults,
                buttons = s.buttons.takeIf { ready },
                reconnects = s.reconnects,
            )
        }
    }
}

/** What already happened since app start (or the last "Leeren"), counted from the event log. */
private class DiagHistory(val shipping: Int, val kickLockWhileRiding: Int, val supplyDrops: Int) {
    companion object {
        fun from(events: List<EventLog.Event>): DiagHistory {
            var shipping = 0
            var kick = 0
            var supply = 0
            for (e in events) {
                when (e.code) {
                    EventLog.Code.SHIPPING -> shipping++
                    EventLog.Code.KICKLOCK_RIDING -> kick++
                    EventLog.Code.SUPPLY_LOW -> supply++
                    else -> Unit
                }
            }
            return DiagHistory(shipping, kick, supply)
        }
    }
}

/**
 * The rows of the check while parked. A row explains itself in a line only when it is not in order: the
 * explanation of a healthy row goes to TalkBack alone, and the fault text itself stands once, under "Fehler".
 */
@Composable
private fun LiveCheck(v: LiveView, events: List<EventLog.Event>) {
    val c = S3.colors
    val ready = v.ready
    val history = remember(events) { DiagHistory.from(events) }
    val moving = v.moving

    val status = v.status
    CheckRow(
        label = stringResource(R.string.bike_live_status),
        value = status?.label ?: DASH,
        health = when {
            v.shippingImminent -> Health.PROBLEM
            else -> when (status) {
                null -> Health.UNKNOWN
                BikeStatus.SHIPPING -> Health.PROBLEM
                BikeStatus.SLEEPING -> if (moving) Health.PROBLEM else Health.OK
                BikeStatus.ALARM, BikeStatus.UPDATING, BikeStatus.OTHER -> Health.ATTENTION
                else -> Health.OK
            }
        },
        hint = if (status == BikeStatus.SHIPPING || v.shippingImminent) stringResource(R.string.bike_live_status_shipping_hint) else null,
        note = history.shipping.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bike_live_shipping_count, it, it) },
        noteColor = c.signal,
    )

    val coilHealth = if (!ready) Health.UNKNOWN else if (v.coilMissing) Health.PROBLEM else Health.OK
    CheckRow(
        label = stringResource(R.string.bike_live_kicklock_coil),
        value = when {
            !ready -> DASH
            v.coilMissing -> stringResource(R.string.bike_live_kicklock_coil_missing)
            else -> stringResource(R.string.bike_live_kicklock_coil_ok)
        },
        health = coilHealth,
        // Fault 42 is explained under "Fehler"; a healthy coil carries its explanation for TalkBack only.
        hint = if (coilHealth == Health.PROBLEM) stringResource(R.string.bike_live_coil_see_fault) else null,
        spoken = if (coilHealth == Health.OK) {
            "${coilHealth.spoken}. ${stringResource(R.string.bike_live_kicklock_coil_hint)}"
        } else {
            coilHealth.spoken
        },
    )

    // The kick-lock microswitch has no characteristic of its own: the bike folds it into LOCK_STATE.
    // Parked, the row shows the lock itself (it absorbed the old lock row); while moving, "locked" is the fault.
    val lock = v.lock
    val lockedWhileMoving = moving && lock == LockState.LOCKED
    val switchHealth = when {
        lock == null -> Health.UNKNOWN
        lockedWhileMoving -> Health.PROBLEM
        lock == LockState.PIN_ENTRY || history.kickLockWhileRiding > 0 -> Health.ATTENTION
        else -> Health.OK
    }
    val switchHint = stringResource(R.string.bike_live_kicklock_switch_hint)
    CheckRow(
        label = stringResource(R.string.bike_live_kicklock_switch),
        value = when {
            lock == null -> DASH
            lockedWhileMoving -> stringResource(R.string.bike_live_kicklock_reports_locked)
            else -> BikeController.lockLabel(lock).replaceFirstChar(Char::uppercase)
        },
        health = switchHealth,
        hint = switchHint.takeIf { switchHealth == Health.ATTENTION || switchHealth == Health.PROBLEM },
        note = history.kickLockWhileRiding.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bike_live_kicklock_riding, it, it) },
        noteColor = c.signal,
        spoken = if (switchHealth == Health.OK) "${switchHealth.spoken}. $switchHint" else switchHealth.spoken,
    )

    val supply = v.supplyOk
    CheckRow(
        label = stringResource(R.string.bike_live_supply),
        value = yesNo(supply),
        health = when (supply) {
            null -> Health.UNKNOWN
            true -> Health.OK
            false -> Health.PROBLEM
        },
        note = history.supplyDrops.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bike_live_supply_drops, it, it) },
    )

    val buttons = v.buttons
    val bellName = stringResource(R.string.bike_live_button_bell)
    val boostName = stringResource(R.string.bike_live_button_boost)
    val powerName = stringResource(R.string.bike_live_button_power)
    val noneName = stringResource(R.string.bike_live_buttons_none)
    CheckRow(
        label = stringResource(R.string.bike_live_buttons),
        value = when {
            buttons == null -> DASH
            else -> listOfNotNull(
                bellName.takeIf { buttons.horn },
                boostName.takeIf { buttons.boost },
                powerName.takeIf { buttons.powerButton },
            ).joinToString(", ").ifEmpty { noneName }
        },
        health = when {
            buttons == null -> Health.UNKNOWN
            buttons.powerButton && moving -> Health.PROBLEM
            buttons.horn || buttons.boost || buttons.powerButton -> Health.ATTENTION
            else -> Health.OK
        },
        hint = if (buttons != null && (buttons.horn || buttons.boost || buttons.powerButton)) {
            stringResource(R.string.bike_live_buttons_hint)
        } else null,
    )

    if (ready && v.reconnects > 0) {
        CheckRow(
            label = stringResource(R.string.bike_live_reconnects),
            value = v.reconnects.toString(),
            health = Health.ATTENTION,
            hint = stringResource(R.string.bike_live_reconnects_hint),
        )
    }
}

@Composable
private fun yesNo(v: Boolean?): String = when (v) {
    null -> DASH
    true -> stringResource(R.string.bike_yes)
    false -> stringResource(R.string.bike_no)
}

// --- Fehler --------------------------------------------------------------------------------------

@Immutable
private data class FaultView(val ready: Boolean, val faults: List<Int>, val family: BikeFamily)

@Composable
private fun FaultSection(v: FaultView) {
    val c = S3.colors
    val faults = v.faults
    BikeSectionTitle(
        stringResource(R.string.bike_faults_title),
        trailing = if (v.ready && faults.isNotEmpty()) {
            { Text(pluralStringResource(R.plurals.bike_faults_active, faults.size, faults.size), style = S3.type.label, color = c.signal) }
        } else null,
    )
    Spacer(Modifier.height(S3.m))
    when {
        !v.ready -> Hint(stringResource(R.string.bike_faults_offline))
        faults.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
            LedDot(c.led)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.bike_faults_none), style = S3.type.body, color = c.paint)
        }
        // The family's own table ([BikeState.describeFault]): an S2's code means something else than the S3 bit.
        else -> {
            val table = remember(v.family) { BikeState(family = v.family) }
            faults.forEach { FaultRow(table.describeFault(it)) }
        }
    }
}

/** One active fault: the code in signal, its name, and the explanation in the reading face (the one place it stands). */
@Composable
private fun FaultRow(f: Faults.Fault) {
    val c = S3.colors
    val spoken = stringResource(R.string.bike_fault_spoken, f.code, f.name, f.hint)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.Top,
    ) {
        Text(f.code.toString(), style = S3.type.dataLarge, color = c.signal, modifier = Modifier.widthIn(min = 56.dp))
        Spacer(Modifier.width(S3.m))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(f.name, style = S3.type.bodyStrong, color = c.paint)
            Text(f.hint, style = S3.type.body, color = c.paint)
        }
    }
    Hairline()
}

/**
 * Type the number the top tube shows; see it the way the bike draws it, plus what it means. [standalone]: the
 * active faults stand at the top of the page, so the lookup carries its own section title down here.
 */
@Composable
private fun CodeLookup(activeFaults: List<Int>, family: BikeFamily, standalone: Boolean) {
    val c = S3.colors
    var input by rememberSaveable { mutableStateOf("") }
    val code = input.toIntOrNull()
    // The S2 has a 5-bit error code of its own; the S3 a 64-bit fault pair with the wheel bit at 48.
    val table = remember(family) { BikeState(family = family) }

    if (standalone) {
        BikeSectionTitle(stringResource(R.string.bike_lookup_title))
        Spacer(Modifier.height(S3.s))
    } else {
        Text(stringResource(R.string.bike_lookup_title), style = S3.type.bodyStrong, color = c.paint)
    }
    Hint(stringResource(if (family == BikeFamily.S2) R.string.s2ui_lookup_hint else R.string.bike_lookup_hint))
    Spacer(Modifier.height(S3.m))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.filter(Char::isDigit).take(2) },
            modifier = Modifier.width(112.dp),
            textStyle = S3.type.dataLarge.copy(color = c.paint),
            label = { Text(stringResource(R.string.bike_lookup_field)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            colors = bikeFieldColors(),
        )
        Spacer(Modifier.width(S3.l))
        MatrixDisplay(
            frame = remember(input) { MatrixFrame.text(input) },
            columns = 13,
            modifier = Modifier.width(112.dp),
            description = if (input.isEmpty()) {
                stringResource(R.string.bike_lookup_display_empty)
            } else {
                stringResource(R.string.bike_lookup_display_shows, input)
            },
        )
    }
    if (code != null) {
        Spacer(Modifier.height(S3.m))
        val (name, hint) = when {
            code == Faults.WHEEL_MOVING && FamilyGate.wheelMovingCode(table) ->
                stringResource(R.string.core_fault_48_name) to stringResource(R.string.core_fault_48_hint)
            code > FamilyGate.maxFaultCode(table) ->
                stringResource(R.string.bike_lookup_unknown_name) to
                    stringResource(if (family == BikeFamily.S2) R.string.s2_lookup_unknown_hint else R.string.bike_lookup_unknown_hint)
            else -> table.describeFault(code).let { it.name to it.hint }
        }
        Text(name, style = S3.type.bodyStrong, color = c.paint)
        Text(hint, style = S3.type.body, color = c.paint)
        if (code in activeFaults) {
            Text(
                stringResource(R.string.bike_lookup_active),
                style = S3.type.label,
                color = c.signal,
                modifier = Modifier.padding(top = S3.xs),
            )
        }
    }
}

// --- Fahrtprotokoll ------------------------------------------------------------------------------

/** One recording: its file, the start as a heading, and the file's state ([LogViewNav.fileKey]) read off the main thread. */
private class DiagLogEntry(val file: File, val title: String, val key: String)

private val FILE_STAMP = Regex("""(\d{8})-(\d{6})""")
private val FILE_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Recordings shown before "Alle zeigen". */
private const val RECORDINGS_SHOWN = 3

private fun describeLog(f: File, dateFormat: DateTimeFormatter): DiagLogEntry {
    val started = FILE_STAMP.find(f.name)?.let { m ->
        runCatching { LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], FILE_STAMP_FORMAT) }.getOrNull()
    } ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(f.lastModified()), ZoneId.systemDefault())
    return DiagLogEntry(f, dateFormat.format(started), LogViewNav.fileKey(f))
}

/** What one row of the recordings says about its ride. */
@Immutable
private class RecordingSummary(val durationMillis: Long, val distanceKm: Double?, val incidents: Int, val moved: Boolean)

/**
 * The summaries of the recordings, read with [RideLogReader] once per file state (path, size, time of change) and kept
 * for the life of the process. A file the viewer already read comes from its cache.
 */
private object RecordingSummaries {
    private val map = LinkedHashMap<String, RecordingSummary>(16, 0.75f, true)

    @Synchronized private fun get(key: String): RecordingSummary? = map[key]

    @Synchronized private fun put(key: String, value: RecordingSummary) {
        map[key] = value
        while (map.size > 64) map.remove(map.keys.first())
    }

    /** Blocking: call on [Dispatchers.IO]. Null when the file cannot be read. [key] is [LogViewNav.fileKey] of [file]. */
    fun of(file: File, key: String): RecordingSummary? {
        get(key)?.let { return it }
        val log = LogViewNav.cachedRide(key) ?: runCatching { RideLogReader.read(file) }.getOrNull() ?: return null
        val summary = RecordingSummary(
            durationMillis = log.durationMillis,
            distanceKm = log.distanceKm.takeIf { log.everConnected && log.sampleCount > 1 },
            incidents = log.incidentCount,
            moved = log.movingMillis > 0L || (log.topKmh ?: 0) >= RideLogReader.MOVING_KMH,
        )
        put(key, summary)
        return summary
    }
}

@Composable
private fun RideLoggerSection(vm: AppViewModel, ready: Boolean, rec: RideRecorder.Status) {
    val c = S3.colors
    val context = LocalContext.current
    var filesVersion by remember { mutableIntStateOf(0) }
    // Deleting happens in the viewer now; it says so, and the list follows.
    val viewerChanges by LogViewNav.changes.collectAsStateWithLifecycle()
    // Raw cause only; the sentence around it is resolved in composition, in the current language.
    // Shared with the cockpit's starter, so a failed start shows wherever the logger was started.
    val startError by vm.cockpitLoggerError.collectAsStateWithLifecycle()
    val starter = rememberRideLoggerStarter(vm)
    var showAll by rememberSaveable { mutableStateOf(false) }

    val locale = appLocale()
    val datePattern = stringResource(R.string.bike_logger_entry_date)
    val dateFormat = remember(datePattern, locale) { DateTimeFormatter.ofPattern(datePattern, locale) }

    // Refresh after start/stop/delete, and every 10 samples so the live file's numbers move.
    val files by produceState<List<DiagLogEntry>?>(null, filesVersion, viewerChanges, rec.active, rec.samples / 10, dateFormat) {
        value = withContext(Dispatchers.IO) { vm.rideLogs().map { describeLog(it, dateFormat) } }
    }

    BikeSectionTitle(stringResource(R.string.bike_logger_title))
    Spacer(Modifier.height(S3.s))
    val unrestricted = rememberBatteryUnrestricted()
    if (unrestricted) {
        // Nothing stands in its way: one sentence is enough.
        Hint(stringResource(R.string.bike_logger_intro_short))
    } else {
        Text(stringResource(R.string.bike_logger_intro), style = S3.type.body, color = c.paint)
        Spacer(Modifier.height(S3.xs))
        Hint(stringResource(R.string.bike_logger_coloros))
    }
    Spacer(Modifier.height(S3.l))

    // Outlined: the page's one big button is the cockpit's (S3) or "Antrieb prüfen" (S2), and the cockpit can start
    // the logger itself. Stopping is outlined in signal, the colour of a recording that runs.
    if (rec.active) {
        LoggerRunning(rec)
        Spacer(Modifier.height(S3.l))
        QuietButton(
            stringResource(R.string.bike_logger_stop),
            { vm.stopRideLogger(); filesVersion++ },
            Modifier.fillMaxWidth(),
            color = c.signal,
        )
    } else {
        QuietButton(stringResource(R.string.bike_logger_start), { starter.start(mayAsk = true) { filesVersion++ } }, Modifier.fillMaxWidth())
        if (!ready) {
            Hint(stringResource(R.string.bike_logger_offline), Modifier.padding(top = S3.s))
        }
    }
    startError?.let {
        Text(
            stringResource(R.string.bike_logger_start_failed, it),
            style = S3.type.label,
            color = c.signal,
            modifier = Modifier.padding(top = S3.s),
        )
    }

    // The recordings right under start and stop: each one is one tap into the viewer.
    Spacer(Modifier.height(S3.l))
    Text(stringResource(R.string.bike_logger_recordings), style = S3.type.bodyStrong, color = c.paint)
    Spacer(Modifier.height(S3.xs))
    val list = files
    when {
        list == null -> Unit
        list.isEmpty() -> Hint(stringResource(R.string.bike_logger_empty))
        else -> {
            val shown = if (showAll) list else list.take(RECORDINGS_SHOWN)
            shown.forEach { entry ->
                RecordingRow(entry, live = rec.active && entry.file == rec.file) { LogViewNav.openRide(entry.file) }
            }
            if (!showAll && list.size > RECORDINGS_SHOWN) {
                TextLink(stringResource(R.string.bike_logger_show_all, list.size), { showAll = true })
            }
        }
    }

    // Once Android stops optimising YABA there is nothing left to switch off.
    if (!unrestricted) {
        Spacer(Modifier.height(S3.l))
        QuietButton(stringResource(R.string.bike_logger_battery_settings), { openBatterySettings(context) }, Modifier.fillMaxWidth())
        Hint(stringResource(R.string.bike_logger_coloros_path), Modifier.padding(top = S3.s))
    }
}

@Composable
private fun LoggerRunning(rec: RideRecorder.Status) {
    val c = S3.colors
    val started = rec.startedAtMillis ?: System.currentTimeMillis()
    val now = rememberNow(true)
    val reduce = rememberReducedMotion()
    LinkIndicator(
        stringResource(R.string.bike_logger_running_since, CLOCK.format(Instant.ofEpochMilli(started))),
        c.signal,
        pulsing = !reduce,
    )
    Spacer(Modifier.height(S3.m))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.m)) {
        Figure(stringResource(R.string.bike_logger_samples), rec.samples.toString(), Modifier.weight(1f))
        Figure(
            stringResource(R.string.bike_logger_incidents),
            rec.incidents.toString(),
            Modifier.weight(1f),
            if (rec.incidents > 0) c.signal else c.paint,
        )
        Figure(stringResource(R.string.bike_logger_duration), formatClock(now - started), Modifier.weight(1f))
    }
}

/** A number that matters, in the large condensed face, with its label under it. */
@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier, color: Color = S3.colors.paint) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = S3.type.dataLarge, color = color, maxLines = 1)
        Text(label, style = S3.type.caption, color = S3.colors.fog, maxLines = 1)
    }
}

/** Durations and distances the way the viewer writes them ("3 min 24 s", "0,78 km"). */
@Composable
private fun rememberRowFormat(): (RecordingSummary) -> Triple<String, String, Int> {
    val locale = appLocale()
    val secP = stringResource(R.string.logview_unit_sec)
    val minSecP = stringResource(R.string.logview_unit_min_sec)
    val hourMinP = stringResource(R.string.logview_unit_h_min)
    val kmP = stringResource(R.string.logview_unit_km)
    return remember(locale, secP, minSecP, hourMinP, kmP) {
        { s ->
            val sec = (s.durationMillis / 1000).coerceAtLeast(0)
            val duration = when {
                sec < 60 -> String.format(locale, secP, sec)
                sec < 3600 -> String.format(locale, minSecP, sec / 60, sec % 60)
                else -> ((sec + 30) / 60).let { min -> String.format(locale, hourMinP, min / 60, min % 60) }
            }
            val km = s.distanceKm?.let { String.format(locale, kmP, String.format(locale, if (it < 1.0) "%.2f" else "%,.1f", it)) } ?: DASH
            Triple(duration, km, s.incidents)
        }
    }
}

/**
 * One recording: one 56 dp target into the viewer. Date and time, then duration, distance and Vorfälle in the
 * data face (Vorfälle in signal once there is one); a recording in which the bike never rolled says so in fog.
 * Teilen and Löschen are in the viewer's head.
 */
@Composable
private fun RecordingRow(entry: DiagLogEntry, live: Boolean, onOpen: () -> Unit) {
    val c = S3.colors
    // A file that is still being written gets a new key with every refresh of the list, and is read again.
    val summary by produceState<RecordingSummary?>(null, entry.key) {
        value = withContext(Dispatchers.IO) { RecordingSummaries.of(entry.file, entry.key) }
    }
    val format = rememberRowFormat()
    val openLabel = stringResource(R.string.logview_open_ride_cd, entry.title)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClickLabel = openLabel, onClick = onOpen)
                .semantics(mergeDescendants = true) {}
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = S3.type.bodyStrong, color = c.paint)
                val s = summary
                when {
                    s == null -> Unit
                    !s.moved -> Text(stringResource(R.string.bike_logger_no_ride), style = S3.type.label, color = c.fog)
                    else -> {
                        val (duration, km, incidents) = format(s)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.m)) {
                            Text(duration, style = S3.type.data, color = c.paint, maxLines = 1)
                            Text(km, style = S3.type.data, color = if (km == DASH) c.fog else c.paint, maxLines = 1)
                            Text(
                                pluralStringResource(R.plurals.bike_logger_entry_incidents, incidents, incidents),
                                style = S3.type.data,
                                color = if (incidents > 0) c.signal else c.paint,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (live) {
                    Text(
                        stringResource(R.string.bike_logger_live_file),
                        style = S3.type.caption,
                        color = c.signal,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Hairline()
    }
}

/** Whether Android exempts YABA from battery optimisation; checked again each time the screen resumes. */
@Composable
private fun rememberBatteryUnrestricted(): Boolean {
    val context = LocalContext.current
    fun check() = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    var unrestricted by remember { mutableStateOf(check()) }
    LifecycleResumeEffect(Unit) {
        unrestricted = check()
        onPauseOrDispose { }
    }
    return unrestricted
}

private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
}

// --- Ereignisse ----------------------------------------------------------------------------------

private const val PAGE = 80

/** What the list shows. A Vorfall is an alert or an error; the rider's marks are counted on their own. */
private enum class EventFilter { ALL, INCIDENTS, MARKS }

private val EventLog.Event.isIncident: Boolean
    get() = kind == EventLog.Kind.ALERT || kind == EventLog.Kind.ERROR

private val EventLog.Event.isMark: Boolean get() = kind == EventLog.Kind.MARK

/** When this process started, on the wall clock: the event log holds what happened since. */
private val processStartMillis: Long by lazy {
    System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
}

/** When the list was last emptied in this process ("Leeren"), so the line above it stays true. */
private object EventsCleared {
    var atMillis by mutableStateOf<Long?>(null)
}

@Composable
private fun eventsSince(): String {
    val locale = appLocale()
    val zone = ZoneId.systemDefault()
    val cleared = EventsCleared.atMillis
    val at = Instant.ofEpochMilli(cleared ?: processStartMillis).atZone(zone)
    val time = CLOCK.format(at)
    return when {
        cleared != null -> stringResource(R.string.bike_events_since_clear, time)
        at.toLocalDate() == LocalDate.now(zone) -> stringResource(R.string.bike_events_since, time)
        else -> stringResource(
            R.string.bike_events_since_day,
            DateTimeFormatter.ofPattern(stringResource(R.string.logview_fmt_day), locale).format(at),
            time,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventSection(vm: AppViewModel, events: List<EventLog.Event>, faultHints: Set<String>) {
    val c = S3.colors
    var filter by rememberSaveable { mutableStateOf(EventFilter.ALL) }
    var limit by remember(filter) { mutableIntStateOf(PAGE) }
    var confirmClear by remember { mutableStateOf(false) }
    val incidents = remember(events) { events.count { it.isIncident } }
    val marks = remember(events) { events.count { it.isMark } }
    val list = remember(events, filter) {
        val newestFirst = events.asReversed()
        when (filter) {
            EventFilter.ALL -> newestFirst
            EventFilter.INCIDENTS -> newestFirst.filter { it.isIncident }
            EventFilter.MARKS -> newestFirst.filter { it.isMark }
        }
    }

    BikeSectionTitle(stringResource(R.string.bike_events_title))
    Hint(eventsSince(), Modifier.padding(top = S3.xs))
    Spacer(Modifier.height(S3.m))
    ChoiceBar(
        options = listOf(
            EventFilter.ALL to stringResource(R.string.bike_events_all),
            EventFilter.INCIDENTS to pluralStringResource(R.plurals.bike_events_incidents_count, incidents, incidents),
            EventFilter.MARKS to pluralStringResource(R.plurals.bike_events_marks_count, marks, marks),
        ),
        selected = filter,
        onSelect = { filter = it },
    )
    Spacer(Modifier.height(S3.s))
    if (list.isEmpty()) {
        Spacer(Modifier.height(S3.s))
        Hint(
            stringResource(
                when (filter) {
                    EventFilter.ALL -> R.string.bike_events_empty
                    EventFilter.INCIDENTS -> R.string.bike_events_no_incidents
                    EventFilter.MARKS -> R.string.bike_events_no_marks
                },
            ),
        )
    } else {
        list.take(limit).forEach { e ->
            EventRow(e, showDetail = !(e.code == EventLog.Code.FAULT && e.detail in faultHints))
        }
    }
    Spacer(Modifier.height(S3.m))
    if (list.size > limit) {
        Hint(stringResource(R.string.bike_events_showing, limit, list.size), Modifier.padding(bottom = S3.s))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
        if (list.size > limit) QuietButton(stringResource(R.string.bike_events_more), { limit += PAGE })
        QuietButton(stringResource(R.string.bike_events_clear), { confirmClear = true }, enabled = events.isNotEmpty())
    }

    OutburstBlockWhile(confirmClear)
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.bike_events_clear_title), style = S3.type.section, color = c.paint) },
            text = { Text(stringResource(R.string.bike_events_clear_text), style = S3.type.body, color = c.paint) },
            confirmButton = {
                TextButton({
                    confirmClear = false
                    vm.clearEvents()
                    EventsCleared.atMillis = System.currentTimeMillis()
                }) { Text(stringResource(R.string.bike_events_clear), style = S3.type.bodyStrong, color = c.signal) }
            },
            dismissButton = {
                TextButton({ confirmClear = false }) { Text(stringResource(R.string.bike_keep), style = S3.type.bodyStrong, color = c.paint) }
            },
        )
    }
}

/** The colour of an event by its meaning (RideLogReader.toneOf, the same rule as in the ride log viewer). */
@Composable
private fun EventLog.Event.toneColor(): Color {
    val c = S3.colors
    return when (RideLogReader.toneOf(kind.name, code?.name, isMark)) {
        RideLogReader.Tone.SIGNAL -> c.signal
        RideLogReader.Tone.LAMP -> c.lamp
        RideLogReader.Tone.MARK, RideLogReader.Tone.PLAIN -> c.paint
    }
}

/**
 * One event: time, message, and its detail on one line; a tap shows the whole detail and folds it again. Without
 * [showDetail] only the message (the detail stands elsewhere on the page).
 */
@Composable
private fun EventRow(e: EventLog.Event, showDetail: Boolean = true) {
    val c = S3.colors
    val color = e.toneColor()
    // Cockpit marks are the moments the rider asked to find again: they stand out in bold.
    val mark = e.isMark
    val detail = e.detail?.takeIf { showDetail && it.isNotBlank() }
    var open by rememberSaveable(e.seq) { mutableStateOf(false) }
    val toggleLabel = stringResource(if (open) R.string.bike_events_collapse else R.string.bike_events_expand)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (detail != null) Modifier.clickable(onClickLabel = toggleLabel) { open = !open } else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(e.time, style = S3.type.data, color = c.fog, modifier = Modifier.widthIn(min = 76.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.message, style = if (mark) S3.type.bodyStrong else S3.type.body, color = color)
            if (detail != null) {
                Text(
                    detail,
                    style = S3.type.caption,
                    color = c.fog,
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- Protokoll des Bikes ---------------------------------------------------------------------------

/** Only what the bike-log section needs from the bike state. */
@Immutable
private data class LogView(val ready: Boolean, val progress: Float?, val reading: Boolean)

/**
 * The bike's own log in one place: one read with its progress, the Fahrtenschreiber built on it as the way in,
 * and the plain text as a link. The text viewer has its own share. Until a log is saved on the phone the read is the
 * section's main button; after that the Fahrtenschreiber is ([LogInsightsEntry]).
 */
@Composable
private fun BikeLogSection(vm: AppViewModel, v: LogView, fb: BikeFeedback, guardOn: Boolean = false) {
    val c = S3.colors
    val context = LocalContext.current
    // Lives in the view model: survives tab switches, fold/unfold and a download that ends while away.
    val log by vm.bikeLog.collectAsStateWithLifecycle()
    val progress = v.progress
    val reading = v.reading

    val read: () -> Unit = { fb.run("bikelog", Op.BIKE_LOG) { vm.readBikeLog() } }
    val archive = rememberLogArchiveView(vm)

    BikeSectionTitle(stringResource(R.string.bike_log_title))
    Spacer(Modifier.height(S3.s))
    Text(stringResource(R.string.bike_log_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.l))

    if (reading) {
        Text(
            progress?.let { stringResource(R.string.bike_log_reading_percent, (it * 100).roundToInt()) }
                ?: stringResource(R.string.bike_log_reading),
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
        val label = stringResource(if (log?.text.isNullOrEmpty()) R.string.bike_log_read else R.string.bike_log_read_again)
        if (archive.saved == false) {
            BigButton(label, read, enabled = v.ready && !guardOn)
        } else {
            QuietButton(label, read, Modifier.fillMaxWidth(), enabled = v.ready && !guardOn)
        }
    }
    if (guardOn && !reading) {
        Hint(stringResource(R.string.guard_core_error_active), Modifier.padding(top = S3.s))
    } else if (!v.ready && !reading) {
        Hint(stringResource(R.string.bike_needs_link), Modifier.padding(top = S3.s))
    }
    BikeFeedbackNote(fb, "bikelog")

    // What was read: in this session, or the newest download saved on the phone.
    val saved by produceState<File?>(null, log?.atMillis) {
        value = withContext(Dispatchers.IO) { runCatching { BikeLogArchive(File(context.filesDir, "logs")).files().lastOrNull() }.getOrNull() }
    }
    val savedFile = saved
    val r = log
    var viewText: (() -> Unit)? = null
    if (!reading) {
        val text = r?.text
        when {
            r != null && (r.failed || text == null) -> {
                Spacer(Modifier.height(S3.m))
                Text(stringResource(R.string.bike_log_failed), style = S3.type.bodyStrong, color = c.signal)
            }
            r != null && text != null -> {
                val lines = remember(text) { text.lines().count { it.isNotBlank() } }
                Spacer(Modifier.height(S3.m))
                if (lines == 0) {
                    Text(stringResource(R.string.bike_log_empty), style = S3.type.body, color = c.paint)
                } else {
                    val locale = appLocale()
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(String.format(locale, "%,d", lines), style = S3.type.dataLarge, color = c.paint)
                        Spacer(Modifier.width(S3.s))
                        Text(
                            pluralStringResource(R.plurals.bike_log_lines_unit, lines),
                            style = S3.type.body,
                            color = c.fog,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Hint(stringResource(R.string.bike_log_read_at, CLOCK.format(Instant.ofEpochMilli(r.atMillis))))
                    viewText = { LogViewNav.openBikeLog() }
                }
            }
            savedFile != null -> {
                val savedPattern = stringResource(R.string.logview_fmt_date)
                val locale = appLocale()
                val savedAt = remember(savedFile, savedPattern, locale) {
                    val at = logFileStamp(savedFile.name) ?: savedFile.lastModified()
                    DateTimeFormatter.ofPattern(savedPattern, locale).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
                }
                Spacer(Modifier.height(S3.m))
                Hint(stringResource(R.string.logview_bike_saved, savedAt))
                viewText = { LogViewNav.openBikeLog(savedFile) }
            }
        }
    }

    // The Fahrtenschreiber: its summary line (rides, km, the last one) and the way in.
    Spacer(Modifier.height(S3.l))
    LogInsightsEntry(vm, view = archive)

    viewText?.let { open ->
        Spacer(Modifier.height(S3.xs))
        val viewLabel = stringResource(R.string.logview_open_bike_cd)
        TextLink(stringResource(R.string.bike_log_view_text), open, Modifier.semantics { contentDescription = viewLabel })
    }
}

// --- Rohdaten ------------------------------------------------------------------------------------

/**
 * The raw values. Until they have been read here, only the button: the list is long, and most of it is empty
 * before a read. Then only the characteristics that have a value, and one line for the rest.
 */
@Composable
private fun RawDataSection(vm: AppViewModel, ready: Boolean, raw: Map<String, ByteArray>, fb: BikeFeedback, s2: Boolean) {
    // Asked for here once: the list shows from the end of that read on (whatever its outcome) until the page goes.
    var asked by rememberSaveable { mutableStateOf(false) }
    val busy = fb.isBusy("raw")
    BikeSectionTitle(stringResource(R.string.bike_raw_title))
    Spacer(Modifier.height(S3.m))
    WorkingQuietButton(
        text = stringResource(R.string.bike_raw_read),
        busyText = stringResource(R.string.bike_raw_reading),
        busy = busy,
        onClick = {
            asked = true
            fb.run("raw", Op.REFRESH) { vm.bike.readDiagnostics() }
        },
        enabled = ready,
    )
    if (!ready) Hint(stringResource(R.string.bike_needs_link), Modifier.padding(top = S3.s))
    BikeFeedbackNote(fb, "raw", okText = stringResource(R.string.bike_raw_done))
    if (!asked || busy) return
    Spacer(Modifier.height(S3.m))
    Hint(stringResource(R.string.bike_raw_hint))
    Spacer(Modifier.height(S3.s))
    // S2: the decrypted status block and the plain Device Information strings.
    val rows: List<Pair<String, String>> = if (s2) {
        S2RawRows.map { (labelRes, short) -> stringResource(labelRes) to short }
    } else {
        Gatt.diagnostics.map { ch -> ch.label to ch.short }
    }
    val (withValue, without) = rows.partition { (_, short) -> raw[short]?.isNotEmpty() == true }
    withValue.forEach { (label, short) -> RawRow(label, short, raw[short]) }
    if (without.isNotEmpty()) {
        Hint(
            pluralStringResource(R.plurals.bike_raw_without_value, without.size, without.size),
            Modifier.padding(top = S3.s),
        )
    }
}

/** The S2 characteristics the raw view lists, as (label, short id); keys as the S2 driver stores them in [BikeState.raw]. */
private val S2RawRows: List<Pair<Int, String>> = (listOf(S2Gatt.PARAMETERS) + S2Gatt.deviceInfo).mapNotNull { id ->
    S2Gatt.labelRes(id)?.let { it to rawKey(id) }
}

/**
 * One characteristic. Every read stores a fresh array only for the characteristic it read, so the
 * other rows keep their array instance, skip recomposition and keep their remembered hex.
 */
@Composable
private fun RawRow(label: String, short: String, bytes: ByteArray?) {
    val c = S3.colors
    val empty = stringResource(R.string.bike_raw_empty)
    val hex = remember(bytes) { bytes?.takeIf { it.isNotEmpty() }?.toHex()?.chunked(2)?.joinToString(" ") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(label, style = S3.type.label, color = c.paint, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(S3.s))
            Text(short, style = S3.type.caption, color = c.fog)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            when {
                bytes == null -> DASH
                hex == null -> empty
                else -> hex
            },
            style = S3.type.caption,
            color = if (bytes == null) c.fog else c.paint,
        )
    }
    Hairline()
}
