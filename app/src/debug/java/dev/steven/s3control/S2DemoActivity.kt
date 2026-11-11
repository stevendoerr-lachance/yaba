package dev.steven.s3control

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LightMode
import dev.steven.s3control.ble.S2Alarm
import dev.steven.s3control.ble.S2Frame
import dev.steven.s3control.ble.S2Gatt
import dev.steven.s3control.ble.S2Region
import dev.steven.s3control.ble.featuresFor
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ui.cockpit.CockpitRuntime
import dev.steven.s3control.ui.s2.S2DriveWatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * Debug-only: a demo S2/X2 (account profile ELECTRIFIED_2018) with live status blocks, then opens the app.
 * Every screen then shows what an S2 gets (the S2 plan A1, B6): no shifting, no sounds, no Bike-Lab, the
 * module battery, the error code in the S2 table, the S2 diagnosis entry, the cockpit with its error-code lamp.
 *
 * adb shell am start -S -n dev.steven.yaba/dev.steven.s3control.S2DemoActivity --es mode ready|riding|cockpit|offline|clear
 *   [--ei code N] [--ez test true|false]
 * (-S stops the app first: without it a running app's task only comes to the front and the demo does not run.)
 *
 * ready: parked and locked. riding / cockpit: a ride at 14–24 km/h with a status block every second; every
 * 15 s the speed drops to 0 for two blocks while the motor is on (a speed-sensor dropout, the friend's case), and
 * at 20 s error 16 (hall sensor) shows for three blocks. cockpit opens the handlebar cockpit. offline: link lost.
 * [--ei code N] keeps error code N (0..31) on every block. [--ez test] switches S2 test mode on or off for real
 * (it is stored, as the switch would); without it the stored setting is kept. clear: the demo S2 leaves the
 * garage again, its caught error codes leave the error catcher's list (every hit carries its bike, so a real
 * bike's list never shows them), auto-connect is back to what it was before the first demo start, and the app is
 * not opened.
 */
class S2DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as S3App).graph
        val mode = intent.getStringExtra("mode") ?: "ready"
        val demoPrefs = getSharedPreferences("s2demo", MODE_PRIVATE)
        if (mode == "clear") {
            running?.cancel()
            if (graph.bike.state.value.bike?.normalizedMac == DEMO_MAC) graph.bike.disconnect(silent = true)
            graph.account.remove(DEMO_MAC)
            S2DriveWatch.attach(this)
            S2DriveWatch.clearHits { it.mac == DEMO_MAC }
            if (demoPrefs.contains(KEY_AUTO_CONNECT)) {
                val before = demoPrefs.getBoolean(KEY_AUTO_CONNECT, true)
                graph.settings.update { it.copy(autoConnect = before) }
                demoPrefs.edit().remove(KEY_AUTO_CONNECT).apply()
            }
            finish()
            return
        }
        val fixedCode = intent.getIntExtra("code", -1).takeIf { it in 0..31 }
        if (intent.hasExtra("test")) graph.bike.s2Prefs.setTestMode(intent.getBooleanExtra("test", false))
        val testMode = graph.bike.s2Prefs.testMode.value

        val bike = BikeCredentials(
            id = 2002, name = "My S2", ownerName = "Demo", mac = DEMO_MAC,
            encryptionKey = "00000000000000000000000000000000", userKeyId = 1,
            modelName = "Electrified S2", colorPrimary = "#1E1F22", bleProfile = "ELECTRIFIED_2018", fromAccount = false,
        )
        graph.account.addManual(bike)
        graph.account.setLastBike(bike.mac)
        // Remember auto-connect from before the first demo start, so "clear" can put it back.
        if (!demoPrefs.contains(KEY_AUTO_CONNECT)) {
            demoPrefs.edit().putBoolean(KEY_AUTO_CONNECT, graph.settings.state.value.autoConnect).apply()
        }
        graph.settings.update { it.copy(autoConnect = false, introSeen = true, onboardingDone = true) }

        val riding = mode == "riding" || mode == "cockpit"
        val base = BikeState(
            bike = bike,
            family = BikeFamily.S2,
            features = featuresFor(BikeFamily.S2, testMode),
            link = if (mode == "offline") Link.LOST else Link.READY,
            linkMessage = if (mode == "offline") getString(R.string.core_link_bike_not_found) else null,
            firmware = "1.04.2",
            deviceInfo = listOf(
                S2Gatt.FIRMWARE_REVISION to "1.04.2",
                S2Gatt.HARDWARE_REVISION to "B",
                S2Gatt.MODEL_NUMBER to "SX2",
            ),
            connectedAtMillis = System.currentTimeMillis() - 61_000,
        )
        graph.bike.demo(base)
        // A Wache demo from DemoActivity does not carry over to the S2 (it has no Wache).
        graph.guard.demo(null)
        graph.log.add(EventLog.Kind.LINK, getString(R.string.core_log_connected), getString(R.string.core_log_mtu, 23))
        if (mode != "offline") {
            graph.bike.demoS2Frame(
                frame(locked = !riding, speed = if (riding) 18 else 0, error = fixedCode ?: 0, alarm = if (riding) S2Alarm.OFF else S2Alarm.MANUAL),
            )
        }
        if (riding) ride(graph, fixedCode)
        if (mode == "cockpit") CockpitRuntime.requestOpenOnLaunch()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /** A status block every second until the process ends or another demo starts. */
    private fun ride(graph: AppGraph, fixedCode: Int?) {
        running?.cancel()
        running = graph.scope.launch {
            val speeds = intArrayOf(14, 17, 19, 21, 23, 24, 22, 20, 18, 16)
            var km = DISTANCE_KM
            var t = 0
            while (true) {
                delay(1_000)
                t++
                if (graph.bike.state.value.bike?.normalizedMac != DEMO_MAC) break
                val dropout = t % 15 in 0..1 && t >= 15
                val hall = t in 20..22
                val speed = if (dropout) 0 else speeds[t % speeds.size]
                km += speed / 3600.0
                val code = fixedCode ?: if (hall) HALL_CODE else 0
                if (t == 20 && fixedCode == null) {
                    val f = graph.bike.state.value.describeFault(HALL_CODE)
                    graph.log.add(EventLog.Kind.ALERT, getString(R.string.core_log_fault, HALL_CODE, f.name), f.hint, EventLog.Code.FAULT, HALL_CODE)
                }
                graph.bike.demoS2Frame(frame(locked = false, speed = speed, error = code, distanceKm = km, motor = 64 - t / 120))
            }
        }
    }

    private companion object {
        const val DEMO_MAC = "F8:8A:5E:4B:2C:1D"
        const val DISTANCE_KM = 3125.4
        const val HALL_CODE = 16
        const val KEY_AUTO_CONNECT = "autoConnectBefore"

        /** The ride of the last demo start: a new start replaces it. */
        var running: Job? = null

        /** A decrypted 16-byte status block laid out as S2Frame decodes it (plan B5). */
        fun frame(
            locked: Boolean,
            speed: Int,
            error: Int = 0,
            alarm: S2Alarm = S2Alarm.OFF,
            motor: Int = 64,
            module: Int = 81,
            light: LightMode = LightMode.AUTO,
            level: Int = 2,
            region: S2Region = S2Region.EU,
            distanceKm: Double = DISTANCE_KM,
        ): S2Frame {
            val b = ByteArray(S2Frame.SIZE)
            b[2] = (0x01 or (alarm.code shl 1)).toByte()
            b[3] = (if (locked) S2Frame.LOCK_MASK else 0).toByte()
            b[4] = speed.toByte()
            b[5] = motor.toByte()
            b[6] = module.toByte()
            b[7] = light.code.toByte()
            b[8] = (((level shl S2Frame.LEVEL_SHIFT) and S2Frame.LEVEL_MASK) or region.code).toByte()
            val tenths = (distanceKm * S2Frame.DISTANCE_DIVISOR).roundToLong()
            for (i in 0 until 4) b[11 + i] = (tenths shr (8 * i)).toByte()
            b[15] = ((error and 0x1F) shl 3).toByte()
            return S2Frame.parse(b)!!
        }
    }
}
