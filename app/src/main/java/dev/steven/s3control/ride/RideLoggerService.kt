package dev.steven.s3control.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.steven.s3control.MainActivity
import dev.steven.s3control.R
import dev.steven.s3control.S3App
import dev.steven.s3control.ble.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Keeps the process and BLE link alive while the ride logger runs with the screen off. */
class RideLoggerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as S3App).graph
        if (intent?.action == ACTION_STOP) {
            graph.recorder.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        // The S2 GPS comparison (GpsSpeed) also needs location while the screen is off; without it the type is as always.
        val withGps = if (Build.VERSION.SDK_INT >= 29 && graph.recorder.wantsGps) type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else type
        val notification = build(getString(R.string.core_ride_notif_running), getString(R.string.core_ride_notif_connecting))
        try {
            // Honour the startForegroundService contract first, then decide whether we are needed.
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, withGps)
            } catch (e: Exception) {
                // Location refused (permission gone, started from the background): log the ride without GPS.
                if (withGps == type) throw e
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            }
        } catch (e: Exception) {
            graph.recorder.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // A restart after process death has no recorder state to serve: leave quietly.
        if (intent == null || !graph.recorder.status.value.active) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        val nm = getSystemService(NotificationManager::class.java)
        scope.launch {
            combine(graph.bike.state, graph.recorder.status) { b, r ->
                if (!r.active) return@combine null
                val line = when (b.link) {
                    Link.READY -> buildList {
                        b.speedKmh?.let { add(getString(R.string.core_detail_speed, it)) }
                        b.battery?.let { add(getString(R.string.core_ride_notif_battery, it)) }
                        b.status?.let { add(it.label) }
                    }.joinToString(", ")
                    Link.LOST -> getString(R.string.core_ride_notif_lost)
                    else -> getString(R.string.core_ride_notif_searching)
                }
                val title = when (r.incidents) {
                    0 -> getString(R.string.core_ride_notif_running)
                    else -> resources.getQuantityString(R.plurals.core_ride_notif_incidents, r.incidents, r.incidents)
                }
                title to line
            }.filterNotNull().distinctUntilChanged().conflate().collect { (title, line) ->
                nm.notify(NOTIFICATION_ID, build(title, line))
                delay(1_000) // at most one update per second
            }
        }
        return START_NOT_STICKY
    }

    private var started = false
    private val openIntent by lazy {
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
    private val stopIntent by lazy {
        PendingIntent.getService(
            this, 1, Intent(this, RideLoggerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.core_ride_notif_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Creating an existing channel again only updates its name and description, so a changed
        // app language reaches the system settings too.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ride_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.ride_channel_description)
            },
        )
    }

    companion object {
        const val CHANNEL = "ride"
        const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "dev.steven.s3control.STOP_RIDE"
    }
}
