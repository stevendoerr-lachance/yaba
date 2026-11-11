package dev.steven.s3control

import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.PollClaims
import dev.steven.s3control.ui.s2.S2DriveWatch
import dev.steven.s3control.ui.s2.S2ErrorCatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll registry (BikeController.requestPoll): the ride logger, the cockpit, the S2 drive page and the wiggle
 * test claim a cadence each, the fastest wins, and releasing one never undoes another (before, whoever wrote the
 * interval last won, and stopping the logger mid wiggle test dropped it to 5 s).
 */
class PollClaimsTest {

    private val slow = BikeController.SLOW_POLL_MS

    @Test fun withoutClaimsTheBikeIsReadEveryFiveSeconds() {
        assertEquals(5_000L, slow)
        assertEquals(slow, PollClaims(slow).current())
    }

    @Test fun theFastestClaimWinsAndAReleaseOnlyDropsItsOwn() {
        val p = PollClaims(slow)
        assertEquals(1_500L, p.set("ride-logger", 1_500))
        assertEquals(1_000L, p.set("s2-drive-page", 1_000))
        assertEquals(700L, p.set("s2-wiggle", 700))
        // The logger stops during the wiggle test: the test keeps its 0.7 s.
        assertEquals(700L, p.set("ride-logger", null))
        assertEquals(1_000L, p.set("s2-wiggle", null))
        assertEquals(slow, p.set("s2-drive-page", null))
    }

    @Test fun theS3CadenceIsWhatItWas() {
        // Logger and cockpit on an S3: 1.5 s while either is on, 5 s once both are off.
        val p = PollClaims(slow)
        p.set("ride-logger", 1_500)
        assertEquals(1_500L, p.set("cockpit@1", 1_500))
        assertEquals(1_500L, p.set("ride-logger", null))
        assertEquals(slow, p.set("cockpit@1", null))
        // A claim slower than the default never slows the bike down.
        assertEquals(slow, p.set("odd", 60_000))
    }

    @Test fun onlyAProfileThatNamesTheModelSettlesIt() {
        assertTrue(BikeFamily.settles("ELECTRIFIED_2022"))
        assertTrue(BikeFamily.settles("ELECTRIFIED_2018"))
        assertTrue(BikeFamily.settles("SMARTBIKE_2016"))
        assertFalse("only likely an S2", BikeFamily.settles("ELECTRIFIED_2019"))
        assertFalse(BikeFamily.settles(null))
        assertFalse(BikeFamily.settles("ELECTRIFIED_2030"))
    }

    @Test fun theCatcherListShowsTheCurrentBikeOnly() {
        fun hit(mac: String?) = S2ErrorCatcher.Hit(16, 0, 0, 1, 0, 0, 50, 50, "00".repeat(16), mac)
        val hits = listOf(hit("AA"), hit("BB"), hit(null))
        assertEquals(listOf("AA", null), S2DriveWatch.hitsFor(hits, "AA").map { it.mac })
        assertEquals(hits, S2DriveWatch.hitsFor(hits, null))
    }
}
