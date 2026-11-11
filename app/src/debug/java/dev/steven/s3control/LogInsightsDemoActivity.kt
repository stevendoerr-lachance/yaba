package dev.steven.s3control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ble.DerateTable
import dev.steven.s3control.ride.BikeLogArchive
import dev.steven.s3control.ui.screens.LogInsightsEntry
import dev.steven.s3control.ui.screens.LogInsightsScreen
import dev.steven.s3control.ui.screens.LogTextSource
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Theme
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt

/**
 * Debug-only: the Fahrtenschreiber on a generated three-day log, without touching the real archive.
 * adb shell am start -n dev.steven.yaba/dev.steven.s3control.LogInsightsDemoActivity
 *   [--es theme dark|light] [--es view entry]
 * Start `DemoActivity --es mode ready` first to have a "connected" demo bike for the live battery.
 */
class LogInsightsDemoActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val text = DemoBikeLog.build()
        val source = LogTextSource { BikeLogArchive.Snapshot(text, 3, text.count { it == '\n' }) }
        val theme = intent.getStringExtra("theme")
        val entry = intent.getStringExtra("view") == "entry"
        setContent {
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            S3Theme(darkTheme = dark) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().background(S3.colors.asphalt).windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    if (entry) {
                        Column(Modifier.padding(20.dp)) { LogInsightsEntry(vm) }
                    } else {
                        LogInsightsScreen(vm, wide = maxWidth >= 600.dp, onClose = { finish() }, source = source)
                    }
                }
            }
        }
    }
}

/** A plausible bike log over the last three days, in the firmware's line format. Deterministic. */
private object DemoBikeLog {
    private const val HEADER = "TIME;LiPOSOC;BMSSOC;BATTEMP;BATVOLTAGE;BATCURRENT;MOTORCURRENT;MOTORTMP;DRIVERTMP;SPEED;ODO;BOOST;LUX;BATDSG"

    private class Gen {
        val sb = StringBuilder()
        val rnd = Random(7)
        var t = 0L
        var odo = 4_702.4
        var soc = 74.0
        var lipo = 88.0
        var batT = 14.0
        var motorT = 18
        var drvT = 21
        var limit = 30

        fun at(day: LocalDate, h: Int, m: Int) {
            t = day.atTime(h, m).atZone(ZoneId.systemDefault()).toEpochSecond()
        }
        fun line(msg: String) { sb.append(t).append(' ').append(msg).append("\r\n") }
        fun raw(msg: String) { sb.append(msg).append("\r\n") }

        /** Boot: the reason line carries no time stamp, as on the bike. */
        fun boot(reason: String, counter: Int) {
            raw("Wake Reason: $reason")
            line(HEADER)
            t += 1
            line("Wake counter $counter of 168, type 3")
            line("Set power state to PWR_NORMAL (Current limit: $limit.0 A, SOC: ${soc.roundToInt()} %)")
            t += 1
        }

        fun row(speed: Double, motorA: Double, boost: Boolean, lux: Int = 210) {
            odo += speed * 30 / 3600
            val volt = 33.0 + soc / 100 * 8.5
            val cur = -(motorA * 1.1 + 0.2)
            line(
                String.format(
                    Locale.US, ";%.1f%%;%d;%.1f;%.2f;%.2f;%.1f;%d;%d;%.1f;%.1f;%d;%d;1",
                    lipo, soc.roundToInt(), batT, volt, cur, motorA, motorT, drvT, speed, odo, if (boost) 100 else 0, lux,
                ),
            )
            t += 30
        }

        fun idle(rows: Int) = repeat(rows) { row(0.0, 0.0, false) }

        /** A ride of [rows] samples cruising near [cruise], draining [socDrop] and warming the motor. */
        fun ride(rows: Int, cruise: Double, socDrop: Double, warm: Double, stops: Set<Int> = emptySet(), events: Map<Int, String> = emptyMap()) {
            val soc0 = soc
            val bat0 = batT
            for (i in 0 until rows) {
                val ramp = minOf(1.0, (i + 1) / 3.0, (rows - i) / 2.0)
                val speed = if (i in stops || i - 1 in stops) 0.0 else (cruise + rnd.nextGaussian() * 2.2).coerceAtLeast(6.0) * ramp
                soc = soc0 - socDrop * (i + 1) / rows
                batT = bat0 + warm * (i + 1) / rows
                motorT = (motorT + if (speed > 0) 1 else 0).coerceAtMost(62)
                drvT = (drvT + if (speed > 0 && i % 2 == 0) 1 else 0).coerceAtMost(48)
                val newLimit = DerateTable.limitAmps(soc.roundToInt(), batT) ?: limit
                if (newLimit != limit) {
                    limit = newLimit
                    line("Set power state to PWR_NORMAL (Current limit: $limit.0 A, SOC: ${soc.roundToInt()} %)")
                }
                val boost = speed > 0 && rnd.nextDouble() < 0.08
                val amps = if (speed == 0.0) 0.0 else (3.0 + speed / 25 * 10 + rnd.nextGaussian() * 1.5 + if (boost) 7 else 0).coerceIn(0.5, limit.toDouble())
                events[i]?.let { line(it) }
                row(speed, (amps * 10).roundToInt() / 10.0, boost)
            }
            motorT -= 6
        }
    }

    fun build(): String {
        val g = Gen()
        val today = LocalDate.now()
        val d2 = today.minusDays(2)
        val d1 = today.minusDays(1)

        // Two days ago: commute in, long ride home that runs the battery into the derate steps.
        g.at(d2, 7, 40); g.boot("WAKE_SRC_BUTTON_1", 12); g.line("MT zeropos")
        g.idle(2)
        g.ride(46, cruise = 23.5, socDrop = 8.0, warm = 3.0, stops = setOf(12, 27))
        g.idle(2); g.line("Shifter off")
        g.at(d2, 18, 5); g.soc = 40.0; g.batT = 19.0; g.boot("WAKE_SRC_KICKLOCK", 13)
        g.idle(1)
        g.ride(140, cruise = 22.0, socDrop = 25.0, warm = 4.0, stops = setOf(30, 70, 101))
        g.idle(3); g.line("Shifter off")
        g.at(d2, 23, 40); g.line("BleDeepSleep soc 15 wakeups 168")

        // Yesterday: shaken twice at night without a ride, charged, then a ride with shift retries.
        g.at(d1, 3, 12); g.soc = 15.0; g.batT = 11.0; g.limit = DerateTable.limitAmps(15, 11.0) ?: 15
        g.boot("WAKE_SRC_MEMS", 14); g.idle(3)
        g.at(d1, 3, 47); g.boot("WAKE_SRC_MEMS", 15); g.idle(2)
        g.at(d1, 9, 0); g.boot("WAKE_SRC_CHG", 16)
        repeat(8) { g.soc += 6.0; g.row(0.0, 0.0, false, lux = 400) }
        g.at(d1, 17, 30); g.soc = 92.0; g.batT = 17.0; g.limit = 30
        g.boot("WAKE_SRC_BLE", 17)
        g.line(";-247.-6;-1;-247.-6;-247.-6;0;0;0;0;0;0;0;0;0")
        g.idle(1)
        g.ride(
            52, cruise = 24.0, socDrop = 9.0, warm = 2.0, stops = setOf(20),
            events = mapOf(14 to "Error gear 3 = 2, retry", 33 to "Error gear 4 = 3, retry", 34 to "Error gear 4 = 3, retry"),
        )
        g.idle(2); g.line("Shifter off")

        // This morning: cold battery, the limit opens once it is warmer than 5 °C.
        g.at(today, 6, 55); g.soc = 81.0; g.batT = 3.5; g.motorT = 6; g.drvT = 8
        g.limit = DerateTable.limitAmps(81, 3.5) ?: 20
        g.boot("WAKE_SRC_KICKLOCK", 18); g.line("MT zeropos")
        g.idle(2)
        g.ride(44, cruise = 22.5, socDrop = 7.0, warm = 3.0, stops = setOf(18))
        g.idle(2)
        return g.sb.toString()
    }
}
