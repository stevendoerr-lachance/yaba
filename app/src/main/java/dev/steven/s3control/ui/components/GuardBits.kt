package dev.steven.s3control.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.GuardEvent
import dev.steven.s3control.ble.GuardIssue
import dev.steven.s3control.ble.GuardPhase
import dev.steven.s3control.ble.GuardRules
import dev.steven.s3control.ble.GuardState
import dev.steven.s3control.ui.BlePermissions
import dev.steven.s3control.ui.Destination
import dev.steven.s3control.ui.LocalNavigator
import dev.steven.s3control.ui.onboarding.TextLink
import dev.steven.s3control.ui.theme.S3
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The Wache on Fahren (the guard design section 7): the section in RideControls, its "Zuletzt" list, the hints it needs
 * (notifications, battery optimisation) and the arm tap that asks for notifications first. The hero's part (tube, header,
 * lever) lives in RideScreen. Theft is a serious moment: no call on the tube, plain sentences, signal only for the alarm.
 */

/** How many lines of the Wache the section lists ("Zuletzt"); Diagnose has them all. */
private const val EventsShown = 6

/** "Alle Ereignisse unter Diagnose": Diagnose scrolls to its events once it is shown, then clears this. */
object GuardJump {
    var toEvents by mutableStateOf(false)
}

/**
 * The section "Wache" by phase (the guard design 7.5). OFF: what it does, "Sirene sofort", "Wache scharf schalten" with the
 * reason when it is not offered. Active: since when, whether the bike confirmed its own watch, the alarm with "Sirene aus",
 * the newest lines and "Wache beenden". S3 only: RideControls shows it behind [dev.steven.s3control.ui.FamilyGate.guard].
 */
@Composable
fun GuardSection(vm: AppViewModel, s: BikeState, guard: GuardState, modifier: Modifier = Modifier) {
    val sending: String? = when (guard.phase) {
        GuardPhase.ARMING -> stringResource(R.string.guard_arming)
        GuardPhase.REARMING -> stringResource(R.string.guard_rearming)
        GuardPhase.DISARMING -> stringResource(R.string.guard_disarming)
        else -> null
    }
    val trailing: (@Composable RowScope.() -> Unit)? = if (sending != null) ({ ShellSending(sending) }) else null
    Column(modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.guard_section_title), trailing = trailing)
        Spacer(Modifier.height(S3.m))
        // What went wrong last time stands first, until it is dismissed or the next arm clears it.
        issueText(guard)?.let { text ->
            // Signal only where the bike may be left open or alarming; the rest is a lamp line.
            if (guard.issue == GuardIssue.DISARM_UNCONFIRMED || guard.issue == GuardIssue.NOT_LOCKED) ShellSignalLine(text) else GuardLampLine(text)
            TextLink(stringResource(R.string.guard_issue_dismiss), { vm.guard.clearIssue() })
            Spacer(Modifier.height(S3.s))
        }
        when (guard.phase) {
            GuardPhase.OFF -> GuardOff(vm, s, guard)
            GuardPhase.ARMING -> GuardArming(vm, guard)
            GuardPhase.WATCHING, GuardPhase.REARMING -> GuardWatching(vm, guard)
            GuardPhase.ALARM -> GuardAlarming(vm, guard)
            GuardPhase.LOST -> GuardLost(vm, guard)
            GuardPhase.DISARMING -> GuardDisarming(s, guard)
        }
    }
}

@Composable
private fun ColumnScope.GuardOff(vm: AppViewModel, s: BikeState, guard: GuardState) {
    val c = S3.colors
    val blocker = GuardRules.armBlocker(s)
    val armer = rememberGuardArmer(vm)
    Text(stringResource(R.string.guard_intro), style = S3.type.body, color = c.paint)
    Spacer(Modifier.height(S3.s))
    SirenSwitch(vm, guard)
    Spacer(Modifier.height(S3.m))
    BigButton(stringResource(R.string.guard_arm), onClick = { armer.arm() }, enabled = blocker == null)
    blockerText(blocker)?.let {
        Spacer(Modifier.height(S3.s))
        Hint(it)
    }
    GuardHints()
}

@Composable
private fun ColumnScope.GuardArming(vm: AppViewModel, guard: GuardState) {
    Text(
        stringResource(R.string.guard_arming_text, GuardRules.ARM_MAX_SECONDS),
        style = S3.type.body,
        color = S3.colors.paint,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(S3.s))
    SirenSwitch(vm, guard)
    Spacer(Modifier.height(S3.m))
    EndButton(vm)
}

/** WATCHING and REARMING (the retry waits while the re-arm runs). */
@Composable
private fun ColumnScope.GuardWatching(vm: AppViewModel, guard: GuardState) {
    val c = S3.colors
    val since = guard.armedAtMillis?.let { rememberShortTime(it) }
    if (since != null) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(S3.xs),
        ) {
            ShellLed(lit = true, color = c.led, modifier = Modifier.padding(top = 3.dp))
            Text(stringResource(R.string.guard_active_since, since), style = S3.type.bodyStrong, color = c.paint, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(S3.s))
    }
    // The bike is open (lock found open after a reconnect, or YABA's re-lock did not take): the issue line above says
    // so, and "Erneut versuchen" is the way to lock it again.
    val notLocked = guard.issue == GuardIssue.NOT_LOCKED
    if (guard.bikeWatch) {
        Text(stringResource(R.string.guard_watch_bike), style = S3.type.body, color = c.paint)
    } else {
        // Not confirmed: the exact 5523 value, and what YABA still sees on its own.
        GuardLampLine(
            guard.defence?.let { stringResource(R.string.guard_watch_unconfirmed, it) }
                ?: stringResource(R.string.guard_watch_unconfirmed_unknown),
        )
    }
    if (!guard.bikeWatch || notLocked) {
        Spacer(Modifier.height(S3.s))
        QuietButton(
            stringResource(R.string.guard_watch_retry),
            onClick = { vm.guard.retryWatch() },
            enabled = guard.phase == GuardPhase.WATCHING,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(S3.s))
    SirenSwitch(vm, guard)
    GuardEvents(guard)
    Spacer(Modifier.height(S3.l))
    EndButton(vm)
    JournalLink(vm, guard)
    GuardHints()
}

@Composable
private fun ColumnScope.GuardAlarming(vm: AppViewModel, guard: GuardState) {
    val c = S3.colors
    val alarm = guard.lastAlarm
    val at = alarm?.atMillis?.let { rememberShortTime(it) }
    // The one line TalkBack reads out at once.
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(S3.xs),
    ) {
        ShellLed(
            lit = true,
            color = c.signal,
            glow = if (c.isDark) c.signal.copy(alpha = 0.33f) else Color.Transparent,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            if (at != null) stringResource(R.string.guard_alarm_at, at) else stringResource(R.string.guard_tube_alarm_cd),
            style = S3.type.bodyStrong,
            color = c.signal,
            modifier = Modifier.weight(1f),
        )
    }
    if (alarm != null) {
        Spacer(Modifier.height(S3.s))
        Text(alarm.text, style = S3.type.body, color = c.paint)
    }
    Spacer(Modifier.height(S3.l))
    BigButton(stringResource(R.string.guard_silence), onClick = { vm.guard.silence() }, color = c.signal, onColor = c.onSignal)
    Spacer(Modifier.height(S3.s))
    EndButton(vm)
    GuardEvents(guard)
}

@Composable
private fun ColumnScope.GuardLost(vm: AppViewModel, guard: GuardState) {
    GuardLampLine(stringResource(if (guard.bikeWatch) R.string.guard_lost_watch else R.string.guard_lost))
    Spacer(Modifier.height(S3.m))
    EndButton(vm)
    GuardEvents(guard)
    GuardHints()
}

@Composable
private fun ColumnScope.GuardDisarming(s: BikeState, guard: GuardState) {
    // The link is down: "Wache beenden" waits for it, then ends in YABA only.
    if (!s.isReady) {
        Hint(
            stringResource(R.string.guard_disarming_wait, (GuardRules.DISARM_LINK_WAIT_MS / 1000).toInt()),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    GuardEvents(guard)
}

@Composable
private fun SirenSwitch(vm: AppViewModel, guard: GuardState) {
    SwitchRow(
        label = stringResource(R.string.guard_siren_now),
        caption = stringResource(R.string.guard_siren_now_caption),
        checked = guard.sirenImmediate,
        onCheckedChange = { vm.guard.setSirenImmediate(it) },
    )
}

/** "Wache beenden": never blocked by the parked-only guard; works while the siren runs and while the bike rolls. */
@Composable
private fun EndButton(vm: AppViewModel) {
    QuietButton(stringResource(R.string.guard_end), onClick = { vm.guard.disarm() }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun JournalLink(vm: AppViewModel, guard: GuardState) {
    val context = LocalContext.current
    // The journal is a file of CORE's; asked again whenever a new line came in.
    val available = remember(guard.events.size, guard.events.firstOrNull()?.atMillis) { vm.guard.journalShareIntent() != null }
    if (!available) return
    TextLink(stringResource(R.string.guard_share_journal), {
        vm.guard.journalShareIntent()?.let { send -> runCatching { context.startActivity(Intent.createChooser(send, null)) } }
    })
}

// --- Zuletzt ------------------------------------------------------------------------------------------------------------

/**
 * The newest Wache lines, newest first: the time in the data cut, the line in the Hand. A trigger carries a signal dot.
 * Side by side where there is room; below 360 dp or with the font scaled past 1.15 the time stands above its line.
 */
@Composable
private fun ColumnScope.GuardEvents(guard: GuardState) {
    val events = guard.events.take(EventsShown)
    if (events.isEmpty()) return
    val c = S3.colors
    val nav = LocalNavigator.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val timeF = remember(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    val times = remember(events, timeF) { events.map { timeF.format(Instant.ofEpochMilli(it.atMillis)) } }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timeStyle = S3.type.data
    // One column for every time, as wide as the widest of them, so the lines start at the same place.
    val timeWidth = remember(times, timeStyle, density) {
        with(density) { times.maxOf { measurer.measure(it, timeStyle).size.width }.toDp() }
    }
    Spacer(Modifier.height(S3.l))
    Text(stringResource(R.string.guard_events_title), style = S3.type.caption, color = c.fog)
    Spacer(Modifier.height(S3.xs))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = density.fontScale > 1.15f || maxWidth < 360.dp || maxWidth - timeWidth < 200.dp
        Column(Modifier.fillMaxWidth()) {
            events.forEachIndexed { i, e -> GuardEventRow(e, times[i], timeWidth, stacked) }
        }
    }
    TextLink(stringResource(R.string.guard_events_all), {
        GuardJump.toEvents = true
        nav.go(Destination.DIAGNOSIS)
    })
}

@Composable
private fun GuardEventRow(e: GuardEvent, time: String, timeWidth: androidx.compose.ui.unit.Dp, stacked: Boolean) {
    val c = S3.colors
    val line: @Composable (Modifier) -> Unit = { m ->
        val body = S3.type.body
        val dot = 6.dp
        val dotTop = with(LocalDensity.current) { ((body.lineHeight.toDp() - dot) / 2).coerceAtLeast(0.dp) }
        Row(m, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (e.alert) Box(Modifier.padding(top = dotTop).size(dot).clip(CircleShape).background(c.signal))
            Text(e.text, style = body, color = c.paint, modifier = Modifier.weight(1f))
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(vertical = S3.xs).semantics(mergeDescendants = true) {}) {
            Text(time, style = S3.type.data, color = c.fog)
            line(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = S3.xs).semantics(mergeDescendants = true) {}) {
            Text(time, style = S3.type.data, color = c.fog, modifier = Modifier.width(timeWidth).alignByBaseline())
            Spacer(Modifier.width(S3.m))
            line(Modifier.weight(1f).alignByBaseline())
        }
    }
}

// --- Lines --------------------------------------------------------------------------------------------------------------

/** A lamp dot and a sentence in paint: a state worth knowing that is not an alarm (as the hero's Bluetooth line). */
@Composable
private fun GuardLampLine(text: String, modifier: Modifier = Modifier) {
    val c = S3.colors
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(S3.xs),
    ) {
        ShellLed(lit = true, color = c.lamp, glow = Color.Transparent, modifier = Modifier.padding(top = 3.dp))
        Text(text, style = S3.type.body, color = c.paint, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun rememberShortTime(atMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(atMillis, locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))
    }
}

/** The sentence for why arming is not offered; null when it is (or for an S2, which never sees the section). */
@Composable
private fun blockerText(blocker: GuardIssue?): String? = when (blocker) {
    GuardIssue.NOT_CONNECTED -> stringResource(R.string.guard_blocker_not_connected)
    GuardIssue.NOT_PARKED -> stringResource(R.string.guard_blocker_not_parked)
    GuardIssue.BIKE_STATE -> stringResource(R.string.guard_blocker_bike_state)
    GuardIssue.BIKE_BUSY -> stringResource(R.string.guard_blocker_busy)
    else -> null
}

/** [GuardState.issue] as one sentence with the exact value it names; null when there is none to show. */
@Composable
private fun issueText(g: GuardState): String? {
    val value = g.issueValue?.toString() ?: "?"
    return when (g.issue) {
        null, GuardIssue.NOT_S3 -> null
        GuardIssue.NOT_CONNECTED, GuardIssue.NOT_PARKED, GuardIssue.BIKE_STATE, GuardIssue.BIKE_BUSY -> blockerText(g.issue)
        GuardIssue.SERVICE -> stringResource(R.string.guard_issue_service)
        GuardIssue.ARM_FAILED -> g.issueDetail?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.guard_issue_arm_failed, it) }
            ?: stringResource(R.string.guard_issue_arm_failed_unknown)
        GuardIssue.ARM_FAILED_PARTLY -> stringResource(R.string.guard_issue_arm_failed_partly, g.issueDetail?.takeIf { it.isNotBlank() } ?: "?")
        GuardIssue.ARM_ALARM_DURING -> stringResource(R.string.guard_issue_alarm_during)
        GuardIssue.ARM_ALARM_MODE -> stringResource(R.string.guard_issue_alarm_mode, value)
        GuardIssue.ARM_LOCK -> stringResource(R.string.guard_issue_lock, value)
        GuardIssue.DISARM_UNCONFIRMED -> stringResource(R.string.guard_issue_disarm_unconfirmed, value)
        GuardIssue.DISARM_LOCKED -> stringResource(R.string.guard_issue_disarm_locked, value)
        GuardIssue.ENDED_OFFLINE -> stringResource(R.string.guard_issue_ended_offline)
        GuardIssue.ENDED_PARTLY -> stringResource(R.string.guard_issue_ended_partly)
        GuardIssue.BIKE_GONE -> stringResource(R.string.guard_issue_bike_gone)
        GuardIssue.NOT_LOCKED -> g.issueValue?.let { stringResource(R.string.guard_issue_not_locked, it.toString()) }
            ?: stringResource(R.string.guard_issue_not_locked_unknown)
    }
}

// --- Hints --------------------------------------------------------------------------------------------------------------

/** Notification requests in this process, the arm tap's included: after two, Android shows no prompt any more. */
private object GuardAsks {
    var notifications by mutableIntStateOf(0)
}

/** Notifications off (Android 13+) and battery optimisation on: both cost the Wache, both have one way to fix them. */
@Composable
private fun GuardHints() {
    NotificationHint()
    BatteryHint()
}

@Composable
private fun NotificationHint() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(BlePermissions.notificationsGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = BlePermissions.notificationsGranted(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) return
    Spacer(Modifier.height(S3.l))
    GuardLampLine(stringResource(R.string.guard_notifications_off))
    Spacer(Modifier.height(S3.s))
    QuietButton(
        stringResource(R.string.guard_notifications_allow),
        onClick = {
            if (GuardAsks.notifications >= 2) {
                openNotificationSettings(context)
            } else {
                GuardAsks.notifications++
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure { openNotificationSettings(context) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BatteryHint() {
    if (rememberBatteryUnrestricted()) return
    val context = LocalContext.current
    Spacer(Modifier.height(S3.l))
    Hint(stringResource(R.string.guard_battery_hint))
    Spacer(Modifier.height(S3.s))
    QuietButton(stringResource(R.string.guard_battery_open), { openBatterySettings(context) }, Modifier.fillMaxWidth())
}

/** Whether Android exempts YABA from battery optimisation; checked again each time the screen resumes (as Diagnose). */
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

private fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
}

// --- Arming -------------------------------------------------------------------------------------------------------------

/** The arm tap: on Android 13+ it first asks for notifications, while the phone is in the hand, then arms either way. */
class GuardArmer internal constructor(private val launch: () -> Unit) {
    fun arm() = launch()
}

/**
 * "Wache scharf schalten" as a tap (pattern of rememberRideLoggerStarter): without the notification permission Android 13+
 * asks first and the Wache arms whatever the answer (the siren works without it; the section then shows the hint).
 * [dev.steven.s3control.ble.BikeGuard.arm] checks the blocker again itself.
 */
@Composable
fun rememberGuardArmer(vm: AppViewModel): GuardArmer {
    val context = LocalContext.current
    val latestVm by rememberUpdatedState(vm)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { latestVm.guard.arm() }
    return remember(launcher, context) {
        GuardArmer {
            val ask = Build.VERSION.SDK_INT >= 33 && !BlePermissions.notificationsGranted(context) && GuardAsks.notifications < 2
            if (ask) {
                GuardAsks.notifications++
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure { latestVm.guard.arm() }
            } else {
                latestVm.guard.arm()
            }
        }
    }
}
