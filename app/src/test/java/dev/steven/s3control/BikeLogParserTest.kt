package dev.steven.s3control

import dev.steven.s3control.ride.BikeLogParser
import dev.steven.s3control.ride.BikeLogParser.ShifterEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeLogParserTest {

    @Test fun parsesRealTelemetryRow() {
        // The example row from the decomp (knight1_EXAMPLES.md:81-95).
        val log = BikeLogParser.parse("1723229090 ;21.2;69;27.0;39.04;-0.18;0.0;28;32;0.0;4864.3;0;220;1\r\n")
        assertEquals(1, log.telemetry.size)
        val t = log.telemetry.first()
        assertTrue(t.valid)
        assertEquals(1723229090L, t.epoch)
        assertEquals(21.2, t.liPoSoc!!, 1e-6)
        assertEquals(69, t.bmsSoc)
        assertEquals(27.0, t.batTempC!!, 1e-6)
        assertEquals(39.04, t.batVolt!!, 1e-6)
        assertEquals(-0.18, t.batCurrent!!, 1e-6)
        assertEquals(28, t.motorTempC)
        assertEquals(32, t.driverTempC)
        assertEquals(0.0, t.speedKmh!!, 1e-6)
        assertEquals(4864.3, t.odoKm!!, 1e-6)
        assertEquals(false, t.boost)
        assertEquals(220, t.lux)
        assertEquals(1, t.batDischarging)
    }

    @Test fun toleratesLiPoPercentSuffix() {
        val log = BikeLogParser.parse("100 ;21.2 %;69;27.0;39.04;-0.18;0.0;28;32;12.5;4864.3;100;5;1")
        assertEquals(21.2, log.telemetry.first().liPoSoc!!, 1e-6)
        assertEquals(true, log.telemetry.first().boost)
    }

    @Test fun marksBatteryAbsentGarbageInvalid() {
        val log = BikeLogParser.parse("200 ;-247.-6;-1;-247.-6;-247.-6;0;0;0;0;0;0;0;0;0")
        assertEquals(1, log.telemetry.size)
        assertFalse(log.telemetry.first().valid)
        assertTrue(log.validTelemetry.isEmpty())
    }

    @Test fun parsesSubZeroBatteryTemperatures() {
        // The firmware prints "%d.%d" of the tenths split, so a sub-zero °C has the minus on the fraction:
        // -2.5 °C -> "-2.-5", -0.5 °C -> "0.-5". A plain toDouble drops both; parseBatTemp must not.
        assertEquals(-2.5, BikeLogParser.parseBatTemp("-2.-5")!!, 1e-6)
        assertEquals(-0.5, BikeLogParser.parseBatTemp("0.-5")!!, 1e-6)
        assertEquals(-12.3, BikeLogParser.parseBatTemp("-12.-3")!!, 1e-6)
        // Normal positive and whole-negative values still take the fast path.
        assertEquals(27.0, BikeLogParser.parseBatTemp("27.0")!!, 1e-6)
        assertEquals(-2.0, BikeLogParser.parseBatTemp("-2.0")!!, 1e-6)
        assertEquals(0.5, BikeLogParser.parseBatTemp("0.5")!!, 1e-6)
    }

    @Test fun usesSubZeroTempInColdDerateBands() {
        // A connected but cold battery at -2.5 °C: the row is valid and its temperature must survive, so
        // the derate explainer can see the ≤ 0 °C band instead of falling back to an older, warmer reading.
        val log = BikeLogParser.parse("300 ;21.2;69;-2.-5;39.04;-0.18;0.0;28;32;0.0;4864.3;0;220;1")
        val t = log.telemetry.first()
        assertTrue(t.valid)
        assertEquals(-2.5, t.batTempC!!, 1e-6)
        assertEquals(-2.5, log.validTelemetry.last().batTempC!!, 1e-6)
    }

    @Test fun parsesDerateLine() {
        val log = BikeLogParser.parse("1723229000 Set power state to PWR_SAVING (Current limit: 15.0 A, SOC: 15 %)")
        assertEquals(1, log.derate.size)
        val d = log.derate.first()
        assertEquals("PWR_SAVING", d.powerState)
        assertEquals(15.0, d.currentLimitA, 1e-6)
        assertEquals(15, d.socPercent)
    }

    @Test fun parsesNegativeSocDerate() {
        val log = BikeLogParser.parse("1 Set power state to PWR_BAT_EMPTY (Current limit: 0.0 A, SOC: -1 %)")
        assertEquals(-1, log.derate.first().socPercent)
        assertEquals(0.0, log.derate.first().currentLimitA, 1e-6)
    }

    @Test fun parsesShifterEvents() {
        val text = """
            10 Error gear 3 = 2, retry
            11 ERR Position
            12 Shifter off
            13 MT zeropos
        """.trimIndent()
        val log = BikeLogParser.parse(text)
        assertEquals(4, log.shifter.size)
        val retry = log.shifter.first()
        assertEquals(ShifterEventKind.GEAR_RETRY, retry.kind)
        assertEquals(3, retry.gear)
        assertEquals(2, retry.reported)
        assertEquals(ShifterEventKind.POSITION_ERROR, log.shifter[1].kind)
        assertEquals(ShifterEventKind.SHIFTER_OFF, log.shifter[2].kind)
        assertEquals(ShifterEventKind.ZERO_CAL, log.shifter[3].kind)
    }

    @Test fun parsesWakeAndDeepSleep() {
        val text = """
            100 Wake Reason: Shock
            101 Wake counter 4 of 12, type 2
            102 BleDeepSleep soc 7 wakeups 33
        """.trimIndent()
        val log = BikeLogParser.parse(text)
        assertEquals("Shock", log.wake.first().reason)
        assertEquals(4, log.wake[1].counter)
        assertEquals(12, log.wake[1].counterOf)
        assertEquals(2, log.wake[1].type)
        assertEquals(7, log.deepSleep.first().soc)
        assertEquals(33, log.deepSleep.first().wakeups)
    }

    @Test fun parsesMotorErrorAndSelfTest() {
        val log = BikeLogParser.parse("5 Motor error 0051\n6 Diag ok\n7 Diag fail")
        assertEquals(0x51, log.motorErrors.first().code)
        assertEquals(2, log.selfTests.size)
        assertTrue(log.selfTests[0].passed)
        assertFalse(log.selfTests[1].passed)
    }

    @Test fun recordsBootHeaders() {
        val text = "900 TIME;LiPOSOC;BMSSOC;BATTEMP;BATVOLTAGE;BATCURRENT;MOTORCURRENT;MOTORTMP;DRIVERTMP;SPEED;ODO;BOOST;LUX;BATDSG\n" +
            "901 ;50.0;70;25.0;40.00;0.0;0.0;20;20;0.0;100.0;0;500;0"
        val log = BikeLogParser.parse(text)
        assertEquals(listOf(900L), log.boots)
        assertEquals(1, log.telemetry.size)
    }

    @Test fun speedAtPicksNearestValidRow() {
        val text = """
            100 ;50.0;70;25.0;40.00;0.0;0.0;20;20;5.0;100.0;0;500;0
            130 ;50.0;70;25.0;40.00;0.0;0.0;20;20;22.0;100.2;100;5;1
            125 Error gear 2 = 1, retry
        """.trimIndent()
        val log = BikeLogParser.parse(text)
        assertEquals(22.0, log.speedAt(125)!!, 1e-6)
    }

    @Test fun keepsUnknownLines() {
        val log = BikeLogParser.parse("42 Some unexpected line")
        assertEquals(listOf("42 Some unexpected line"), log.unparsed)
    }

    @Test fun emptyInputIsEmpty() {
        val log = BikeLogParser.parse("")
        assertTrue(log.telemetry.isEmpty())
        assertNull(log.span)
    }
}
