package dev.steven.s3control.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The official VanMoof app keeps a Bluetooth link to the bike from the background, and the bike
 * accepts only one phone. Android does not let one app force-stop another, so YABA offers the
 * next best things: push it out of the background, and open its settings in one tap.
 */
object OfficialApp {
    const val PACKAGE = "nl.samsonit.vanmoofapp"

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** Kills its cached background process. Does nothing while it runs a foreground service. */
    fun pushOutOfBackground(context: Context) {
        runCatching { context.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(PACKAGE) }
    }

    /** Its App info page: "Force stop" and Permissions → Nearby devices live there. */
    fun openAppInfo(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
