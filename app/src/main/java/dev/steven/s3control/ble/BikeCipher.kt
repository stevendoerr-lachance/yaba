package dev.steven.s3control.ble

import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-ECB with the per-bike key from the VanMoof account (32 hex chars).
 * Byte layouts mirror mooovy's lib/bike.ts so behaviour matches what is known to work.
 */
class BikeCipher(keyHex: String) {
    private val key = SecretKeySpec(hexToBytes(keyHex).also {
        require(it.size == 16) { "Encryption key must be 32 hex characters" }
    }, "AES")

    fun encrypt(data: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "AES-ECB input must be a multiple of 16 bytes" }
        return Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(data)
        }
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % 16 != 0) return data
        return Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key)
            doFinal(data)
        }
    }

    /** nonce ‖ data ‖ zero padding, encrypted. A full zero block is added when already aligned (as mooovy does). */
    fun sealWrite(nonce: ByteArray, data: ByteArray): ByteArray {
        val pad = 16 - ((nonce.size + data.size) % 16)
        return encrypt(nonce + data + ByteArray(pad))
    }

    /** Challenge response for KEY_INDEX: AES(nonce padded to 16) ‖ 00 00 00 userKeyId. */
    fun authPayload(nonce: ByteArray, userKeyId: Int): ByteArray {
        val block = ByteArray(16)
        nonce.copyInto(block, endIndex = minOf(nonce.size, 16))
        return encrypt(block) + byteArrayOf(0, 0, 0, userKeyId.toByte())
    }

    companion object {
        fun isValidKey(hex: String): Boolean =
            hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim().replace(" ", "")
    require(clean.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Strips trailing zero padding; for text-like values only (a numeric 0 would vanish). */
fun ByteArray.trimTrailingZeros(): ByteArray {
    var end = size
    while (end > 0 && this[end - 1] == 0.toByte()) end--
    return copyOf(end)
}

/** Same bits as npm crc-32 CRC32.buf (signed int32), as unsigned big-endian bytes. */
fun crc32BigEndian(data: ByteArray): ByteArray {
    val v = CRC32().apply { update(data) }.value
    return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
