package dev.steven.s3control.ble

/**
 * The per-sound volume configuration behind characteristic 5572 (SOUND_GROUPS). Each of the 32 sound
 * ids may sit in one of three volume tiers — low (20), medium (30), high (38) — or in none, which
 * makes it silent. The firmware keeps three 32-bit masks; bit n of a mask means "sound id n plays at
 * that tier". When a sound is in several masks, low wins over medium wins over high
 * (decomp mainware app.c:107-116, ble.c:713-749, ble_read.c:625-641).
 *
 * Wire layout (5572): 12 bytes = three big-endian u32 in order [low, medium, high]. Read-modify-write.
 * The 20/30/38 levels themselves are not settable over BLE. The write erases two flash config sectors,
 * so BikeLab only sends it on an explicit save, never per edit.
 *
 * No value here is blocked: on his own bike the owner may mute any sound, the bell included.
 * [mutesBell] / [weakensAlarm] are offered as information for the UI, not as a gate.
 */
data class SoundTiers(val low: Long, val medium: Long, val high: Long) {

    enum class Tier(val resolvedLevel: Int) { LOW(20), MEDIUM(30), HIGH(38), SILENT(0) }

    /** The tier that actually plays for [soundId] (low beats medium beats high; none = silent). */
    fun tierOf(soundId: Int): Tier {
        val bit = 1L shl (soundId and 31)
        return when {
            low and bit != 0L -> Tier.LOW
            medium and bit != 0L -> Tier.MEDIUM
            high and bit != 0L -> Tier.HIGH
            else -> Tier.SILENT
        }
    }

    /** A copy with [soundId] moved to [tier] (and cleared from the other two masks). */
    fun withTier(soundId: Int, tier: Tier): SoundTiers {
        val bit = 1L shl (soundId and 31)
        val clear = bit.inv()
        var l = low and clear
        var m = medium and clear
        var h = high and clear
        when (tier) {
            Tier.LOW -> l = l or bit
            Tier.MEDIUM -> m = m or bit
            Tier.HIGH -> h = h or bit
            Tier.SILENT -> {}
        }
        return SoundTiers(l and MASK32, m and MASK32, h and MASK32)
    }

    /** 12 bytes, three big-endian u32 [low, medium, high], as the characteristic expects. */
    fun encode(): ByteArray {
        val out = ByteArray(12)
        putBe32(out, 0, low)
        putBe32(out, 4, medium)
        putBe32(out, 8, high)
        return out
    }

    /** True if every bell id (0x0A, 0x0B, 0x16, 0x17, 0x18) is silent — a muted bell. Informational only. */
    val mutesBell: Boolean get() = (anyMask() and BELL_MASK) != BELL_MASK
    /** True if the alarm tones (0x0E, 0x0F, 0x1A) are all silent. Informational only. */
    val weakensAlarm: Boolean get() = (anyMask() and ALARM_MASK) == 0L

    private fun anyMask(): Long = low or medium or high

    companion object {
        private const val MASK32 = 0xFFFFFFFFL
        const val BELL_MASK = 0x01C00C00L   // 0x0A, 0x0B, 0x16, 0x17, 0x18
        const val ALARM_MASK = 0x0400C000L  // 0x0E, 0x0F, 0x1A
        const val FIND_MY_MASK = 0x78000320L

        /** Factory tiers for 1.9.1+ (chwdt): low empty, the rest split between medium and high. */
        val DEFAULT_1_9_1 = SoundTiers(0x00000000L, 0xBC3F33FFL, 0x43C0CC00L)

        /** 12 bytes, three big-endian u32; null if the buffer is too short. */
        fun decode(v: ByteArray): SoundTiers? {
            if (v.size < 12) return null
            return SoundTiers(be32(v, 0), be32(v, 4), be32(v, 8))
        }

        private fun be32(v: ByteArray, off: Int): Long {
            var r = 0L
            for (i in 0 until 4) r = (r shl 8) or (v[off + i].toLong() and 0xFF)
            return r
        }

        private fun putBe32(out: ByteArray, off: Int, value: Long) {
            out[off] = (value ushr 24).toByte()
            out[off + 1] = (value ushr 16).toByte()
            out[off + 2] = (value ushr 8).toByte()
            out[off + 3] = value.toByte()
        }
    }
}
