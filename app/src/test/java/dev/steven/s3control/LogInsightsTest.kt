package dev.steven.s3control

import dev.steven.s3control.ride.BikeLogArchive
import dev.steven.s3control.ride.LogInsights
import dev.steven.s3control.ride.LogInsights.DerateCause
import dev.steven.s3control.ride.LogInsights.Verdict
import dev.steven.s3control.ride.LogInsights.WakeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

class LogInsightsTest {

    private val t0 = 1_760_000_000L

    /** One telemetry row: speed km/h, odometer km, main SoC. */
    private fun row(epoch: Long, kmh: Double, odo: Double, soc: Int = 80, motorA: Double = 0.0, batTemp: Double = 20.0) =
        "$epoch ;21.2;$soc;$batTemp;39.04;-0.18;$motorA;31;33;$kmh;$odo;0;220;1"

    /** A ride of [moving] rows at 20 km/h, with idle rows before and after. */
    private fun ride(start: Long, moving: Int, odoStart: Double): List<String> {
        val lines = ArrayList<String>()
        var odo = odoStart
        lines.add(row(start, 0.0, odo))
        for (i in 1..moving) {
            odo += 20.0 * 30 / 3600
            lines.add(row(start + i * 30, 20.0, odo, motorA = 8.0))
        }
        lines.add(row(start + (moving + 1) * 30, 0.0, odo))
        return lines
    }

    @Test fun cutsRidesAtSleepGaps() {
        val text = (ride(t0, 10, 100.0) + ride(t0 + 3_600, 6, 104.0)).joinToString("\r\n")
        val rides = LogInsights.analyze(text).rides
        assertEquals(2, rides.size)
        assertEquals(10, rides[0].movingRows)
        assertEquals(t0, rides[0].startEpoch)
        assertEquals(10 * 20.0 * 30 / 3600, rides[0].distanceKm!!, 1e-6)
        assertEquals(20.0, rides[0].maxSpeedKmh!!, 1e-6)
    }

    @Test fun shortStopStaysInOneRide() {
        val lines = ride(t0, 4, 10.0).dropLast(1).toMutableList()
        // two idle rows (one minute at a light), then riding again
        lines.add(row(t0 + 150, 0.0, 10.7)); lines.add(row(t0 + 180, 0.0, 10.7))
        lines.add(row(t0 + 210, 18.0, 10.8)); lines.add(row(t0 + 240, 18.0, 10.9)); lines.add(row(t0 + 270, 0.0, 10.9))
        assertEquals(1, LogInsights.analyze(lines.joinToString("\n")).rides.size)
    }

    @Test fun singleBlipIsNoRide() {
        val text = listOf(row(t0, 0.0, 5.0), row(t0 + 30, 3.0, 5.0), row(t0 + 60, 0.0, 5.0)).joinToString("\n")
        assertTrue(LogInsights.analyze(text).rides.isEmpty())
    }

    @Test fun bootHeaderSplitsSessions() {
        val a = ride(t0, 3, 1.0)
        val b = ride(t0 + 200, 3, 2.0)
        val text = (a + listOf("${t0 + 150} TIME;LiPOSOC;BMSSOC;BATTEMP;BATVOLTAGE;BATCURRENT;MOTORCURRENT;MOTORTMP;DRIVERTMP;SPEED;ODO;BOOST;LUX;BATDSG") + b)
            .joinToString("\n")
        assertEquals(2, LogInsights.analyze(text).rides.size)
    }

    @Test fun usesMainBatteryNotModuleBattery() {
        val text = ride(t0, 5, 1.0).joinToString("\n")
        val r = LogInsights.analyze(text).rides.single()
        assertEquals(80, r.socStart)   // BMSSOC column, not the LiPo 21.2
    }

    @Test fun limitInForceComesFromTheLog() {
        val lines = ride(t0, 6, 1.0).toMutableList()
        lines.add(0, "${t0 - 500} Set power state to PWR_NORMAL (Current limit: 30.0 A, SOC: 69 %)")
        lines.add(4, "${t0 + 95} Set power state to PWR_NORMAL (Current limit: 20.0 A, SOC: 22 %)")
        val r = LogInsights.analyze(lines.joinToString("\n")).rides.single()
        assertEquals(30.0, r.limitAt(t0 + 30)!!, 1e-6)
        assertEquals(20.0, r.limitAt(t0 + 120)!!, 1e-6)
    }

    @Test fun wakeReasonWithoutStampTakesNextTime() {
        val text = listOf(
            "${t0 - 10} Shifter off",
            "Wake Reason: WAKE_SRC_MEMS",
            "${t0} TIME;LiPOSOC;BMSSOC;BATTEMP;BATVOLTAGE;BATCURRENT;MOTORCURRENT;MOTORTMP;DRIVERTMP;SPEED;ODO;BOOST;LUX;BATDSG",
            "${t0 + 2} Wake counter 37 of 168, type 5",
        ).joinToString("\r\n")
        val w = LogInsights.analyze(text).wakes.wakes.single()
        assertEquals(t0, w.epoch)
        assertEquals(WakeSource.MEMS, w.source)
        assertEquals(37, w.counter)
        assertEquals(168, w.counterOf)
        assertTrue(w.movedWithoutRide)
    }

    @Test fun wakeFollowedByRideIsNotAMove() {
        val text = (listOf("Wake Reason: WAKE_SRC_MEMS") + ride(t0, 5, 1.0)).joinToString("\n")
        val w = LogInsights.analyze(text).wakes.wakes.single()
        assertFalse(w.movedWithoutRide)
    }

    @Test fun coldBootAndDeepSleep() {
        val text = listOf("Cold boot clear log..", "$t0 BleDeepSleep soc 15 wakeups 168").joinToString("\n")
        val tl = LogInsights.analyze(text).wakes
        assertEquals(WakeSource.COLD_BOOT, tl.wakes.single().source)
        assertEquals(15, tl.deepSleeps.single().soc)
    }

    @Test fun shifterHealthCountsAndMatchesSpeed() {
        val lines = ride(t0, 6, 1.0).toMutableList()
        lines.add(3, "${t0 + 61} Error gear 3 = 2, retry")
        lines.add("${t0 + 400} Shifter off")
        val h = LogInsights.analyze(lines.joinToString("\n")).shifter
        assertEquals(1, h.retries)
        assertEquals(listOf(0, 0, 1, 0), h.retriesByGear)
        assertEquals(20.0, h.entries.first { it.event.gear == 3 }.speedKmh!!, 1e-6)
        assertNull(h.entries.last().speedKmh)   // no row within 45 s
        assertEquals(Verdict.ATTENTION, h.verdict)
    }

    @Test fun derateExplainerMatchesFirmwareTable() {
        assertEquals(DerateCause.FULL, LogInsights.explainDerate(87, 21.0).cause)
        val low = LogInsights.explainDerate(15, 21.0)
        assertEquals(DerateCause.BATTERY, low.cause)
        assertEquals(15, low.limitAmps)
        assertEquals(19, low.nextSocPercent)
        assertEquals(20, low.nextLimitAmps)
        val cold = LogInsights.explainDerate(80, 3.0)
        assertEquals(DerateCause.COLD, cold.cause)
        assertEquals(20, cold.limitAmps)
        assertEquals(5.0, cold.tempEdgeC!!, 1e-9)
        assertEquals(30, cold.tempEdgeLimitAmps)
        assertEquals(DerateCause.EMPTY, LogInsights.explainDerate(4, 20.0).cause)
        assertEquals(DerateCause.HEAT, LogInsights.explainDerate(80, 65.0).cause)
        // the grid as the doc prints it (rows ≤0 / ≤5 / ≤60 / >60 °C, columns ≤5 / ≤13 / ≤18 / ≤22 / >22 %)
        val grid = (0..3).map { r -> (0..4).map { c -> LogInsights.derateCell(r, c) } }
        assertEquals(listOf(0, 10, 10, 10, 10), grid[0])
        assertEquals(listOf(0, 10, 10, 15, 20), grid[1])
        assertEquals(listOf(0, 10, 15, 20, 30), grid[2])
        assertEquals(listOf(0, 10, 10, 15, 20), grid[3])
    }

    @Test fun seekGuardNeedsEverything() {
        val ok = LogInsights.SeekGuard(true, true, true, 60, true, t0)
        assertTrue(ok.allowed)
        assertEquals(t0 - 1, ok.target)
        assertFalse(ok.copy(complete = false).allowed)
        assertFalse(ok.copy(saved = false).allowed)
        assertFalse(ok.copy(ageSeconds = 900).allowed)
        assertFalse(ok.copy(sameConnection = false).allowed)
        assertFalse(ok.copy(newestEpoch = 86_400).allowed)   // clock never set
        assertFalse(ok.copy(linkReady = false).allowed)
        assertNull(ok.copy(saved = false).target)
    }

    @Test fun newestEpochIsTheLastLineNotTheMaximum() {
        val text = listOf("${t0 + 999} a", "${t0} b", "Wake Reason: WAKE_SRC_BLE").joinToString("\n")
        assertEquals(t0, LogInsights.newestEpoch(text))
    }

    @Test fun archiveMergesOverlappingDownloads() {
        val a = listOf("29090 ;21.2;69;27.0", "${t0} A", "${t0 + 1} B", "Wake Reason: WAKE_SRC_BLE", "${t0 + 2} C").joinToString("\r\n")
        val b = listOf("${t0 + 1} B", "Wake Reason: WAKE_SRC_BLE", "${t0 + 2} C", "Wake Reason: WAKE_SRC_BLE", "${t0 + 3} D").joinToString("\r\n")
        val c = listOf("${t0 + 9} after seek").joinToString("\n")
        val merged = BikeLogArchive.merge(listOf(a, b, b, c))
        assertEquals(
            listOf("${t0} A", "${t0 + 1} B", "Wake Reason: WAKE_SRC_BLE", "${t0 + 2} C", "Wake Reason: WAKE_SRC_BLE", "${t0 + 3} D", "${t0 + 9} after seek"),
            merged,
        )
    }

    @Test fun archiveChecksTheSavedFile() {
        val dir = Files.createTempDirectory("bikelog").toFile()
        try {
            val at = 1_760_000_123_000L
            val zone = ZoneId.of("Europe/Zagreb")
            val text = "${t0} A\r\n${t0 + 1} B\r\n"
            val archive = BikeLogArchive(dir)
            assertFalse(archive.holds(text, at, zone))
            File(dir, BikeLogArchive.fileName(at, zone)).writeText(text)
            assertTrue(archive.holds(text, at, zone))
            assertFalse(archive.holds(text + "x", at, zone))
            assertEquals(2, archive.load().lines)
            assertEquals(3, archive.load(latest = text + "${t0 + 2} C").lines)
        } finally {
            dir.deleteRecursively()
        }
    }
}
