package dev.steven.s3control.ride

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.steven.s3control.ble.GuardStore

/**
 * Brings an armed Wache back after a phone reboot or an app update (the guard design 1.6). Both broadcasts may start a
 * connectedDevice foreground service from the background. The process start itself already restored the Wache
 * (AppGraph); this only starts its service. Nothing happens unless the store says the Wache was armed.
 */
class GuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!GuardStore(context).armed) return
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, GuardService::class.java).setAction(GuardNotifications.ACTION_RESTORE),
            )
        } catch (e: Exception) {
            GuardNotifications.postRestart(context)
        }
    }
}
