package dev.steven.s3control.ride

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's GPS speed, for the ride logger's `gps_kmh` column (S2 drive diagnosis: where the bike reports
 * 0 km/h while GPS says it rolls, the speed signal dropped out). Opt-in from the S2 drive diagnosis screen and
 * stored on its own; nothing listens unless it is [enabled], fine location is granted and somebody holds it
 * ([acquire]: the ride logger while it records, the diagnosis screen while it shows the live value).
 *
 * Only the speed is kept, never a position.
 */
class GpsSpeed private constructor(private val context: Context) {

    /** One GPS update: [kmh] is null when the fix carried no speed. [atMillis] is the phone's wall clock on arrival. */
    data class Fix(val atMillis: Long, val kmh: Double?, val accuracyM: Float?)

    private val prefs = context.getSharedPreferences("gps", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    private val _fix = MutableStateFlow<Fix?>(null)
    private val _listening = MutableStateFlow(false)
    private val holders = HashSet<Any>()
    private val manager: LocationManager? get() = context.getSystemService(LocationManager::class.java)

    /** The rider allowed the GPS comparison in the S2 drive diagnosis. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The latest fix since listening started (null before the first one). */
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    /** Location updates are requested right now. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val kmh = if (location.hasSpeed()) location.speed * 3.6 else null
            _fix.value = Fix(System.currentTimeMillis(), kmh, if (location.hasAccuracy()) location.accuracy else null)
        }

        // Required on API 26–29, where these are not default methods yet.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        update()
    }

    /** Fine location is granted (coarse alone gives no GPS speed). */
    fun permitted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Only coarse location is granted: the rider chose "approximate" in the system dialog. */
    fun coarseOnly(): Boolean = !permitted() &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The phone's location switch is on for GPS. */
    fun providerOn(): Boolean = runCatching { manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)

    /** Enabled and permitted: the ride logger writes GPS speed (and its service may hold location). */
    val active: Boolean get() = _enabled.value && permitted()

    /** [owner] wants GPS while it is [active]. Call from the main thread. */
    fun acquire(owner: Any) {
        synchronized(holders) { holders.add(owner) }
        update()
    }

    fun release(owner: Any) {
        synchronized(holders) { holders.remove(owner) }
        update()
    }

    /** Re-evaluates whether to listen, after a permission answer or a holder change. Call from the main thread. */
    @SuppressLint("MissingPermission") // checked in active
    fun update() {
        val want = active && synchronized(holders) { holders.isNotEmpty() }
        if (want == _listening.value) return
        val lm = manager ?: return
        if (want) {
            val ok = runCatching {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, 0f, listener, Looper.getMainLooper())
            }.isSuccess
            _listening.value = ok
        } else {
            runCatching { lm.removeUpdates(listener) }
            _listening.value = false
            _fix.value = null
        }
    }

    /** The GPS speed for a row written at [now]: null unless a fix with a speed arrived at most [MAX_AGE_MS] before. */
    fun kmhAt(now: Long): Double? {
        if (!_listening.value) return null
        val f = _fix.value ?: return null
        return f.kmh?.takeIf { now - f.atMillis in 0..MAX_AGE_MS }
    }

    companion object {
        /** A fix older than this is not written: the phone lost GPS (a tunnel, a pocket). */
        const val MAX_AGE_MS = 3_000L
        private const val UPDATE_MS = 1_000L
        private const val KEY_ENABLED = "enabled"

        @Volatile private var instance: GpsSpeed? = null

        /** The process-wide instance: the logger and the diagnosis screen must share one listener. */
        fun get(context: Context): GpsSpeed =
            instance ?: synchronized(this) { instance ?: GpsSpeed(context.applicationContext).also { instance = it } }
    }
}
