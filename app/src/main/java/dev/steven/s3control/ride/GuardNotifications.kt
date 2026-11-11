package dev.steven.s3control.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import dev.steven.s3control.MainActivity
import dev.steven.s3control.R
import dev.steven.s3control.ble.GuardAlarm
import dev.steven.s3control.ble.GuardPhase
import dev.steven.s3control.ble.GuardState
import java.util.Date

/**
 * The Wache's notifications (the guard design 6): the silent ongoing one of the foreground service (channel
 * `guard_status`) and the alarm, lost and restart notifications that must ring (channel `guard`). No emoji.
 */
object GuardNotifications {
    const val CHANNEL_ALARM = "guard"
    const val CHANNEL_STATUS = "guard_status"

    const val ID_ONGOING = 21
    const val ID_ALARM = 22
    const val ID_LOST = 23
    const val ID_RESTART = 24
    const val ID_NOT_LOCKED = 25

    const val ACTION_END = "dev.steven.s3control.GUARD_END"
    const val ACTION_SILENCE = "dev.steven.s3control.GUARD_SILENCE"
    const val ACTION_RESTORE = "dev.steven.s3control.GUARD_RESTORE"
    /** The arm tap's own start (no action of its own in the service; documents the caller). */
    const val ACTION_ARM = "dev.steven.s3control.GUARD_ARM"

    private val VIBRATION = longArrayOf(0, 600, 300, 600, 300, 600)

    /**
     * Creates both channels. Sound and vibration of an existing channel never change after the first creation (Android
     * rule); creating it again only updates name and description, so a changed app language reaches the settings.
     */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarm = NotificationChannel(CHANNEL_ALARM, context.getString(R.string.guard_core_channel_alarm_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.guard_core_channel_alarm_desc)
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = VIBRATION
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val status = NotificationChannel(CHANNEL_STATUS, context.getString(R.string.guard_core_channel_status_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.guard_core_channel_status_desc)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(alarm)
        nm.createNotificationChannel(status)
    }

    /** The foreground service's notification for [s] (the guard design 6.2, id 21). */
    fun ongoing(context: Context, s: GuardState): Notification {
        val since = s.armedAtMillis?.let { time(context, it) } ?: "–"
        val (title, text) = when (s.phase) {
            GuardPhase.ARMING -> context.getString(R.string.guard_core_notif_arming_title) to context.getString(R.string.guard_core_notif_arming_text)
            GuardPhase.ALARM -> {
                val a = s.lastAlarm
                (if (a != null) context.getString(R.string.guard_core_notif_alarm_title_at, time(context, a.atMillis)) else context.getString(R.string.guard_core_notif_alarm_title)) to
                    (a?.text ?: "")
            }
            GuardPhase.LOST -> context.getString(R.string.guard_core_notif_active_title) to context.getString(R.string.guard_core_notif_searching)
            GuardPhase.DISARMING -> context.getString(R.string.guard_core_notif_disarming_title) to ""
            else -> context.getString(R.string.guard_core_notif_active_title) to context.getString(
                if (s.bikeWatch) R.string.guard_core_notif_active_text else R.string.guard_core_notif_active_text_phone, since,
            )
        }
        val b = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (text.isNotEmpty()) b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        if (s.phase == GuardPhase.ALARM) b.addAction(silenceAction(context))
        if (s.phase != GuardPhase.DISARMING) b.addAction(endAction(context))
        return b.build()
    }

    /** A trigger (id 22): rings when [alert], else updates the shown notification silently. */
    fun postAlarm(context: Context, a: GuardAlarm, alert: Boolean) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.guard_core_notif_alarm_title))
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!alert)
            .setOngoing(false)
            .setAutoCancel(false)
            .setWhen(a.atMillis)
            .setShowWhen(true)
            .setContentIntent(openIntent(context))
            .addAction(silenceAction(context))
            .addAction(endAction(context))
            .build()
        notify(context, ID_ALARM, n)
    }

    /** The link has been down longer than 20 s while armed (id 23), once per loss. */
    fun postLost(context: Context, bikeWatch: Boolean) {
        val text = context.getString(if (bikeWatch) R.string.guard_core_notif_lost_watch else R.string.guard_core_notif_lost)
        val n = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.guard_core_notif_lost_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setContentIntent(openIntent(context))
            .addAction(endAction(context))
            .build()
        notify(context, ID_LOST, n)
    }

    /**
     * YABA's own re-arm opened the lock and its re-lock attempts did not close it again (id 25). [lock] is what 5521
     * read last, or null when no attempt got an answer.
     */
    fun postNotLocked(context: Context, lock: Int?) {
        val text = if (lock != null) context.getString(R.string.guard_core_notif_not_locked, lock.toString())
        else context.getString(R.string.guard_core_notif_not_locked_unknown)
        val n = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.guard_core_notif_not_locked_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setContentIntent(openIntent(context))
            .addAction(endAction(context))
            .build()
        notify(context, ID_NOT_LOCKED, n)
    }

    /** Android refused to (re)start the service (id 24); a tap opens the app, which starts it again. */
    fun postRestart(context: Context) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.guard_core_notif_restart_title))
            .setContentText(context.getString(R.string.guard_core_notif_restart_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.guard_core_notif_restart_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context))
            .build()
        notify(context, ID_RESTART, n)
    }

    /** Debug demo only: the ongoing notification without a service. */
    internal fun postOngoingDemo(context: Context, s: GuardState) = notify(context, ID_ONGOING, ongoing(context, s))

    fun cancel(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id) }
    }

    /** Every Wache notification (the Wache ended); an issue is shown in the app. */
    fun cancelAll(context: Context) {
        for (id in listOf(ID_ONGOING, ID_ALARM, ID_LOST, ID_RESTART, ID_NOT_LOCKED)) cancel(context, id)
    }

    /** Short local time ("21:14" or "9:14 PM", as the phone is set). */
    fun time(context: Context, atMillis: Long): String = android.text.format.DateFormat.getTimeFormat(context).format(Date(atMillis))

    private fun notify(context: Context, id: Int, n: Notification) {
        // Without POST_NOTIFICATIONS (Android 13+) the system drops it; the siren works either way.
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getForegroundService(
        context, code, Intent(context, GuardService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "Wache beenden" unlocks the bike: it asks for an unlocked phone (effective on Android 12+). */
    private fun endAction(context: Context): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, context.getString(R.string.guard_core_action_end), serviceIntent(context, ACTION_END, 31))
            .setAuthenticationRequired(true)
            .build()

    /** "Sirene aus" re-locks and watches again: no unlocked phone needed. */
    private fun silenceAction(context: Context): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, context.getString(R.string.guard_core_action_silence), serviceIntent(context, ACTION_SILENCE, 32))
            .build()
}
