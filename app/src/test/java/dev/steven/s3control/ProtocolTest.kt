package dev.steven.s3control

import dev.steven.s3control.audio.BellConverter
import dev.steven.s3control.ble.BikeCipher
import dev.steven.s3control.ble.Buttons
import dev.steven.s3control.ble.Faults
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.SOUNDS
import dev.steven.s3control.ble.Sound
import dev.steven.s3control.ble.SoundGroup
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.compareVersions
import dev.steven.s3control.ble.crc32BigEndian
import dev.steven.s3control.ble.hexToBytes
import dev.steven.s3control.ble.toHex
import dev.steven.s3control.ble.uLE
import dev.steven.s3control.ble.versionString
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.data.normalizeMac
import dev.steven.s3control.ui.components.MatrixFont
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class ProtocolTest {
    private val fipsKey = "000102030405060708090a0b0c0d0e0f"

    @Test
    fun aesMatchesFips197Vector() {
        val c = BikeCipher(fipsKey)
        val out = c.encrypt(hexToBytes("00112233445566778899aabbccddeeff"))
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", out.toHex())
        assertEquals("00112233445566778899aabbccddeeff", c.decrypt(out).toHex())
    }

    @Test
    fun sealWritePadsLikeMooovy() {
        val c = BikeCipher(fipsKey)
        val nonce = byteArrayOf(0x12, 0x34)
        // 2 + 2 bytes → padded to 16
        val sealed = c.sealWrite(nonce, byteArrayOf(1, 1))
        assertEquals(16, sealed.size)
        assertEquals("1234010100000000000000000000000".padEnd(32, '0'), c.decrypt(sealed).toHex())
        // 2 + 14 = 16 → mooovy appends a full zero block
        assertEquals(32, c.sealWrite(nonce, ByteArray(14)).size)
        // 2 + 9 (bell metadata) → 16
        assertEquals(16, c.sealWrite(nonce, ByteArray(9)).size)
    }

    @Test
    fun authPayloadLayout() {
        val c = BikeCipher(fipsKey)
        val nonce = byteArrayOf(0x0a, 0x0b)
        val p = c.authPayload(nonce, userKeyId = 1)
        assertEquals(20, p.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), p.copyOfRange(16, 20))
        val block = ByteArray(16).also { it[0] = 0x0a; it[1] = 0x0b }
        assertArrayEquals(c.encrypt(block), p.copyOfRange(0, 16))
    }

    @Test
    fun keyValidation() {
        assertTrue(BikeCipher.isValidKey("00112233445566778899AABBCCDDEEFF"))
        assertFalse(BikeCipher.isValidKey("0011"))
        assertFalse(BikeCipher.isValidKey("zz112233445566778899aabbccddeeff"))
    }

    @Test
    fun crcMatchesStandardCheckValue() {
        // CRC-32/ISO-HDLC check value for "123456789" is 0xCBF43926 (same bits as npm crc-32).
        assertEquals("cbf43926", crc32BigEndian("123456789".toByteArray()).toHex())
    }

    @Test
    fun faultBitsDecodeToMatrixCodes() {
        // high word bit 10 → code 42 (kick-lock coil); low word bit 18 → code 18
        val raw = ByteArray(8)
        raw[2] = 0x04 // low word bit 18
        raw[5] = 0x04 // high word bit 10 (byte 5 = bits 8..15 of the high word)
        assertEquals(listOf(18, 42), Faults.decode(raw))
        // Texts live in resources (I18n is not initialised on the JVM): check the ids instead.
        assertEquals(R.string.core_fault_42_name, Faults.describe(42).nameRes)
        assertEquals(R.string.core_fault_42_hint, Faults.describe(42).hintRes)
        assertEquals(42, Faults.describe(42).code)
        // status bit 48 (wheel moving) is filtered from faults
        val moving = ByteArray(8).also { it[6] = 0x01 }
        assertEquals(listOf(48), Faults.decode(moving))
        assertTrue(Faults.faultsOnly(Faults.decode(moving)).isEmpty())
        assertTrue(Faults.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun faultTableUsesResources() {
        assertEquals(R.string.core_fault_unknown_name, Faults.describe(59).nameRes)
        assertEquals(R.string.core_fault_unknown_hint, Faults.describe(59).hintRes)
        val known = Faults.knownCodes.map(Faults::describe)
        assertTrue(known.none { it.nameRes == R.string.core_fault_unknown_name })
        // Every known fault has its own name.
        assertEquals(known.size, known.map { it.nameRes }.toSet().size)
    }

    @Test
    fun updateAndMotorCodesAreInTheTable() {
        // 24..37 are update and flash errors, 22 the motor channel, 46 and 49..52 the rest of the workshop motor codes.
        for (code in (22..37) + listOf(46, 49, 50, 51, 52)) {
            assertTrue("code $code is missing", code in Faults.knownCodes)
        }
        // 48 has its own text (status bit plus the workshop note) but never counts as a fault.
        assertEquals(R.string.core_fault_48_hint, Faults.describe(Faults.WHEEL_MOVING).hintRes)
        assertEquals(listOf(53), Faults.faultsOnly(listOf(48, 53)))
    }

    @Test
    fun protocolTablesCarryResourceIds() {
        assertEquals(R.string.core_region_europe, SpeedLimit.EU.regionRes)
        assertEquals(R.string.core_region_private, SpeedLimit.NO_LIMIT.regionRes)
        assertEquals(R.string.core_char_bike_firmware, Gatt.BIKE_FIRMWARE.labelRes)
        assertEquals(R.string.core_char_frame_number, Gatt.find(Gatt.FRAME_NUMBER.id)?.labelRes)
        assertEquals(26, SOUNDS.size)
        assertTrue(SOUNDS.all { it.labelRes != 0 })
        assertEquals(SOUNDS.size, SOUNDS.map { it.labelRes }.toSet().size)
        assertNotEquals(SoundGroup.HORN.labelRes, SoundGroup.ALARM.labelRes)
        // A ready-made label (from the UI) is used as is, without resources.
        assertEquals("Custom", Sound(0x18, "Custom", SoundGroup.HORN).label)
    }

    @Test
    fun bikeJsonWithNameNeedsNoResources() {
        val o = JSONObject()
            .put("name", "My S3").put("mac", "74:D2:85:AB:CD:EF")
            .put("encryptionKey", "00112233445566778899aabbccddeeff").put("userKeyId", 1)
        val b = BikeCredentials.fromJson(o)
        assertEquals("My S3", b.name)
        assertEquals(listOf("ES3-74D285ABCDEF", "EX3-74D285ABCDEF"), b.advertisedNames)
    }

    @Test
    fun buttonsDecode() {
        val b = Buttons.from(byteArrayOf(0, 1, 1, 0))!!
        assertFalse(b.horn)
        assertTrue(b.boost)
        assertTrue(b.powerButton)
    }

    @Test
    fun littleEndianAndDistance() {
        // 47105 hectometres = 4710.5 km
        val raw = byteArrayOf(0x01, 0xB8.toByte(), 0x00, 0x00) + ByteArray(12)
        assertEquals(47105L, raw.uLE(4))
    }

    @Test
    fun versionsAndComparison() {
        assertEquals("1.9.3", "01.09.03".toByteArray().plus(ByteArray(8)).versionString())
        assertEquals("1.9.3", "1.9.3".toByteArray().versionString())
        assertEquals("ASY31234", "ASY31234".toByteArray().versionString())
        assertTrue(compareVersions("1.9.3", "1.7.6") > 0)
        assertTrue(compareVersions("1.7.6", "1.7.6") == 0)
        assertTrue(compareVersions("1.7.5", "1.7.6") < 0)
    }

    @Test
    fun speedLimitCodes() {
        assertEquals(SpeedLimit.EU, SpeedLimit.from(0))
        assertEquals(SpeedLimit.US, SpeedLimit.from(1))
        assertEquals(SpeedLimit.JP, SpeedLimit.from(2))
        assertEquals(SpeedLimit.NO_LIMIT, SpeedLimit.from(255))
    }

    @Test
    fun macNormalisation() {
        assertEquals("74:D2:85:AB:CD:EF", normalizeMac("74d285abcdef"))
        assertEquals("74:D2:85:AB:CD:EF", normalizeMac("ES3-74D285ABCDEF".removePrefix("ES3-")))
        assertEquals("74:D2:85:AB:CD:EF", normalizeMac("74:d2:85:ab:cd:ef"))
    }

    @Test
    fun bellFileLayout() {
        val wav = BellConverter.wav(ShortArray(100), 22050)
        assertEquals(44 + 200, wav.size)
        val file = BellConverter.vmSound(wav)
        assertEquals("VM_SOUND", String(file.copyOfRange(0, 8)))
        assertEquals(wav.size.toLong(), file.copyOfRange(24, 28).uLE(4))
        assertEquals(28 + wav.size, file.size)
        // mooovy: 44.1 kHz for 4 s fits, 10 s needs 16 kHz
        assertEquals(44100, BellConverter.bestSampleRate(4f, 44100))
        assertEquals(16000, BellConverter.bestSampleRate(10f, 48000))
    }

    @Test
    fun resampleKeepsLength() {
        val pcm = ShortArray(48000) { (it % 100).toShort() }
        assertEquals(16000, BellConverter.resample(pcm, 48000, 16000).size)
    }

    @Test
    fun backupCodeTypedOrderSurvivesBlewareReversal() {
        // App sends [1,2,3]; bleware packs big-endian, relays little-endian → mainware sees [3,2,1]
        // and stores p0 + 10*p1 + 100*p2 = 3 + 20 + 100 = 123, the typed number.
        val sent = byteArrayOf(1, 2, 3)
        val v24 = (sent[0].toInt() shl 16) or (sent[1].toInt() shl 8) or sent[2].toInt()
        val relayed = intArrayOf(v24 and 0xFF, (v24 shr 8) and 0xFF, (v24 shr 16) and 0xFF)
        assertEquals(123, relayed[0] + relayed[1] * 10 + relayed[2] * 100)
    }

    @Test
    fun matrixFontRendersDigitsSevenRowsHigh() {
        val cols = MatrixFont.render("42")
        assertEquals(5 + 1 + 5, cols.size) // "4" and "2" are 5 wide; "1" and "I" are narrow (3)
        assertTrue(cols.all { it in 0..0x7F })
    }
}
