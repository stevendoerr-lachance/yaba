package dev.steven.s3control.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.steven.s3control.ui.components.Outbursts

object BlePermissions {
    /** What we need before scanning/connecting on this Android version. */
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context) = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Below Android 12, BLE scans return nothing while system Location is off. */
    fun locationOk(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 ||
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } == true

    /**
     * Android stops showing the prompt after two refusals. Then no rationale is offered any more although the permission is
     * missing, and only the app settings page helps. Before the first request the rationale is false as well, which is why
     * [asked] (the prompt was shown at least once) has to be true, and why a caller asks after the answer came, not while the
     * prompt is still up ([BleReadiness.permissionResults] counts the answers).
     */
    fun permanentlyDenied(activity: Activity, asked: Boolean): Boolean =
        asked && !granted(activity) && required.none { activity.shouldShowRequestPermissionRationale(it) }

    fun notificationsGranted(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Live view of what blocks a connection, re-checked whenever the app comes back to the front. */
class BleReadiness(
    val permissionsGranted: Boolean,
    val bluetoothOn: Boolean,
    val requestPermissions: () -> Unit,
    val requestEnable: () -> Unit,
    /** Always true on Android 12+. */
    val locationOn: Boolean = true,
    val openLocationSettings: () -> Unit = {},
    /** How many answers the permission prompt has given so far (a refusal changes nothing else the UI could watch). */
    val permissionResults: Int = 0,
) {
    val ready: Boolean get() = permissionsGranted && bluetoothOn && locationOn
}

@Composable
fun rememberBleReadiness(): BleReadiness {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(BlePermissions.granted(context)) }
    var on by remember { mutableStateOf(BlePermissions.bluetoothOn(context)) }
    var location by remember { mutableStateOf(BlePermissions.locationOk(context)) }

    var results by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = BlePermissions.granted(context)
        on = BlePermissions.bluetoothOn(context)
        results++
        // The prompt is closed again: the bike may call out (it never does behind a permission dialog).
        Outbursts.dialogClosed()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        on = BlePermissions.bluetoothOn(context)
    }

    // Quick Settings toggles do not pause the activity: listen for the adapter directly.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                on = BlePermissions.bluetoothOn(context)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                granted = BlePermissions.granted(context)
                on = BlePermissions.bluetoothOn(context)
                location = BlePermissions.locationOk(context)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    return BleReadiness(
        permissionsGranted = granted,
        bluetoothOn = on,
        requestPermissions = {
            Outbursts.dialogOpened()
            permissionLauncher.launch(BlePermissions.required)
        },
        requestEnable = {
            runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        },
        locationOn = location,
        permissionResults = results,
        openLocationSettings = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
    )
}
