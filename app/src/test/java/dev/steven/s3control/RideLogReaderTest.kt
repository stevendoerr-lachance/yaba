package dev.steven.s3control

import dev.steven.s3control.ride.RideLogReader
import dev.steven.s3control.ride.RideLogReader.Signal
import dev.steven.s3control.ride.RideLogReader.Stretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RideLogReaderTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val t0: Long = LocalDateTime.of(2026, 10, 17, 7, 52, 14, 31_000_000).atZone(zone).toInstant().toEpochMilli()

    /** The header exactly as RideRecorder.CSV_HEADER. */
    private val header = "time,type,link,status,shipping_reported,lock,kmh,battery,charger,level,reset_button," +
        "supply_ok,wheel_moving,faults,faults_raw,battery_raw,message"

    private val rowTs: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(zone)

    /** One row the way RideRecorder.appendLine builds it (same field order, same encodings). */
    private fun row(
        at: Long,
        type: String = "sample",
        link: String = "READY",
        status: String? = "RIDING",
        shipping: Boolean = false,
        lock: String? = "UNLOCKED",
        kmh: Int? = 20,
        battery: Int? = 80,
        charger: String? = "NOT_CHARGING",
        level: Int? = 4,
        reset: Boolean? = false,
        supply: Boolean? = true,
        wheel: Boolean = true,
        faults: List<Int> = emptyList(),
        message: String = "",
    ): String = listOf(
        rowTs.format(java.time.Instant.ofEpochMilli(at)),
        type,
        link,
        status ?: "",
        if (shipping) "1" else "0",
        lock ?: "",
        kmh?.toString() ?: "",
        battery?.toString() ?: "",
        charger ?: "",
        level?.toString() ?: "",
        reset?.let { if (it) "1" else "0" } ?: "",
        supply?.let { if (it) "1" else "0" } ?: "",
        if (wheel) "1" else "0",
        faults.joinToString("|"),
        if (faults.isEmpty()) "0000000000000000" else "0004000000000000",
        "51",
        "\"" + message.replace("\"", "'") + "\"",
    ).joinToString(",")

    private fun file(vararg rows: String): String = (listOf(header) + rows).joinToString("") { it + "\n" }

    private fun read(text: String) = RideLogReader.read(text, zone)

    // --- Basic shape ----------------------------------------------------------------------------

    @Test fun readsSamplesEventsAndMarks() {
        val text = file(
            row(t0, type = "event", kmh = 0, battery = 87, wheel = false, message = "INFO: Fahrtprotokoll gestartet (ride-20261017-075214.csv)"),
            row(t0 + 2_000, kmh = 18, battery = 87),
            row(t0 + 4_000, kmh = 18, battery = 87),
            row(t0 + 5_100, type = "mark", kmh = 18, battery = 87, message = "MARK: Marke 1 (18 km/h, Daten 0,4 s alt)"),
            row(t0 + 6_000, kmh = 27, battery = 86),
            row(t0 + 8_000, kmh = 18, battery = 85),
        )
        val log = read(text)
        assertTrue(log.hasHeader)
        assertEquals(4, log.sampleCount)
        assertEquals(1, log.eventCount)
        assertEquals(1, log.markCount)
        assertEquals(t0, log.startMillis)
        assertEquals(t0 + 8_000, log.endMillis)
        assertEquals(8_000L, log.durationMillis)
        assertEquals(27, log.topKmh)
        assertEquals(87, log.batteryStart)
        assertEquals(85, log.batteryEnd)
        assertEquals(0, log.skippedLines)
        assertFalse(log.partialTail)
        assertEquals(2, log.events.size)
        val mark = log.events[1]
        assertTrue(mark.mark)
        assertEquals("MARK", mark.kind)
        assertEquals("Marke 1", mark.text)
        assertEquals("18 km/h, Daten 0,4 s alt", mark.detail)
        assertEquals(5, mark.line)
        // A mark is no incident (a Vorfall is an alert or an error); it is kept like one, though.
        assertFalse(mark.incident)
        assertTrue(mark.kept)
        assertEquals(0, log.incidentCount)
        val info = log.events[0]
        assertEquals("INFO", info.kind)
        assertEquals("Fahrtprotokoll gestartet", info.text)
        assertFalse(info.incident)
    }

    @Test fun integratesDistanceFromSpeed() {
        // 18 km/h for 60 s (31 samples, 2 s apart) = 0.3 km; all of it rolling.
        val rows = (0..30).map { row(t0 + it * 2_000L, kmh = 18) }
        val log = read(file(*rows.toTypedArray()))
        assertEquals(0.3, log.distanceKm, 1e-9)
        assertEquals(60_000L, log.movingMillis)
        assertEquals(18.0, log.avgMovingKmh!!, 1e-9)
    }

    @Test fun distanceSkipsGapsAndLostLink() {
        val log = read(
            file(
                row(t0, kmh = 36),
                row(t0 + 2_000, kmh = 36), // 0.02 km
                row(t0 + 4_000, link = "LOST", kmh = null),
                row(t0 + 6_000, kmh = 36), // after a drop: no interval
                row(t0 + 60_000, kmh = 36), // 54 s later: a gap, not joined
                row(t0 + 62_000, kmh = 36), // 0.02 km
            ),
        )
        assertEquals(0.04, log.distanceKm, 1e-9)
        assertEquals(4_000L, log.movingMillis)
        // Below 30 s of rolling there is no average.
        assertNull(log.avgMovingKmh)
    }

    @Test fun staleValuesAfterLinkLossDoNotCount() {
        // The controller keeps the last lock, battery and faults after a drop; those rows must not count.
        val log = read(
            file(
                row(t0, kmh = 20, battery = 70),
                row(t0 + 2_000, link = "LOST", kmh = 45, battery = 10, lock = "LOCKED", faults = listOf(42)),
            ),
        )
        assertEquals(20, log.topKmh)
        assertEquals(70, log.batteryEnd)
        assertTrue(log.faultsSeen.isEmpty())
        assertTrue(log.spans.isEmpty())
        val stale = log.samples.last()
        assertFalse(stale.ready)
        assertNull(stale.kmh)
        assertNull(stale.battery)
    }

    // --- Messages -------------------------------------------------------------------------------

    @Test fun splitsMessageIntoKindTextAndDetail() {
        assertEquals(
            RideLogReader.Message("ALERT", null, "Kick-Lock meldet gesperrt während der Fahrt", "23 km/h"),
            RideLogReader.splitMessage("ALERT: Kick-Lock meldet gesperrt während der Fahrt (23 km/h)"),
        )
        // A detail that itself ends in parentheses (a situation tag) stays whole.
        assertEquals(
            RideLogReader.Message("MARK", null, "Situation: Bremsen", "Nachher 08:03:12 (brake)"),
            RideLogReader.splitMessage("MARK: Situation: Bremsen (Nachher 08:03:12 (brake))"),
        )
        // No known kind, no detail.
        assertEquals(RideLogReader.Message(null, null, "Something: else", null), RideLogReader.splitMessage("Something: else"))
        // Parentheses that are part of the text (no space before) are not a detail.
        assertEquals(RideLogReader.Message("STATE", null, "Status(1)", null), RideLogReader.splitMessage("STATE: Status(1)"))
        // The newer form carries the code after the kind.
        assertEquals(
            RideLogReader.Message("ALERT", "SUPPLY_LOW", "Versorgung unter 20 V", "8 km/h, Akku 81 %"),
            RideLogReader.splitMessage("ALERT/SUPPLY_LOW: Versorgung unter 20 V (8 km/h, Akku 81 %)"),
        )
        // A slash in a head that is no kind/code pair leaves the text whole.
        assertEquals(RideLogReader.Message(null, null, "km/h: 25", null), RideLogReader.splitMessage("km/h: 25"))
        assertEquals(RideLogReader.Message(null, null, "ALERT/x y: z", null), RideLogReader.splitMessage("ALERT/x y: z"))
    }

    @Test fun readsTheOldAndTheNewMessageForm() {
        val log = read(
            file(
                // Written by YABA 1.0: no code.
                row(t0, type = "event", lock = "LOCKED", kmh = 22, message = "ALERT: Kick-Lock meldet gesperrt während der Fahrt (22 km/h)"),
                // Written by YABA 1.1: the code after the kind.
                row(t0 + 2_000, type = "event", supply = false, message = "ALERT/SUPPLY_LOW: Versorgung unter 20 V (22 km/h, Akku 80 %)"),
                row(t0 + 4_000, type = "event", message = "ALERT/LINK_LOST_RIDING: Verbindung während der Fahrt unterbrochen"),
                row(t0 + 6_000, type = "event", message = "ALERT/BATTERY_JUMP: Akku springt von 80 auf 60 %"),
                row(t0 + 8_000, type = "event", message = "ERROR: Befehl abgelehnt (Licht)"),
                row(t0 + 9_000, type = "mark", message = "MARK: Marke 1"),
                row(t0 + 10_000, type = "event", message = "STATE: Status Fährt"),
            ),
        )
        val e = log.events
        assertEquals(7, e.size)
        assertNull(e[0].code)
        assertEquals("ALERT", e[0].kind)
        assertEquals("Kick-Lock meldet gesperrt während der Fahrt", e[0].text)
        assertEquals("22 km/h", e[0].detail)
        assertEquals(RideLogReader.Tone.SIGNAL, e[0].tone)
        assertEquals("SUPPLY_LOW", e[1].code)
        assertEquals("ALERT", e[1].kind)
        assertEquals("Versorgung unter 20 V", e[1].text)
        assertEquals(RideLogReader.Tone.LAMP, e[1].tone)
        assertEquals(RideLogReader.Tone.LAMP, e[2].tone)
        assertEquals(RideLogReader.Tone.PLAIN, e[3].tone)
        assertEquals(RideLogReader.Tone.LAMP, e[4].tone)
        assertEquals(RideLogReader.Tone.MARK, e[5].tone)
        assertEquals(RideLogReader.Tone.PLAIN, e[6].tone)
        // Five alerts and errors are Vorfälle, the mark is not.
        assertEquals(5, log.incidentCount)
        assertEquals(5, e.count { it.incident })
        assertEquals(1, log.markCount)
    }

    @Test fun recorderWritesWhatTheReaderReads() {
        val supply = dev.steven.s3control.ride.EventLog.Event(
            t0, dev.steven.s3control.ride.EventLog.Kind.ALERT, "Versorgung unter 20 V", "8 km/h",
            dev.steven.s3control.ride.EventLog.Code.SUPPLY_LOW,
        )
        assertEquals("ALERT/SUPPLY_LOW: Versorgung unter 20 V (8 km/h)", dev.steven.s3control.ride.RideRecorder.messageOf(supply))
        val mark = dev.steven.s3control.ride.EventLog.Event(
            t0, dev.steven.s3control.ride.EventLog.Kind.MARK, "Marke 1", null, dev.steven.s3control.ride.EventLog.Code.MARK,
        )
        assertEquals("MARK: Marke 1", dev.steven.s3control.ride.RideRecorder.messageOf(mark))
        val plain = dev.steven.s3control.ride.EventLog.Event(t0, dev.steven.s3control.ride.EventLog.Kind.STATE, "Status Fährt")
        assertEquals("STATE: Status Fährt", dev.steven.s3control.ride.RideRecorder.messageOf(plain))
        val back = RideLogReader.splitMessage(dev.steven.s3control.ride.RideRecorder.messageOf(supply))
        assertEquals(RideLogReader.Message("ALERT", "SUPPLY_LOW", "Versorgung unter 20 V", "8 km/h"), back)
    }

    @Test fun keepsCommasInsideTheQuotedMessage() {
        val log = read(file(row(t0, type = "event", message = "ALERT: Fehler 42: Spule, Kabel (Status Fährt, Akku 81 %, 22 km/h)")))
        val e = log.events.single()
        assertEquals("Fehler 42: Spule, Kabel", e.text)
        assertEquals("Status Fährt, Akku 81 %, 22 km/h", e.detail)
    }

    @Test fun recorderReplacesQuotesWithApostrophes() {
        val log = read(file(row(t0, type = "event", message = "INFO: Ton \"Amsel\" übertragen")))
        assertEquals("Ton 'Amsel' übertragen", log.events.single().text)
    }

    @Test fun joinsAMessageThatContainsALineBreak() {
        val text = file(
            row(t0, type = "event", message = "ERROR: Erste Zeile\nzweite Zeile (Detail)"),
            row(t0 + 2_000),
        )
        val log = read(text)
        assertEquals(1, log.eventCount)
        assertEquals(1, log.sampleCount)
        assertEquals("Erste Zeile zweite Zeile", log.events.single().text)
        assertEquals("Detail", log.events.single().detail)
        assertEquals(0, log.skippedLines)
    }

    // --- Partial and broken lines ----------------------------------------------------------------

    @Test fun dropsALastLineCutOffWhileWriting() {
        val whole = file(row(t0), row(t0 + 2_000))
        val cut = row(t0 + 4_000).let { it.substring(0, it.length / 2) }
        val log = read(whole + cut)
        assertTrue(log.partialTail)
        assertEquals(2, log.sampleCount)
        assertEquals(t0 + 2_000, log.endMillis)
    }

    @Test fun dropsALastLineCutInsideTheMessage() {
        val cut = row(t0 + 2_000, type = "event", message = "ALERT: Bike meldet Versandmodus (Akku 24 %)").dropLast(6)
        val log = read(file(row(t0)) + cut)
        assertTrue(log.partialTail)
        assertEquals(0, log.eventCount)
    }

    @Test fun keepsAWholeLastLineWithoutNewline() {
        val log = read(file(row(t0)) + row(t0 + 2_000, kmh = 31))
        assertFalse(log.partialTail)
        assertEquals(2, log.sampleCount)
        assertEquals(31, log.topKmh)
    }

    @Test fun countsGarbageAndIgnoresASecondHeader() {
        val text = file(
            row(t0),
            "this is not a row",
            header,
            "2026-13-45T99:00:00.000,sample,READY",
            row(t0 + 2_000, type = "unknown"),
            row(t0 + 4_000),
        )
        val log = read(text)
        assertEquals(2, log.sampleCount)
        assertEquals(3, log.skippedLines)
    }

    @Test fun toleratesCrlfAndAByteOrderMark() {
        val text = "﻿" + file(row(t0), row(t0 + 2_000, kmh = 25)).replace("\n", "\r\n")
        val log = read(text)
        assertTrue(log.hasHeader)
        assertEquals(2, log.sampleCount)
        assertEquals(25, log.topKmh)
        assertEquals(0, log.skippedLines)
    }

    @Test fun readsColumnsByHeaderName() {
        // A future column in the middle must not shift the others.
        val newHeader = header.replace("kmh,", "kmh,gear,")
        val line = row(t0, kmh = 22, battery = 64).split(",").toMutableList().also { it.add(7, "3") }.joinToString(",")
        val log = read(newHeader + "\n" + line + "\n")
        assertEquals(22, log.topKmh)
        assertEquals(64, log.batteryStart)
    }

    @Test fun emptyFileAndHeaderOnly() {
        assertTrue(read("").isEmpty)
        val headerOnly = read(header + "\n")
        assertTrue(headerOnly.isEmpty)
        assertTrue(headerOnly.hasHeader)
        assertNull(headerOnly.startMillis)
    }

    @Test fun parsesTimeInTheGivenZone() {
        assertEquals(t0, RideLogReader.parseTime("2026-10-17T07:52:14.031", zone))
        assertEquals(t0 - 31, RideLogReader.parseTime("2026-10-17T07:52:14", zone))
        assertNull(RideLogReader.parseTime("2026-10-17 07:52:14.031", zone))
        assertNull(RideLogReader.parseTime("2026-02-30T07:52:14.031", zone))
        assertNull(RideLogReader.parseTime("garbage", zone))
    }

    // --- Shipping-rule signals ----------------------------------------------------------------------

    @Test fun tracksTheLockReadingLockedWhileRiding() {
        val log = read(
            file(
                row(t0, lock = "UNLOCKED", kmh = 22),
                row(t0 + 2_000, lock = "LOCKED", kmh = 22),
                row(t0 + 3_000, type = "event", lock = "LOCKED", kmh = 22, message = "ALERT: Kick-Lock meldet gesperrt während der Fahrt (22 km/h)"),
                row(t0 + 4_000, lock = "LOCKED", kmh = 0, wheel = false),
                row(t0 + 6_000, lock = "UNLOCKED", kmh = 0, wheel = false),
            ),
        )
        val spans = log.spans(Signal.LOCKED)
        assertEquals(1, spans.size)
        assertEquals(t0 + 2_000, spans[0].startMillis)
        assertEquals(t0 + 6_000, spans[0].endMillis)
        assertTrue(spans[0].moving)
        assertEquals(listOf(Signal.LOCKED), log.signalsAt(t0 + 3_500))
        assertTrue(log.signalsAt(t0 + 7_000).isEmpty())
    }

    @Test fun faultsAndShippingBecomeSpansAndTheLowestFaultWins() {
        val log = read(
            file(
                row(t0),
                row(t0 + 2_000, faults = listOf(42)),
                row(t0 + 4_000, faults = listOf(8, 42), supply = false),
                row(t0 + 6_000, faults = listOf(18), shipping = true, status = "SHIPPING"),
                row(t0 + 8_000, link = "LOST", kmh = null),
            ),
        )
        assertEquals(listOf(8, 18, 42), log.faultsSeen)
        assertEquals(8, log.lowestFault)
        val f42 = log.spans(Signal.FAULT_42).single()
        assertEquals(t0 + 2_000, f42.startMillis)
        assertEquals(t0 + 6_000, f42.endMillis)
        assertTrue(f42.closedByRow)
        val f8 = log.spans(Signal.FAULT_8).single()
        assertEquals(t0 + 4_000, f8.startMillis)
        assertEquals(t0 + 6_000, f8.endMillis)
        val supply = log.spans(Signal.SUPPLY_LOW).single()
        assertEquals(t0 + 4_000, supply.startMillis)
        // The link drop says nothing about the signals: shipping and fault 18 end where they were last seen.
        val ship = log.spans(Signal.SHIPPING).single()
        assertEquals(t0 + 6_000, ship.startMillis)
        assertEquals(t0 + 6_000, ship.endMillis)
        assertFalse(ship.closedByRow)
        assertEquals(t0 + 6_000, log.spans(Signal.FAULT_18).single().endMillis)
        // At the row that ended them, 42 and 8 no longer hold; shipping and 18 do.
        assertEquals(setOf(Signal.FAULT_18, Signal.SHIPPING), log.signalsAt(t0 + 6_000).toSet())
        // The tube: the first shipping row with faults, its lowest code.
        assertEquals(18, log.tubeFault)
        assertEquals(t0 + 6_000, log.tubeFaultAtMillis)
        assertTrue(log.tubeFromShipping)
    }

    @Test fun aFlakyLinkDoesNotSplitASpan() {
        // A bike parked locked at the edge of Bluetooth range: every other row has the link down.
        val rows = (0 until 200).map { i ->
            row(t0 + i * 2_000L, link = if (i % 2 == 0) "READY" else "LOST", lock = "LOCKED", kmh = 0, wheel = false, status = "PARKED")
        }
        val log = read(file(*rows.toTypedArray()))
        val locked = log.spans(Signal.LOCKED).single()
        assertEquals(t0, locked.startMillis)
        assertEquals(t0 + 198 * 2_000L, locked.endMillis)
        assertFalse(locked.moving)
        assertEquals(100, log.linkDown.size)
        assertEquals(Stretch(t0 + 2_000, t0 + 4_000), log.linkDown.first())
        assertEquals(Stretch(t0 + 398_000L, t0 + 398_000L), log.linkDown.last())
        assertEquals(100, log.readyRows)
    }

    @Test fun aLongTimeWithoutConnectionEndsASpan() {
        // Locked, then 12 minutes without a connection (the bike may have been ridden), then locked again.
        val lost = (1..360).map { row(t0 + it * 2_000L, link = "SEARCHING", lock = "LOCKED", kmh = null) }
        val log = read(
            file(
                row(t0, lock = "LOCKED", kmh = 0, wheel = false),
                *lost.toTypedArray(),
                row(t0 + 361 * 2_000L, lock = "LOCKED", kmh = 0, wheel = false),
            ),
        )
        val spans = log.spans(Signal.LOCKED)
        assertEquals(2, spans.size)
        assertEquals(t0, spans[0].endMillis)
        assertEquals(t0 + 361 * 2_000L, spans[1].startMillis)
        assertEquals(listOf(Stretch(t0 + 2_000, t0 + 361 * 2_000L)), log.linkDown)
    }

    @Test fun aRecordingWithoutConnectionSaysSo() {
        val rows = (0 until 20).map { row(t0 + it * 2_000L, link = "SEARCHING", status = null, lock = null, kmh = null, battery = null, supply = null) }
        val log = read(file(*rows.toTypedArray()))
        assertFalse(log.everConnected)
        assertEquals(0, log.readyRows)
        assertEquals(listOf(Stretch(t0, t0 + 38_000)), log.linkDown)
        assertTrue(log.spans.isEmpty())
        assertNull(log.topKmh)
    }

    @Test fun theTubeShowsTheLastFaultEpisodeNotTheLowestOfTheLog() {
        val log = read(
            file(
                row(t0, faults = listOf(8)),
                row(t0 + 2_000),
                row(t0 + 4_000, faults = listOf(42, 18)),
                row(t0 + 6_000, faults = listOf(42)),
                row(t0 + 8_000),
            ),
        )
        assertEquals(8, log.lowestFault)
        assertEquals(42, log.tubeFault)
        assertEquals(t0 + 6_000, log.tubeFaultAtMillis)
        assertFalse(log.tubeFromShipping)
    }

    @Test fun eventsAndSamplesCarryTheirOwnSignals() {
        val log = read(
            file(
                row(t0, supply = false),
                row(t0 + 1_000, type = "event", supply = true, kmh = 25, message = "INFO: Versorgung wieder normal"),
                row(t0 + 2_000, supply = true, kmh = 24),
            ),
        )
        val e = log.events.single()
        assertEquals(25, e.kmh)
        assertEquals(true, e.supplyOk)
        assertTrue(e.signals.isEmpty())
        assertEquals(setOf(Signal.SUPPLY_LOW), log.samples.first().signals)
        // The supply span ends at the event row, which no longer holds it.
        assertTrue(log.signalsAt(t0 + 1_000).isEmpty())
        assertEquals(listOf(Signal.SUPPLY_LOW), log.signalsAt(t0 + 500))
    }

    @Test fun aGapClosesSpansWhereTheyWereLastSeen() {
        val log = read(
            file(
                row(t0, faults = listOf(42)),
                row(t0 + 2_000, faults = listOf(42)),
                row(t0 + 120_000, faults = listOf(42)),
            ),
        )
        val spans = log.spans(Signal.FAULT_42)
        assertEquals(2, spans.size)
        assertEquals(t0 + 2_000, spans[0].endMillis)
        assertEquals(t0 + 120_000, spans[1].startMillis)
        assertEquals(t0 + 120_000, spans[1].endMillis)
    }

    @Test fun aSignalOnTheLastRowIsASpanOfZeroLength() {
        val log = read(file(row(t0), row(t0 + 2_000, shipping = true)))
        val ship = log.spans(Signal.SHIPPING).single()
        assertEquals(ship.startMillis, ship.endMillis)
        assertEquals(listOf(Signal.SHIPPING), log.signalsAt(t0 + 2_000))
    }

    // --- Huge files -----------------------------------------------------------------------------------

    @Test fun foldsSamplesPastTheCapAndKeepsPeaks() {
        // 1,000 samples with one spike, one supply drop and one fault, read with a cap of 100.
        val rows = (0 until 1_000).map { i ->
            row(
                t0 + i * 2_000L,
                kmh = if (i == 777) 41 else 20,
                battery = 90 - i / 20,
                supply = i != 500,
                faults = if (i == 321) listOf(42) else emptyList(),
            )
        }
        val log = RideLogReader.read(file(*rows.toTypedArray()).reader(), zone, maxSamples = 100)
        assertEquals(1_000, log.sampleCount)
        assertTrue(log.samples.size <= 100)
        assertEquals(16, log.sampleStride)
        assertEquals(41, log.topKmh)
        assertEquals(41, log.samples.maxOf { it.kmh ?: 0 })
        assertTrue(log.samples.any { it.supplyOk == false })
        assertTrue(log.samples.any { it.hasFault(42) })
        assertEquals(41, log.samples.last().battery)
        // Samples stay in time order.
        assertEquals(log.samples.map { it.atMillis }.sorted(), log.samples.map { it.atMillis })
        // The full-resolution numbers do not depend on the folding.
        assertEquals(20.0 * 1_998 / 3_600.0 + (41 - 20) * 2 / 3_600.0, log.distanceKm, 1e-9)
    }

    @Test fun keepsTheNewestEventsPastTheCap() {
        val rows = (0 until 50).map { i -> row(t0 + i * 1_000L, type = "event", message = "STATE: Ereignis $i") } +
            row(t0 + 3_000, type = "mark", message = "MARK: Marke 1")
        val log = RideLogReader.read(file(*rows.toTypedArray()).reader(), zone, maxEvents = 10)
        assertEquals(50, log.eventCount)
        assertEquals(1, log.markCount)
        assertEquals(40, log.eventsLeftOut)
        // Ten newest events plus the mark, oldest first.
        assertEquals(11, log.events.size)
        assertEquals("Marke 1", log.events.first().text)
        assertEquals("Ereignis 40", log.events[1].text)
        assertEquals("Ereignis 49", log.events.last().text)
    }

    @Test fun incidentsSurviveTheEventCap() {
        // An early alert, then a link that chatters 10,000 times.
        val rows = listOf(row(t0, type = "event", message = "ALERT: Fehler 8: Akku (Status Fährt)")) +
            (1..10_000).map { i -> row(t0 + i * 1_000L, type = "event", link = "LOST", message = "LINK: Verbindung unterbrochen") }
        val log = RideLogReader.read(file(*rows.toTypedArray()).reader(), zone, maxEvents = 100)
        assertEquals(10_001, log.eventCount)
        assertEquals(1, log.incidentCount)
        assertEquals(9_900, log.eventsLeftOut)
        assertEquals(0, log.marksLeftOut)
        assertEquals(101, log.events.size)
        val alert = log.events.first()
        assertEquals("ALERT", alert.kind)
        assertEquals(t0, alert.atMillis)
        // The rug is built from every row: the first stretch is not empty, and the alert wins its bucket.
        val rug = log.rug
        assertEquals(RideLogReader.RUG_BUCKETS, rug.buckets)
        assertEquals(RideLogReader.Rug.RANK_ALERT, rug.rank(0))
        assertTrue(rug.startOf(rug.buckets - 1) + rug.bucketMillis > t0 + 10_000_000L)
        val filled = (0 until rug.buckets).count { rug.rank(it) > 0 }
        assertTrue("rug covers the whole log, $filled buckets", filled > 500)
    }

    @Test fun incidentsPastTheirCapAreCountedApartFromMarks() {
        val rows = (0 until 30).map { i -> row(t0 + i * 1_000L, type = "event", message = "ALERT: Alarm $i") } +
            (0 until 5).map { i -> row(t0 + 100_000L + i * 1_000L, type = "mark", message = "MARK: Marke $i") }
        val log = RideLogReader.read(file(*rows.toTypedArray()).reader(), zone, maxIncidents = 10)
        // Marks are kept with the incidents, but they are no incidents.
        assertEquals(30, log.incidentCount)
        assertEquals(5, log.markCount)
        assertEquals(25, log.eventsLeftOut)
        assertEquals(0, log.marksLeftOut)
        assertEquals(10, log.events.size)
        assertEquals("Alarm 25", log.events.first().text)
    }

    @Test fun streamsAVeryLongLogWithoutHoldingIt() {
        // About 17 MB of rows (a week of logging) generated on the fly, never as one string.
        val count = 150_000
        val reader = object : Reader() {
            var i = -1
            var cur = ""
            var pos = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (pos >= cur.length) {
                    i++
                    if (i > count) return -1
                    cur = (if (i == 0) header else row(t0 + i * 2_000L, kmh = i % 30)) + "\n"
                    pos = 0
                }
                val n = minOf(len, cur.length - pos)
                cur.toCharArray(cbuf, off, pos, pos + n)
                pos += n
                return n
            }
            override fun close() = Unit
        }
        val log = RideLogReader.read(reader, zone)
        assertEquals(count, log.sampleCount)
        assertTrue(log.samples.size <= RideLogReader.MAX_SAMPLES)
        assertEquals(29, log.topKmh)
        assertNotNull(log.sampleAt(t0 + 100_000L * 2_000))
    }

    @Test fun findsTheSampleClosestToATime() {
        val log = read(file(row(t0, kmh = 10), row(t0 + 2_000, kmh = 20), row(t0 + 4_000, kmh = 30)))
        assertEquals(20, log.sampleAt(t0 + 2_400)?.kmh)
        assertEquals(30, log.sampleAt(t0 + 3_100)?.kmh)
        assertEquals(10, log.sampleAt(t0 - 1_000)?.kmh)
        assertNull(log.sampleAt(t0 + 60_000))
    }
}
