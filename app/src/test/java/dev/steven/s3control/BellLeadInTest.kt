package dev.steven.s3control

import dev.steven.s3control.audio.BellConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class BellLeadInTest {
    @Test fun aBellThatStartsAtOnceGetsTheMinimumLeadIn() {
        assertEquals(17_640, BellConverter.leadInSamples(0, 44_100))
    }

    @Test fun aFilesOwnLeadInIsKept() {
        assertEquals(22_050, BellConverter.leadInSamples(22_050, 44_100))
    }

    @Test fun aLongLeadInIsCut() {
        assertEquals(35_280, BellConverter.leadInSamples(100_000, 44_100))
    }
}
