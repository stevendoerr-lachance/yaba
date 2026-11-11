package dev.steven.s3control.ble

import androidx.annotation.StringRes
import dev.steven.s3control.R

/**
 * Which VanMoof generation a bike belongs to, from the account's `bleProfile` (the S2 plan B2/B3).
 * Pure Kotlin, JVM-testable. The profile is only a hint: once connected, the GATT services decide.
 */
enum class BikeFamily {
    /** S3/X3 (and the later 2021/2022 profiles): service 6acc5500, the driver YABA always had. */
    S3,

    /** Electrified S2/X2: service 8e7f1a50, see [S2Gatt]. Experimental until tested on a real bike. */
    S2,

    /** SmartBike and Electrified S/X (S1): same service UUID as the S2, different protocol. Not supported. */
    LEGACY_UNSUPPORTED,

    /** No or an unrecognised profile (manual entry, mooovy import): behaves exactly like YABA did before (S3). */
    UNKNOWN;

    /**
     * The S3 firmware version gates (`BikeState.firmwareHasNoLimit` / `firmwareHasLevel5`) apply. They compare
     * against S3 version numbers, so an S2 with firmware below 1.7.6 would wrongly get level 5 (plan B1 point 5).
     * UNKNOWN keeps today's behaviour.
     */
    val usesS3FirmwareGates: Boolean get() = this == S3 || this == UNKNOWN

    /**
     * Why a bike that answers on 8e7f1a50 (S1, S2 and SmartBike share it) is refused while its profile does not
     * say S2 (plan B3.3); null for S2 and S3. The driver choice and the S3 driver's "not an S3" branch both use
     * it, so the bike gets the same message whether that service showed up before or after the cache refresh.
     */
    @get:StringRes
    val sharedServiceRefusal: Int?
        get() = when (this) {
            UNKNOWN -> R.string.core_error_older_vanmoof
            LEGACY_UNSUPPORTED -> R.string.core_error_legacy_unsupported
            S2, S3 -> null
        }

    companion object {
        /** Profiles whose family rests on one weak source (L): the bike may as well be an S3. */
        private val TENTATIVE = setOf("ELECTRIFIED_2019")

        /**
         * True when [fromProfile] is only a guess for [profile]. YABA then scans for the S3 names too, so such a
         * bike is found whichever it is; once connected, the GATT services decide (plan B3).
         */
        fun isTentative(profile: String?): Boolean = profile?.trim()?.uppercase() in TENTATIVE

        /**
         * The account profile names the model for sure (S3, S2 or an older, unsupported one). A missing, unknown or
         * [isTentative] profile does not: the garage then offers the S3/X3 | S2/X2 choice, also for an account bike,
         * and an account refresh keeps that choice.
         */
        fun settles(profile: String?): Boolean = fromProfile(profile) != UNKNOWN && !isTentative(profile)

        fun fromProfile(profile: String?): BikeFamily = when (profile?.trim()?.uppercase()) {
            "ELECTRIFIED_2018" -> S2                                             // H: OldMoofKit BikeDetails.swift:16
            "ELECTRIFIED_2019" -> S2                                             // L: only Homey lib/vanmoofbike.ts:145-149
            "ELECTRIFIED_2020", "ELECTRIFIED_2021", "ELECTRIFIED_2022" -> S3
            "SMARTBIKE_2016", "SMARTBIKE_2018",
            "ELECTRIFIED_2016", "ELECTRIFIED_2016_2017", "ELECTRIFIED_2017" -> LEGACY_UNSUPPORTED
            else -> UNKNOWN
        }
    }
}

/**
 * The bike answered, but YABA does not speak to it: an older VanMoof (S1, SmartBike) or one whose model is not set
 * ([BikeFamily.sharedServiceRefusal]). The same bike answers the same way every time, so the controller stops
 * retrying and keeps this message on screen instead of the "not found" hint.
 */
class BikeRefusedException(message: String) : Exception(message)

/** What the UI may offer for a bike ([BikeState.features]); the controller refuses a command whose feature is missing ([CommandGate]). */
enum class Feature {
    UNLOCK, REMOTE_LOCK, TOUCH_UNLOCK, LIGHT, ALARM_ON_OFF, ALARM_THREE_WAY, REGION,
    REGION_OFFROAD, POWER_LEVEL, UNIT, WAKE, BACKUP_CODE_SET, BACKUP_CODE_CLEAR, BELL_TONE, SOUNDBOARD,
    CUSTOM_BELL, MUTE_SOUNDS, BIKE_LOG, LIVE_SUPPLY_BUTTONS_COIL, SHIPPING_MARKER, GEAR, CHARGE_STATE,
    MODULE_BATTERY, SHARING, S3_FIRMWARE_EXPERIMENTS, GATT_DUMP,
}

private val S2_ONLY = setOf(
    Feature.ALARM_THREE_WAY, Feature.MUTE_SOUNDS, Feature.MODULE_BATTERY, Feature.REGION_OFFROAD, Feature.GATT_DUMP,
)

/** Everything the S3 screens show today (S2-only features left out). */
private val S3_FEATURES: Set<Feature> = Feature.entries.toSet() - S2_ONLY

private val S2_BASE = setOf(
    Feature.UNLOCK, Feature.LIGHT, Feature.REGION, Feature.POWER_LEVEL, Feature.UNIT, Feature.WAKE, Feature.MODULE_BATTERY,
)

/**
 * Unverified on a real S2: only offered in S2 test mode (plan A1 "Test"). The alarm too: ALARM 0x0F has a single
 * source (OldMoofKit; vanbike-lib's command list ends at 0x0D) and writing it is M (A6.7). Test step 11 runs in
 * test mode; once it confirms 0x0F, ALARM_THREE_WAY moves to [S2_BASE] (with the check in S2Driver.setAlarm).
 */
private val S2_TEST = setOf(
    Feature.REMOTE_LOCK, Feature.BACKUP_CODE_SET, Feature.MUTE_SOUNDS, Feature.REGION_OFFROAD, Feature.GATT_DUMP,
    Feature.ALARM_THREE_WAY,
)

fun featuresFor(family: BikeFamily, s2TestMode: Boolean): Set<Feature> = when (family) {
    BikeFamily.S3, BikeFamily.UNKNOWN -> S3_FEATURES
    BikeFamily.S2 -> if (s2TestMode) S2_BASE + S2_TEST else S2_BASE
    BikeFamily.LEGACY_UNSUPPORTED -> emptySet()
}

/** What the controller's feature gate says about one command (plan B2, `command(op, what, needs)`). */
enum class CommandGate {
    OK,
    /** The family has it, but only in S2 test mode (unverified on a real S2). */
    TEST_MODE_ONLY,
    /** The family does not have it at all. */
    UNSUPPORTED;

    companion object {
        /** [needs] null: every family has the command. An S3 (or UNKNOWN) always gets OK for its own commands. */
        fun of(family: BikeFamily, s2TestMode: Boolean, needs: Feature?): CommandGate = when {
            needs == null || needs in featuresFor(family, s2TestMode) -> OK
            needs in featuresFor(family, s2TestMode = true) -> TEST_MODE_ONLY
            else -> UNSUPPORTED
        }
    }
}
