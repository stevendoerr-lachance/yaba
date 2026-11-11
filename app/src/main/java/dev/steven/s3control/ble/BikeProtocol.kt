package dev.steven.s3control.ble

import androidx.annotation.StringRes
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import java.util.UUID

/**
 * VanMoof S3/X3 GATT map ("ELECTRIFIED_2021" BLE profile).
 * Each characteristic's short id is also the firmware's internal command id (0x5521 → lock state).
 * Sources: mooovy lib/bike.ts, VanMoofKit, pymoof, and the Knight1 S3 firmware decompilation
 * (mainware ble.c / ble_read.c, docs/error-flags.md, docs/state-machine.md).
 */
object Gatt {
    private fun u(short: String) = UUID.fromString("6acc$short-e631-4069-944d-b8ca7598ad50")

    class Char(val service: UUID, val id: UUID, @StringRes val labelRes: Int, val encrypted: Boolean = true) {
        val short: String get() = id.toString().substring(4, 8)

        /** Localised name, resolved on every access so it follows the app language. */
        val label: String get() = I18n.get(labelRes)
    }

    val SECURITY = u("5500")
    val CHALLENGE = Char(SECURITY, u("5501"), R.string.core_char_challenge, encrypted = false)
    val KEY_INDEX = Char(SECURITY, u("5502"), R.string.core_char_key_index, encrypted = false)
    val BACKUP_CODE = Char(SECURITY, u("5503"), R.string.core_char_backup_code)
    val BIKE_MESSAGE = Char(SECURITY, u("5505"), R.string.core_char_bike_message)

    val FIRMWARE = u("5510")
    val FIRMWARE_METADATA = Char(FIRMWARE, u("5511"), R.string.core_char_firmware_metadata)
    val FIRMWARE_BLOCK = Char(FIRMWARE, u("5512"), R.string.core_char_firmware_block, encrypted = false)
    val OAD_STATUS = Char(FIRMWARE, u("5513"), R.string.core_char_oad_status)

    val DEFENSE = u("5520")
    val LOCK_STATE = Char(DEFENSE, u("5521"), R.string.core_char_lock_state)
    val UNLOCK_REQUEST = Char(DEFENSE, u("5522"), R.string.core_char_unlock_request)
    val ALARM_STATE = Char(DEFENSE, u("5523"), R.string.core_char_alarm_state)
    val ALARM_MODE = Char(DEFENSE, u("5524"), R.string.core_char_alarm_mode)

    val MOVEMENT = u("5530")
    val DISTANCE = Char(MOVEMENT, u("5531"), R.string.core_char_distance)
    val SPEED = Char(MOVEMENT, u("5532"), R.string.core_char_speed)
    val UNIT_SYSTEM = Char(MOVEMENT, u("5533"), R.string.core_char_unit_system)
    val POWER_LEVEL = Char(MOVEMENT, u("5534"), R.string.core_char_power_level)
    val SPEED_LIMIT = Char(MOVEMENT, u("5535"), R.string.core_char_speed_limit)
    val E_SHIFTER_GEAR = Char(MOVEMENT, u("5536"), R.string.core_char_e_shifter_gear)
    val E_SHIFT_POINTS = Char(MOVEMENT, u("5537"), R.string.core_char_e_shift_points)
    val E_SHIFTER_MODE = Char(MOVEMENT, u("5538"), R.string.core_char_e_shifter_mode)

    val INFO = u("5540")
    val MOTOR_BATTERY_LEVEL = Char(INFO, u("5541"), R.string.core_char_motor_battery_level)
    val MOTOR_BATTERY_STATE = Char(INFO, u("5542"), R.string.core_char_motor_battery_state)
    val MODULE_BATTERY_LEVEL = Char(INFO, u("5543"), R.string.core_char_module_battery_level)
    val MODULE_BATTERY_STATE = Char(INFO, u("5544"), R.string.core_char_module_battery_state)
    val ENVIRONMENT = Char(INFO, u("5545"), R.string.core_char_environment)
    val MOTOR_TELEMETRY_A = Char(INFO, u("5546"), R.string.core_char_motor_telemetry_a)
    val MOTOR_TELEMETRY_B = Char(INFO, u("5547"), R.string.core_char_motor_telemetry_b)
    val MOTOR_ERRORS = Char(INFO, u("5548"), R.string.core_char_motor_errors)
    val MODEL = Char(INFO, u("5549"), R.string.core_char_model)
    val BIKE_FIRMWARE = Char(INFO, u("554a"), R.string.core_char_bike_firmware)
    val BLE_FIRMWARE = Char(INFO, u("554b"), R.string.core_char_ble_firmware)
    val CONTROLLER_FIRMWARE = Char(INFO, u("554c"), R.string.core_char_controller_firmware)
    val PCBA_HARDWARE = Char(INFO, u("554d"), R.string.core_char_pcba_hardware)
    val GSM_FIRMWARE = Char(INFO, u("554e"), R.string.core_char_gsm_firmware)
    val E_SHIFTER_FIRMWARE = Char(INFO, u("554f"), R.string.core_char_e_shifter_firmware)
    val BATTERY_FIRMWARE = Char(INFO, u("5550"), R.string.core_char_battery_firmware)
    val FRAME_NUMBER = Char(INFO, u("5552"), R.string.core_char_frame_number, encrypted = false)

    val STATE = u("5560")
    val MODULE_MODE = Char(STATE, u("5561"), R.string.core_char_module_mode)
    val MODULE_STATE = Char(STATE, u("5562"), R.string.core_char_module_state)
    val ERRORS = Char(STATE, u("5563"), R.string.core_char_errors)
    val WHEEL_SIZE = Char(STATE, u("5564"), R.string.core_char_wheel_size)
    val CLOCK = Char(STATE, u("5567"), R.string.core_char_clock)
    val BUTTONS = Char(STATE, u("5568"), R.string.core_char_buttons)
    val SUPPLY_OK = Char(STATE, u("5569"), R.string.core_char_supply_ok)

    val SOUND = u("5570")
    val PLAY_SOUND = Char(SOUND, u("5571"), R.string.core_char_play_sound)
    val SOUND_GROUPS = Char(SOUND, u("5572"), R.string.core_char_sound_groups)
    val BELL_SOUND = Char(SOUND, u("5574"), R.string.core_char_bell_sound)

    val LIGHT = u("5580")
    val LIGHT_MODE = Char(LIGHT, u("5581"), R.string.core_char_light_mode)
    val LED_STATE = Char(LIGHT, u("5582"), R.string.core_char_led_state)
    val LIGHT_SENSOR = Char(LIGHT, u("5584"), R.string.core_char_light_sensor)

    val MAINTENANCE = u("55c0")
    val LOG_MODE = Char(MAINTENANCE, u("55c1"), R.string.core_char_log_mode)
    val LOG_SIZE = Char(MAINTENANCE, u("55c2"), R.string.core_char_log_size)
    val LOG_BLOCK = Char(MAINTENANCE, u("55c3"), R.string.core_char_log_block)

    /** Read once per connection for the bike page. */
    val versions = listOf(
        BIKE_FIRMWARE, BLE_FIRMWARE, CONTROLLER_FIRMWARE, PCBA_HARDWARE,
        GSM_FIRMWARE, E_SHIFTER_FIRMWARE, BATTERY_FIRMWARE, MODEL, FRAME_NUMBER,
    )

    /** Pushed by the bike when they change; we subscribe to each one that offers notify. */
    val live = listOf(
        LOCK_STATE, UNLOCK_REQUEST, ALARM_STATE, ALARM_MODE, SPEED, DISTANCE, POWER_LEVEL, SPEED_LIMIT,
        MOTOR_BATTERY_LEVEL, MOTOR_BATTERY_STATE, MODULE_MODE, MODULE_STATE, ERRORS, BUTTONS, SUPPLY_OK,
        LIGHT_MODE, BELL_SOUND, UNIT_SYSTEM, E_SHIFTER_GEAR, E_SHIFTER_MODE, MOTOR_ERRORS, OAD_STATUS,
    )

    /** Everything worth showing raw on the diagnosis page. ENVIRONMENT (5545) is left out: a READ of it is
     *  a firmware no-op, so it only times out here; its value is notify-only (see [BikeLab] board temp). */
    val diagnostics = listOf(
        MODULE_MODE, MODULE_STATE, ERRORS, MOTOR_ERRORS, BUTTONS, SUPPLY_OK, MOTOR_BATTERY_LEVEL,
        MOTOR_BATTERY_STATE, MODULE_BATTERY_LEVEL, MODULE_BATTERY_STATE, MOTOR_TELEMETRY_A,
        MOTOR_TELEMETRY_B, LOCK_STATE, ALARM_STATE, ALARM_MODE, SPEED_LIMIT, POWER_LEVEL, E_SHIFTER_GEAR,
        E_SHIFT_POINTS, E_SHIFTER_MODE, WHEEL_SIZE, CLOCK, LIGHT_MODE, LED_STATE, LIGHT_SENSOR, BACKUP_CODE, LOG_MODE,
    )

    private val byId = listOf(
        CHALLENGE, KEY_INDEX, BACKUP_CODE, BIKE_MESSAGE, FIRMWARE_METADATA, FIRMWARE_BLOCK, OAD_STATUS,
        LOCK_STATE, UNLOCK_REQUEST, ALARM_STATE, ALARM_MODE, DISTANCE, SPEED, UNIT_SYSTEM, POWER_LEVEL,
        SPEED_LIMIT, E_SHIFTER_GEAR, E_SHIFT_POINTS, E_SHIFTER_MODE, MOTOR_BATTERY_LEVEL, MOTOR_BATTERY_STATE,
        MODULE_BATTERY_LEVEL, MODULE_BATTERY_STATE, ENVIRONMENT, MOTOR_TELEMETRY_A, MOTOR_TELEMETRY_B,
        MOTOR_ERRORS, MODEL, BIKE_FIRMWARE, BLE_FIRMWARE, CONTROLLER_FIRMWARE, PCBA_HARDWARE, GSM_FIRMWARE,
        E_SHIFTER_FIRMWARE, BATTERY_FIRMWARE, FRAME_NUMBER, MODULE_MODE, MODULE_STATE, ERRORS, WHEEL_SIZE,
        CLOCK, BUTTONS, SUPPLY_OK, PLAY_SOUND, SOUND_GROUPS, BELL_SOUND, LIGHT_MODE, LED_STATE, LIGHT_SENSOR,
        LOG_MODE, LOG_SIZE, LOG_BLOCK,
    ).associateBy { it.id }

    fun find(id: UUID): Char? = byId[id]
}

enum class SpeedLimit(val code: Int, val kmh: Int, @StringRes val regionRes: Int) {
    JP(2, 24, R.string.core_region_japan),
    EU(0, 25, R.string.core_region_europe),
    US(1, 32, R.string.core_region_usa),
    NO_LIMIT(3, 37, R.string.core_region_private);

    val region: String get() = I18n.get(regionRes)

    companion object {
        fun from(code: Int?): SpeedLimit? = when (code) {
            null -> null
            255 -> NO_LIMIT
            else -> entries.firstOrNull { it.code == code }
        }
    }
}

/** LOCK_STATE read. Writes: 1 = lock, 2 = unlock (firmware ble.c case 0x5521). */
enum class LockState(val code: Int) {
    UNLOCKED(0), LOCKED(1), PIN_ENTRY(2);

    companion object { fun from(code: Int?) = entries.firstOrNull { it.code == code } }
}

/**
 * ALARM_STATE (5523): the firmware reports the lock/alarm phase here on read (ble_unlock_state_get).
 * The core controller never writes it; the Bike-Lab experiments do (siren 2/3/4, alarm stop 0), each a
 * parked-only write, because a write drives the lock/alarm state machine and value 0 also clears the
 * soft-lock. See [BikeLab.triggerSiren] / [BikeLab.stopAlarm].
 */
enum class DefenceState(val code: Int, @StringRes val labelRes: Int) {
    UNLOCKED(0, R.string.core_defence_unlocked),
    LOCKED(1, R.string.core_defence_locked),
    ALARM_ONE(2, R.string.core_defence_alarm_one),
    ALARM_TWO(3, R.string.core_defence_alarm_two),
    TRACKING(4, R.string.core_defence_tracking);

    val label: String get() = I18n.get(labelRes)
    val alarming: Boolean get() = code >= 2

    companion object { fun from(code: Int?) = entries.firstOrNull { it.code == code } }
}

/**
 * ALARM_MODE (5524): alarm switched off (0) or on (1); the firmware accepts nothing else. [AUTOMATIC] exists only
 * on the S2 (its alarm byte 2, [S2Alarm]); [ARMED] is the S2's "manual". [from] decodes the S3 wire values only.
 */
enum class AlarmStage(val code: Int, @StringRes val labelRes: Int) {
    DISARMED(0, R.string.core_alarm_stage_disarmed), ARMED(1, R.string.core_alarm_stage_armed),
    AUTOMATIC(2, R.string.core_alarm_stage_automatic);

    val label: String get() = I18n.get(labelRes)
    val armed: Boolean get() = this != DISARMED

    companion object {
        /** An S3 ALARM_MODE value: 0 or 1. Anything else (2 included) is not an S3 value: null, as always. */
        fun from(code: Int?): AlarmStage? = when (code) {
            DISARMED.code -> DISARMED
            ARMED.code -> ARMED
            else -> null
        }
    }
}

/**
 * MOTOR_BATTERY_STATE (5542) byte 0, from mainware ble_get_charge_plug_state: 4 when the pack-present
 * line (PC10, BAT_KEY_IN; the decomp's "charger detect" comment is wrong, see mainware/docs/hardware.md)
 * reads low, 1 or 2 while the bike is in its charging state, 0 otherwise. There is no charger-plug bit:
 * a plugged charger only shows once the bike charges.
 */
enum class ChargeState(val code: Int, @StringRes val labelRes: Int) {
    NOT_CHARGING(0, R.string.core_charge_not_charging),
    CHARGING(1, R.string.core_charge_charging),
    CHARGING_ALT(2, R.string.core_charge_charging),
    BATTERY_NOT_DETECTED(4, R.string.core_charge_battery_missing);

    val label: String get() = I18n.get(labelRes)
    val charging: Boolean get() = this == CHARGING || this == CHARGING_ALT
    val batteryDetected: Boolean get() = this != BATTERY_NOT_DETECTED

    companion object { fun from(code: Int?) = entries.firstOrNull { it.code == code } }
}

enum class LightMode(val code: Int, @StringRes val labelRes: Int) {
    AUTO(0, R.string.core_light_auto), ON(1, R.string.core_light_on), OFF(2, R.string.core_light_off);

    val label: String get() = I18n.get(labelRes)

    companion object { fun from(code: Int?) = entries.firstOrNull { it.code == code } }
}

/** MODULE_MODE (5561), the firmware's coarse status (bike_status_coarse_get). */
enum class BikeStatus(val code: Int, @StringRes val labelRes: Int) {
    READY(0, R.string.core_status_ready),
    RIDING(1, R.string.core_status_riding),
    ALARM(2, R.string.core_status_alarm),
    UPDATING(3, R.string.core_status_updating),
    SHIPPING(4, R.string.core_status_shipping),
    SLEEPING(6, R.string.core_status_sleeping),
    OTHER(7, R.string.core_status_other),

    /** S2 only: none of the module bits set (E18:74-84). Never on the S3 wire, so [from] never returns it. */
    STANDBY(-1, R.string.core_status_standby);

    val label: String get() = I18n.get(labelRes)

    companion object { fun from(code: Int?) = entries.firstOrNull { it.code == code } }
}

/**
 * MODULE_STATE (5562). Notified on every state change: 0 = riding or anything else, 1 = standby,
 * 2 = SHIPPING (the earliest hard marker, ~10 s before Bluetooth goes off). Reads only ever give 0/1.
 * Writes: 0 wake, 1 standby, 2 shipping — never while riding.
 */
object ModuleCommand {
    const val WAKE = 0
    const val SLEEP = 1
    const val SHIPPING = 2
}

enum class UnitSystem(val code: Int, @StringRes val labelRes: Int) {
    METRIC(0, R.string.core_unit_metric), IMPERIAL(1, R.string.core_unit_imperial);

    val label: String get() = I18n.get(labelRes)

    companion object { fun from(code: Int?) = entries.firstOrNull { it.code == code } }
}

enum class BellTone(val code: Int, @StringRes val labelRes: Int) {
    BELL(0x16, R.string.core_bell_bell),
    SONAR(0x0a, R.string.core_bell_sonar),
    PARTY(0x17, R.string.core_bell_party),
    FOGHORN(0x18, R.string.core_bell_foghorn);

    val label: String get() = I18n.get(labelRes)

    /** The same emoji as this tone's pad in [SOUNDS], so one sound never shows two pictures on the Klang tab. */
    val emoji: String get() = SOUNDS.first { it.id == code }.emoji

    companion object { fun from(code: Int?) = entries.firstOrNull { it.code == code } }
}

/**
 * One playable sound. Table entries carry a string resource; callers may also pass a ready-made
 * label (e.g. a bell tone's name or "custom sound" resolved in the UI).
 */
class Sound private constructor(
    val id: Int,
    @StringRes val labelRes: Int,
    private val fixedLabel: String?,
    val group: SoundGroup,
    /** Shown on the sound pad; purely decorative, screen readers skip it. */
    val emoji: String,
) {
    constructor(id: Int, @StringRes labelRes: Int, group: SoundGroup, emoji: String = "") : this(id, labelRes, null, group, emoji)
    constructor(id: Int, label: String, group: SoundGroup, emoji: String = "") : this(id, 0, label, group, emoji)

    val label: String get() = fixedLabel ?: I18n.get(labelRes)
}

enum class SoundGroup(@StringRes val labelRes: Int) {
    HORN(R.string.core_sound_group_horn),
    SIGNAL(R.string.core_sound_group_signal),
    ALARM(R.string.core_sound_group_alarm),
    SYSTEM(R.string.core_sound_group_system);

    val label: String get() = I18n.get(labelRes)
}

/** PLAY_SOUND ids (pymoof / VanMoofKit tables; firmware plays anything 0x01..0x1D). */
val SOUNDS = listOf(
    Sound(0x16, R.string.core_sound_ding_dong, SoundGroup.HORN, "🛎️"),
    Sound(0x17, R.string.core_sound_toot, SoundGroup.HORN, "📯"),
    Sound(0x18, R.string.core_sound_foghorn, SoundGroup.HORN, "🚢"),
    Sound(0x0a, R.string.core_sound_submarine, SoundGroup.HORN, "🤿"),
    Sound(0x0b, R.string.core_sound_urgent_whistle, SoundGroup.HORN, "📢"),
    Sound(0x01, R.string.core_sound_click, SoundGroup.SIGNAL, "👆"),
    Sound(0x03, R.string.core_sound_positive, SoundGroup.SIGNAL, "👍"),
    Sound(0x02, R.string.core_sound_negative, SoundGroup.SIGNAL, "👎"),
    Sound(0x0c, R.string.core_sound_lock, SoundGroup.SIGNAL, "🔒"),
    Sound(0x0d, R.string.core_sound_unlock, SoundGroup.SIGNAL, "🔓"),
    Sound(0x04, R.string.core_sound_unlock_countdown, SoundGroup.SIGNAL, "⏳"),
    Sound(0x06, R.string.core_sound_backup_code, SoundGroup.SIGNAL, "🔢"),
    Sound(0x08, R.string.core_sound_pairing_ok, SoundGroup.SIGNAL, "🤝"),
    Sound(0x09, R.string.core_sound_pairing_failed, SoundGroup.SIGNAL, "💔"),
    Sound(0x05, R.string.core_sound_find_my, SoundGroup.SIGNAL, "📍"),
    Sound(0x0e, R.string.core_sound_alarm_one, SoundGroup.ALARM, "🚨"),
    Sound(0x0f, R.string.core_sound_alarm_two, SoundGroup.ALARM, "🚓"),
    Sound(0x19, R.string.lab_sound_update_failed, SoundGroup.SYSTEM, "🧨"),  // 0x19 = firmware update failed, not "noise" (decomp states.c:1951)
    Sound(0x10, R.string.core_sound_startup, SoundGroup.SYSTEM, "⚡"),
    Sound(0x11, R.string.core_sound_shutdown, SoundGroup.SYSTEM, "💤"),
    Sound(0x12, R.string.core_sound_charging, SoundGroup.SYSTEM, "🔌"),
    Sound(0x13, R.string.core_sound_diagnostics, SoundGroup.SYSTEM, "🩺"),
    Sound(0x07, R.string.core_sound_reset_countdown, SoundGroup.SYSTEM, "🔁"),
    Sound(0x14, R.string.core_sound_firmware_loading, SoundGroup.SYSTEM, "📦"),
    Sound(0x15, R.string.lab_sound_update_ok, SoundGroup.SYSTEM, "✅"),      // 0x15 = firmware update success
    Sound(0x1a, R.string.lab_sound_find_loud, SoundGroup.ALARM, "📣"),       // 0x1A = Loud Noise / Find-My loud search tone (0x1B is the unpair tone)
)

/**
 * ERRORS (5563): 8 bytes, a 64-bit fault pair (low word = bytes 0..3, high word = bytes 4..7, LE).
 * The number on the matrix is the lowest set bit index. Names: Knight1 ERRORS.md + error-flags.md and the workshop
 * table (22 is the motor channel, 24..37 are update/flash errors, not e-shifter faults: shifter.c, update.c).
 */
object Faults {
    class Fault(val code: Int, @StringRes val nameRes: Int, @StringRes val hintRes: Int) {
        val name: String get() = I18n.get(nameRes)
        val hint: String get() = I18n.get(hintRes)
    }

    /**
     * High-word bit 16 (code 48) is kept as a status bit, not a fault. In the decompiled mainware 1.07.06 it is
     * raised by the wheel-pulse handler (sensor.c, exti9_5_app_hook), never from the motor status word (that one
     * only feeds 45 and 53). The workshop table calls 48 a motor controller error; core_fault_48_hint says both.
     */
    const val WHEEL_MOVING = 48

    private val table: Map<Int, Pair<Int, Int>> = mapOf(
        0 to (R.string.core_fault_0_name to R.string.core_fault_0_hint),
        1 to (R.string.core_fault_1_name to R.string.core_fault_1_hint),
        2 to (R.string.core_fault_2_name to R.string.core_fault_2_hint),
        3 to (R.string.core_fault_3_name to R.string.core_fault_3_hint),
        4 to (R.string.core_fault_4_name to R.string.core_fault_4_hint),
        5 to (R.string.core_fault_5_name to R.string.core_fault_5_hint),
        6 to (R.string.core_fault_6_name to R.string.core_fault_6_hint),
        7 to (R.string.core_fault_7_name to R.string.core_fault_7_hint),
        8 to (R.string.core_fault_8_name to R.string.core_fault_8_hint),
        9 to (R.string.core_fault_9_name to R.string.core_fault_9_hint),
        10 to (R.string.core_fault_10_name to R.string.core_fault_10_hint),
        11 to (R.string.core_fault_11_name to R.string.core_fault_11_hint),
        12 to (R.string.core_fault_12_name to R.string.core_fault_12_hint),
        13 to (R.string.core_fault_13_name to R.string.core_fault_13_hint),
        14 to (R.string.core_fault_14_name to R.string.core_fault_14_hint),
        15 to (R.string.core_fault_15_name to R.string.core_fault_15_hint),
        16 to (R.string.core_fault_16_name to R.string.core_fault_16_hint),
        17 to (R.string.core_fault_17_name to R.string.core_fault_17_hint),
        18 to (R.string.core_fault_18_name to R.string.core_fault_18_hint),
        19 to (R.string.core_fault_19_name to R.string.core_fault_19_hint),
        20 to (R.string.core_fault_20_name to R.string.core_fault_20_hint),
        21 to (R.string.core_fault_21_name to R.string.core_fault_21_hint),
        22 to (R.string.core_fault_22_name to R.string.core_fault_22_hint),
        23 to (R.string.core_fault_23_name to R.string.core_fault_23_hint),
        24 to (R.string.core_fault_24_name to R.string.core_fault_24_hint),
        25 to (R.string.core_fault_25_name to R.string.core_fault_25_hint),
        26 to (R.string.core_fault_26_name to R.string.core_fault_26_hint),
        27 to (R.string.core_fault_27_name to R.string.core_fault_27_hint),
        28 to (R.string.core_fault_28_name to R.string.core_fault_28_hint),
        29 to (R.string.core_fault_29_name to R.string.core_fault_29_hint),
        30 to (R.string.core_fault_30_name to R.string.core_fault_30_hint),
        31 to (R.string.core_fault_31_name to R.string.core_fault_31_hint),
        32 to (R.string.core_fault_32_name to R.string.core_fault_32_hint),
        33 to (R.string.core_fault_33_name to R.string.core_fault_33_hint),
        34 to (R.string.core_fault_34_name to R.string.core_fault_34_hint),
        35 to (R.string.core_fault_35_name to R.string.core_fault_35_hint),
        36 to (R.string.core_fault_36_name to R.string.core_fault_36_hint),
        37 to (R.string.core_fault_37_name to R.string.core_fault_37_hint),
        38 to (R.string.core_fault_38_name to R.string.core_fault_38_hint),
        39 to (R.string.core_fault_39_name to R.string.core_fault_39_hint),
        40 to (R.string.core_fault_40_name to R.string.core_fault_40_hint),
        41 to (R.string.core_fault_41_name to R.string.core_fault_41_hint),
        42 to (R.string.core_fault_42_name to R.string.core_fault_42_hint),
        43 to (R.string.core_fault_43_name to R.string.core_fault_43_hint),
        44 to (R.string.core_fault_44_name to R.string.core_fault_44_hint),
        45 to (R.string.core_fault_45_name to R.string.core_fault_45_hint),
        46 to (R.string.core_fault_46_name to R.string.core_fault_46_hint),
        47 to (R.string.core_fault_47_name to R.string.core_fault_47_hint),
        WHEEL_MOVING to (R.string.core_fault_48_name to R.string.core_fault_48_hint),
        49 to (R.string.core_fault_49_name to R.string.core_fault_49_hint),
        50 to (R.string.core_fault_50_name to R.string.core_fault_50_hint),
        51 to (R.string.core_fault_51_name to R.string.core_fault_51_hint),
        52 to (R.string.core_fault_52_name to R.string.core_fault_52_hint),
        53 to (R.string.core_fault_53_name to R.string.core_fault_53_hint),
        54 to (R.string.core_fault_54_name to R.string.core_fault_54_hint),
        55 to (R.string.core_fault_55_name to R.string.core_fault_55_hint),
        56 to (R.string.core_fault_56_name to R.string.core_fault_56_hint),
        57 to (R.string.core_fault_57_name to R.string.core_fault_57_hint),
        58 to (R.string.core_fault_58_name to R.string.core_fault_58_hint),
        60 to (R.string.core_fault_60_name to R.string.core_fault_60_hint),
    )

    /** Codes with a known meaning (the rest describe as "unknown code"). */
    val knownCodes: Set<Int> get() = table.keys

    fun describe(code: Int): Fault {
        val (name, hint) = table[code] ?: (R.string.core_fault_unknown_name to R.string.core_fault_unknown_hint)
        return Fault(code, name, hint)
    }

    /** All set bit indices, in matrix order (lowest first). */
    fun decode(raw: ByteArray): List<Int> {
        if (raw.isEmpty()) return emptyList()
        val low = raw.uLE(4)
        val high = if (raw.size >= 8) raw.copyOfRange(4, 8).uLE(4) else 0L
        val codes = ArrayList<Int>()
        for (i in 0 until 32) if (low and (1L shl i) != 0L) codes += i
        for (i in 0 until 32) if (high and (1L shl i) != 0L) codes += 32 + i
        return codes
    }

    /** Faults without status-only bits. */
    fun faultsOnly(codes: List<Int>) = codes.filter { it != WHEEL_MOVING }
}

/**
 * BUTTONS (5568): [bell pressed (PC0), boost pressed (PC1), power/reset button pressed (PD2 "Off/rst"), 0].
 * Pushed unconditionally. Not the kick-lock switch: that one surfaces as LOCK_STATE = locked.
 */
data class Buttons(val horn: Boolean, val boost: Boolean, val powerButton: Boolean) {
    companion object {
        fun from(v: ByteArray): Buttons? =
            if (v.size < 3) null else Buttons(v[0].toInt() != 0, v[1].toInt() != 0, v[2].toInt() != 0)
    }
}

/** Little-endian unsigned integer from the first [bytes] bytes. */
fun ByteArray.uLE(bytes: Int): Long {
    var v = 0L
    for (i in 0 until minOf(bytes, size)) v = v or ((this[i].toLong() and 0xFF) shl (8 * i))
    return v
}

fun ByteArray.u8(): Int? = firstOrNull()?.toInt()?.and(0xFF)

/** Firmware strings arrive zero padded and sometimes with zero-padded segments ("01.09.03"). */
fun ByteArray.versionString(): String? {
    val raw = String(trimTrailingZeros(), Charsets.UTF_8).filter { it >= ' ' }.trim()
    if (raw.isEmpty()) return null
    if (!raw.contains('.')) return raw
    return raw.split('.').joinToString(".") { part ->
        if (part.all(Char::isDigit)) part.trimStart('0').ifEmpty { "0" } else part
    }
}

/** Numeric comparison of dotted versions; non-numeric parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}
