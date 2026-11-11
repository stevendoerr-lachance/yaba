package dev.steven.s3control.ble

import dev.steven.s3control.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The GATT operations the S2 driver needs: [GattClient] in the app ([GattClientPort]), a fake bike in JVM tests.
 * Same meaning as the [GattClient] methods of the same name.
 */
interface GattPort {
    val isReady: Boolean
    fun hasService(service: UUID): Boolean
    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean
    fun properties(service: UUID, characteristic: UUID): Int
    suspend fun read(service: UUID, characteristic: UUID): ByteArray
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean)
    suspend fun enableNotifications(service: UUID, characteristic: UUID)
    fun refreshCache(): Boolean
    suspend fun rediscover(): Boolean
}

/** [GattPort] on the app's [GattClient], with its default timeouts. */
class GattClientPort(private val gatt: GattClient) : GattPort {
    override val isReady: Boolean get() = gatt.isReady
    override fun hasService(service: UUID): Boolean = gatt.hasService(service)
    override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = gatt.hasCharacteristic(service, characteristic)
    override fun properties(service: UUID, characteristic: UUID): Int = gatt.properties(service, characteristic)
    override suspend fun read(service: UUID, characteristic: UUID): ByteArray = gatt.read(service, characteristic)
    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) =
        gatt.write(service, characteristic, value, withResponse = withResponse)
    override suspend fun enableNotifications(service: UUID, characteristic: UUID) = gatt.enableNotifications(service, characteristic)
    override fun refreshCache(): Boolean = gatt.refreshCache()
    override suspend fun rediscover(): Boolean = gatt.rediscover()
}

/**
 * An authenticated conversation with one S2/X2 (the S2 plan B4). EXPERIMENTAL: not yet run on a real S2.
 *
 * Every command is one AES block on [S2Gatt.FUNCTIONS], sealed with a challenge read fresh from
 * [S2Gatt.CHALLENGE] right before it; the pair runs under one lock so no other write consumes the nonce
 * (OldMoofKit Bike.swift:280-292). Only [S2Write]s go out, and only S2Write's own builders can make one
 * (private constructor), so commands and payloads outside the plan's whitelist cannot be sent. The session addresses the S2 service and Device Information
 * only; [S2Gatt.forbidden] (key change, firmware update) is refused even if a caller asked for it.
 */
class S2Session(private val port: GattPort, keyHex: String, private val text: Texts) {
    private val cipher = BikeCipher(keyHex)
    private val passcode = S2Codec.passcode(keyHex)
    private val lock = Mutex()

    fun has(characteristic: UUID): Boolean = port.hasCharacteristic(S2Gatt.SERVICE, characteristic)

    /** PASSCODE with the first 12 key bytes. The bike sends no acknowledgement (plan B4). */
    suspend fun authenticate() = send(passcode)

    suspend fun send(w: S2Write) = lock.withLock { writeUnlocked(w) }

    /** Write, wait [waitMs], read the status block back: atomically, as [BikeSession.writeThenRead]. */
    suspend fun sendThenRead(w: S2Write, waitMs: Long): S2Frame? = lock.withLock {
        writeUnlocked(w)
        if (waitMs > 0) delay(waitMs)
        readFrameUnlocked()
    }

    /** The status block; null when the bike's value is not one whole AES block. */
    suspend fun readFrame(): S2Frame? = lock.withLock { readFrameUnlocked() }

    /** A pushed [S2Gatt.PARAMETERS] value; null unless it is whole AES blocks. */
    fun decode(value: ByteArray): S2Frame? = S2Codec.decryptFrame(cipher, value)

    /** Notifications on the status block, if the bike offers them. */
    suspend fun subscribe(): Boolean {
        if (!has(S2Gatt.PARAMETERS)) return false
        if (port.properties(S2Gatt.SERVICE, S2Gatt.PARAMETERS) and (PROPERTY_NOTIFY or PROPERTY_INDICATE) == 0) return false
        return runCatching { port.enableNotifications(S2Gatt.SERVICE, S2Gatt.PARAMETERS) }.isSuccess
    }

    /** Device Information values the bike has (plain, unencrypted), in [S2Gatt.deviceInfo] order. */
    suspend fun readDeviceInfo(): List<Pair<UUID, ByteArray>> = buildList {
        for (id in S2Gatt.deviceInfo) {
            if (!port.hasCharacteristic(S2Gatt.DEVICE_INFO, id)) continue
            runCatching { readWithRetry(S2Gatt.DEVICE_INFO, id) }.getOrNull()?.let { add(id to it) }
        }
    }

    private suspend fun readFrameUnlocked(): S2Frame? = decode(readWithRetry(S2Gatt.SERVICE, S2Gatt.PARAMETERS))

    private suspend fun writeUnlocked(w: S2Write) {
        val challenge = readWithRetry(S2Gatt.SERVICE, S2Gatt.CHALLENGE)
        if (challenge.size < S2Codec.CHALLENGE_SIZE) throw GattException(text.get(R.string.core_error_no_challenge))
        val block = S2Codec.block(cipher, challenge, w)
        // With response where the bike offers it (OldMoofKit always asks for one); otherwise without.
        val withResponse = port.properties(S2Gatt.SERVICE, S2Gatt.FUNCTIONS) and PROPERTY_WRITE != 0
        guarded(S2Gatt.FUNCTIONS)
        port.write(S2Gatt.SERVICE, S2Gatt.FUNCTIONS, block, withResponse)
    }

    /** Three tries while the link is up, as [BikeSession] reads. */
    private suspend fun readWithRetry(service: UUID, characteristic: UUID): ByteArray {
        guarded(characteristic)
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                return port.read(service, characteristic)
            } catch (e: GattException) {
                lastError = e
                if (!port.isReady) throw e
                delay(120L * (attempt + 1))
            }
        }
        throw lastError ?: GattException(text.get(R.string.core_error_read_failed, characteristic.toString()))
    }

    private fun guarded(characteristic: UUID) {
        check(characteristic !in S2Gatt.forbidden) { "S2: $characteristic is never touched" }
    }

    companion object {
        /** BluetoothGattCharacteristic.PROPERTY_* bits (kept here so the session runs on the plain JVM). */
        const val PROPERTY_WRITE = 0x08
        const val PROPERTY_NOTIFY = 0x10
        const val PROPERTY_INDICATE = 0x20
    }
}
