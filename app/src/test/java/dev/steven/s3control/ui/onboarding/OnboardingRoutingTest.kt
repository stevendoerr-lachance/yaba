package dev.steven.s3control.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the onboarding stands is derived from what is true; nothing but the side, the celebration and "change key" is stored. */
class OnboardingRoutingTest {
    private fun step(
        hasBikes: Boolean = false,
        changingKey: Boolean = false,
        side: Side? = null,
        bleReady: Boolean = false,
        linkReady: Boolean = false,
        celebrated: Boolean = false,
    ) = stepFor(hasBikes, changingKey, side, bleReady, linkReady, celebrated)

    @Test
    fun aFreshInstallStartsAtTheWelcome() {
        assertEquals(Step.Welcome, step())
    }

    @Test
    fun theThreeWaysToAKeyAreSides() {
        assertEquals(Step.SignIn, step(side = Side.SignIn))
        assertEquals(Step.ImportChoose, step(side = Side.ImportChoose))
        assertEquals(Step.ImportPaste, step(side = Side.ImportPaste))
        assertEquals(Step.Manual, step(side = Side.Manual))
    }

    @Test
    fun aStoredKeyMovesOnToBluetoothAccessWhateverSideWasOpen() {
        assertEquals(Step.Permission, step(hasBikes = true, side = Side.SignIn))
        assertEquals(Step.Permission, step(hasBikes = true))
    }

    @Test
    fun afterAccessComesTheSearchThenTheUnlockThenTheEnd() {
        assertEquals(Step.Permission, step(hasBikes = true, bleReady = false))
        assertEquals(Step.Find, step(hasBikes = true, bleReady = true))
        assertEquals(Step.Unlock, step(hasBikes = true, bleReady = true, linkReady = true))
        assertNull(step(hasBikes = true, bleReady = true, linkReady = true, celebrated = true))
    }

    @Test
    fun aLostLinkOrRevokedAccessGoesBackWithoutLosingAnything() {
        // the unlock screen with the link gone is the search again, and without Bluetooth access it is the access again
        assertEquals(Step.Find, step(hasBikes = true, bleReady = true, linkReady = false))
        assertEquals(Step.Permission, step(hasBikes = true, bleReady = false, linkReady = true))
    }

    @Test
    fun changeKeyShowsTheWaysToAKeyWhileTheKeyStays() {
        assertEquals(Step.Welcome, step(hasBikes = true, changingKey = true, bleReady = true))
        assertEquals(Step.Manual, step(hasBikes = true, changingKey = true, side = Side.Manual, bleReady = true, linkReady = true))
    }
}
