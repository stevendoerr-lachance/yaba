package dev.steven.s3control.ble

import dev.steven.s3control.R
import java.util.UUID

/*
 * VanMoof Electrified S2/X2 ("ELECTRIFIED_2018") Bluetooth protocol. EXPERIMENTAL: built from OldMoofKit's
 * Electrified2018Profile.swift (Jegge, MIT) and the vanbike-lib byte layouts (facts only, no code), and not yet
 * run against a real S2. See the S2 plan B4 (wire protocol) and B5 (frame layout).
 *
 * Pure Kotlin: nothing here touches Android or I18n at runtime, only R ids (as [Faults] does).
 *
 * Every write to [S2Gatt.FUNCTIONS] is exactly one AES-128-ECB block with the account key:
 *   challenge[0..1] ‖ command ‖ payload ‖ zero padding  (16 bytes)
 * The challenge is read fresh from [S2Gatt.CHALLENGE] before every write (OldMoofKit Bike.swift:280-292).
 * Authentication is one PASSCODE write with the first 12 raw key bytes; the bike sends no acknowledgement.
 * The bike's status is one encrypted 16-byte block on [S2Gatt.PARAMETERS] ([S2Frame]).
 */

object S2Gatt {
    private fun bike(short: String): UUID = UUID.fromString("8e7f$short-087a-44c9-b292-a2c628fdd9aa")
    private fun sig(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    private fun oad(short: String): UUID = UUID.fromString("f000$short-0451-4000-b000-000000000000")

    /** Shared with the S1 and SmartBike 2016: on its own it does not identify an S2 (plan B3). */
    val SERVICE: UUID = bike("1a50")
    /** Plain read; bytes 0..1 are the nonce for the next write. */
    val CHALLENGE: UUID = bike("1a51")
    /** Encrypted command blocks; write with response when the characteristic offers PROPERTY_WRITE. */
    val FUNCTIONS: UUID = bike("1a53")
    /** Read and notify: the encrypted 16-byte status block. */
    val PARAMETERS: UUID = bike("1a54")

    /** Device Information, plain UTF-8 (parse with versionString()). Values unverified on an S2 (M). */
    val DEVICE_INFO: UUID = sig("180a")
    val MODEL_NUMBER: UUID = sig("2a24")
    val SERIAL_NUMBER: UUID = sig("2a25")
    val FIRMWARE_REVISION: UUID = sig("2a26")
    val HARDWARE_REVISION: UUID = sig("2a27")
    val SOFTWARE_REVISION: UUID = sig("2a28")
    val deviceInfo: List<UUID> = listOf(FIRMWARE_REVISION, HARDWARE_REVISION, SOFTWARE_REVISION, MODEL_NUMBER, SERIAL_NUMBER)

    /** vanbike-lib calls it NEW_PRIVATE_KEY (L). YABA never reads or writes it. */
    val KEY_CHANGE: UUID = bike("1a52")
    /** TI OAD firmware update. YABA never touches it. */
    val OAD_SERVICE: UUID = oad("ffc0")

    /** Characteristics and services a driver must never read or write. */
    val forbidden: Set<UUID> = setOf(KEY_CHANGE, OAD_SERVICE, oad("ffc1"), oad("ffc2"), oad("ffc3"), oad("ffc4"))

    private val labels: Map<UUID, Int> = mapOf(
        CHALLENGE to R.string.core_char_challenge,
        FUNCTIONS to R.string.core_char_s2_functions,
        PARAMETERS to R.string.core_char_s2_parameters,
        KEY_CHANGE to R.string.core_char_s2_key_change,
        MODEL_NUMBER to R.string.core_char_device_model,
        SERIAL_NUMBER to R.string.core_char_device_serial,
        FIRMWARE_REVISION to R.string.core_char_device_firmware,
        HARDWARE_REVISION to R.string.core_char_device_hardware,
        SOFTWARE_REVISION to R.string.core_char_device_software,
    )

    /** Name of an S2 or Device Information characteristic, for error texts and the GATT overview; null if unknown. */
    fun labelRes(id: UUID): Int? = labels[id]

    /**
     * Advertised names: "VANMOOF-" plus the last 6 hex digits of the MAC (OldMoofKit BikeDetails.swift:69-71).
     * The case is unverified and YABA upper-cases stored MACs, so both spellings are returned (plan B3).
     */
    fun deviceNames(mac: String): List<String> {
        val tail = mac.filter { it.isLetterOrDigit() }.takeLast(6)
        return listOf("VANMOOF-" + tail.lowercase(), "VANMOOF-" + tail.uppercase()).distinct()
    }
}

/**
 * The only commands YABA can send to an S2. 0x08 (show firmware?), 0x09 (reset distance), 0x0A (enable errors?),
 * 0x0C (off-road mode) and 0x0D (firmware update) are deliberately missing so they can never go out (plan B10).
 */
enum class S2Command(val code: Int) {
    PASSCODE(0x01),
    MODULE_STATE(0x02),
    LOCK(0x03),
    ASSIST_REGION(0x04),
    LIGHT(0x05),
    /** OldMoofKit: mute sounds; vanbike-lib: "pair remote". Test mode only. */
    MUTE(0x06),
    UNIT(0x07),
    /** OldMoofKit: set backup code; vanbike-lib: "disableErrors". Test mode only. */
    BACKUP_CODE(0x0B),
    ALARM(0x0F);

    companion object {
        fun from(code: Int): S2Command? = entries.firstOrNull { it.code == code }
    }
}

/** S2 region byte (OldMoofKit Region.swift). The S3 codes in [SpeedLimit.code] are different. */
enum class S2Region(val code: Int, val limit: SpeedLimit) {
    EU(0, SpeedLimit.EU),
    US(1, SpeedLimit.US),
    /** 37 km/h, shown as [SpeedLimit.NO_LIMIT]; whether the bike accepts it is untested (M). */
    OFFROAD(2, SpeedLimit.NO_LIMIT),
    JP(3, SpeedLimit.JP);

    companion object {
        fun from(code: Int?): S2Region? = entries.firstOrNull { it.code == code }
        fun of(limit: SpeedLimit): S2Region = entries.first { it.limit == limit }
    }
}

/** Frame byte 2 bits 1..3 and the ALARM command (OldMoofKit Alarm.swift). */
enum class S2Alarm(val code: Int) {
    OFF(0),
    /** Armed by hand; YABA's S3 "armed" maps here. */
    MANUAL(1),
    AUTOMATIC(2);

    companion object {
        fun from(code: Int?): S2Alarm? = entries.firstOrNull { it.code == code }
    }
}

/** Frame byte 2, decoded in OldMoofKit's priority order (E18:74-84). STANDBY = none of the bits set. */
enum class S2ModuleState { ON, TRACKING, SLEEPING, STANDBY }

/**
 * Muted-sound bits as OldMoofKit keeps them (MutedSounds.swift). Only bits 6..13 travel: the frame carries them
 * in byte 10 (mask shr 6) and the MUTE command sends the same byte.
 */
object S2Mute {
    const val LOCK = 1 shl 8
    const val UNLOCK = 1 shl 9
    const val WAKEUP = 1 shl 12
    const val SLEEP = 1 shl 13
    const val LOCK_STATE = LOCK or UNLOCK
    const val MODULE_STATE = WAKEUP or SLEEP
    /** The bits one wire byte can hold. */
    const val WIRE_BITS = 0xFF shl 6
}

/**
 * One vetted command for [S2Gatt.FUNCTIONS], before the challenge is known. The constructor is private and the
 * only code that calls it is the builders in the companion (reached through [S2Codec]), so the compiler, not a
 * convention, keeps every payload to what the plan allows (e.g. never MODULE_STATE 2 = shipping).
 */
class S2Write private constructor(val command: S2Command, payload: ByteArray) {
    private val data: ByteArray = payload.copyOf()

    init {
        require(data.size <= S2Codec.MAX_PAYLOAD) { "S2 payload too long: ${data.size} > ${S2Codec.MAX_PAYLOAD}" }
    }

    val payload: ByteArray get() = data.copyOf()

    /** Safe for logs: the PASSCODE payload is key material and is never printed. */
    override fun toString(): String =
        if (command == S2Command.PASSCODE) "PASSCODE <redacted>" else "${command.name} ${data.toHex()}"

    /** The command builders (plan B4 table); [S2Codec] hands them out under the same names. */
    companion object {
        /** PASSCODE (0x01): the first 12 raw bytes of the 16-byte account key (E18:64-66). */
        fun passcode(keyHex: String): S2Write {
            val key = hexToBytes(keyHex)
            require(key.size == 16) { "Encryption key must be 32 hex characters" }
            return S2Write(S2Command.PASSCODE, key.copyOfRange(0, S2Codec.PASSCODE_SIZE))
        }

        /** MODULE_STATE (0x02) with 0 = on. The only module state YABA sends (1 = off, 2 = shipping are never sent). */
        fun wake(): S2Write = S2Write(S2Command.MODULE_STATE, byteArrayOf(0))

        /** LOCK (0x03) 0: unlock; the rider then rolls the bike while the animation runs (kick lock, M). */
        fun unlock(): S2Write = S2Write(S2Command.LOCK, byteArrayOf(0))

        /** LOCK (0x03) 1: effect unverified (L), test mode only. */
        fun lock(): S2Write = S2Write(S2Command.LOCK, byteArrayOf(1))

        /** ASSIST_REGION (0x04): level and region always travel together; the caller must know both. */
        fun assistRegion(level: Int, region: S2Region): S2Write {
            require(level in 0..S2Codec.MAX_LEVEL) { "S2 assist level must be 0..${S2Codec.MAX_LEVEL}, got $level" }
            return S2Write(S2Command.ASSIST_REGION, byteArrayOf(level.toByte(), region.code.toByte()))
        }

        /** LIGHT (0x05): 0 auto, 1 on, 2 off, the same codes as [LightMode]. */
        fun light(mode: LightMode): S2Write = S2Write(S2Command.LIGHT, byteArrayOf(mode.code.toByte()))

        /** MUTE (0x06): [00, mask shr 6] (E18:133-135). Meaning disputed (L), test mode only. */
        fun mute(mask: Int): S2Write {
            require(mask and S2Mute.WIRE_BITS.inv() == 0) { "S2 mute mask has bits outside 6..13: 0x%04x".format(mask) }
            return S2Write(S2Command.MUTE, byteArrayOf(0, (mask shr 6).toByte()))
        }

        /** UNIT (0x07): 0 metric, 1 imperial, the same codes as [UnitSystem]. */
        fun unit(unit: UnitSystem): S2Write = S2Write(S2Command.UNIT, byteArrayOf(unit.code.toByte()))

        /** BACKUP_CODE (0x0B): exactly three digits 1..9, one byte each (OldMoofKit Bike.swift:592-601). No clear. */
        fun backupCode(code: String): S2Write {
            require(S2Codec.isValidBackupCode(code)) { "S2 backup code must be three digits 1..9" }
            return S2Write(S2Command.BACKUP_CODE, ByteArray(3) { (code[it] - '0').toByte() })
        }

        /** ALARM (0x0F): 0 off, 1 manual, 2 automatic. Single source (OldMoofKit), test mode only. */
        fun alarm(mode: S2Alarm): S2Write = S2Write(S2Command.ALARM, byteArrayOf(mode.code.toByte()))
    }
}

object S2Codec {
    const val BLOCK_SIZE = 16
    const val CHALLENGE_SIZE = 2
    /** 16 − 2 challenge bytes − 1 command byte. */
    const val MAX_PAYLOAD = BLOCK_SIZE - CHALLENGE_SIZE - 1
    const val PASSCODE_SIZE = 12
    const val MAX_LEVEL = 4

    /**
     * The plaintext block: challenge[0..1] ‖ command ‖ payload ‖ zero padding, always exactly 16 bytes.
     * Deliberately not [BikeCipher.sealWrite], whose mooovy quirk adds a whole extra block when aligned.
     * Takes only an [S2Write], so only a vetted payload can become a block.
     */
    fun plain(challenge: ByteArray, write: S2Write): ByteArray {
        require(challenge.size >= CHALLENGE_SIZE) { "S2 challenge needs $CHALLENGE_SIZE bytes, got ${challenge.size}" }
        val payload = write.payload
        require(CHALLENGE_SIZE + 1 + payload.size <= BLOCK_SIZE) { "S2 payload too long: ${payload.size} > $MAX_PAYLOAD" }
        val out = ByteArray(BLOCK_SIZE)
        challenge.copyInto(out, 0, 0, CHALLENGE_SIZE)
        out[CHALLENGE_SIZE] = write.command.code.toByte()
        payload.copyInto(out, CHALLENGE_SIZE + 1)
        return out
    }

    /** The encrypted 16-byte block to write to [S2Gatt.FUNCTIONS]. */
    fun block(cipher: BikeCipher, challenge: ByteArray, write: S2Write): ByteArray = cipher.encrypt(plain(challenge, write))

    // --- Command builders (plan B4 table): [S2Write]'s own, documented there ---

    fun passcode(keyHex: String): S2Write = S2Write.passcode(keyHex)
    fun wake(): S2Write = S2Write.wake()
    fun unlock(): S2Write = S2Write.unlock()
    fun lock(): S2Write = S2Write.lock()
    fun assistRegion(level: Int, region: S2Region): S2Write = S2Write.assistRegion(level, region)
    fun light(mode: LightMode): S2Write = S2Write.light(mode)
    fun mute(mask: Int): S2Write = S2Write.mute(mask)
    fun unit(unit: UnitSystem): S2Write = S2Write.unit(unit)
    fun backupCode(code: String): S2Write = S2Write.backupCode(code)
    fun alarm(mode: S2Alarm): S2Write = S2Write.alarm(mode)

    fun isValidBackupCode(code: String): Boolean = code.length == 3 && code.all { it in '1'..'9' }

    /**
     * Decrypts a [S2Gatt.PARAMETERS] value. Null unless it is whole AES blocks of at least 16 bytes:
     * [BikeCipher.decrypt] would hand anything else back unchanged, and that must never be decoded as plaintext.
     */
    fun decryptFrame(cipher: BikeCipher, value: ByteArray): S2Frame? {
        if (value.size < S2Frame.SIZE || value.size % BLOCK_SIZE != 0) return null
        return S2Frame.parse(cipher.decrypt(value))
    }
}

/**
 * The decrypted 16-byte status block (plan B5, OldMoofKit E18:68-98). Fields are decoded on construction;
 * [bytes] keeps the whole frame for raw views and logs. Where OldMoofKit substitutes a default for an
 * out-of-range value (alarm, light, level), this returns null instead, so nothing invented reaches the UI.
 */
class S2Frame private constructor(private val b: ByteArray) {
    private fun u(i: Int): Int = b[i].toInt() and 0xFF

    val bytes: ByteArray get() = b.copyOf()
    val hex: String get() = b.toHex()

    /** Byte 2 bit 0 on, bit 4 tracking, bit 5 sleeping; on wins, then tracking, then sleeping. */
    val moduleState: S2ModuleState = when {
        u(2) and 0x01 != 0 -> S2ModuleState.ON
        u(2) and 0x10 != 0 -> S2ModuleState.TRACKING
        u(2) and 0x20 != 0 -> S2ModuleState.SLEEPING
        else -> S2ModuleState.STANDBY
    }

    /** Byte 2 bits 1..3. */
    val alarmCode: Int = (u(2) and 0x0E) shr 1
    val alarm: S2Alarm? = S2Alarm.from(alarmCode)

    /** Byte 3 & [LOCK_MASK]. */
    val locked: Boolean = u(3) and LOCK_MASK != 0

    /** Byte 4 as the bike reports it; under imperial it may be mph (open question 12). */
    val speed: Int = u(4)
    val motorBattery: Int = u(5)
    val moduleBattery: Int = u(6)

    /** Byte 7 bits 0..1. */
    val lightCode: Int = u(7) and 0x03
    val light: LightMode? = LightMode.from(lightCode)

    /** Byte 7 bit 2. */
    val unit: UnitSystem = if (u(7) and 0x04 != 0) UnitSystem.IMPERIAL else UnitSystem.METRIC

    /** Byte 8 masked with [LEVEL_MASK] (OldMoofKit; vanbike-lib reads the raw byte, open question 3). */
    val levelCode: Int = (u(8) and LEVEL_MASK) shr LEVEL_SHIFT
    val powerLevel: Int? = levelCode.takeIf { it <= S2Codec.MAX_LEVEL }

    /** Byte 8 bits 0..1; all four values are defined. */
    val region: S2Region = S2Region.from(u(8) and 0x03)!!

    /** Byte 10, shifted back into [S2Mute] bit positions. */
    val muteMask: Int = u(10) shl 6

    /** Bytes 11..14 little-endian ÷ [DISTANCE_DIVISOR] (OldMoofKit; vanbike-lib takes the raw value). */
    val distanceKm: Double = b.copyOfRange(11, 15).uLE(4) / DISTANCE_DIVISOR

    /** Byte 15 bits 3..7: described by [S2Faults]. 0 = no error. */
    val errorCode: Int = u(15) shr 3

    /** Byte 15 bits 0..2, kept raw: charging flag or run mode is unsettled (open question 4). */
    val runBits: Int = u(15) and 0x07

    /** The error as a fault list: empty for 0, as BikeState.faultCodes expects. */
    val faultCodes: List<Int> get() = if (errorCode == S2Faults.NO_ERROR) emptyList() else listOf(errorCode)

    /**
     * False when a value cannot come from a real S2: a battery above 100 % or a speed above
     * [MAX_PLAUSIBLE_SPEED]. The bike never acknowledges PASSCODE and AES-ECB "decrypts" with any key, so a
     * wrong key shows only as such a block. Checked on the raw bytes, before [S2State] clamps them.
     */
    val plausible: Boolean
        get() = motorBattery <= 100 && moduleBattery <= 100 && speed <= MAX_PLAUSIBLE_SPEED

    /** Byte indices that differ from [previous]; all of them for the first frame. */
    fun changedBytes(previous: S2Frame?): List<Int> =
        (0 until SIZE).filter { previous == null || previous.b[it] != b[it] }

    override fun equals(other: Any?): Boolean = other is S2Frame && other.b.contentEquals(b)
    override fun hashCode(): Int = b.contentHashCode()
    override fun toString(): String = "S2Frame($hex)"

    companion object {
        const val SIZE = 16

        // The disputed points of plan B4, one constant each, settled by the friend's test.
        /** Byte 3 lock bit (E18:87, M). Test steps 9 and 10. */
        const val LOCK_MASK = 0x02
        /** Byte 8 level bits (E18:93, M). Test step 6. */
        const val LEVEL_MASK = 0x1C
        const val LEVEL_SHIFT = 2
        /** Bytes 11..14 scale (E18:96, M). Test step 3. */
        const val DISTANCE_DIVISOR = 10.0
        /** Fastest speed byte [plausible] accepts, km/h or mph (open question 12). */
        const val MAX_PLAUSIBLE_SPEED = 60

        /** Decodes the first 16 bytes of a decrypted block; null when shorter. */
        fun parse(plain: ByteArray): S2Frame? = if (plain.size < SIZE) null else S2Frame(plain.copyOf(SIZE))
    }
}

/**
 * The S2's own 5-bit error codes (OldMoofKit ErrorCode.swift:48-62, vanbike-lib ErrorCodeStateEntity.js).
 * Not the S3 table: 16 is "battery (BMS)" on an S3 but a hall-sensor fault on an S2.
 */
object S2Faults {
    const val NO_ERROR = 0

    /** Disputed (plan B4): vanbike-lib and vanmoofista say 5 = motor too fast, OldMoofKit says 4. Not provokable. */
    const val MOTOR_FAST_CODE = 5
    /** The other candidate; described as unconfirmed. */
    const val MOTOR_FAST_ALT_CODE = 4

    private val table: Map<Int, Pair<Int, Int>> = mapOf(
        NO_ERROR to (R.string.core_fault_s2_0_name to R.string.core_fault_s2_0_hint),
        1 to (R.string.core_fault_s2_1_name to R.string.core_fault_s2_1_hint),
        2 to (R.string.core_fault_s2_2_name to R.string.core_fault_s2_2_hint),
        3 to (R.string.core_fault_s2_3_name to R.string.core_fault_s2_3_hint),
        MOTOR_FAST_ALT_CODE to (R.string.core_fault_s2_motor_fast_alt_name to R.string.core_fault_s2_motor_fast_alt_hint),
        MOTOR_FAST_CODE to (R.string.core_fault_s2_motor_fast_name to R.string.core_fault_s2_motor_fast_hint),
        6 to (R.string.core_fault_s2_6_name to R.string.core_fault_s2_6_hint),
        7 to (R.string.core_fault_s2_7_name to R.string.core_fault_s2_7_hint),
        8 to (R.string.core_fault_s2_8_name to R.string.core_fault_s2_8_hint),
        9 to (R.string.core_fault_s2_9_name to R.string.core_fault_s2_9_hint),
        16 to (R.string.core_fault_s2_16_name to R.string.core_fault_s2_16_hint),
        25 to (R.string.core_fault_s2_25_name to R.string.core_fault_s2_25_hint),
        26 to (R.string.core_fault_s2_26_name to R.string.core_fault_s2_26_hint),
        27 to (R.string.core_fault_s2_27_name to R.string.core_fault_s2_27_hint),
        28 to (R.string.core_fault_s2_28_name to R.string.core_fault_s2_28_hint),
        29 to (R.string.core_fault_s2_29_name to R.string.core_fault_s2_29_hint),
    )

    /** Codes with a known meaning; every other code 0..31 describes as "unknown code". */
    val knownCodes: Set<Int> get() = table.keys

    fun describe(code: Int): Faults.Fault {
        val (name, hint) = table[code] ?: (R.string.core_fault_unknown_name to R.string.core_fault_unknown_hint)
        return Faults.Fault(code, name, hint)
    }
}
