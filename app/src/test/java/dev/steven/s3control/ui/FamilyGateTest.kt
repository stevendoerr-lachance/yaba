package dev.steven.s3control.ui

import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Feature
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.featuresFor
import dev.steven.s3control.data.BikeCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screens' family gating (the S2 plan A1, B6): S3 exactly as before, S2 only what it has. */
class FamilyGateTest {

    private fun state(family: BikeFamily, testMode: Boolean = false, firmware: String? = "1.9.3", regionLock: Int? = null) =
        BikeState(family = family, features = featuresFor(family, testMode), link = Link.READY, firmware = firmware, regionLock = regionLock)

    private fun bike(profile: String? = null, fromAccount: Boolean = false) = BikeCredentials(
        id = 1, name = "B", ownerName = null, mac = "74:D2:85:AB:CD:EF",
        encryptionKey = "00112233445566778899aabbccddeeff", userKeyId = 1, bleProfile = profile, fromAccount = fromAccount,
    )

    // --- S3 and UNKNOWN: every screen as it was --------------------------------------------------------

    @Test
    fun s3AndUnknownKeepEverySection() {
        for (family in listOf(BikeFamily.S3, BikeFamily.UNKNOWN)) {
            for (test in listOf(false, true)) {
                val s = state(family, test)
                val msg = "$family test=$test"
                assertTrue(msg, FamilyGate.remoteLock(s))
                assertFalse(msg, FamilyGate.rollToUnlock(s))
                assertTrue(msg, FamilyGate.touchUnlock(s))
                assertTrue(msg, FamilyGate.shifting(s))
                assertEquals(msg, FamilyGate.AlarmControl.ON_OFF, FamilyGate.alarmControl(s))
                assertTrue(msg, FamilyGate.alarmStop(s))
                assertTrue(msg, FamilyGate.bellTones(s))
                assertTrue(msg, FamilyGate.customBell(s))
                assertTrue(msg, FamilyGate.soundboard(s))
                assertTrue(msg, FamilyGate.soundLab(s))
                assertTrue(msg, FamilyGate.anySound(s))
                assertFalse(msg, FamilyGate.moduleBattery(s))
                assertTrue(msg, FamilyGate.backupCode(s))
                assertTrue(msg, FamilyGate.backupCodeClear(s))
                assertTrue(msg, FamilyGate.sharing(s))
                assertTrue(msg, FamilyGate.s3Experiments(s))
                assertTrue(msg, FamilyGate.s3LiveChecks(s))
                assertTrue(msg, FamilyGate.bikeLog(s))
                assertTrue(msg, FamilyGate.wheelMovingCode(s))
                assertEquals(msg, 63, FamilyGate.maxFaultCode(s))
            }
        }
    }

    /** The speed signs and the ladder top are the very expressions RideControls used inline before. */
    @Test
    fun s3SpeedSignsAndLevelsAreTheOldFormula() {
        val firmwares = listOf(null, "1.7.4", "1.7.6", "1.8.2", "1.9.3")
        val locks = listOf(null, 0, 1, 2)
        val limits = listOf(null) + SpeedLimit.entries
        val levels = listOf(null, 0, 3, 4, 5)
        for (family in listOf(BikeFamily.S3, BikeFamily.UNKNOWN)) for (fw in firmwares) for (lock in locks) for (limit in limits) for (level in levels) {
            val s = state(family, firmware = fw, regionLock = lock).copy(speedLimit = limit, powerLevel = level)
            val oldLimits = buildList {
                add(SpeedLimit.JP); add(SpeedLimit.EU); add(SpeedLimit.US)
                if (s.firmwareHasNoLimit || s.speedLimit == SpeedLimit.NO_LIMIT) add(SpeedLimit.NO_LIMIT)
            }
            val oldMax = if (s.firmwareHasLevel5 || (s.powerLevel ?: 0) > 4) 5 else 4
            val msg = "$family fw=$fw lock=$lock limit=$limit level=$level"
            assertEquals(msg, oldLimits, FamilyGate.speedLimits(s))
            assertEquals(msg, oldMax, FamilyGate.maxPowerLevel(s))
        }
    }

    // --- S2 --------------------------------------------------------------------------------------------

    @Test
    fun s2OffersOnlyWhatItHas() {
        val s = state(BikeFamily.S2)
        assertFalse("locked with the foot", FamilyGate.remoteLock(s))
        assertTrue(FamilyGate.rollToUnlock(s))
        assertFalse(FamilyGate.touchUnlock(s))
        assertFalse("automatic two-speed hub", FamilyGate.shifting(s))
        assertEquals(FamilyGate.AlarmControl.READ_ONLY, FamilyGate.alarmControl(s))
        assertFalse(FamilyGate.alarmStop(s))
        assertFalse(FamilyGate.bellTones(s))
        assertFalse(FamilyGate.customBell(s))
        assertFalse(FamilyGate.soundboard(s))
        assertFalse(FamilyGate.soundLab(s))
        assertFalse("the Klang page says why instead", FamilyGate.anySound(s))
        assertTrue(FamilyGate.moduleBattery(s))
        assertFalse(FamilyGate.backupCode(s))
        assertFalse(FamilyGate.sharing(s))
        assertFalse("no Experimente, Bike-Lab, Speed++", FamilyGate.s3Experiments(s))
        assertFalse("its own diagnosis block instead", FamilyGate.s3LiveChecks(s))
        assertFalse(FamilyGate.bikeLog(s))
        assertFalse(FamilyGate.wheelMovingCode(s))
        assertEquals(31, FamilyGate.maxFaultCode(s))
    }

    @Test
    fun s2TestModeAddsOnlyTheUnverifiedCommands() {
        val s = state(BikeFamily.S2, testMode = true)
        assertTrue(FamilyGate.remoteLock(s))
        assertEquals(FamilyGate.AlarmControl.THREE_WAY, FamilyGate.alarmControl(s))
        assertTrue(FamilyGate.backupCode(s))
        assertFalse("the S2 cannot clear a code", FamilyGate.backupCodeClear(s))
        // Still none of the S3 things.
        assertFalse(FamilyGate.shifting(s))
        assertFalse(FamilyGate.anySound(s))
        assertFalse(FamilyGate.s3Experiments(s))
        assertFalse(FamilyGate.touchUnlock(s))
        assertFalse(FamilyGate.s3LiveChecks(s))
        assertFalse(FamilyGate.sharing(s))
        assertFalse(FamilyGate.bikeLog(s))
    }

    @Test
    fun theSliderNeverOffersLockingWhileTheBikeRolls() {
        val parked = state(BikeFamily.S2, testMode = true).copy(speedKmh = 0)
        assertTrue(FamilyGate.remoteLock(parked))
        assertFalse("18 km/h", FamilyGate.remoteLock(parked.copy(speedKmh = 18)))
        assertFalse("walking pace", FamilyGate.remoteLock(parked.copy(speedKmh = 3)))
        // Every family: rolling (speed or the wheel bit 48) takes the lock off the slider, standing gives it back.
        for (family in listOf(BikeFamily.S3, BikeFamily.UNKNOWN)) {
            assertTrue("$family parked", FamilyGate.remoteLock(state(family).copy(speedKmh = 0)))
            assertFalse("$family 18 km/h", FamilyGate.remoteLock(state(family).copy(speedKmh = 18)))
            assertFalse("$family wheel moving", FamilyGate.remoteLock(state(family).copy(faultCodes = listOf(48))))
        }
    }

    @Test
    fun beforeTheFirstConnectThePreferredBikeDecides() {
        val empty = BikeState()
        val s2 = FamilyGate.beforeConnect(empty, bike("ELECTRIFIED_2018"), s2TestMode = true)
        assertEquals(BikeFamily.S2, s2.family)
        assertFalse(FamilyGate.s3LiveChecks(s2))
        assertEquals(featuresFor(BikeFamily.S2, true), s2.features)
        // An S3 (or no preferred bike) keeps the S3 page, and a bike in the controller always wins.
        assertTrue(FamilyGate.s3LiveChecks(FamilyGate.beforeConnect(empty, bike("ELECTRIFIED_2022"), false)))
        assertTrue(FamilyGate.s3LiveChecks(FamilyGate.beforeConnect(empty, null, false)))
        val live = state(BikeFamily.S3).copy(bike = bike())
        assertEquals(live, FamilyGate.beforeConnect(live, bike("ELECTRIFIED_2018"), false))
    }

    @Test
    fun s2SpeedSignsAreJpEuUsAndAssistStopsAtFour() {
        // Old firmware numbers mean nothing on an S2: no 37 km/h sign and no level 5 from them.
        for (fw in listOf(null, "1.0.2", "1.7.4", "1.9.3")) for (test in listOf(false, true)) {
            val s = state(BikeFamily.S2, test, firmware = fw, regionLock = 0)
            assertEquals(listOf(SpeedLimit.JP, SpeedLimit.EU, SpeedLimit.US), FamilyGate.speedLimits(s))
            assertEquals(4, FamilyGate.maxPowerLevel(s))
            assertEquals(4, FamilyGate.maxPowerLevel(s.copy(powerLevel = 5)))
        }
        // A bike already in off-road shows that sign, so the selection is visible.
        val offroad = state(BikeFamily.S2).copy(speedLimit = SpeedLimit.NO_LIMIT)
        assertEquals(SpeedLimit.entries.sortedBy { it.kmh }, FamilyGate.speedLimits(offroad).sortedBy { it.kmh })
    }

    /** The Wache writes 5523: an S3 (and a bike of unknown family) has it, an S2 never, in test mode or not. */
    @Test
    fun theGuardIsS3Only() {
        for (test in listOf(false, true)) {
            assertTrue("S3 test=$test", FamilyGate.guard(state(BikeFamily.S3, test)))
            assertTrue("UNKNOWN test=$test", FamilyGate.guard(state(BikeFamily.UNKNOWN, test)))
            assertFalse("S2 test=$test", FamilyGate.guard(state(BikeFamily.S2, test)))
        }
        assertFalse(FamilyGate.guard(state(BikeFamily.LEGACY_UNSUPPORTED)))
        // Before the first connect the stored S2 hides it too.
        assertFalse(FamilyGate.guard(FamilyGate.beforeConnect(BikeState(), bike("ELECTRIFIED_2018"), s2TestMode = true)))
        assertTrue(FamilyGate.guard(FamilyGate.beforeConnect(BikeState(), bike("ELECTRIFIED_2022"), s2TestMode = false)))
    }

    @Test
    fun legacyBikeGetsNothing() {
        val s = state(BikeFamily.LEGACY_UNSUPPORTED)
        assertFalse(FamilyGate.anySound(s))
        assertFalse(FamilyGate.shifting(s))
        assertFalse(FamilyGate.backupCode(s))
        assertFalse(FamilyGate.s3LiveChecks(s))
        assertEquals(FamilyGate.AlarmControl.READ_ONLY, FamilyGate.alarmControl(s))
    }

    @Test
    fun everyGateReadsTheFeatureSetNotTheFamily() {
        // An S2 state whose feature set somehow held a feature shows it: the screens follow BikeState.features.
        val s = state(BikeFamily.S2).copy(features = featuresFor(BikeFamily.S2, false) + Feature.SOUNDBOARD)
        assertTrue(FamilyGate.soundboard(s))
        assertTrue(FamilyGate.anySound(s))
        assertFalse(FamilyGate.bellTones(s))
    }

    // --- Garage ----------------------------------------------------------------------------------------

    @Test
    fun garageModelChoiceOnlyForBikesTheAccountDoesNotDescribe() {
        assertTrue(FamilyGate.modelChoice(bike()))
        assertTrue(FamilyGate.modelChoice(bike("ELECTRIFIED_2018")))
        assertFalse(FamilyGate.modelChoice(bike("ELECTRIFIED_2022", fromAccount = true)))
        assertFalse(FamilyGate.modelChoice(bike("ELECTRIFIED_2018", fromAccount = true)))
        assertFalse("an older model stays refused", FamilyGate.modelChoice(bike("SMARTBIKE_2016", fromAccount = true)))
        // An account bike the account does not settle: no, an unknown or only a likely profile.
        assertTrue(FamilyGate.modelChoice(bike(null, fromAccount = true)))
        assertTrue(FamilyGate.modelChoice(bike("ELECTRIFIED_2030", fromAccount = true)))
        assertTrue(FamilyGate.modelChoice(bike("ELECTRIFIED_2019", fromAccount = true)))
    }

    @Test
    fun theChoiceStandsOpenOnlyWhileNothingSettlesTheModel() {
        assertTrue(FamilyGate.modelChoiceOpen(bike(), liveFamily = null))
        assertTrue(FamilyGate.modelChoiceOpen(bike(null, fromAccount = true), liveFamily = null))
        // A known profile, a stored choice or a live link fold it into "Change model".
        assertFalse(FamilyGate.modelChoiceOpen(bike("ELECTRIFIED_2022"), liveFamily = null))
        assertFalse(FamilyGate.modelChoiceOpen(bike("ELECTRIFIED_2019"), liveFamily = null))
        assertFalse(FamilyGate.modelChoiceOpen(bike().withModel(BikeFamily.S2), liveFamily = null))
        assertFalse(FamilyGate.modelChoiceOpen(bike(), liveFamily = BikeFamily.S3))
        // A model name that says S3 folds it too; one that says S2 needs the choice.
        assertFalse(FamilyGate.modelChoiceOpen(bike().copy(modelName = "S3 2022"), liveFamily = null))
        assertFalse(FamilyGate.modelChoiceOpen(bike().copy(modelName = "VanMoof X3"), liveFamily = null))
        assertTrue(FamilyGate.modelChoiceOpen(bike().copy(modelName = "Electrified S2"), liveFamily = null))
    }

    @Test
    fun garageShownModelAndConnect() {
        assertEquals(BikeFamily.S3, FamilyGate.shownModel(bike()))
        assertEquals(BikeFamily.S3, FamilyGate.shownModel(bike("ELECTRIFIED_2022")))
        assertEquals(BikeFamily.S2, FamilyGate.shownModel(bike("ELECTRIFIED_2018")))
        assertNull(FamilyGate.shownModel(bike("SMARTBIKE_2016")))
        assertTrue(FamilyGate.canConnect(bike()))
        assertTrue(FamilyGate.canConnect(bike("ELECTRIFIED_2018")))
        assertFalse(FamilyGate.canConnect(bike("ELECTRIFIED_2017")))
    }
}
