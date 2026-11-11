package dev.steven.s3control

import dev.steven.s3control.audio.BellSlotRecord
import dev.steven.s3control.audio.BellSlotStore
import dev.steven.s3control.audio.ClipSlot
import dev.steven.s3control.ble.SoundTiers
import dev.steven.s3control.ble.crc32BigEndian
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundLabTest {

    // --- SoundTiers 5572 ----------------------------------------------------------------------
    @Test fun encodeDecodeRoundTrip() {
        val t = SoundTiers.DEFAULT_1_9_1
        assertEquals(12, t.encode().size)
        assertEquals(t, SoundTiers.decode(t.encode()))
    }

    @Test fun encodeIsThreeBigEndianU32() {
        val t = SoundTiers(0x00000001L, 0x00000002L, 0xFF000003L)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 0xFF.toByte(), 0, 0, 3),
            t.encode(),
        )
    }

    @Test fun lowBeatsMediumBeatsHigh() {
        val bit = 1L
        assertEquals(SoundTiers.Tier.LOW, SoundTiers(bit, bit, bit).tierOf(0))
        assertEquals(SoundTiers.Tier.MEDIUM, SoundTiers(0, bit, bit).tierOf(0))
        assertEquals(SoundTiers.Tier.HIGH, SoundTiers(0, 0, bit).tierOf(0))
        assertEquals(SoundTiers.Tier.SILENT, SoundTiers(0, 0, 0).tierOf(0))
    }

    @Test fun withTierMovesAndClears() {
        var t = SoundTiers(0, 0, 0)
        t = t.withTier(0x0C, SoundTiers.Tier.MEDIUM)
        assertEquals(SoundTiers.Tier.MEDIUM, t.tierOf(0x0C))
        t = t.withTier(0x0C, SoundTiers.Tier.HIGH)
        assertEquals(SoundTiers.Tier.HIGH, t.tierOf(0x0C))
        assertEquals(0L, t.medium and (1L shl 0x0C))  // cleared from the old tier
        t = t.withTier(0x0C, SoundTiers.Tier.SILENT)
        assertEquals(SoundTiers.Tier.SILENT, t.tierOf(0x0C))
    }

    @Test fun defaultsKeepBellAudibleAndAlarmPresent() {
        val t = SoundTiers.DEFAULT_1_9_1
        assertFalse("factory config leaves the bell audible", t.mutesBell)
        assertFalse("factory config keeps an alarm tone", t.weakensAlarm)
    }

    @Test fun mutingEveryBellIsReportedButNotBlocked() {
        // The owner may mute anything on his own bike; the model only reports it.
        val muted = SoundTiers(0, 0, 0)
        assertTrue(muted.mutesBell)
    }

    // --- Clip replace 2.2 ---------------------------------------------------------------------
    private fun fakeVmSound(bytes: Int = 80): ByteArray {
        val out = ByteArray(bytes)
        "VM_SOUND".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 28)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(out, 36)
        return out
    }

    @Test fun slotFiletypesAreSoundIdPlusOne() {
        assertEquals(0x0D, ClipSlot.LOCK.filetype)
        assertEquals(0x0C, ClipSlot.LOCK.soundId)
        assertEquals(ClipSlot.CHARGING, ClipSlot.forSoundId(0x12))
        assertNull(ClipSlot.forSoundId(0x0E))  // unlock-sound-id 0x0D maps, 0x0E is a filetype not a slot id
    }

    @Test fun brickSlotIsFiletypeZero() {
        assertTrue(ClipSlot.isBrickSlot(0))
        assertFalse(ClipSlot.isBrickSlot(ClipSlot.STARTUP.filetype))
    }

    @Test fun metadataIsFiletypeSizeAndCrc() {
        val file = fakeVmSound(80)
        val meta = ClipSlot.metadata(ClipSlot.LOCK, file)
        assertEquals(9, meta.size)
        assertEquals(0x0D.toByte(), meta[0])
        // size BE u32 == 80 (mask the bytes so values above 127 do not sign-extend)
        val size = ((meta[1].toInt() and 0xFF) shl 24) or ((meta[2].toInt() and 0xFF) shl 16) or
            ((meta[3].toInt() and 0xFF) shl 8) or (meta[4].toInt() and 0xFF)
        assertEquals(80, size)
        // last 4 bytes are the big-endian CRC-32 of the file
        assertArrayEquals(crc32BigEndian(file), meta.copyOfRange(5, 9))
    }

    @Test fun metadataSizeDoesNotSignExtendAboveOneTwentySeven() {
        // A size with 0x80+ in a byte would break a shl without an & 0xFF mask; verify the decode holds.
        val file = fakeVmSound(200)  // 0x0000_00C8
        val meta = ClipSlot.metadata(ClipSlot.LOCK, file)
        assertEquals(0xC8.toByte(), meta[4])
        val size = ((meta[1].toInt() and 0xFF) shl 24) or ((meta[2].toInt() and 0xFF) shl 16) or
            ((meta[3].toInt() and 0xFF) shl 8) or (meta[4].toInt() and 0xFF)
        assertEquals(200, size)
        assertArrayEquals(crc32BigEndian(file), meta.copyOfRange(5, 9))
    }

    @Test fun metadataRejectsNonVmSoundAndOversize() {
        try { ClipSlot.metadata(ClipSlot.LOCK, ByteArray(80)); error("plain bytes should throw") } catch (e: IllegalArgumentException) {}
        try { ClipSlot.metadata(ClipSlot.LOCK, ByteArray(0)); error("empty should throw") } catch (e: IllegalArgumentException) {}
        val huge = fakeVmSound(ClipSlot.MAX_CLIP_BYTES + 1)
        try { ClipSlot.metadata(ClipSlot.LOCK, huge); error("oversize should throw") } catch (e: IllegalArgumentException) {}
    }

    @Test fun looksLikeVmSoundNeedsMagicAndRiffWave() {
        assertTrue(ClipSlot.looksLikeVmSound(fakeVmSound()))
        assertFalse(ClipSlot.looksLikeVmSound(ByteArray(80)))
        assertFalse(ClipSlot.looksLikeVmSound("VM_SOUND".toByteArray()))  // too short
    }

    // --- Custom bell slot record --------------------------------------------------------------
    private fun record(name: String = "Ufo", at: Long = 1_792_000_000_000L) = BellSlotRecord(
        name = name,
        bundledId = "UFO",
        seconds = 1.9f,
        wave = intArrayOf(8, 28, 62, 127, 8),
        sentAt = at,
    )

    @Test fun bellSlotRecordRoundTrips() {
        val r = record()
        assertEquals(r, BellSlotRecord.fromProperties(r.toProperties()))
        val picked = BellSlotRecord("my bell", null, 4.2f, null, 1L)
        assertEquals(picked, BellSlotRecord.fromProperties(picked.toProperties()))
    }

    @Test fun bellSlotRecordNeedsNameAndDate() {
        assertNull(BellSlotRecord.fromProperties(java.util.Properties()))
        val noDate = record().toProperties().apply { remove("sentAt") }
        assertNull(BellSlotRecord.fromProperties(noDate))
    }

    @Test fun bellSlotStoreKeepsOneRecordPerBikeAndTheFile() {
        val dir = java.nio.file.Files.createTempDirectory("bellslot").toFile()
        try {
            val store = BellSlotStore(dir)
            val file = ByteArray(64) { it.toByte() }
            val saved = store.save("F8:8A:5E:01:02:03", record(), file)
            assertNotNull(saved.keptId)
            // The MAC is a key, whatever its spelling.
            assertEquals(saved, store.load("f8:8a:5e:01:02:03"))
            assertNull(store.load("00:00:00:00:00:01"))
            assertEquals(listOf(saved.keptId), store.kept().map { it.id })
            assertArrayEquals(file, store.kept().single().file.readBytes())

            // A later upload replaces the record; a broken one after the erase leaves none.
            val later = store.save("F8:8A:5E:01:02:03", record("Tram", at = 1_792_000_100_000L), ByteArray(64) { 7 })
            assertEquals("Tram", store.load("F8:8A:5E:01:02:03")?.name)
            assertEquals(later.keptId, store.kept().first().id)
            store.clear("F8:8A:5E:01:02:03")
            assertNull(store.load("F8:8A:5E:01:02:03"))
            assertEquals(2, store.kept().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun bellSlotStoreKeepsOnlyTheNewestFiles() {
        val dir = java.nio.file.Files.createTempDirectory("bellslot").toFile()
        try {
            val store = BellSlotStore(dir)
            repeat(BellSlotStore.MAX_KEPT + 3) { i ->
                store.save("aa:bb:cc:dd:ee:ff", record("bell $i", at = 1_000L + i), ByteArray(32) { i.toByte() })
            }
            val kept = store.kept()
            assertEquals(BellSlotStore.MAX_KEPT, kept.size)
            assertEquals("bell ${BellSlotStore.MAX_KEPT + 2}", kept.first().name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun bellSampleRateComesFromTheWavHeader() {
        val file = ByteArray(80)
        // 22 050 Hz little-endian at byte 52 (VM_SOUND header 28 bytes + WAV offset 24).
        file[52] = 0x22; file[53] = 0x56
        assertEquals(22_050, BellSlotStore.sampleRate(file))
        assertEquals(0, BellSlotStore.sampleRate(ByteArray(10)))
    }
}
