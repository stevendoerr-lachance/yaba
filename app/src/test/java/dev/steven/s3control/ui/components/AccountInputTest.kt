package dev.steven.s3control.ui.components

import androidx.compose.runtime.mutableStateOf
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.featuresFor
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ui.FamilyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountInputTest {
    @Test
    fun aBluetoothNameTypedKeyByKeyArrivesAsTheMacNotAsHexDigits() {
        // the field keeps what is typed; the MAC is worked out from the whole text, so the E and the 3 of ES3- are never digits
        var typed = ""
        val seen = mutableListOf<String>()
        for (ch in "ES3-112233445566") {
            typed = macInputFrom(typed + ch)
            seen += macHexFrom(typed)
        }
        assertEquals("ES3-112233445566", typed)
        assertEquals("112233445566", macHexFrom(typed))
        // the prefix as a whole is dropped, before the first digit of the MAC counts
        assertEquals(listOf("E", "E", "", "", "1", "11", "112", "1122", "11223", "112233", "1122334", "11223344", "112233445", "1122334455", "11223344556", "112233445566"), seen)
    }

    @Test
    fun everyWayToWriteAMacGivesTheSameTwelveDigits() {
        for (text in listOf("ES3-A1B2C3D4E5F6", "es3-a1b2c3d4e5f6", "ES3A1B2C3D4E5F6", "EX3-A1B2C3D4E5F6", "A1:B2:C3:D4:E5:F6", "A1-B2-C3-D4-E5-F6", "A1B2C3D4E5F6")) {
            assertEquals(text, "A1B2C3D4E5F6", macHexFrom(macInputFrom(text)))
        }
        // a MAC that starts with E is still a MAC
        assertEquals("E8B2C3D4E5F6", macHexFrom("E8:B2:C3:D4:E5:F6"))
        // never more than twelve digits
        assertEquals("A1B2C3D4E5F6", macHexFrom("A1B2C3D4E5F6FFFF"))
    }

    @Test
    fun anS2BluetoothNameGivesItsSixDigitsAndNeverTheAAndFOfVanMoof() {
        // an S2 advertises VANMOOF- and the last 6 MAC digits: the prefix goes as a whole, so nothing makes up a MAC
        for (text in listOf("VANMOOF-A1B2C3", "vanmoof-a1b2c3", "VANMOOFA1B2C3")) {
            assertEquals(text, "A1B2C3", macHexFrom(macInputFrom(text)))
        }
        val state = manualState(model = BikeFamily.S2).apply { macInput = macInputFrom("VANMOOF-A1B2C3") }
        assertFalse(state.macOk)
        assertFalse(state.canAdd)
        // the whole MAC typed after it still counts
        assertEquals("A1B2C3D4E5F6", macHexFrom("VANMOOF-A1:B2:C3:D4:E5:F6"))
    }

    @Test
    fun theMacHintFollowsTheModel() {
        // S3/X3: the MAC from the Bluetooth name; S2/X2: from the key file, as its name holds only 6 digits
        assertEquals(dev.steven.s3control.R.string.account_mac_hint, manualMacHint(manualState().model))
        assertEquals(dev.steven.s3control.R.string.account_mac_hint_s2, manualMacHint(manualState(model = BikeFamily.S2).model))
    }

    @Test
    fun theFieldKeepsOnlyWhatCanBePartOfAMac() {
        assertEquals("ES3-A1B2", macInputFrom("es3-a1b2  !?"))
        assertEquals(24, macInputFrom("A".repeat(40)).length)
    }

    // --- the model on the form for a bike added by hand ---------------------------------------------------------------

    private fun manualState(name: String = "Mein S3", model: BikeFamily? = null) = ManualBikeState(
        nameState = mutableStateOf(name),
        macState = mutableStateOf("ES3-A1B2C3D4E5F6"),
        keyState = mutableStateOf("00112233445566778899AABBCCDDEEFF"),
        userKeyIdState = mutableStateOf("1"),
        showKeyState = mutableStateOf(false),
        modelState = mutableStateOf(BikeFamily.S3.name),
        defaultName = "Mein S3",
        defaultNameS2 = "Mein S2",
    ).also { s -> model?.let { s.model = it } }

    @Test
    fun theFormShowsS3UntilSomethingElseIsPicked() {
        assertEquals(BikeFamily.S3, manualState().model)
    }

    @Test
    fun anS3PickIsStoredAsS3AndNotLeftToTheProfile() {
        val bike = manualState().toCredentials()
        // stored as picked: withModel(S3) would leave it at null and the Garage would ask again until the first connect
        assertEquals(BikeFamily.S3, bike.chosenFamily)
        assertEquals(BikeFamily.S3, bike.family)
        assertEquals(listOf("ES3-A1B2C3D4E5F6", "EX3-A1B2C3D4E5F6"), bike.advertisedNames)
        assertFalse(FamilyGate.modelChoiceOpen(bike, liveFamily = null))
        // an explicit S3 gets what an unknown bike gets: the same features, the same model shown
        assertEquals(featuresFor(BikeFamily.UNKNOWN, s2TestMode = false), featuresFor(bike.family, s2TestMode = false))
        assertEquals(BikeFamily.S3, FamilyGate.shownModel(bike))
        // S3 is the form's default: an S2 that answers on the older service still hears to set the model in the Garage
        assertEquals(BikeFamily.UNKNOWN, bike.refusalFamily)
        assertEquals("Mein S3", bike.name)
        assertEquals("A1:B2:C3:D4:E5:F6", bike.mac)
        assertEquals("00112233445566778899aabbccddeeff", bike.encryptionKey)
        assertEquals(1, bike.userKeyId)
    }

    @Test
    fun anS2PickIsStoredAsS2WithTheS2Name() {
        val bike = manualState(model = BikeFamily.S2).toCredentials()
        assertEquals(BikeFamily.S2, bike.chosenFamily)
        assertEquals(BikeFamily.S2, bike.family)
        assertEquals("Mein S2", bike.name)
        assertEquals(BikeFamily.S2, bike.refusalFamily)
        assertFalse(FamilyGate.modelChoiceOpen(bike, liveFamily = null))
        // the chosen model survives the vault
        assertEquals(BikeFamily.S2, BikeCredentials.fromJson(bike.toJson()).chosenFamily)
    }

    @Test
    fun aDefaultNameFollowsTheModelAndAnOwnNameStays() {
        val state = manualState()
        state.model = BikeFamily.S2
        assertEquals("Mein S2", state.name)
        state.model = BikeFamily.S3
        assertEquals("Mein S3", state.name)
        // a name typed by hand is never replaced
        val own = manualState(name = "Blitz")
        own.model = BikeFamily.S2
        assertEquals("Blitz", own.name)
        assertEquals("Blitz", own.toCredentials().name)
        // the S3 default left in the field while S2 is picked (a restored state) still becomes the S2 default
        val stale = manualState(model = BikeFamily.S2).apply { name = " Mein S3 " }
        assertEquals("Mein S2", stale.toCredentials().name)
        // a blank name takes the default of the model
        assertEquals("Mein S2", manualState(model = BikeFamily.S2).apply { name = "  " }.toCredentials().name)
        assertEquals("Mein S3", manualState().apply { name = "" }.toCredentials().name)
    }

    @Test
    fun onlyS2AndS3CanBePicked() {
        val state = manualState()
        state.model = BikeFamily.LEGACY_UNSUPPORTED
        assertEquals(BikeFamily.S3, state.model)
        state.model = BikeFamily.UNKNOWN
        assertEquals(BikeFamily.S3, state.model)
        assertTrue(state.toCredentials().chosenFamily == BikeFamily.S3)
    }
}
