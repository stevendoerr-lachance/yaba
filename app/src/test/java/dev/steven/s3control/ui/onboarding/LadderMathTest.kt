package dev.steven.s3control.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class LadderMathTest {
    private val needs = floatArrayOf(750f, 691f, 666f, 584f, 400f, 300f, 158f)

    @Test
    fun heightIsQuantisedDownToEightDp() {
        assertEquals(592f, LadderMath.quantize(592f), 0f)
        assertEquals(592f, LadderMath.quantize(599.9f), 0f)
        assertEquals(584f, LadderMath.quantize(591.9f), 0f)
        assertEquals(0f, LadderMath.quantize(7f), 0f)
    }

    @Test
    fun picksTheSmallestRungThatFits() {
        assertEquals(0, LadderMath.choose(needs, 800f, 0))
        // 750 quantises down to 744, which is one short of rung 0: the height counts in steps of eight
        assertEquals(1, LadderMath.choose(needs, 750f, 0))
        assertEquals(0, LadderMath.choose(needs, 752f, 0))
        assertEquals(1, LadderMath.choose(needs, 749f, 0))
        // the worked example of the spec: a 592 dp window at font 1.3 lands on "hero none" (584) with 8 dp slack
        assertEquals(3, LadderMath.choose(needs, 592f, 0))
        assertEquals(6, LadderMath.choose(needs, 158f, 0))
    }

    @Test
    fun fallsThroughToTheLastRungWhenNothingFits() {
        assertEquals(6, LadderMath.choose(needs, 100f, 0))
        assertEquals(6, LadderMath.choose(needs, 0f, 3))
    }

    @Test
    fun goingRicherNeedsSixteenDpOfSlack() {
        // standing on rung 3 (584): rung 2 needs 666, so it takes 666 + 16 = 682
        assertEquals(3, LadderMath.choose(needs, 681f, 3))
        assertEquals(3, LadderMath.choose(needs, 679f, 3))
        assertEquals(2, LadderMath.choose(needs, 690f, 3))
        // going poorer, or staying, needs only the rung itself
        assertEquals(3, LadderMath.choose(needs, 584f, 3))
        assertEquals(4, LadderMath.choose(needs, 583f, 3))
    }

    @Test
    fun theHysteresisUsesTheQuantisedHeight() {
        // 695.9 quantises to 688, which clears 666 + 16 = 682
        assertEquals(2, LadderMath.choose(needs, 695.9f, 3))
        // 689.9 quantises to 688 as well; 681.9 to 680, which does not
        assertEquals(3, LadderMath.choose(needs, 681.9f, 3))
    }

    @Test
    fun equalNeedsNeverMakeTheChoiceJumpBack() {
        // "primary hidden while the keyboard is open" saves nothing with the keyboard closed: two equal needs in a row
        val flat = floatArrayOf(500f, 400f, 400f, 300f)
        assertEquals(1, LadderMath.choose(flat, 450f, 1))
        assertEquals(1, LadderMath.choose(flat, 450f, 2))
        assertEquals(1, LadderMath.choose(flat, 450f, 0))
        assertEquals(3, LadderMath.choose(flat, 399f, 1))
    }

    @Test
    fun anEmptyLadderIsRungZero() {
        assertEquals(0, LadderMath.choose(FloatArray(0), 100f, 0))
    }
}
