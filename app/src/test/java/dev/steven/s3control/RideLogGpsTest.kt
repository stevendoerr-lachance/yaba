package dev.steven.s3control

import dev.steven.s3control.ride.RideLogReader
import dev.steven.s3control.ride.RideLogReader.Signal
import dev.steven.s3control.ride.RideRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The ride log columns of the S2 drive diagnosis (gps_kmh, s2_error, raw_frame, before `message`) and the signal
 * dropouts the reader finds with them: GPS above 8 km/h while the bike reports 0 km/h.
 */
class RideLogGpsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Zagreb")
    private val t0: Long = LocalDateTime.of(2026, 10, 18, 9, 0, 0).atZone(zone).toInstant().toEpochMilli()
    private val rowTs: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(zone)

    /** The header the logger writes ([RideRecorder.CSV_HEADER]); [theLoggerWritesTheColumnsTheReaderKnows] pins it. */
    private val header = RideRecorder.CSV_HEADER

    /** The first layout, without the three columns. */
    private val oldHeader = "time,type,link,status,shipping_reported,lock,kmh,battery,charger,level,reset_button," +
        "supply_ok,wheel_moving,faults,faults_raw,battery_raw,message"

    private val frameHex = "0000010000425e0004a0860100000080"

    /** One row the way RideRecorder.appendLine builds it for an S2 (faults_raw and battery_raw stay empty). */
    private fun row(
        at: Long,
        kmh: Int?,
        gps: Double?,
        type: String = "sample",
        link: String = "READY",
        s2Error: Int? = 0,
        faults: List<Int> = if (s2Error != null && s2Error != 0) listOf(s2Error) else emptyList(),
        frame: String = if (s2Error != null) frameHex else "",
        message: String = "",
    ): String = listOf(
        rowTs.format(java.time.Instant.ofEpochMilli(at)),
        type,
        link,
        "RIDING",
        "0",
        "UNLOCKED",
        kmh?.toString() ?: "",
        "66",
        "",
        "2",
        "",
        "",
        "0",
        faults.joinToString("|"),
        "",
        "",
        gps?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "",
        s2Error?.toString() ?: "",
        frame,
        "\"" + message + "\"",
    ).joinToString(",")

    private fun file(vararg rows: String): String = (listOf(header) + rows).joinToString("") { it + "\n" }

    private fun read(text: String) = RideLogReader.read(text, zone)

    // --- Columns ----------------------------------------------------------------------------------

    @Test fun theLoggerWritesTheColumnsTheReaderKnows() {
        // The first layout's columns in their order, the three S2 columns, then `message` last (always quoted).
        val expected = RideLogReader.COLUMNS.dropLast(1) + RideLogReader.EXTRA_COLUMNS + "message"
        assertEquals("message", RideLogReader.COLUMNS.last())
        assertEquals(expected, RideRecorder.CSV_HEADER.split(','))
    }

    @Test fun readsTheNewColumnsByName() {
        val log = read(
            file(
                row(t0, kmh = 18, gps = 18.4),
                row(t0 + 2_000, kmh = 19, gps = 19.1, s2Error = 16),
                row(t0 + 3_000, kmh = 19, gps = 19.0, type = "event", s2Error = 16, message = "ALERT: Fehler 16 (Hall)"),
            ),
        )
        assertEquals(3, log.gpsRows)
        assertEquals(19.1, log.gpsTopKmh!!, 1e-9)
        assertEquals(3, log.s2Rows)
        assertTrue(log.hasGps)
        assertTrue(log.isS2)
        assertEquals(listOf(16), log.faultsSeen)
        assertEquals(19.1, log.samples[1].gpsKmh!!, 1e-9)
        assertEquals(19.0, log.events.single().gpsKmh!!, 1e-9)
        assertEquals("Fehler 16", log.events.single().text)
        assertFalse(log.partialTail)
        assertEquals(0, log.skippedLines)
    }

    @Test fun aLogWithoutTheColumnsReadsAsBefore() {
        val text = listOf(
            oldHeader,
            rowTs.format(java.time.Instant.ofEpochMilli(t0)) + ",sample,READY,RIDING,0,UNLOCKED,20,80,NOT_CHARGING,4,0,1,1,8,0000000000000100,51,\"\"",
            rowTs.format(java.time.Instant.ofEpochMilli(t0 + 2_000)) + ",sample,READY,RIDING,0,UNLOCKED,0,80,NOT_CHARGING,4,0,1,1,8,0000000000000100,51,\"\"",
        ).joinToString("") { it + "\n" }
        val log = read(text)
        assertEquals(0, log.gpsRows)
        assertNull(log.gpsTopKmh)
        assertEquals(0, log.s2Rows)
        assertTrue(log.dropouts.isEmpty())
        // An S3 log keeps its shipping-rule signal for fault 8.
        assertTrue(log.spans(Signal.FAULT_8).isNotEmpty())
        assertNull(log.samples[0].gpsKmh)
    }

    @Test fun anS2CodeNeverRaisesAnS3ShippingSignal() {
        // S2 code 8 is a torque sensor fault, not the BMS switching off.
        val log = read(file(row(t0, kmh = 12, gps = 12.0, s2Error = 8), row(t0 + 2_000, kmh = 12, gps = 12.0, s2Error = 8)))
        assertEquals(listOf(8), log.faultsSeen)
        assertTrue(log.spans(Signal.FAULT_8).isEmpty())
        assertTrue(log.samples.none { Signal.FAULT_8 in it.signals })
    }

    @Test fun theLastRowWithoutNewlineIsStillJudgedByItsQuotedMessage() {
        val whole = file(row(t0, kmh = 10, gps = 10.0)) + row(t0 + 2_000, kmh = 11, gps = 11.0)
        assertEquals(2, read(whole).sampleCount)
        val cut = file(row(t0, kmh = 10, gps = 10.0)) + row(t0 + 2_000, kmh = 11, gps = 11.0).dropLast(3)
        val log = read(cut)
        assertEquals(1, log.sampleCount)
        assertTrue(log.partialTail)
    }

    // --- Signal dropouts --------------------------------------------------------------------------

    @Test fun aDropoutThatEndsWithTheBikeRollingAgainCountsEvenForOneRow() {
        val log = read(
            file(
                row(t0, kmh = 21, gps = 21.0),
                row(t0 + 2_000, kmh = 0, gps = 20.6),
                row(t0 + 4_000, kmh = 20, gps = 20.2),
            ),
        )
        val d = log.dropouts.single()
        assertEquals(t0 + 2_000, d.startMillis)
        assertEquals(t0 + 4_000, d.endMillis)
        assertTrue(d.resumed)
        assertEquals(1, d.rows)
        assertEquals(20.6, d.peakGpsKmh, 1e-9)
        assertTrue(log.dropoutAt(t0 + 3_000))
        assertFalse(log.dropoutAt(t0 + 4_000))
        assertEquals(2_000L, log.dropoutMillis)
    }

    @Test fun braking_to_a_stop_with_gps_trailing_is_not_a_dropout() {
        val log = read(
            file(
                row(t0, kmh = 18, gps = 18.0),
                row(t0 + 2_000, kmh = 0, gps = 11.0),
                row(t0 + 4_000, kmh = 0, gps = 9.0),
                row(t0 + 6_000, kmh = 0, gps = 1.0),
            ),
        )
        assertTrue(log.dropouts.isEmpty())
    }

    @Test fun aLongDropoutCountsWhateverEndsIt() {
        val log = read(
            file(
                row(t0, kmh = 0, gps = 15.0),
                row(t0 + 2_000, kmh = 0, gps = 16.0),
                row(t0 + 4_000, kmh = 0, gps = 14.0),
                row(t0 + 6_000, kmh = 0, gps = 3.0),
            ),
        )
        val d = log.dropouts.single()
        assertFalse(d.resumed)
        assertEquals(t0, d.startMillis)
        assertEquals(t0 + 4_000, d.endMillis)
        assertEquals(3, d.rows)
        assertEquals(16.0, d.peakGpsKmh, 1e-9)
    }

    @Test fun aShortGpsHoleDoesNotEndADropout() {
        val log = read(
            file(
                row(t0, kmh = 0, gps = 15.0),
                row(t0 + 2_000, kmh = 0, gps = null),
                row(t0 + 4_000, kmh = 14, gps = 14.5),
            ),
        )
        val d = log.dropouts.single()
        assertEquals(t0, d.startMillis)
        assertEquals(t0 + 4_000, d.endMillis)
        assertTrue(d.resumed)
    }

    @Test fun aLostLinkEndsADropoutWhereItWasLastSeen() {
        // One row with the bike at 0 and GPS at 15, then the link is gone for a minute (a harness fault can reset
        // the module), then riding again: that is a lost link, not a minute of dropped speed signal.
        val rows = buildList {
            add(row(t0, kmh = 21, gps = 21.0))
            add(row(t0 + 2_000, kmh = 0, gps = 15.0))
            for (i in 0 until 30) add(row(t0 + 4_000 + i * 2_000L, kmh = 0, gps = 15.0, link = "LOST"))
            add(row(t0 + 64_000, kmh = 14, gps = 14.0))
        }
        val log = read(file(*rows.toTypedArray()))
        assertTrue("one row, then the link went: too short to count, and not resumed", log.dropouts.isEmpty())
        // A long one before the link went still counts, up to where it was last seen.
        val long = buildList {
            for (i in 0 until 4) add(row(t0 + i * 2_000L, kmh = 0, gps = 16.0))
            add(row(t0 + 8_000, kmh = 0, gps = 16.0, link = "LOST"))
            add(row(t0 + 40_000, kmh = 15, gps = 15.0))
        }
        val d = read(file(*long.toTypedArray())).dropouts.single()
        assertFalse(d.resumed)
        assertEquals(t0 + 6_000, d.endMillis)
    }

    @Test fun aGpsOutageBetweenAStopAndRidingOnIsNoDropout() {
        // GPS trails the stop at a light, then has no fix while standing; riding on later must not close it as resumed.
        val rows = buildList {
            add(row(t0, kmh = 12, gps = 12.0))
            add(row(t0 + 2_000, kmh = 0, gps = 9.5))
            for (i in 0 until 10) add(row(t0 + 4_000 + i * 2_000L, kmh = 0, gps = null))
            add(row(t0 + 24_000, kmh = 10, gps = 10.0))
        }
        assertTrue(read(file(*rows.toTypedArray())).dropouts.isEmpty())
    }

    @Test fun aRowWithTheBikeRollingLongAfterTheLastHoldingRowIsNotAResume() {
        // Rows far apart (an event every 6 s, no samples in between): the end is only "resumed" right after.
        val log = read(file(row(t0, kmh = 0, gps = 20.0), row(t0 + 6_000, kmh = 20, gps = 20.0)))
        assertTrue(log.dropouts.isEmpty())
    }

    @Test fun anS2LogNeverRaisesTheLockedSignal() {
        // Byte 3's lock bit is not confirmed yet (test steps 9 and 10): no red "locked while rolling" for an S2.
        val lockedRow = row(t0, kmh = 15, gps = 15.0).replace(",UNLOCKED,", ",LOCKED,")
        val log = read(file(lockedRow, row(t0 + 2_000, kmh = 15, gps = 15.0).replace(",UNLOCKED,", ",LOCKED,")))
        assertTrue(log.spans(Signal.LOCKED).isEmpty())
        assertTrue(log.samples.none { Signal.LOCKED in it.signals })
    }

    @Test fun exactlyTheThresholdIsNoDropoutAndAGapClosesOne() {
        assertTrue(read(file(row(t0, kmh = 0, gps = 8.0), row(t0 + 2_000, kmh = 9, gps = 9.0))).dropouts.isEmpty())
        val gap = RideLogReader.GAP_MS + 1
        val log = read(
            file(
                row(t0, kmh = 0, gps = 12.0),
                row(t0 + 2_000, kmh = 0, gps = 12.0),
                row(t0 + 4_000, kmh = 0, gps = 12.0),
                row(t0 + 4_000 + gap, kmh = 12, gps = 12.0),
            ),
        )
        val d = log.dropouts.single()
        assertFalse(d.resumed)
        assertEquals(t0 + 4_000, d.endMillis)
    }

    @Test fun anS3LogWithGpsGetsDropoutsToo() {
        val log = read(file(row(t0, kmh = 0, gps = 25.0, s2Error = null), row(t0 + 2_000, kmh = 24, gps = 24.0, s2Error = null)))
        assertFalse(log.isS2)
        assertEquals(1, log.dropouts.size)
    }

    @Test fun foldedSamplesKeepTheHighestGpsSpeed() {
        val rows = (0 until 9).map { i -> row(t0 + i * 2_000L, kmh = 10, gps = if (i == 3) 30.0 else 10.0) }
        val log = RideLogReader.read(file(*rows.toTypedArray()).reader(), zone, maxSamples = 4)
        assertTrue(log.sampleStride > 1)
        assertEquals(30.0, log.samples.maxOf { it.gpsKmh ?: 0.0 }, 1e-9)
    }
}
