package dev.steven.s3control

import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.S2Gatt
import dev.steven.s3control.ble.ScanSpec
import dev.steven.s3control.data.AccountRepository
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ui.FamilyGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The garage's model choice (S3/X3 or S2/X2) for bikes the account does not describe, as stored in the vault. */
class ModelChoiceTest {

    private fun bike(profile: String? = null, fromAccount: Boolean = false) = BikeCredentials(
        id = null, name = "B", ownerName = null, mac = "74:D2:85:AB:CD:EF",
        encryptionKey = "00112233445566778899aabbccddeeff", userKeyId = 1, bleProfile = profile, fromAccount = fromAccount,
    )

    private val s3Names = listOf("ES3-74D285ABCDEF", "EX3-74D285ABCDEF")

    @Test
    fun choosingS2MakesAManualBikeAnS2() {
        val b = bike().withModel(BikeFamily.S2)
        assertEquals(BikeFamily.S2, b.chosenFamily)
        assertEquals(BikeFamily.S2, b.family)
        assertEquals(listOf("VANMOOF-abcdef", "VANMOOF-ABCDEF"), b.advertisedNames)
        assertEquals(S2Gatt.SERVICE, ScanSpec.forBike(b).service)
    }

    @Test
    fun choosingS3OnAnUnknownBikeChangesNothing() {
        val plain = bike()
        val b = plain.withModel(BikeFamily.S3)
        assertNull("nothing stored: the bike stays exactly what it was", b.chosenFamily)
        assertEquals(plain, b)
        assertEquals(BikeFamily.UNKNOWN, b.family)
        assertEquals(s3Names, b.advertisedNames)
        // Back from S2 to S3 clears the choice again.
        assertEquals(plain, plain.withModel(BikeFamily.S2).withModel(BikeFamily.S3))
    }

    @Test
    fun aChoiceTheProfileAlreadyMakesIsNotStored() {
        assertNull(bike("ELECTRIFIED_2018").withModel(BikeFamily.S2).chosenFamily)
        assertNull(bike("ELECTRIFIED_2022").withModel(BikeFamily.S3).chosenFamily)
    }

    @Test
    fun aChoiceOverridesAWrongProfile() {
        val b = bike("ELECTRIFIED_2018").withModel(BikeFamily.S3)
        assertEquals(BikeFamily.S3, b.chosenFamily)
        assertEquals(BikeFamily.S3, b.family)
        assertEquals(s3Names, b.advertisedNames)
        assertNull(ScanSpec.forBike(b).service)
        assertEquals(BikeFamily.S2, bike("ELECTRIFIED_2022").withModel(BikeFamily.S2).family)
    }

    @Test
    fun onlyS2AndS3CanBeChosen() {
        val b = bike()
        assertSame(b, b.withModel(BikeFamily.UNKNOWN))
        assertSame(b, b.withModel(BikeFamily.LEGACY_UNSUPPORTED))
    }

    @Test
    fun theChoiceSurvivesTheVaultAndOnlyWhenSet() {
        val chosen = bike().withModel(BikeFamily.S2)
        val json = chosen.toJson()
        assertEquals("S2", json.getString("chosenFamily"))
        assertEquals(chosen, BikeCredentials.fromJson(JSONObject(json.toString())))
        // Without a choice the vault entry is what it always was.
        assertFalse(bike().toJson().has("chosenFamily"))
        assertFalse(bike("ELECTRIFIED_2018").toJson().has("chosenFamily"))
    }

    @Test
    fun anUnknownStoredChoiceIsIgnored() {
        for (v in listOf("LEGACY_UNSUPPORTED", "UNKNOWN", "s2", "X2", "")) {
            val o = bike().toJson().put("chosenFamily", v)
            val b = BikeCredentials.fromJson(o)
            assertNull(v, b.chosenFamily)
            assertEquals(v, BikeFamily.UNKNOWN, b.family)
        }
        assertTrue(BikeCredentials.fromJson(bike().toJson().put("chosenFamily", "S3")).chosenFamily == BikeFamily.S3)
    }

    // --- Account bikes ----------------------------------------------------------------------------

    @Test
    fun anAccountBikeWithAnUnknownProfileGetsTheChoiceAndCanBecomeAnS2() {
        for (profile in listOf(null, "ELECTRIFIED_2030", "")) {
            val account = bike(profile, fromAccount = true)
            assertEquals(BikeFamily.UNKNOWN, account.family)
            assertTrue("$profile", FamilyGate.modelChoice(account))
            val s2 = account.withModel(BikeFamily.S2)
            assertEquals(BikeFamily.S2, s2.family)
            assertEquals(S2Gatt.SERVICE, ScanSpec.forBike(s2).service)
        }
    }

    @Test
    fun anAccountRefreshKeepsTheChoiceWhileTheProfileDoesNotSettleTheModel() {
        val chosen = bike(null, fromAccount = true).withModel(BikeFamily.S2)
        val refreshed = AccountRepository.merge(listOf(chosen), listOf(bike(null, fromAccount = true)))
        assertEquals(BikeFamily.S2, refreshed.single().chosenFamily)
        assertEquals(BikeFamily.S2, refreshed.single().family)
        // A key-imported S2 the person then signs in for keeps its S2 too.
        val imported = bike(null).withModel(BikeFamily.S2)
        assertEquals(BikeFamily.S2, AccountRepository.merge(listOf(imported), listOf(bike(null, fromAccount = true))).single().family)
        // "Probably S2" (2019) chosen as S3 stays S3.
        val s3 = bike("ELECTRIFIED_2019", fromAccount = true).withModel(BikeFamily.S3)
        assertEquals(BikeFamily.S3, AccountRepository.merge(listOf(s3), listOf(bike("ELECTRIFIED_2019", fromAccount = true))).single().family)
    }

    @Test
    fun aProfileThatSettlesTheModelWinsOverAnOldChoice() {
        // The account now says S3 for sure: no hidden S2 override the garage could no longer change.
        val old = bike(null).withModel(BikeFamily.S2)
        val merged = AccountRepository.merge(listOf(old), listOf(bike("ELECTRIFIED_2022", fromAccount = true))).single()
        assertNull(merged.chosenFamily)
        assertEquals(BikeFamily.S3, merged.family)
        assertFalse(FamilyGate.modelChoice(merged))
        // An S2 profile makes the stored S2 superfluous: nothing stored, still an S2.
        val s2 = AccountRepository.merge(listOf(old), listOf(bike("ELECTRIFIED_2018", fromAccount = true))).single()
        assertNull(s2.chosenFamily)
        assertEquals(BikeFamily.S2, s2.family)
    }

    @Test
    fun aRefreshStillKeepsACustomName() {
        val renamed = bike(null, fromAccount = true).copy(name = "Mein S2", customName = true).withModel(BikeFamily.S2)
        val merged = AccountRepository.merge(listOf(renamed), listOf(bike(null, fromAccount = true))).single()
        assertEquals("Mein S2", merged.name)
        assertTrue(merged.customName)
        assertEquals(BikeFamily.S2, merged.chosenFamily)
    }
}
