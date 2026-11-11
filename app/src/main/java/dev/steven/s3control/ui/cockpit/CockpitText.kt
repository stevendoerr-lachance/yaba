package dev.steven.s3control.ui.cockpit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ui.components.appLocale
import dev.steven.s3control.ui.theme.ArchivoCondensed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * Everything the cockpit says, resolved from the session's enums and numbers in composition, so it
 * follows the app language. Copy is Zone 1: plain, exact, short.
 */

/** Tabular figures: changing values never shift their neighbours. */
internal const val TNUM = "tnum"

/** The dimension line's face until BRAND lands `S3.type.measure`: condensed 600, 13/16, tabular. */
internal val CockpitMeasure = TextStyle(
    fontFamily = ArchivoCondensed,
    fontWeight = FontWeight(600),
    fontSize = 13.sp,
    lineHeight = 16.sp,
    fontFeatureSettings = "tnum, zero",
)

/** Clock times in the app language; "17:42:05" in German, "5:42:07 PM" in English. */
internal class ClockFormats(locale: Locale) {
    private val zone = ZoneId.systemDefault()
    private val seconds = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
    private val minutes = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)

    // Archivo has no U+202F, so the narrow space some locales put before AM/PM becomes U+00A0.
    fun seconds(ms: Long): String = seconds.format(Instant.ofEpochMilli(ms)).replace(' ', ' ')
    fun minutes(ms: Long): String = minutes.format(Instant.ofEpochMilli(ms)).replace(' ', ' ')
}

@Composable
internal fun rememberClockFormats(): ClockFormats {
    val locale = appLocale()
    return remember(locale) { ClockFormats(locale) }
}

/**
 * The fault's cockpit wording: on an S3, 42, 18 and 8 have their own neutral lines, the rest the table name. An S2's
 * codes are its own ([BikeState.describeFault]): its 8 is a torque sensor, not the battery, so only the table name.
 */
@Composable
@ReadOnlyComposable
internal fun faultLine(code: Int, family: BikeFamily = BikeFamily.UNKNOWN): String = when {
    family == BikeFamily.S2 -> stringResource(faultTable(family).describeFault(code).nameRes)
    code == 42 -> stringResource(R.string.cockpit_alert_fault_42)
    code == 18 -> stringResource(R.string.cockpit_alert_fault_18)
    code == 8 -> stringResource(R.string.cockpit_alert_fault_8)
    else -> stringResource(faultTable(family).describeFault(code).nameRes)
}

/** A state that only says whose fault table applies (the cockpit view carries the family, not the state). */
private val s2Table = BikeState(family = BikeFamily.S2)
private val s3Table = BikeState()

private fun faultTable(family: BikeFamily): BikeState = if (family == BikeFamily.S2) s2Table else s3Table

@Composable
@ReadOnlyComposable
internal fun variantHeadline(v: TakeoverVariant): String = stringResource(v.headlineRes())

/** Short label of an alert that cleared, for the history line. */
@Composable
@ReadOnlyComposable
private fun clearedLabel(kind: AlertKind?, code: Int, family: BikeFamily): String = when (kind) {
    AlertKind.LINK_LOST -> stringResource(R.string.cockpit_alert_link_lost_plain)
    AlertKind.SLEEPING -> stringResource(R.string.cockpit_alert_sleeping)
    AlertKind.FAULT -> faultLine(code, family)
    AlertKind.LOCK_RIDING -> stringResource(R.string.cockpit_alert_lock_riding)
    AlertKind.POWER_BUTTON -> stringResource(R.string.cockpit_alert_power_button)
    else -> ""
}

/** The alert line's sentence. */
@Composable
internal fun alertText(a: AlertView, clock: ClockFormats): String = when (a.kind) {
    AlertKind.BACK_AGAIN -> stringResource(R.string.cockpit_exit_back_again)
    AlertKind.SHIPPING -> stringResource(R.string.core_log_shipping)
    AlertKind.LINK_LOST -> if (a.n1 >= 0) {
        stringResource(R.string.cockpit_alert_link_lost, a.n1)
    } else {
        stringResource(R.string.cockpit_alert_link_lost_plain)
    }
    AlertKind.SLEEPING -> stringResource(R.string.cockpit_alert_sleeping)
    AlertKind.FAULT -> faultLine(a.code, a.family)
    AlertKind.LOCK_RIDING -> stringResource(R.string.cockpit_alert_lock_riding)
    AlertKind.POWER_BUTTON -> stringResource(R.string.cockpit_alert_power_button)
    // Next to the exit slot the time stays short (07:52); the seconds are in the log.
    AlertKind.MEMORY -> {
        val name = a.variant?.let { variantHeadline(it) } ?: ""
        if (a.n1 > 1) {
            stringResource(R.string.cockpit_alert_memory_count, name, a.n1, clock.minutes(a.t2))
        } else {
            stringResource(R.string.cockpit_alert_memory, name, clock.minutes(a.t1))
        }
    }
    AlertKind.SUPPLY_LOW -> stringResource(R.string.cockpit_alert_supply_low)
    AlertKind.BATTERY_JUMP -> stringResource(R.string.cockpit_alert_battery_jump, a.n1, a.n2)
    AlertKind.BATTERY_LOW -> stringResource(R.string.cockpit_alert_battery_low, a.n1)
    AlertKind.STALE -> stringResource(R.string.cockpit_alert_stale, a.n1)
    AlertKind.SEARCHING -> stringResource(R.string.cockpit_alert_searching)
    AlertKind.LOGGER_OFF -> stringResource(R.string.cockpit_alert_logger_off)
    AlertKind.HISTORY -> {
        val label = clearedLabel(a.ref, a.code, a.family)
        if (a.t2 - a.t1 < 1_500) {
            stringResource(R.string.cockpit_alert_last_at, label, clock.seconds(a.t2))
        } else {
            stringResource(R.string.cockpit_alert_last_span, label, clock.seconds(a.t1), clock.seconds(a.t2))
        }
    }
}

@Composable
@ReadOnlyComposable
internal fun lampValueText(v: LampValue): String = when (v) {
    LampValue.NONE -> "–"
    LampValue.DETECTED -> stringResource(R.string.cockpit_val_detected)
    LampValue.MISSING -> stringResource(R.string.cockpit_val_missing)
    LampValue.QUIET -> stringResource(R.string.cockpit_val_quiet)
    LampValue.OPEN -> stringResource(R.string.cockpit_val_open)
    LampValue.LOCKED -> stringResource(R.string.cockpit_val_locked)
    LampValue.CODE -> stringResource(R.string.cockpit_val_code)
    LampValue.SUPPLY_OK -> stringResource(R.string.cockpit_val_supply_ok)
    LampValue.SUPPLY_LOW -> stringResource(R.string.cockpit_val_supply_low)
}

/** The link word next to the status dot. */
@Composable
@ReadOnlyComposable
internal fun linkText(link: CockpitLink): String = stringResource(
    when (link) {
        CockpitLink.LIVE -> R.string.cockpit_link_live
        CockpitLink.STALE -> R.string.cockpit_link_stale
        CockpitLink.SEARCHING -> R.string.cockpit_link_searching
        CockpitLink.CONNECTING -> R.string.cockpit_link_connecting
        CockpitLink.LOST -> R.string.cockpit_link_lost
        CockpitLink.OFFLINE -> R.string.cockpit_link_offline
    },
)

/** What TalkBack reads for the speed matrix. */
@Composable
internal fun matrixSpoken(v: CockpitView): String = when (v.matrix) {
    MatrixKind.LOG -> stringResource(R.string.cockpit_matrix_log, v.logPercent ?: 0)
    MatrixKind.SPEED -> stringResource(R.string.cockpit_speed_spoken, v.speed ?: 0)
    MatrixKind.STALE -> stringResource(R.string.cockpit_matrix_no_data)
    MatrixKind.NO_SPEED -> stringResource(R.string.cockpit_speed_unknown)
    MatrixKind.BLE_SIGNAL, MatrixKind.BLE_FOG -> stringResource(R.string.cockpit_matrix_searching)
    MatrixKind.BLE_OFFLINE -> stringResource(R.string.cockpit_link_offline)
}

@Composable
@ReadOnlyComposable
internal fun lockWord(lock: LockState?): String = lock?.let { stringResource(BikeController.lockLabelRes(it)) } ?: "?"

/** The frozen moment as one sentence: time, speed, lock, supply, faults. */
@Composable
internal fun snapshotLine(s: Snapshot, clock: ClockFormats): String = stringResource(
    R.string.cockpit_to_snapshot,
    clock.seconds(s.atMillis),
    s.speed?.toString() ?: "?",
    lockWord(s.lock),
    when (s.supply) {
        true -> stringResource(R.string.cockpit_val_supply_ok)
        false -> stringResource(R.string.cockpit_val_supply_low)
        null -> "?"
    },
    if (s.faults.isEmpty()) stringResource(R.string.cockpit_to_faults_none) else stringResource(R.string.cockpit_to_faults, s.faults.joinToString(", ")),
)

/** One precursor with its age before the trigger: "Spule fehlte 1,5 s vorher (42)". */
@Composable
internal fun evidenceText(e: Evidence): String {
    val age = stringResource(R.string.cockpit_ev_age, e.agoMs / 1000.0)
    return when (e.kind) {
        PrecursorKind.FAULT -> when (e.code) {
            42 -> stringResource(R.string.cockpit_ev_coil, age)
            18 -> stringResource(R.string.cockpit_ev_key, age)
            8 -> stringResource(R.string.cockpit_ev_bms, age)
            else -> stringResource(R.string.cockpit_ev_fault, e.code, age)
        }
        PrecursorKind.LOCK -> stringResource(R.string.cockpit_ev_lock, age)
        PrecursorKind.SUPPLY -> stringResource(R.string.cockpit_ev_supply, age)
        PrecursorKind.BUTTON -> stringResource(R.string.cockpit_ev_button, age)
        PrecursorKind.JUMP -> stringResource(R.string.cockpit_ev_jump, age)
    }
}

/** Every precursor of the last 12 s as one sentence, or null when there was none. */
@Composable
internal fun evidenceLine(list: List<Evidence>): String? {
    if (list.isEmpty()) return null
    val parts = list.map { evidenceText(it) }
    return stringResource(R.string.cockpit_ev_sentence, parts.joinToString(", "))
}

@Composable
@ReadOnlyComposable
internal fun precursorText(kind: PrecursorKind?, code: Int): String = when (kind) {
    PrecursorKind.FAULT -> stringResource(R.string.cockpit_pre_fault, code)
    PrecursorKind.LOCK -> stringResource(R.string.cockpit_pre_lock)
    PrecursorKind.SUPPLY -> stringResource(R.string.cockpit_pre_supply)
    PrecursorKind.BUTTON -> stringResource(R.string.cockpit_pre_button)
    PrecursorKind.JUMP -> stringResource(R.string.cockpit_pre_jump)
    null -> "?"
}

/** The takeover's first line under the headline (phase 1). */
@Composable
internal fun takeoverLine(t: TakeoverView): String = when (t.variant) {
    TakeoverVariant.SHIPPING -> when {
        !t.linkReady -> stringResource(R.string.cockpit_to_gone)
        (t.countdown ?: 0) > 0 -> (t.countdown ?: 0).let { n -> pluralStringResource(R.plurals.cockpit_to_countdown, n, n) }
        else -> stringResource(R.string.cockpit_to_soon)
    }
    TakeoverVariant.ASLEEP -> stringResource(R.string.cockpit_to_stop)
    TakeoverVariant.OFF -> pluralStringResource(
        R.plurals.cockpit_to_off_after, t.precursorAgoS, t.precursorAgoS, precursorText(t.precursor, t.precursorCode),
    )
    TakeoverVariant.KICKLOCK -> stringResource(R.string.cockpit_to_kicklock_text)
}

/** What the shipping countdown is doing, for TalkBack: a number would be stale a second later. */
enum class CountdownState { COUNTING, ANY_MOMENT, GONE }

internal fun countdownState(t: TakeoverView): CountdownState? = when {
    t.variant != TakeoverVariant.SHIPPING -> null
    !t.linkReady -> CountdownState.GONE
    (t.countdown ?: 0) > 0 -> CountdownState.COUNTING
    else -> CountdownState.ANY_MOMENT
}

/** The line under the headline as TalkBack reads it: follows the state, never a frozen count. */
@Composable
internal fun takeoverSpoken(t: TakeoverView): String = when (countdownState(t)) {
    CountdownState.COUNTING -> stringResource(R.string.cockpit_to_counting_spoken)
    CountdownState.ANY_MOMENT -> stringResource(R.string.cockpit_to_soon)
    CountdownState.GONE -> stringResource(R.string.cockpit_to_gone)
    null -> takeoverLine(t)
}

/** Phase 2 meta under the band's headline: when and how fast, plus what else fired. */
@Composable
internal fun takeoverMeta(t: TakeoverView, clock: ClockFormats): String {
    val first = stringResource(R.string.cockpit_to_meta, clock.seconds(t.snapshot.atMillis), t.snapshot.speed?.toString() ?: "?")
    if (t.also.isEmpty()) return first
    val also = t.also.map { variantHeadline(it) }.joinToString(", ")
    return first + " " + stringResource(R.string.cockpit_to_also, also)
}

@Composable
@ReadOnlyComposable
internal fun stepTexts(v: TakeoverVariant): List<String> = when (v) {
    TakeoverVariant.KICKLOCK -> listOf(
        stringResource(R.string.cockpit_step_kl_unlock),
        stringResource(R.string.cockpit_step_kl_follow),
        stringResource(R.string.cockpit_step_kl_test),
    )
    else -> listOf(
        stringResource(R.string.cockpit_step_wake),
        stringResource(R.string.cockpit_step_battery),
        stringResource(R.string.cockpit_step_log),
    )
}

@Composable
@ReadOnlyComposable
internal fun markLine2(v: CockpitView, clock: ClockFormats): String = when {
    v.justMarked -> stringResource(R.string.cockpit_mark_done, clock.seconds(v.lastMarkAt))
    v.marks == 0 -> stringResource(R.string.cockpit_mark_none)
    else -> pluralStringResource(R.plurals.cockpit_mark_count, v.marks, v.marks, clock.seconds(v.lastMarkAt))
}

@Composable
@ReadOnlyComposable
internal fun markLine3(v: CockpitView, clock: ClockFormats): String {
    val since = v.loggerSince
    return if (v.loggerActive && since != null) {
        stringResource(R.string.cockpit_mark_logger_on, clock.minutes(since))
    } else {
        stringResource(R.string.cockpit_mark_logger_off)
    }
}
