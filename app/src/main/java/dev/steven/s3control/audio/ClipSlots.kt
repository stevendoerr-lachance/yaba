package dev.steven.s3control.audio

import dev.steven.s3control.ble.crc32BigEndian

/**
 * Which bike sound clips YABA is allowed to overwrite, and how to frame the upload metadata.
 *
 * Replacing a clip uses the same OAD transport as the custom bell (5511 metadata, 5512 blocks,
 * 5513 status) but a different target: `filetype = clip index + 1` (decomp bleware oad.c:172-189,
 * audiotask.c:51-55). Only the five harmless slots below are offered — lock, unlock, startup,
 * shutdown and charging. Two technical reasons bound this, not the law:
 *  - filetype 0 is the firmware / bootloader PACK slot; writing it bricks the bike. [isBrickSlot] /
 *    hard block.
 *  - the bike erases a slot the moment it accepts the metadata and cannot read the original back, so
 *    the bell, alarm, Find-My and error tones (your only audible theft defence and fault signal) are
 *    kept off this list, and every replacement must stash the current file locally first.
 *
 * The payload must be a VM_SOUND container (as produced by [BellConverter]); [looksLikeVmSound]
 * sanity-checks one before it goes near the bike. Max 512 KiB (oad.c slot capacity).
 */
enum class ClipSlot(val soundId: Int, val filetype: Int, val labelResName: String) {
    LOCK(0x0C, 0x0D, "lab_clip_lock"),
    UNLOCK(0x0D, 0x0E, "lab_clip_unlock"),
    STARTUP(0x10, 0x11, "lab_clip_startup"),
    SHUTDOWN(0x11, 0x12, "lab_clip_shutdown"),
    CHARGING(0x12, 0x13, "lab_clip_charging");

    companion object {
        const val MAX_CLIP_BYTES = 512 * 1024
        /** filetype 0 lands on the bootloader/firmware PACK slot — never writable. */
        const val BRICK_FILETYPE = 0

        fun isBrickSlot(filetype: Int): Boolean = filetype == BRICK_FILETYPE

        fun forSoundId(soundId: Int): ClipSlot? = entries.firstOrNull { it.soundId == soundId }

        /**
         * Metadata for 5511: [filetype, size BE u32, CRC-32 BE u32] (9 bytes), matching the bell upload.
         * Throws if the target is the brick slot or the file is empty / oversized / not a VM_SOUND clip.
         */
        fun metadata(slot: ClipSlot, file: ByteArray): ByteArray {
            require(!isBrickSlot(slot.filetype)) { "refusing filetype 0 (bootloader slot)" }
            require(file.isNotEmpty()) { "empty clip" }
            require(file.size <= MAX_CLIP_BYTES) { "clip larger than 512 KiB" }
            require(looksLikeVmSound(file)) { "not a VM_SOUND clip" }
            val size = file.size
            return byteArrayOf(
                slot.filetype.toByte(),
                (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
            ) + crc32BigEndian(file)
        }

        /** True if [file] starts with the "VM_SOUND" magic and carries a RIFF/WAVE payload. */
        fun looksLikeVmSound(file: ByteArray): Boolean {
            if (file.size < 24 + 4 + 44) return false
            val magic = String(file.copyOfRange(0, 8), Charsets.US_ASCII)
            if (magic != "VM_SOUND") return false
            // The WAV begins right after the 24-byte VM_SOUND header + 4-byte length.
            val riff = String(file.copyOfRange(28, 32), Charsets.US_ASCII)
            val wave = String(file.copyOfRange(36, 40), Charsets.US_ASCII)
            return riff == "RIFF" && wave == "WAVE"
        }
    }
}
