package dev.steven.s3control.ble

import android.bluetooth.BluetoothGattCharacteristic
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.data.BikeCredentials
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An authenticated, encrypted conversation with one bike on top of [GattClient].
 * Every encrypted write reads a fresh challenge first; the pair runs under one lock
 * so no other write can consume the nonce in between.
 */
class BikeSession(private val gatt: GattClient, val bike: BikeCredentials) {
    private val cipher = BikeCipher(bike.encryptionKey)
    private val lock = Mutex()

    suspend fun authenticate() = lock.withLock {
        val nonce = gatt.read(Gatt.CHALLENGE.service, Gatt.CHALLENGE.id)
        if (nonce.isEmpty()) throw GattException(I18n.get(R.string.core_error_no_challenge))
        gatt.write(Gatt.KEY_INDEX.service, Gatt.KEY_INDEX.id, cipher.authPayload(nonce, bike.userKeyId))
    }

    fun has(ch: Gatt.Char) = gatt.hasCharacteristic(ch.service, ch.id)

    fun canNotify(ch: Gatt.Char): Boolean {
        val p = gatt.properties(ch.service, ch.id)
        return p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    suspend fun read(ch: Gatt.Char): ByteArray = lock.withLock { readUnlocked(ch) }

    suspend fun write(ch: Gatt.Char, data: ByteArray) = lock.withLock { writeUnlocked(ch, data) }

    /** Write, optionally wait, read back — atomically, as mooovy does for speed limit and power. */
    suspend fun writeThenRead(ch: Gatt.Char, data: ByteArray, waitMs: Long = 0, reads: Int = 1): ByteArray =
        lock.withLock {
            writeUnlocked(ch, data)
            if (waitMs > 0) delay(waitMs)
            var last = ByteArray(0)
            repeat(reads.coerceAtLeast(1)) { last = readUnlocked(ch) }
            last
        }

    /** Plain chunk stream to an unencrypted characteristic (bell upload). */
    suspend fun writeRaw(ch: Gatt.Char, data: ByteArray) = lock.withLock {
        gatt.write(ch.service, ch.id, data)
    }

    suspend fun subscribe(ch: Gatt.Char): Boolean {
        if (!has(ch) || !canNotify(ch)) return false
        return runCatching { gatt.enableNotifications(ch.service, ch.id) }.isSuccess
    }

    suspend fun requestMtu(mtu: Int) = runCatching { gatt.requestMtu(mtu) }.getOrDefault(23)

    /** Decrypts a pushed value if it belongs to an encrypted characteristic. */
    fun decode(ch: Gatt.Char, value: ByteArray): ByteArray =
        if (ch.encrypted && value.isNotEmpty() && value.size % 16 == 0) cipher.decrypt(value) else value

    private suspend fun readUnlocked(ch: Gatt.Char): ByteArray {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val raw = gatt.read(ch.service, ch.id)
                return if (ch.encrypted) cipher.decrypt(raw) else raw
            } catch (e: GattException) {
                lastError = e
                if (!gatt.isReady) throw e
                delay(120L * (attempt + 1))
            }
        }
        throw lastError ?: GattException(I18n.get(R.string.core_error_read_failed, ch.label))
    }

    private suspend fun writeUnlocked(ch: Gatt.Char, data: ByteArray) {
        if (ch.encrypted) {
            val nonce = gatt.read(Gatt.CHALLENGE.service, Gatt.CHALLENGE.id)
            gatt.write(ch.service, ch.id, cipher.sealWrite(nonce, data))
        } else {
            gatt.write(ch.service, ch.id, data)
        }
    }
}
