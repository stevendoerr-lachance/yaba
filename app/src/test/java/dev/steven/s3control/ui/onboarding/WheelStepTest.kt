package dev.steven.s3control.ui.onboarding

import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.SpeedPlus
import dev.steven.s3control.ble.WheelStock
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The onboarding asks for the factory wheel size only when the bike reads 24″ and nothing says S3 or X3. */
class WheelStepTest {
    private fun state(wheel: Int?, stock: WheelStock?, link: Link = Link.READY) = BikeState(link = link, wheelSize = wheel, wheelStock = stock)

    @Test
    fun theTubeShowsBothSizesAndAnswersNothing() {
        // both choices in the bar's order (28″ S3, 24″ X3), not the 24 the bike reads now
        assertEquals(listOf("28", "24"), WheelTubeText.split(' '))
        // every glyph is in the matrix font (nothing falls back to "?") and the frame fits the tube without a marquee
        val unknown = MatrixFont.render("?")
        WheelTubeText.filter { it != ' ' }.forEach { ch ->
            assertFalse("$ch is not in the matrix font", MatrixFont.render(ch.toString()).contentEquals(unknown))
        }
        assertTrue(MatrixFrame.text(WheelTubeText).width <= TUBE_COLUMNS)
    }

    @Test
    fun theQuestionIsOpenOnlyForAnUnsettled24InchRead() {
        assertTrue(wheelQuestionOpen(state(SpeedPlus.WHEEL_24, WheelStock.UNKNOWN)))
        assertFalse(wheelQuestionOpen(state(SpeedPlus.WHEEL_28, WheelStock(SpeedPlus.WHEEL_28, WheelStock.Source.READ))))
        assertFalse(wheelQuestionOpen(state(SpeedPlus.WHEEL_24, WheelStock(SpeedPlus.WHEEL_28, WheelStock.Source.OWNER))))
        assertFalse(wheelQuestionOpen(state(SpeedPlus.WHEEL_24, WheelStock(SpeedPlus.WHEEL_24, WheelStock.Source.MODEL))))
        // Not read yet (or an S2): nothing to ask.
        assertFalse(wheelQuestionOpen(state(null, null)))
        assertFalse(wheelQuestionOpen(state(SpeedPlus.WHEEL_24, WheelStock.UNKNOWN, link = Link.LOST)))
    }

    @Test
    fun itStandsBetweenTheSearchAndTheUnlock() {
        assertEquals(Step.Find, stepFor(true, false, null, bleReady = true, linkReady = false, celebrated = false, wheelOpen = true))
        assertEquals(Step.Wheel, stepFor(true, false, null, bleReady = true, linkReady = true, celebrated = false, wheelOpen = true))
        assertEquals(Step.Unlock, stepFor(true, false, null, bleReady = true, linkReady = true, celebrated = false, wheelOpen = false))
        assertNull(stepFor(true, false, null, bleReady = true, linkReady = true, celebrated = true, wheelOpen = true))
    }

    @Test
    fun theSearchWaitsForAnS3sWheelSizeAFewSecondsAtMost() {
        val t0 = 1_000_000L
        val up = BikeState(link = Link.READY, family = BikeFamily.S3, connectedAtMillis = t0)
        // link up, 5564 not read yet: hold
        assertTrue(wheelPending(up, t0 + 1_000))
        // not for longer than the wait
        assertFalse(wheelPending(up, t0 + WHEEL_WAIT_MS))
        // read: the next step decides (question or unlock)
        assertFalse(wheelPending(up.copy(wheelSize = SpeedPlus.WHEEL_28, wheelStock = WheelStock(SpeedPlus.WHEEL_28, WheelStock.Source.READ)), t0 + 1_000))
        // an S2 has no wheel size; a link that is not up is the search anyway
        assertFalse(wheelPending(up.copy(family = BikeFamily.S2), t0 + 1_000))
        assertFalse(wheelPending(up.copy(link = Link.CONNECTING), t0 + 1_000))
    }
}
