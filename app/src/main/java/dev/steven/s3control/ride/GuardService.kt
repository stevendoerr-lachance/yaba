package dev.steven.s3control.ride

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import dev.steven.s3control.S3App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process, the BLE link and the Wache's clock alive while the Wache is active (the guard design 2.1):
 * foreground service of type connectedDevice, START_STICKY, a partial wake lock renewed every 10 minutes, and the
 * silent ongoing notification. Its notification actions ("Wache beenden", "Sirene aus") arrive here as intents.
 */
class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val guard = (application as S3App).graph.guard
        GuardNotifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            // Honour the startForegroundService contract first, then decide whether we are needed.
            ServiceCompat.startForeground(this, GuardNotifications.ID_ONGOING, GuardNotifications.ongoing(this, guard.state.value), type)
        } catch (e: Exception) {
            // A sticky restart Android does not allow from the background, or Bluetooth permission gone.
            GuardNotifications.postRestart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            GuardNotifications.ACTION_END -> guard.disarm()
            GuardNotifications.ACTION_SILENCE -> guard.silence()
            GuardNotifications.ACTION_RESTORE -> guard.restore()
        }
        if (!guard.serviceWanted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        GuardNotifications.cancel(this, GuardNotifications.ID_RESTART)
        if (!started) {
            started = true
            holdWakeLock()
            val nm = getSystemService(NotificationManager::class.java)
            scope.launch {
                guard.state
                    .distinctUntilChanged { a, b -> a.phase == b.phase && a.bikeWatch == b.bikeWatch && a.lastAlarm == b.lastAlarm && a.armedAtMillis == b.armedAtMillis }
                    .conflate()
                    .collect { s ->
                        // The Wache is over (BikeGuard never stops this service itself: a stop before startForeground
                        // would crash the app). Checked right before every notify, so no update can outlive the stop.
                        if (!guard.serviceWanted) {
                            ServiceCompat.stopForeground(this@GuardService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            return@collect
                        }
                        runCatching { nm?.notify(GuardNotifications.ID_ONGOING, GuardNotifications.ongoing(this@GuardService, s)) }
                        delay(1_000) // at most one update per second
                    }
            }
            scope.launch {
                while (isActive) {
                    delay(WAKE_RENEW_MS)
                    holdWakeLock()
                }
            }
        }
        return START_STICKY
    }

    /**
     * One partial wake lock, not reference counted, with a timeout so it can never outlive a crash by much: the 1.5 s
     * poll and the 1 s Wache clock must keep running in Doze.
     */
    private fun holdWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        runCatching { lock.acquire(WAKE_TIMEOUT_MS) }
    }

    override fun onDestroy() {
        scope.cancel()
        // A notify that raced the stop must not stay behind as an ordinary ongoing notification.
        if (!(application as S3App).graph.guard.serviceWanted) GuardNotifications.cancel(this, GuardNotifications.ID_ONGOING)
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private companion object {
        const val WAKE_TAG = "YABA:guard"
        const val WAKE_TIMEOUT_MS = 15 * 60_000L
        const val WAKE_RENEW_MS = 10 * 60_000L
    }
}
