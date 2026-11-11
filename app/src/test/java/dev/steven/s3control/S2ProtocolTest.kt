package dev.steven.s3control

import dev.steven.s3control.ble.BikeCipher
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.Feature
import dev.steven.s3control.ble.LightMode
import dev.steven.s3control.ble.S2Alarm
import dev.steven.s3control.ble.S2Codec
import dev.steven.s3control.ble.S2Command
import dev.steven.s3control.ble.S2Faults
import dev.steven.s3control.ble.S2Frame
import dev.steven.s3control.ble.S2Gatt
import dev.steven.s3control.ble.S2ModuleState
import dev.steven.s3control.ble.S2Mute
import dev.steven.s3control.ble.S2Region
import dev.steven.s3control.ble.S2Write
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.UnitSystem
import dev.steven.s3control.ble.featuresFor
import dev.steven.s3control.ble.hexToBytes
import dev.steven.s3control.ble.toHex
import dev.steven.s3control.data.BikeCredentials
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * S2/X2 protocol (the S2 plan B8). Vectors come from OldMoofKit's tests (Jegge, MIT):
 * TestEncryption.swift, TestBike.swift (testBikeElectrified2018Connection) and TestBikeDetails.swift.
 */
class S2ProtocolTest {
    /** "ABCDEFGHIJKLMNOP", OMT/TestBike.swift:18. */
    private val omkKey = "4142434445464748494a4b4c4d4e4f50"
    private val cipher = BikeCipher(omkKey)
    /** The challenge OldMoofKit's mock bike hands out (OMT/TestBike.swift:287). */
    private val nonce = hexToBytes("2342")

    private fun padded(hex: String) = hex.padEnd(32, '0')

    // --- B8.1 ---
    @Test
    fun aesZeroPadMatchesOldMoofKit() {
        val c = BikeCipher("30313233343536373839616263646566")
        val plain = hexToBytes(padded("deadbeefc0ffee8badf00d"))
        val enc = c.encrypt(plain)
        assertEquals("6d573016bce683cba2bfbf09e8e23450", enc.toHex())
        assertEquals(padded("deadbeefc0ffee8badf00d"), c.decrypt(enc).toHex())
    }

    // --- B8.2 ---
    @Test
    fun passcodeIsFirst12KeyBytes() {
        val w = S2Codec.passcode(omkKey)
        assertEquals(S2Command.PASSCODE, w.command)
        assertEquals("4142434445464748494a4b4c", w.payload.toHex())
        // Key material never reaches a log line.
        assertFalse(w.toString().contains("41424344"))
        assertThrows(IllegalArgumentException::class.java) { S2Codec.passcode("0011") }
    }

    // --- B8.3 ---
    @Test
    fun authAndLockBlocks() {
        val auth = S2Codec.passcode(omkKey)
        assertEquals("2342014142434445464748494a4b4c00", S2Codec.plain(nonce, auth).toHex())
        assertEquals("bea7dc9ffe348847c69cdabd75b10f92", S2Codec.block(cipher, nonce, auth).toHex())
        assertEquals(padded("23420300"), S2Codec.plain(nonce, S2Codec.unlock()).toHex())
        assertEquals("e783673b0fb6bc5b08a5910940d202f8", S2Codec.block(cipher, nonce, S2Codec.unlock()).toHex())
        assertEquals(padded("23420301"), S2Codec.plain(nonce, S2Codec.lock()).toHex())
        assertEquals("7aef5d5a742efb512ac0cad193e7e2d9", S2Codec.block(cipher, nonce, S2Codec.lock()).toHex())
        // Always exactly one block (no mooovy extra block), whatever the payload length.
        assertEquals(16, S2Codec.block(cipher, nonce, S2Codec.wake()).size)
        // Only challenge bytes 0..1 are used.
        assertEquals(padded("23420300"), S2Codec.plain(hexToBytes("2342ffff"), S2Codec.unlock()).toHex())
        assertThrows(IllegalArgumentException::class.java) { S2Codec.plain(byteArrayOf(0x23), S2Codec.unlock()) }
    }

    // --- B8.4 ---
    @Test
    fun frameDecodesLikeOldMoofKitMock() {
        val f = S2Codec.decryptFrame(cipher, hexToBytes("ef4aaea7e451108cf06859f7043d7abc"))!!
        assertEquals("00000402234247060400000c22384ee1", f.hex)
        assertEquals(S2ModuleState.STANDBY, f.moduleState)
        assertEquals(S2Alarm.AUTOMATIC, f.alarm)
        assertTrue(f.locked)
        assertEquals(35, f.speed)
        assertEquals(66, f.motorBattery)
        assertEquals(71, f.moduleBattery)
        assertEquals(LightMode.OFF, f.light)
        assertEquals(UnitSystem.IMPERIAL, f.unit)
        assertEquals(1, f.powerLevel)
        assertEquals(S2Region.EU, f.region)
        assertEquals(0, f.muteMask)
        assertEquals(131230158.0, f.distanceKm, 0.0)
        assertEquals(28, f.errorCode)
        assertEquals(listOf(28), f.faultCodes)
        assertEquals(1, f.runBits)
    }

    // --- B8.5 ---
    @Test
    fun commandBlocks() {
        fun p(w: S2Write) = S2Codec.plain(nonce, w).toHex()
        assertEquals(padded("2342040400"), p(S2Codec.assistRegion(4, S2Region.EU)))
        assertEquals(padded("2342040403"), p(S2Codec.assistRegion(4, S2Region.JP)))
        assertEquals(padded("23420501"), p(S2Codec.light(LightMode.ON)))
        assertEquals(padded("23420700"), p(S2Codec.unit(UnitSystem.METRIC)))
        assertEquals(padded("23420200"), p(S2Codec.wake()))
        assertEquals(padded("23420f02"), p(S2Codec.alarm(S2Alarm.AUTOMATIC)))
        assertEquals(padded("23420b010203"), p(S2Codec.backupCode("123")))
        assertEquals(padded("23420600cc"), p(S2Codec.mute(S2Mute.LOCK_STATE or S2Mute.MODULE_STATE)))
        assertEquals(0x3300, S2Mute.LOCK_STATE or S2Mute.MODULE_STATE)
        // Every block decrypts back to its plaintext.
        val w = S2Codec.light(LightMode.AUTO)
        assertEquals(p(w), cipher.decrypt(S2Codec.block(cipher, nonce, w)).toHex())
    }

    @Test
    fun builderGuards() {
        assertThrows(IllegalArgumentException::class.java) { S2Codec.assistRegion(5, S2Region.EU) }
        assertThrows(IllegalArgumentException::class.java) { S2Codec.assistRegion(-1, S2Region.EU) }
        for (bad in listOf("012", "1234", "12", "12a", "")) {
            assertFalse(bad, S2Codec.isValidBackupCode(bad))
            assertThrows(IllegalArgumentException::class.java) { S2Codec.backupCode(bad) }
        }
        assertTrue(S2Codec.isValidBackupCode("999"))
        // Bits that cannot travel (OldMoofKit's feedback/timer bits 0 and 3, bits 14/15) are refused, not dropped.
        assertThrows(IllegalArgumentException::class.java) { S2Codec.mute(1) }
        assertThrows(IllegalArgumentException::class.java) { S2Codec.mute(1 shl 3) }
        assertThrows(IllegalArgumentException::class.java) { S2Codec.mute(1 shl 14) }
        assertEquals("0000", S2Codec.mute(0).payload.toHex())
        // Wake is the only module state YABA can send.
        assertEquals("00", S2Codec.wake().payload.toHex())
        assertEquals("UNIT 01", S2Codec.unit(UnitSystem.IMPERIAL).toString())
    }

    // --- B8.6 ---
    @Test
    fun blockRejectsOverlongPayload() {
        // Only a test reaches the private constructor, by reflection: the app cannot build an S2Write itself.
        val make = S2Write::class.java.getDeclaredConstructor(S2Command::class.java, ByteArray::class.java)
            .apply { isAccessible = true }
        val e = assertThrows(InvocationTargetException::class.java) { make.newInstance(S2Command.LIGHT, ByteArray(14)) }
        assertTrue(e.cause is IllegalArgumentException)
        assertEquals(16, S2Codec.plain(nonce, make.newInstance(S2Command.LIGHT, ByteArray(13))).size)
        assertEquals(13, S2Codec.MAX_PAYLOAD)
    }

    @Test
    fun onlyTheBuildersMakeAnS2Write() {
        // No constructor the app could call: Kotlin's internal compiles to public, so none may be public.
        val callable = S2Write::class.java.declaredConstructors.filter { !it.isSynthetic && !Modifier.isPrivate(it.modifiers) }
        assertTrue("constructors outside S2Write: $callable", callable.isEmpty())
        // And no plain() that takes a loose command and payload.
        val plains = S2Codec::class.java.declaredMethods.filter { it.name == "plain" && !Modifier.isPrivate(it.modifiers) }
        assertEquals(listOf(listOf(ByteArray::class.java, S2Write::class.java)), plains.map { it.parameterTypes.toList() })
    }

    @Test
    fun plausibilityCatchesAWrongKeysGarbage() {
        assertTrue(S2Codec.decryptFrame(cipher, hexToBytes("ef4aaea7e451108cf06859f7043d7abc"))!!.plausible)
        fun f(i: Int, v: Int) = S2Frame.parse(ByteArray(16).also { it[i] = v.toByte() })!!
        assertTrue(f(5, 100).plausible)
        assertFalse(f(5, 101).plausible)
        assertFalse(f(6, 101).plausible)
        assertTrue(f(4, S2Frame.MAX_PLAUSIBLE_SPEED).plausible)
        assertFalse(f(4, S2Frame.MAX_PLAUSIBLE_SPEED + 1).plausible)
        // The mock frame decrypted with another key is garbage, and the check sees it.
        val wrong = S2Codec.decryptFrame(BikeCipher("00112233445566778899aabbccddeeff"), hexToBytes("ef4aaea7e451108cf06859f7043d7abc"))!!
        assertFalse(wrong.hex, wrong.plausible)
    }

    // --- B8.7 ---
    @Test
    fun s2CommandWhitelist() {
        assertEquals(setOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0F), S2Command.entries.map { it.code }.toSet())
        for (never in listOf(0x08, 0x09, 0x0A, 0x0C, 0x0D)) assertNull(S2Command.from(never))
    }

    // --- B8.8 ---
    @Test
    fun s2RegionCodes() {
        assertEquals(0, S2Region.of(SpeedLimit.EU).code)
        assertEquals(1, S2Region.of(SpeedLimit.US).code)
        assertEquals(2, S2Region.of(SpeedLimit.NO_LIMIT).code)
        assertEquals(3, S2Region.of(SpeedLimit.JP).code)
        for (r in S2Region.entries) assertEquals(r, S2Region.from(r.code))
        assertEquals(SpeedLimit.JP, S2Region.from(3)?.limit)
        // The S3 codes are untouched (ProtocolTest.speedLimitCodes).
        assertEquals(2, SpeedLimit.JP.code)
    }

    // --- B8.9 ---
    @Test
    fun s2DeviceNames() {
        assertEquals(listOf("VANMOOF-4d5e6f", "VANMOOF-4D5E6F"), S2Gatt.deviceNames("1a:2b:3c:4d:5e:6f"))
        assertEquals(listOf("VANMOOF-4d5e6f", "VANMOOF-4D5E6F"), S2Gatt.deviceNames("1A2B3C4D5E6F"))
        assertEquals(listOf("VANMOOF-123456"), S2Gatt.deviceNames("AA:BB:CC:12:34:56"))
    }

    // --- B8.10 ---
    @Test
    fun familyFromProfile() {
        assertEquals(BikeFamily.S2, BikeFamily.fromProfile("ELECTRIFIED_2018"))
        assertEquals(BikeFamily.S2, BikeFamily.fromProfile("ELECTRIFIED_2019"))
        assertEquals(BikeFamily.S3, BikeFamily.fromProfile("ELECTRIFIED_2020"))
        assertEquals(BikeFamily.S3, BikeFamily.fromProfile("ELECTRIFIED_2021"))
        assertEquals(BikeFamily.S3, BikeFamily.fromProfile("ELECTRIFIED_2022"))
        assertEquals(BikeFamily.LEGACY_UNSUPPORTED, BikeFamily.fromProfile("SMARTBIKE_2018"))
        assertEquals(BikeFamily.LEGACY_UNSUPPORTED, BikeFamily.fromProfile("ELECTRIFIED_2017"))
        assertEquals(BikeFamily.UNKNOWN, BikeFamily.fromProfile(null))
        assertEquals(BikeFamily.UNKNOWN, BikeFamily.fromProfile("ELECTRIFIED_2023_TRACK1"))
        assertEquals(BikeFamily.S2, BikeFamily.fromProfile(" electrified_2018 "))
    }

    @Test
    fun credentialsCarryFamilyAndNames() {
        fun bike(profile: String?) = BikeCredentials.fromJson(
            JSONObject().put("name", "B").put("mac", "74:D2:85:AB:CD:EF")
                .put("encryptionKey", "00112233445566778899aabbccddeeff").put("userKeyId", 1)
                .apply { if (profile != null) put("bleProfile", profile) },
        )
        val s3Names = listOf("ES3-74D285ABCDEF", "EX3-74D285ABCDEF")
        assertEquals(BikeFamily.UNKNOWN, bike(null).family)
        assertEquals(s3Names, bike(null).advertisedNames)
        assertEquals(BikeFamily.S3, bike("ELECTRIFIED_2021").family)
        assertEquals(s3Names, bike("ELECTRIFIED_2021").advertisedNames)
        assertEquals(s3Names, bike("ELECTRIFIED_2023_TRACK1").advertisedNames)
        assertEquals(BikeFamily.S2, bike("ELECTRIFIED_2018").family)
        assertEquals(listOf("VANMOOF-abcdef", "VANMOOF-ABCDEF"), bike("ELECTRIFIED_2018").advertisedNames)
        // The family is derived, not stored: the vault JSON is unchanged.
        assertFalse(bike("ELECTRIFIED_2018").toJson().has("family"))
    }

    // --- B8.11 ---
    @Test
    fun s2FaultTableUsesOwnResources() {
        assertEquals(R.string.core_fault_s2_16_name, S2Faults.describe(16).nameRes)
        assertEquals(R.string.core_fault_s2_16_hint, S2Faults.describe(16).hintRes)
        assertTrue(S2Faults.describe(16).nameRes != R.string.core_fault_16_name)
        assertEquals(R.string.core_fault_unknown_name, S2Faults.describe(31).nameRes)
        assertEquals(R.string.core_fault_unknown_hint, S2Faults.describe(31).hintRes)
        assertEquals(31, S2Faults.describe(31).code)
        assertEquals(R.string.core_fault_s2_motor_fast_name, S2Faults.describe(S2Faults.MOTOR_FAST_CODE).nameRes)
        assertEquals(R.string.core_fault_s2_motor_fast_alt_name, S2Faults.describe(S2Faults.MOTOR_FAST_ALT_CODE).nameRes)
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 25, 26, 27, 28, 29), S2Faults.knownCodes)
        val known = S2Faults.knownCodes.map(S2Faults::describe)
        assertTrue(known.none { it.nameRes == R.string.core_fault_unknown_name })
        assertEquals(known.size, known.map { it.nameRes }.toSet().size)
        assertEquals(known.size, known.map { it.hintRes }.toSet().size)
    }

    // --- B8.12 (family part; the BikeState part is S2DriverTest.firmwareGatesOnlyForTheS3Family) ---
    @Test
    fun firmwareGatesOnlyForS3() {
        assertFalse(BikeFamily.S2.usesS3FirmwareGates)
        assertFalse(BikeFamily.LEGACY_UNSUPPORTED.usesS3FirmwareGates)
        assertTrue(BikeFamily.S3.usesS3FirmwareGates)
        assertTrue(BikeFamily.UNKNOWN.usesS3FirmwareGates)
    }

    @Test
    fun featureSets() {
        val s2 = featuresFor(BikeFamily.S2, s2TestMode = false)
        assertEquals(
            setOf(Feature.UNLOCK, Feature.LIGHT, Feature.REGION, Feature.POWER_LEVEL, Feature.UNIT, Feature.WAKE, Feature.MODULE_BATTERY),
            s2,
        )
        // The alarm (0x0F, one source, A6.7) waits in test mode for test step 11.
        assertEquals(
            s2 + setOf(
                Feature.REMOTE_LOCK, Feature.BACKUP_CODE_SET, Feature.MUTE_SOUNDS, Feature.REGION_OFFROAD, Feature.GATT_DUMP,
                Feature.ALARM_THREE_WAY,
            ),
            featuresFor(BikeFamily.S2, s2TestMode = true),
        )
        val s3 = featuresFor(BikeFamily.S3, s2TestMode = true)
        assertEquals(s3, featuresFor(BikeFamily.UNKNOWN, s2TestMode = false))
        for (f in listOf(Feature.SOUNDBOARD, Feature.BELL_TONE, Feature.CUSTOM_BELL, Feature.BIKE_LOG, Feature.TOUCH_UNLOCK,
            Feature.BACKUP_CODE_CLEAR, Feature.S3_FIRMWARE_EXPERIMENTS, Feature.SHARING, Feature.REMOTE_LOCK)) {
            assertTrue(f.name, f in s3)
        }
        for (f in listOf(Feature.ALARM_THREE_WAY, Feature.MUTE_SOUNDS, Feature.MODULE_BATTERY, Feature.GATT_DUMP)) {
            assertFalse(f.name, f in s3)
        }
        assertTrue(featuresFor(BikeFamily.LEGACY_UNSUPPORTED, s2TestMode = true).isEmpty())
    }

    @Test
    fun frameDecodingDetails() {
        fun frame(vararg set: Pair<Int, Int>) = S2Frame.parse(ByteArray(16).also { b -> set.forEach { (i, v) -> b[i] = v.toByte() } })!!
        // Byte 2 priority: on > tracking > sleeping > standby (E18:74-84).
        assertEquals(S2ModuleState.ON, frame(2 to 0x31).moduleState)
        assertEquals(S2ModuleState.TRACKING, frame(2 to 0x30).moduleState)
        assertEquals(S2ModuleState.SLEEPING, frame(2 to 0x20).moduleState)
        assertEquals(S2ModuleState.STANDBY, frame(2 to 0x00).moduleState)
        assertEquals(S2Alarm.MANUAL, frame(2 to 0x03).alarm)
        // Out-of-range values stay unknown instead of OldMoofKit's substitutes.
        assertNull(frame(2 to 0x0E).alarm)
        assertNull(frame(7 to 0x03).light)
        assertNull(frame(8 to 0x1C).powerLevel)
        assertEquals(S2Region.OFFROAD, frame(8 to 0x12).region)
        assertEquals(4, frame(8 to 0x12).powerLevel)
        assertFalse(frame(3 to 0x01).locked)
        assertTrue(frame(3 to 0x02).locked)
        assertEquals(0x3300, frame(10 to 0xCC).muteMask)
        assertEquals(4710.5, frame(11 to 0x01, 12 to 0xB8).distanceKm, 0.0)
        assertTrue(frame(15 to 0x07).faultCodes.isEmpty())
        assertEquals(7, frame(15 to 0x07).runBits)
        assertEquals(31, frame(15 to 0xF8).errorCode)
        // changedBytes for test-mode logging.
        assertEquals((0 until 16).toList(), frame().changedBytes(null))
        assertEquals(listOf(4, 15), frame(4 to 1, 15 to 8).changedBytes(frame()))
        assertEquals(frame(4 to 1), frame(4 to 1))
        // Too short or not whole AES blocks: never decoded (BikeCipher.decrypt would pass ciphertext through).
        assertNull(S2Frame.parse(ByteArray(15)))
        assertNull(S2Codec.decryptFrame(cipher, ByteArray(15)))
        assertNull(S2Codec.decryptFrame(cipher, ByteArray(20)))
        assertNull(S2Codec.decryptFrame(cipher, ByteArray(0)))
    }

    @Test
    fun gattMapNeverIncludesForbiddenCharacteristics() {
        assertEquals(UUID.fromString("8e7f1a50-087a-44c9-b292-a2c628fdd9aa"), S2Gatt.SERVICE)
        assertEquals(UUID.fromString("8e7f1a53-087a-44c9-b292-a2c628fdd9aa"), S2Gatt.FUNCTIONS)
        assertTrue(UUID.fromString("8e7f1a52-087a-44c9-b292-a2c628fdd9aa") in S2Gatt.forbidden)
        assertTrue(UUID.fromString("f000ffc2-0451-4000-b000-000000000000") in S2Gatt.forbidden)
        val used = listOf(S2Gatt.SERVICE, S2Gatt.CHALLENGE, S2Gatt.FUNCTIONS, S2Gatt.PARAMETERS) + S2Gatt.deviceInfo
        assertTrue(used.none { it in S2Gatt.forbidden })
        assertEquals(UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"), S2Gatt.FIRMWARE_REVISION)
    }

    /**
     * Port of OldMoofKit's testBikeElectrified2018Connection: a mock S2 that applies our encrypted writes the way
     * TestBike.swift:237-282 does and answers with its makeParameters() frame (TestBike.swift:221-235).
     */
    private inner class OmkMockBike {
        var authenticated = 0
        var moduleOn = false
        var lighting = 2 // off
        var unit = 1 // imperial
        var assist = 1
        var region = 0 // EU
        var muted = 0
        var locked = true

        fun parameters(): ByteArray = byteArrayOf(
            0x00, 0x00,
            ((if (moduleOn) 0x01 else 0x00) + (S2Alarm.AUTOMATIC.code shl 1)).toByte(),
            (if (locked) 0x02 else 0x00).toByte(),
            0x23, 0x42, 0x47,
            (lighting + (unit shl 2)).toByte(),
            ((assist shl 2) + region).toByte(),
            0x00,
            (muted shr 6).toByte(),
            12, 34, 56, 78,
            ((28 shl 3) + 0x01).toByte(),
        ).let(cipher::encrypt)

        fun write(block: ByteArray): ByteArray? {
            val plain = cipher.decrypt(block)
            assertEquals(16, plain.size)
            assertEquals("2342", plain.copyOfRange(0, 2).toHex())
            val payload = plain.copyOfRange(2, 16).map { it.toInt() and 0xFF }
            when (payload[0]) {
                0x01 -> {
                    assertTrue("Bike authentication failed", omkKey.startsWith(plain.copyOfRange(3, 15).toHex()))
                    authenticated++
                    return null
                }
                0x02 -> moduleOn = payload[1] == 0 // ModuleState.on = 0
                0x05 -> lighting = payload[1]
                0x03 -> locked = false // the mock unlocks on any lock request
                0x06 -> muted = payload[2] shl 6
                0x04 -> { assist = payload[1]; region = payload[2] }
                0x07 -> unit = payload[1]
                else -> return null
            }
            return parameters()
        }
    }

    @Test
    fun oldMoofKitConnectionScenario() {
        val bike = OmkMockBike()
        fun send(w: S2Write): S2Frame =
            S2Codec.decryptFrame(cipher, bike.write(S2Codec.block(cipher, nonce, w))!!)!!

        assertNull(bike.write(S2Codec.block(cipher, nonce, S2Codec.passcode(omkKey))))
        assertEquals(1, bike.authenticated)

        var f = S2Codec.decryptFrame(cipher, bike.parameters())!!
        assertEquals(131230158.0, f.distanceKm, 0.0)
        assertEquals(S2ModuleState.STANDBY, f.moduleState)
        assertEquals(28, f.errorCode)
        assertEquals(LightMode.OFF, f.light)
        assertTrue(f.locked)
        assertEquals(S2Alarm.AUTOMATIC, f.alarm)
        assertEquals(0, f.muteMask)

        f = send(S2Codec.wake())
        assertEquals(S2ModuleState.ON, f.moduleState)

        f = send(S2Codec.light(LightMode.ON))
        assertEquals(LightMode.ON, f.light)

        f = send(S2Codec.unlock())
        assertFalse(f.locked)

        f = send(S2Codec.mute(S2Mute.LOCK_STATE or S2Mute.MODULE_STATE))
        assertEquals(S2Mute.LOCK_STATE or S2Mute.MODULE_STATE, f.muteMask)

        f = send(S2Codec.unit(UnitSystem.METRIC))
        assertEquals(UnitSystem.METRIC, f.unit)

        // Region and level always travel together: set the region at the current level, then the level.
        f = send(S2Codec.assistRegion(f.powerLevel!!, S2Region.JP))
        assertEquals(S2Region.JP, f.region)
        assertEquals(1, f.powerLevel)

        f = send(S2Codec.assistRegion(4, f.region))
        assertEquals(4, f.powerLevel)
        assertEquals(S2Region.JP, f.region)
        assertArrayEquals(f.bytes, S2Frame.parse(f.bytes)!!.bytes)
    }
}
