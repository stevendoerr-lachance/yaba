package dev.steven.s3control.ui

import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Feature
import dev.steven.s3control.ble.S2Codec
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.featuresFor
import dev.steven.s3control.data.BikeCredentials

/**
 * What each screen offers for the bike in a [BikeState] (the S2 plan A1 and B6). Every answer comes from
 * [BikeState.features], which is `featuresFor(family, S2 test mode)`, never from an assumed S3. An S3 (and a bike
 * of unknown family, which behaves as one) has every feature it had before, so each S3 screen stays exactly as
 * it was. Pure Kotlin, so the gating is unit-tested.
 */
object FamilyGate {

    /** The alarm control on the Fahren page. */
    enum class AlarmControl {
        /** S3: off and on (ALARM_MODE 5524). */
        ON_OFF,

        /** S2 in test mode: off, manual, automatic (command 0x0F, unverified until test step 11). */
        THREE_WAY,

        /** S2 outside test mode: the state is shown, nothing is written. */
        READ_ONLY,
    }

    private fun BikeState.has(f: Feature): Boolean = f in features

    // --- Fahren ----------------------------------------------------------------------------------

    /**
     * The slider offers locking too, and for every family only while the bike stands: one stray swipe on the handlebar
     * must not lock a rolling bike (on an S2 LOCK 0x03 [01] is unverified as well). An S2 is locked with the foot at
     * the kick lock; its remote lock is test mode. UI gating only: unlocking stays offered while rolling, so a kick
     * lock that wrongly reports locked mid-ride can still be opened.
     */
    fun remoteLock(s: BikeState): Boolean = s.has(Feature.REMOTE_LOCK) && !s.moving

    /** An S2 opens its kick lock once the bike rolls while the unlock animation runs (plan A1, M). */
    fun rollToUnlock(s: BikeState): Boolean = s.family == BikeFamily.S2

    /** Bell or boost unlocks the bike (5522): S3 only. */
    fun touchUnlock(s: BikeState): Boolean = s.has(Feature.TOUCH_UNLOCK)

    /** The shifting section (E-Shifter 5536..5538): S3 only; the S2 has an automatic two-speed hub. */
    fun shifting(s: BikeState): Boolean = s.has(Feature.GEAR)

    /**
     * The speed signs: JP, EU and US for every family. 37 km/h only where the S3 firmware still has it, or when the
     * bike reports it already (so the sign in use is always shown). An S2 never gets an offered off-road sign here.
     */
    fun speedLimits(s: BikeState): List<SpeedLimit> = buildList {
        add(SpeedLimit.JP)
        add(SpeedLimit.EU)
        add(SpeedLimit.US)
        if (s.firmwareHasNoLimit || s.speedLimit == SpeedLimit.NO_LIMIT) add(SpeedLimit.NO_LIMIT)
    }

    /** Assist levels 0..4; level 5 only on S3 firmware that still has it (or that reports it). An S2 stops at 4. */
    fun maxPowerLevel(s: BikeState): Int = when {
        s.family == BikeFamily.S2 -> S2Codec.MAX_LEVEL
        s.firmwareHasLevel5 || (s.powerLevel ?: 0) > 4 -> 5
        else -> 4
    }

    fun alarmControl(s: BikeState): AlarmControl = when {
        s.has(Feature.ALARM_THREE_WAY) -> AlarmControl.THREE_WAY
        s.has(Feature.ALARM_ON_OFF) -> AlarmControl.ON_OFF
        else -> AlarmControl.READ_ONLY
    }

    /** "Alarm stoppen" writes ALARM_STATE 5523, a Bike-Lab experiment: S3 only. */
    fun alarmStop(s: BikeState): Boolean = s.has(Feature.S3_FIRMWARE_EXPERIMENTS)

    /**
     * The Wache (the guard design): it writes ALARM_STATE 5523, so S3 only; the S2 has a read-only alarm and sees nothing of
     * it (section, hero, locked links). Before the first connect the stored bike's family decides ([beforeConnect]).
     */
    fun guard(s: BikeState): Boolean = s.has(Feature.S3_FIRMWARE_EXPERIMENTS)

    // --- Klang -----------------------------------------------------------------------------------

    fun bellTones(s: BikeState): Boolean = s.has(Feature.BELL_TONE)

    /** The custom bell and the bundled bells, which go into the same slot. */
    fun customBell(s: BikeState): Boolean = s.has(Feature.CUSTOM_BELL)

    fun soundboard(s: BikeState): Boolean = s.has(Feature.SOUNDBOARD)

    /** The Sound-Lab replaces clips on the S3 (S3Driver.uploadClip). */
    fun soundLab(s: BikeState): Boolean = s.has(Feature.S3_FIRMWARE_EXPERIMENTS)

    /** False: the Klang page has nothing this bike can play, and says why in one sentence. */
    fun anySound(s: BikeState): Boolean = bellTones(s) || customBell(s) || soundboard(s)

    // --- Bike ------------------------------------------------------------------------------------

    /** The smart module's own battery (S2 status block byte 6). */
    fun moduleBattery(s: BikeState): Boolean = s.has(Feature.MODULE_BATTERY)

    /** The backup-code section: set (S3; S2 in test mode) and maybe clear. */
    fun backupCode(s: BikeState): Boolean = s.has(Feature.BACKUP_CODE_SET) || s.has(Feature.BACKUP_CODE_CLEAR)

    /** The S2 can set a code but neither clear nor read it. */
    fun backupCodeClear(s: BikeState): Boolean = s.has(Feature.BACKUP_CODE_CLEAR)

    /** Sharing over the VanMoof account: not for the S2 in phase 1 (plan A1). */
    fun sharing(s: BikeState): Boolean = s.has(Feature.SHARING)

    /** Experimente, Bike-Lab and Speed++: S3 firmware experiments. */
    fun s3Experiments(s: BikeState): Boolean = s.has(Feature.S3_FIRMWARE_EXPERIMENTS)

    // --- Diagnose --------------------------------------------------------------------------------

    /**
     * The state the Diagnose page gates on: before any bike is in the controller (auto-connect off, Bluetooth not
     * ready), the [preferred] bike's family, as the hero panel shows it, so an S2 finds its drive diagnosis (and the
     * test-mode switch in it) before the first connect.
     */
    fun beforeConnect(s: BikeState, preferred: BikeCredentials?, s2TestMode: Boolean): BikeState =
        if (s.bike != null || preferred == null) s else s.copy(family = preferred.family, features = featuresFor(preferred.family, s2TestMode))

    /** The S3 live checks (supply, buttons, coil, shipping) and the cockpit entry; an S2 gets its own block instead. */
    fun s3LiveChecks(s: BikeState): Boolean = s.has(Feature.LIVE_SUPPLY_BUTTONS_COIL)

    /** The bike's own log (55c0) and the Fahrtenschreiber built on it: S3 only. */
    fun bikeLog(s: BikeState): Boolean = s.has(Feature.BIKE_LOG)

    /** The highest code the code lookup explains: the S2's error code has 5 bits, the S3's fault pair 64. */
    fun maxFaultCode(s: BikeState): Int = if (s.family == BikeFamily.S2) S2_MAX_CODE else S3_MAX_CODE

    /** The "wheel turning" status bit (48) lives in the S3 fault pair only. */
    fun wheelMovingCode(s: BikeState): Boolean = s.family != BikeFamily.S2

    // --- Garage ----------------------------------------------------------------------------------

    /**
     * A bike the account does not describe gets the model choice S3/X3 or S2/X2: every hand-added or imported bike,
     * and an account bike whose profile is missing, unknown or only a guess ([BikeFamily.settles]).
     */
    fun modelChoice(bike: BikeCredentials): Boolean = !bike.fromAccount || !BikeFamily.settles(bike.bleProfile)

    /**
     * The choice stands open (title, both options, hint) only while nothing settles the model: no stored choice,
     * no known profile, no live link that showed the family ([liveFamily]: the connected bike's, else null), and no
     * model name that already says S3 or X3 (such a bike is what YABA treats it as anyway). Otherwise the row names
     * the model in one line with a quiet "Change model". A name that says S2 keeps it open: that bike needs the choice.
     */
    fun modelChoiceOpen(bike: BikeCredentials, liveFamily: BikeFamily?): Boolean =
        bike.chosenFamily == null && BikeFamily.fromProfile(bike.bleProfile) == BikeFamily.UNKNOWN && liveFamily == null &&
            bike.modelName?.let { S3_NAME.containsMatchIn(it) } != true

    private val S3_NAME = Regex("\\b[SX]3\\b", RegexOption.IGNORE_CASE)

    /** SmartBike and S/X 1: YABA does not speak their protocol, so there is nothing to connect to. */
    fun canConnect(bike: BikeCredentials): Boolean = bike.family != BikeFamily.LEGACY_UNSUPPORTED

    /** The model to show as chosen: S2 for an S2, S3 for everything YABA treats as one, none for an older model. */
    fun shownModel(bike: BikeCredentials): BikeFamily? = when (bike.family) {
        BikeFamily.S2 -> BikeFamily.S2
        BikeFamily.LEGACY_UNSUPPORTED -> null
        BikeFamily.S3, BikeFamily.UNKNOWN -> BikeFamily.S3
    }

    private const val S2_MAX_CODE = 31
    private const val S3_MAX_CODE = 63
}
