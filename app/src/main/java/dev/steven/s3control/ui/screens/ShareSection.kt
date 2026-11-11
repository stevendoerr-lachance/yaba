package dev.steven.s3control.ui.screens

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.R
import dev.steven.s3control.SharesState
import dev.steven.s3control.data.Share
import dev.steven.s3control.ui.LocalNavigator
import dev.steven.s3control.ui.components.ErrorOrServerNotice
import dev.steven.s3control.ui.components.Hint
import dev.steven.s3control.ui.components.OutburstBlockWhile
import dev.steven.s3control.ui.components.Outbursts
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.SectionTitle
import dev.steven.s3control.ui.components.rememberHaptics
import dev.steven.s3control.ui.theme.S3
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private enum class Span(val minutes: Long, @PluralsRes val plural: Int) {
    MINUTE(1, R.plurals.sound_share_minutes),
    HOUR(60, R.plurals.sound_share_hours),
    DAY(1_440, R.plurals.sound_share_days),
    WEEK(10_080, R.plurals.sound_share_weeks),
}

/** mooovy's sharing durations: [count] × [span]. Null span = no end date. */
private class ShareDuration(val count: Int, val span: Span?) {
    val minutes: Long? get() = span?.let { it.minutes * count }
    val seconds: Long? get() = minutes?.times(60)

    /** "12 hours" / "Unlimited". */
    fun label(res: Resources): String =
        if (span == null) res.getString(R.string.sound_share_unlimited) else res.getQuantityString(span.plural, count, count)

    /** "For 12 hours" / "Unlimited". */
    fun sentence(res: Resources): String =
        if (span == null) res.getString(R.string.sound_share_unlimited) else res.getString(R.string.sound_share_for, label(res))
}

private val Durations = listOf(
    ShareDuration(30, Span.MINUTE),
    ShareDuration(1, Span.HOUR),
    ShareDuration(2, Span.HOUR),
    ShareDuration(6, Span.HOUR),
    ShareDuration(12, Span.HOUR),
    ShareDuration(1, Span.DAY),
    ShareDuration(2, Span.DAY),
    ShareDuration(3, Span.DAY),
    ShareDuration(1, Span.WEEK),
    ShareDuration(2, Span.WEEK),
    ShareDuration(1, null),
)
private const val DefaultDuration = 4 // 12 hours

/** Plain [Resources], re-read when the configuration (language) changes. */
@Composable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/** What the user just asked for, so the outcome can be reported next to the right control. */
private sealed interface ShareAction {
    val email: String
    class Invite(override val email: String) : ShareAction
    class End(override val email: String) : ShareAction
}

/** Bike sharing, embedded as a section in the Bike screen (not scrollable itself). */
@Composable
fun ShareSection(vm: AppViewModel, modifier: Modifier = Modifier) {
    val state by vm.bikeState.collectAsStateWithLifecycle()
    val vault by vm.vault.collectAsStateWithLifecycle()
    val shares by vm.shares.collectAsStateWithLifecycle()
    // Same check as vm.isLoggedIn, read from the flow so a login or logout recomposes this.
    val loggedIn = vault.session != null
    val bikeId = state.bike?.id

    Column(modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.sound_share_title))
        Spacer(Modifier.height(S3.s))
        if (!loggedIn || bikeId == null) {
            ShareGate(loggedIn = loggedIn, hasBike = state.bike != null)
        } else {
            ShareContent(vm, shares, bikeId)
        }
    }
}

@Composable
private fun ShareGate(loggedIn: Boolean, hasBike: Boolean) {
    val navigator = LocalNavigator.current
    Hint(
        when {
            !loggedIn -> stringResource(R.string.sound_share_gate_login)
            !hasBike -> stringResource(R.string.sound_share_gate_pick)
            else -> stringResource(R.string.sound_share_gate_manual)
        },
    )
    Spacer(Modifier.height(S3.m))
    QuietButton(stringResource(R.string.sound_open_garage), onClick = navigator.openGarage)
}

@Composable
private fun ShareContent(vm: AppViewModel, shares: SharesState, bikeId: Long) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val res = resources()
    val context = LocalContext.current

    var email by rememberSaveable { mutableStateOf("") }
    var durationIndex by rememberSaveable { mutableStateOf(DefaultDuration) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<ShareAction?>(null) }
    var errorAtForm by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var toEnd by remember { mutableStateOf<Share?>(null) }

    LaunchedEffect(bikeId) { vm.loadShares() }

    // The view model reports a running op via `loading`; once it settles, say what happened.
    LaunchedEffect(action, shares.loading, shares.error) {
        val a = action ?: return@LaunchedEffect
        if (shares.loading) return@LaunchedEffect
        if (shares.error == null) {
            Outbursts.recordServerAnswer(context)
            confirmation = when {
                a is ShareAction.Invite -> res.getString(R.string.sound_share_shared_with, a.email)
                a.email.isBlank() -> res.getString(R.string.sound_share_ended)
                else -> res.getString(R.string.sound_share_ended_for, a.email)
            }
            if (a is ShareAction.Invite) email = ""
            haptics.confirm()
        } else {
            haptics.reject()
        }
        action = null
    }
    LaunchedEffect(confirmation) {
        if (confirmation != null) {
            delay(6_000)
            confirmation = null
        }
    }

    val busy = shares.loading
    val inviting = busy && action is ShareAction.Invite
    val duration = Durations[durationIndex]
    val sentence = duration.sentence(res)
    val badEmail = stringResource(R.string.sound_share_bad_email)

    fun submit() {
        if (busy) return
        val address = email.trim()
        if (!looksLikeEmail(address)) {
            emailError = badEmail
            haptics.reject()
            return
        }
        keyboard?.hide()
        focus.clearFocus()
        confirmation = null
        errorAtForm = true
        action = ShareAction.Invite(address)
        vm.share(address, duration.seconds)
    }

    Hint(stringResource(R.string.sound_share_intro))
    Spacer(Modifier.height(S3.l))

    OutlinedTextField(
        value = email,
        onValueChange = {
            email = it
            emailError = null
            confirmation = null
        },
        label = { Text(stringResource(R.string.sound_share_email_label)) },
        singleLine = true,
        isError = emailError != null,
        enabled = !inviting,
        textStyle = S3.type.body,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    emailError?.let {
        Spacer(Modifier.height(S3.xs))
        Hint(it, color = c.signal)
    }

    Spacer(Modifier.height(S3.l))
    Text(sentence, style = S3.type.data, color = c.paint)
    Slider(
        value = durationIndex.toFloat(),
        onValueChange = { v ->
            val next = v.roundToInt().coerceIn(0, Durations.lastIndex)
            if (next != durationIndex) {
                durationIndex = next
                haptics.tick()
            }
        },
        valueRange = 0f..Durations.lastIndex.toFloat(),
        steps = Durations.size - 2,
        enabled = !inviting,
        colors = SliderDefaults.colors(
            thumbColor = c.led,
            activeTrackColor = c.led,
            inactiveTrackColor = c.rib,
            activeTickColor = c.onLed.copy(alpha = 0.6f),
            inactiveTickColor = c.fog.copy(alpha = 0.6f),
            disabledThumbColor = c.fog,
            disabledActiveTrackColor = c.fog,
            disabledInactiveTrackColor = c.rib,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = sentence },
    )
    Row(Modifier.fillMaxWidth()) {
        Hint(Durations.first().label(res))
        Spacer(Modifier.weight(1f))
        Hint(Durations.last().label(res))
    }
    if (duration.minutes == null) {
        Spacer(Modifier.height(S3.xs))
        Hint(stringResource(R.string.sound_share_open_ended))
    }

    Spacer(Modifier.height(S3.l))
    // The Bike screen owns the big action; sharing is one section of it.
    QuietButton(
        stringResource(if (inviting) R.string.sound_share_sharing else R.string.sound_share_submit),
        onClick = { submit() },
        enabled = !busy,
    )
    val formError = shares.error?.takeIf { errorAtForm }
    when {
        formError != null -> {
            Spacer(Modifier.height(S3.s))
            // Sharing was the person's own action: a failure of VanMoof's server shows the tube and the retry.
            ErrorOrServerNotice(formError, event = shares, onRetry = { submit() })
        }
        confirmation != null -> {
            Spacer(Modifier.height(S3.s))
            Hint(confirmation!!, color = c.led)
        }
        shares.info != null -> {
            Spacer(Modifier.height(S3.s))
            Hint(shares.info, color = c.led)
        }
    }

    Spacer(Modifier.height(S3.xl))
    ShareList(
        shares = shares,
        listError = shares.error?.takeIf { !errorAtForm },
        onEnd = { toEnd = it },
        onReload = {
            errorAtForm = false
            vm.loadShares()
        },
    )

    OutburstBlockWhile(toEnd != null)
    toEnd?.let { share ->
        val who = share.email.ifBlank { stringResource(R.string.sound_share_someone) }
        AlertDialog(
            onDismissRequest = { toEnd = null },
            title = { Text(stringResource(R.string.sound_share_end_title), style = S3.type.section) },
            text = { Text(stringResource(R.string.sound_share_end_text, who), style = S3.type.body) },
            confirmButton = {
                QuietButton(
                    stringResource(R.string.sound_share_end),
                    onClick = {
                        toEnd = null
                        confirmation = null
                        errorAtForm = false
                        action = ShareAction.End(share.email)
                        vm.revokeShare(share.guid)
                    },
                    color = c.signal,
                )
            },
            dismissButton = { QuietButton(stringResource(R.string.sound_share_cancel), onClick = { toEnd = null }) },
            containerColor = c.frame,
            titleContentColor = c.paint,
            textContentColor = c.fog,
            shape = RoundedCornerShape(20.dp),
        )
    }
}

@Composable
private fun ShareList(
    shares: SharesState,
    listError: String?,
    onEnd: (Share) -> Unit,
    onReload: () -> Unit,
) {
    val c = S3.colors
    val list = shares.shares

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sound_share_list_title), style = S3.type.label, color = c.fog, modifier = Modifier.weight(1f))
        if (shares.loading && list != null) {
            CircularProgressIndicator(Modifier.size(14.dp), color = c.fog, strokeWidth = 2.dp, trackColor = Color.Transparent)
        }
    }
    Spacer(Modifier.height(S3.s))

    when {
        list == null && listError == null -> {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.led, trackColor = c.rib)
            Spacer(Modifier.height(S3.s))
            Hint(stringResource(R.string.sound_share_loading))
        }
        list == null -> Unit
        list.isEmpty() -> Text(stringResource(R.string.sound_share_none), style = S3.type.body, color = c.paint)
        else -> Column {
            list.forEachIndexed { index, share ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.rib))
                ShareRow(share, enabled = !shares.loading, onEnd = { onEnd(share) })
            }
        }
    }

    if (listError != null) {
        Spacer(Modifier.height(S3.s))
        Hint(listError, color = c.signal)
        Spacer(Modifier.height(S3.m))
        QuietButton(stringResource(R.string.sound_share_reload), onClick = onReload, enabled = !shares.loading)
    }
}

@Composable
private fun ShareRow(share: Share, enabled: Boolean, onEnd: () -> Unit) {
    val c = S3.colors
    val res = resources()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val (until, expired) = remember(share.expiresAt, share.durationSeconds, res, locale) { untilText(share, res, locale) }
    val name = share.email.ifBlank { stringResource(R.string.sound_share_no_email) }
    // TalkBack: every row's button says whose access it ends.
    val endLabel = if (share.email.isBlank()) {
        stringResource(R.string.sound_share_end)
    } else {
        stringResource(R.string.sound_share_end_for, share.email)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = S3.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = S3.type.bodyStrong,
                color = c.paint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(until, style = S3.type.label, color = if (expired) c.lamp else c.fog)
        }
        Spacer(Modifier.width(S3.m))
        QuietButton(
            stringResource(R.string.sound_share_end),
            onClick = onEnd,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = endLabel },
        )
    }
}

private fun looksLikeEmail(s: String): Boolean {
    if (s.isEmpty() || s.any { it.isWhitespace() }) return false
    val at = s.indexOf('@')
    if (at <= 0 || at != s.lastIndexOf('@')) return false
    val dot = s.lastIndexOf('.')
    return dot > at + 1 && dot < s.lastIndex
}

/** German: "16.10.2026, 14:30"; English (US): "Oct 16, 2026, 2:30 PM". */
private fun dateFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
private val OffsetNoColon: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z")

/** "Until 16.10.2026, 14:30", "Expired on …" or "Unlimited"; second = already expired. */
private fun untilText(share: Share, res: Resources, locale: Locale): Pair<String, Boolean> {
    val raw = share.expiresAt
    if (raw != null) {
        val instant = parseInstant(raw) ?: return res.getString(R.string.sound_share_until, raw) to false
        val text = dateFormat(locale).format(instant.atZone(ZoneId.systemDefault()))
        return if (instant.isBefore(Instant.now())) {
            res.getString(R.string.sound_share_expired, text) to true
        } else {
            res.getString(R.string.sound_share_until, text) to false
        }
    }
    val seconds = share.durationSeconds ?: return res.getString(R.string.sound_share_unlimited_row) to false
    val label = Durations.firstOrNull { it.seconds == seconds }?.label(res)
        ?: if (seconds >= 86_400) {
            val days = (seconds / 86_400).toInt()
            res.getQuantityString(R.plurals.sound_share_days, days, days)
        } else {
            val hours = (seconds / 3_600).coerceAtLeast(1).toInt()
            res.getQuantityString(R.plurals.sound_share_hours, hours, hours)
        }
    return res.getString(R.string.sound_share_for_row, label) to false
}

/** ISO-8601 with offset, Zulu, without offset (taken as UTC, server time), or epoch seconds/millis. */
private fun parseInstant(raw: String): Instant? {
    val s = raw.trim().replace(' ', 'T')
    s.toLongOrNull()?.let { n -> return if (n > 100_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n) }
    return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(s) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(s, OffsetNoColon).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }.getOrNull()
}
