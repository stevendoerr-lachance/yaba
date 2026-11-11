package dev.steven.s3control

import dev.steven.s3control.ble.AlarmStage
import dev.steven.s3control.ble.BellTone
import dev.steven.s3control.ble.BikeCipher
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.CommandGate
import dev.steven.s3control.ble.DefenceState
import dev.steven.s3control.ble.DriverHost
import dev.steven.s3control.ble.Feature
import dev.steven.s3control.ble.GattClient
import dev.steven.s3control.ble.GattException
import dev.steven.s3control.ble.GattPort
import dev.steven.s3control.ble.LightMode
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.S2Driver
import dev.steven.s3control.ble.S2Frame
import dev.steven.s3control.ble.S2Gatt
import dev.steven.s3control.ble.S2Mute
import dev.steven.s3control.ble.S2State
import dev.steven.s3control.ble.Sound
import dev.steven.s3control.ble.SoundGroup
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.Texts
import dev.steven.s3control.ble.UnitSystem
import dev.steven.s3control.ble.featuresFor
import dev.steven.s3control.ble.hexToBytes
import dev.steven.s3control.ble.toHex
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ride.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The S2 driver (the S2 plan B4/B5, step 5) against a fake S2 behind [GattPort]: the bike decrypts every
 * block with the account key, checks that it carries the challenge it just handed out, applies the command
 * the way OldMoofKit's mock bike does (OMT/TestBike.swift:237-282) and answers with its status block.
 */
class S2DriverTest {
    /** "ABCDEFGHIJKLMNOP", OMT/TestBike.swift:18. */
    private val key = "4142434445464748494a4b4c4d4e4f50"

    private val bike = BikeCredentials(
        id = 1, name = "My S2", ownerName = null, mac = "1A:2B:3C:4D:5E:6F",
        encryptionKey = key, userKeyId = 1, bleProfile = "ELECTRIFIED_2018",
    )

    /** Resource ids instead of texts: I18n needs an Android Context. */
    private val texts = object : Texts {
        override fun get(id: Int, vararg args: Any): String = if (args.isEmpty()) "#$id" else "#$id${args.toList()}"
    }

    private fun msg(id: Int, vararg args: Any) = texts.get(id, *args)

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeS2(key: String) : GattPort {
        private val cipher = BikeCipher(key)

        /** Characteristic properties per service; remove entries to model a bike without them. */
        val layout: MutableMap<UUID, MutableMap<UUID, Int>> = mutableMapOf(
            S2Gatt.SERVICE to mutableMapOf(S2Gatt.CHALLENGE to 0x02, S2Gatt.FUNCTIONS to 0x08, S2Gatt.PARAMETERS to 0x12),
            S2Gatt.DEVICE_INFO to mutableMapOf(S2Gatt.FIRMWARE_REVISION to 0x02, S2Gatt.MODEL_NUMBER to 0x02),
        )
        val deviceInfo = mapOf(
            S2Gatt.FIRMWARE_REVISION to "1.04.02".toByteArray() + ByteArray(4),
            S2Gatt.MODEL_NUMBER to "VM-S2".toByteArray(),
        )

        // The bike (OMT/TestBike.swift makeParameters inputs, byte for byte).
        var moduleBits = 0
        var alarm = 2
        var locked = true
        var speed = 0x23
        var motorBattery = 0x42
        var moduleBattery = 0x47
        var light = 2
        var unit = 1
        var level = 1
        var region = 0
        var muted = 0
        var distance = byteArrayOf(12, 34, 56, 78)
        var error = 28
        var runBits = 1
        var passcode: ByteArray? = null
        var backupCode: ByteArray? = null

        /** False: the bike ignores the region half of ASSIST_REGION (a refused region). */
        var takesRegion = true
        /** Set: the bike reports this level whatever ASSIST_REGION sends. */
        var forcedLevel: Int? = null
        /** True: byte 8 holds the raw level, as vanbike-lib reads it (open question A6.3), not YABA's layout. */
        var rawLevelByte = false
        var challengeBytes = 2
        private var nonce = 0x2342
        private var handedOut: ByteArray? = null
        var refreshes = 0
        var notifying = false

        val ops = mutableListOf<Pair<Char, UUID>>()
        val writes = mutableListOf<ByteArray>()
        val withResponse = mutableListOf<Boolean>()

        fun frame(): ByteArray = byteArrayOf(
            0x00, 0x00,
            (moduleBits or (alarm shl 1)).toByte(),
            (if (locked) 0x02 else 0x00).toByte(),
            speed.toByte(), motorBattery.toByte(), moduleBattery.toByte(),
            (light or (unit shl 2)).toByte(),
            (if (rawLevelByte) level else (level shl 2) or region).toByte(),
            0x00,
            (muted shr 6).toByte(),
            distance[0], distance[1], distance[2], distance[3],
            ((error shl 3) or runBits).toByte(),
        )

        fun encryptedFrame(): ByteArray = cipher.encrypt(frame())

        /** The decrypted plaintext of each write, as hex. */
        fun plainWrites(): List<String> = writes.map { cipher.decrypt(it).toHex() }

        override val isReady: Boolean get() = true

        override fun hasService(service: UUID): Boolean = service in layout

        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = layout[service]?.containsKey(characteristic) == true

        override fun properties(service: UUID, characteristic: UUID): Int = layout[service]?.get(characteristic) ?: 0

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray {
            ops += 'R' to characteristic
            if (!hasCharacteristic(service, characteristic)) throw GattException("missing $characteristic")
            return when (characteristic) {
                S2Gatt.CHALLENGE -> {
                    nonce++
                    val c = byteArrayOf((nonce shr 8).toByte(), nonce.toByte(), 0x55, 0x66).copyOf(challengeBytes)
                    handedOut = c.copyOf(2.coerceAtMost(challengeBytes))
                    c
                }
                S2Gatt.PARAMETERS -> encryptedFrame()
                else -> deviceInfo[characteristic] ?: throw GattException("no value")
            }
        }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean) {
            ops += 'W' to characteristic
            assertEquals(S2Gatt.SERVICE, service)
            assertEquals(S2Gatt.FUNCTIONS, characteristic)
            assertEquals("one AES block", 16, value.size)
            val p = cipher.decrypt(value).map { it.toInt() and 0xFF }
            val fresh = handedOut ?: throw AssertionError("write without a fresh challenge")
            assertEquals("the challenge just handed out", fresh.toHex(), cipher.decrypt(value).copyOf(2).toHex())
            handedOut = null
            writes += value
            this.withResponse += withResponse
            when (p[2]) {
                0x01 -> passcode = cipher.decrypt(value).copyOfRange(3, 15)
                0x02 -> if (p[3] == 0) moduleBits = 0x01
                0x03 -> locked = p[3] == 1
                0x04 -> {
                    level = forcedLevel ?: p[3]
                    if (takesRegion) region = p[4]
                }
                0x05 -> light = p[3]
                0x06 -> muted = p[4] shl 6
                0x07 -> unit = p[3]
                0x0B -> backupCode = cipher.decrypt(value).copyOfRange(3, 6)
                0x0F -> alarm = p[3]
                else -> fail("command ${p[2]} is not on the whitelist")
            }
        }

        override suspend fun enableNotifications(service: UUID, characteristic: UUID) {
            assertEquals(S2Gatt.PARAMETERS, characteristic)
            notifying = true
        }

        override fun refreshCache(): Boolean {
            refreshes++
            return true
        }

        override suspend fun rediscover(): Boolean = true
    }

    private class FakeHost(initial: BikeState) : DriverHost {
        override var state: BikeState = initial
        val logs = mutableListOf<Triple<EventLog.Kind, String, String?>>()
        val changes = mutableListOf<Pair<BikeState, BikeState>>()

        override fun update(reduce: (BikeState) -> BikeState) {
            state = reduce(state)
        }

        override fun transition(reduce: (BikeState) -> BikeState): Pair<BikeState, BikeState> {
            val b = state
            state = reduce(b)
            return b to state
        }

        override fun recordChanges(before: BikeState, after: BikeState) {
            changes += before to after
        }

        override fun log(kind: EventLog.Kind, message: String, detail: String?, code: EventLog.Code?) {
            logs += Triple(kind, message, detail)
        }

        override fun onUnlockRequest() {
            fail("the S2 has no unlock request")
        }

        override var paused: Boolean = false
        override val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
        override suspend fun <T> withCommandLock(block: suspend () -> T): T = block()

        /** [onS2Frame]'s pushed flag, block by block. */
        val pushes = mutableListOf<Boolean>()

        override fun onS2Frame(frame: S2Frame, pushed: Boolean) {
            pushes += pushed
        }
    }

    private class Rig(val fake: FakeS2, val host: FakeHost, val driver: S2Driver, var testMode: Boolean = false)

    private fun rig(testMode: Boolean = false, fake: FakeS2 = FakeS2(key)): Rig {
        val host = FakeHost(BikeState(bike = bike, family = BikeFamily.S2, link = Link.READY))
        lateinit var r: Rig
        val driver = S2Driver(fake, host, testMode = { r.testMode }, text = texts, timing = S2Driver.Timing(0, 0, 0, 0, 6))
        r = Rig(fake, host, driver, testMode)
        return r
    }

    /** prepare, authenticate, subscribe, start: what the controller runs on a new link. */
    private fun Rig.connect(): S2Driver.Connection = runBlocking {
        driver.prepare()
        val c = driver.authenticate(this@S2DriverTest.bike)
        c.subscribe()
        c.start()
        c
    }

    private fun Rig.lastWrite(): String = fake.plainWrites().last()

    /** challenge ‖ command ‖ payload, zero padded; the challenge is the fake's counter. */
    private fun block(nonce: Int, hex: String) = ("%04x".format(nonce) + hex).padEnd(32, '0')

    // --- Connection -------------------------------------------------------------------------

    @Test
    fun prepareNeedsTheS2ServiceAndRefreshesTheCacheOnce() = runBlocking {
        val r = rig()
        r.driver.prepare()
        assertEquals(0, r.fake.refreshes)

        r.fake.layout[S2Gatt.SERVICE]!!.remove(S2Gatt.PARAMETERS)
        try {
            r.driver.prepare()
            fail("prepare without the status block")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_not_s2), e.message)
        }
        assertEquals(1, r.fake.refreshes)
        assertTrue("prepare reads and writes nothing", r.fake.ops.isEmpty())
    }

    @Test
    fun authenticationIsOnePasscodeBlockOnAFreshChallenge() = runBlocking {
        val r = rig()
        r.driver.prepare()
        val c = r.driver.authenticate(bike)
        assertEquals(listOf('R' to S2Gatt.CHALLENGE, 'W' to S2Gatt.FUNCTIONS), r.fake.ops)
        assertEquals(block(0x2343, "01" + "4142434445464748494a4b4c"), r.lastWrite())
        assertEquals("4142434445464748494a4b4c", r.fake.passcode!!.toHex())
        assertEquals(listOf(true), r.fake.withResponse)
        assertSame(c.session, r.driver.session)
        assertEquals(msg(R.string.core_log_s2_driver), r.host.logs.single().second)

        r.driver.onLinkDown()
        assertNull("the driver forgets the session when the link goes", r.driver.session)
        assertNotNull("the connection keeps its own", c.session)
    }

    @Test
    fun startReadsDeviceInfoAndDecodesTheOldMoofKitMockFrame() {
        val r = rig()
        r.connect()
        assertTrue(r.fake.notifying)
        val s = r.host.state
        // B8.4 expectations (OMT/TestBike.swift:323-330 and the mock inputs).
        assertEquals("00000402234247060400000c22384ee1", s.raw["1a54"]!!.toHex())
        assertEquals(BikeStatus.STANDBY, s.status)
        assertEquals(true, s.standby)
        assertEquals(AlarmStage.AUTOMATIC, s.alarmStage)
        assertEquals(LockState.LOCKED, s.lock)
        assertEquals(DefenceState.LOCKED, s.defence)
        assertEquals(35, s.speedKmh)
        assertEquals(35, s.speedRawKmh)
        assertEquals(66, s.battery)
        assertEquals(71, s.moduleBattery)
        assertEquals(LightMode.OFF, s.light)
        assertEquals(UnitSystem.IMPERIAL, s.unit)
        assertEquals(1, s.powerLevel)
        assertEquals(SpeedLimit.EU, s.speedLimit)
        assertEquals(0, s.s2MuteMask)
        assertEquals(131230158.0, s.distanceKm!!, 0.0)
        assertEquals(listOf(28), s.faultCodes)
        assertEquals(1, s.s2RunBits)
        // Never set by an S2.
        assertNull(s.supplyOk)
        assertNull(s.buttons)
        assertNull(s.gear)
        assertNull(s.regionLock)
        assertNull(s.chargeState)
        // Device Information, plain text.
        assertEquals("1.4.2", s.firmware)
        assertEquals(listOf(S2Gatt.FIRMWARE_REVISION to "1.4.2", S2Gatt.MODEL_NUMBER to "VM-S2"), s.deviceInfo)
        assertTrue(r.host.logs.any { it.second == msg(R.string.core_log_firmware, "1.4.2") })
    }

    @Test
    fun aSecondLinkReadsTheDeviceInfoOnlyOnce() {
        val r = rig()
        r.connect()
        val reads = r.fake.ops.count { it.second == S2Gatt.FIRMWARE_REVISION }
        r.connect()
        assertEquals(reads, r.fake.ops.count { it.second == S2Gatt.FIRMWARE_REVISION })
    }

    @Test
    fun noWriteWithoutAFullChallenge() = runBlocking {
        val r = rig()
        r.fake.challengeBytes = 1
        try {
            r.driver.authenticate(bike)
            fail("authenticated without a challenge")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_no_challenge), e.message)
        }
        assertTrue(r.fake.writes.isEmpty())
    }

    @Test
    fun writesWithoutResponseWhenTheBikeOffersOnlyThat() = runBlocking {
        val r = rig()
        r.fake.layout[S2Gatt.SERVICE]!![S2Gatt.FUNCTIONS] = 0x04
        r.driver.authenticate(bike)
        assertEquals(listOf(false), r.fake.withResponse)
    }

    @Test
    fun noSubscriptionWithoutNotify() = runBlocking {
        val r = rig()
        r.fake.layout[S2Gatt.SERVICE]!![S2Gatt.PARAMETERS] = 0x02
        val c = r.driver.authenticate(bike)
        assertEquals(0, c.subscribe())
        assertFalse(r.fake.notifying)
    }

    @Test
    fun notificationsDecodeOnlyTheStatusBlock() {
        val r = rig()
        val c = runBlocking { r.driver.authenticate(bike) }
        r.fake.locked = false
        c.onNotification(GattClient.Notification(S2Gatt.CHALLENGE, r.fake.encryptedFrame()))
        assertNull(r.host.state.lock)
        c.onNotification(GattClient.Notification(S2Gatt.PARAMETERS, r.fake.encryptedFrame().copyOf(15)))
        assertNull(r.host.state.lock)
        c.onNotification(GattClient.Notification(S2Gatt.PARAMETERS, r.fake.encryptedFrame()))
        assertEquals(LockState.UNLOCKED, r.host.state.lock)
        assertEquals(1, r.host.changes.size)
    }

    @Test
    fun pollReadsTheStatusBlockAndNeverThrows() = runBlocking {
        val r = rig()
        val c = r.connect()
        r.fake.speed = 18
        c.poll(1)
        assertEquals(18, r.host.state.speedKmh)
        assertEquals(BikeStatus.STANDBY, r.host.state.status)
        r.fake.layout[S2Gatt.SERVICE]!!.remove(S2Gatt.PARAMETERS)
        c.poll(2)
        assertEquals(18, r.host.state.speedKmh)
    }

    // --- Commands ---------------------------------------------------------------------------

    @Test
    fun unlockSendsLock0AndReadsBack() = runBlocking {
        val r = rig()
        val c = r.connect()
        c.unlock()
        assertEquals("0300", r.lastWrite().substring(4, 8))
        assertEquals(LockState.UNLOCKED, r.host.state.lock)
    }

    @Test
    fun remoteLockOnlyInTestMode() = runBlocking {
        val r = rig()
        r.fake.speed = 0
        val c = r.connect()
        r.fake.locked = false
        val writes = r.fake.writes.size
        try {
            c.lock()
            fail("lock without test mode")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_test_mode), e.message)
        }
        assertEquals(writes, r.fake.writes.size)
        r.testMode = true
        c.lock()
        assertEquals("0301", r.lastWrite().substring(4, 8))
        assertEquals(LockState.LOCKED, r.host.state.lock)
    }

    @Test
    fun remoteLockIsRefusedWhileTheBikeRolls() = runBlocking {
        val r = rig(testMode = true)
        r.fake.locked = false
        r.fake.speed = 18
        val c = r.connect()
        assertTrue(r.host.state.moving)
        val writes = r.fake.writes.size
        try {
            c.lock()
            fail("lock while rolling")
        } catch (e: GattException) {
            assertEquals(msg(R.string.s2fix_error_lock_riding), e.message)
        }
        assertEquals("nothing sent to a moving bike", writes, r.fake.writes.size)
        // Standing again: the test-mode lock goes out as before.
        r.fake.speed = 0
        c.poll(0)
        c.lock()
        assertEquals("0301", r.lastWrite().substring(4, 8))
    }

    @Test
    fun aPushedBlockIsHandedOnAsPushed() = runBlocking {
        val r = rig()
        val c = r.connect()
        r.host.pushes.clear()
        c.poll(0)
        c.onNotification(GattClient.Notification(S2Gatt.PARAMETERS, r.fake.encryptedFrame()))
        // The error catcher tells the two apart: read by YABA, then sent by the bike.
        assertEquals(listOf(false, true), r.host.pushes)
    }

    @Test
    fun lightUnitAndAlarm() = runBlocking {
        val r = rig()
        val c = r.connect()
        c.setLight(LightMode.ON)
        assertEquals("0501", r.lastWrite().substring(4, 8))
        assertEquals(LightMode.ON, r.host.state.light)
        c.setUnit(UnitSystem.METRIC)
        assertEquals("0700", r.lastWrite().substring(4, 8))
        assertEquals(UnitSystem.METRIC, r.host.state.unit)
        // ALARM 0x0F waits in test mode for test step 11 (one source, A6.7).
        val writes = r.fake.writes.size
        try {
            c.setAlarm(AlarmStage.DISARMED)
            fail("alarm without test mode")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_test_mode), e.message)
        }
        assertEquals(writes, r.fake.writes.size)
        r.testMode = true
        for ((stage, code) in listOf(AlarmStage.DISARMED to "00", AlarmStage.ARMED to "01", AlarmStage.AUTOMATIC to "02")) {
            c.setAlarm(stage)
            assertEquals("0f$code", r.lastWrite().substring(4, 8))
            assertEquals(stage, r.host.state.alarmStage)
        }
    }

    @Test
    fun regionTravelsWithTheReportedLevelAndIsVerified() = runBlocking {
        val r = rig()
        val c = r.connect()
        c.setSpeedLimit(SpeedLimit.JP)
        assertEquals("040103", r.lastWrite().substring(4, 10))
        assertEquals(SpeedLimit.JP, r.host.state.speedLimit)
        assertEquals(1, r.host.state.powerLevel)
    }

    @Test
    fun regionRefusedWhileTheLevelIsUnknown() = runBlocking {
        val r = rig()
        val c = r.driver.authenticate(bike)
        val writes = r.fake.writes.size
        try {
            c.setSpeedLimit(SpeedLimit.US)
            fail("region without a level")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_level_unknown), e.message)
        }
        assertEquals(writes, r.fake.writes.size)
    }

    @Test
    fun regionRejectedWhenTheBikeKeepsItsOwn() = runBlocking {
        val r = rig()
        val c = r.connect()
        r.fake.takesRegion = false
        val frameReads = r.fake.ops.count { it.second == S2Gatt.PARAMETERS }
        try {
            c.setSpeedLimit(SpeedLimit.US)
            fail("region the bike refused")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_speed_rejected, 32, "25"), e.message)
        }
        assertEquals("read back once, then six retries", frameReads + 1 + 6, r.fake.ops.count { it.second == S2Gatt.PARAMETERS })
    }

    @Test
    fun offroadOnlyInTestMode() = runBlocking {
        val r = rig()
        val c = r.connect()
        val writes = r.fake.writes.size
        try {
            c.setSpeedLimit(SpeedLimit.NO_LIMIT)
            fail("off-road without test mode")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_test_mode), e.message)
        }
        assertEquals(writes, r.fake.writes.size)
        r.testMode = true
        c.setSpeedLimit(SpeedLimit.NO_LIMIT)
        assertEquals("040102", r.lastWrite().substring(4, 10))
        assertEquals(SpeedLimit.NO_LIMIT, r.host.state.speedLimit)
        // Test mode off again: a level change would re-send the reported off-road region, so it is refused too.
        r.testMode = false
        val before = r.fake.writes.size
        try {
            c.setPowerLevel(3)
            fail("off-road re-sent without test mode")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_offroad_level), e.message)
        }
        assertEquals(before, r.fake.writes.size)
        r.testMode = true
        c.setPowerLevel(3)
        assertEquals("040302", r.lastWrite().substring(4, 10))
    }

    @Test
    fun aMisreadByte8NeverTurnsALevelTapIntoOffroad() = runBlocking {
        // vanbike-lib's layout: byte 8 = raw level 2, which YABA decodes as region 2 (off-road), level 0.
        val r = rig()
        r.fake.rawLevelByte = true
        r.fake.level = 2
        val c = r.connect()
        assertEquals(SpeedLimit.NO_LIMIT, r.host.state.speedLimit)
        val writes = r.fake.writes.size
        try {
            c.setPowerLevel(3)
            fail("level tap sent the off-road region")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_offroad_level), e.message)
        }
        assertEquals("nothing written", writes, r.fake.writes.size)
    }

    @Test
    fun aLevelChangeThatMovesTheRegionIsReported() = runBlocking {
        // Raw byte 8 = 1 decodes as region US, level 0; sending [0, US] makes it 0 = EU.
        val r = rig()
        r.fake.rawLevelByte = true
        r.fake.level = 1
        val c = r.connect()
        assertEquals(SpeedLimit.US, r.host.state.speedLimit)
        assertEquals(0, r.host.state.powerLevel)
        try {
            c.setPowerLevel(0)
            fail("region moved silently")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_pair_mismatch, 32, 0, "25", "0"), e.message)
        }
        assertEquals("040001", r.lastWrite().substring(4, 10))
    }

    @Test
    fun aRegionChangeThatMovesTheLevelIsReported() = runBlocking {
        val r = rig()
        val c = r.connect()
        r.fake.forcedLevel = 3
        try {
            c.setSpeedLimit(SpeedLimit.JP)
            fail("level moved silently")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_pair_mismatch, 24, 1, "24", "3"), e.message)
        }
        assertEquals("040103", r.lastWrite().substring(4, 10))
        assertEquals(SpeedLimit.JP, r.host.state.speedLimit)
    }

    @Test
    fun levelTravelsWithTheReportedRegion() = runBlocking {
        val r = rig()
        val c = r.connect()
        c.setSpeedLimit(SpeedLimit.US)
        c.setPowerLevel(4)
        assertEquals("040401", r.lastWrite().substring(4, 10))
        assertEquals(4, r.host.state.powerLevel)
        assertEquals(SpeedLimit.US, r.host.state.speedLimit)
        val writes = r.fake.writes.size
        for (bad in listOf(5, -1)) {
            try {
                c.setPowerLevel(bad)
                fail("level $bad")
            } catch (e: GattException) {
                assertEquals(msg(R.string.core_error_level_rejected, bad, "4"), e.message)
            }
        }
        assertEquals(writes, r.fake.writes.size)
    }

    @Test
    fun wakeIsModuleState0AndRefusedWhileMoving() = runBlocking {
        val r = rig()
        val c = r.connect()
        r.fake.speed = 0
        c.poll(0)
        c.wake()
        assertEquals("0200", r.lastWrite().substring(4, 8))
        assertEquals(BikeStatus.READY, r.host.state.status)
        assertEquals(false, r.host.state.standby)

        r.fake.speed = 12
        c.poll(1)
        assertEquals(BikeStatus.RIDING, r.host.state.status)
        val writes = r.fake.writes.size
        try {
            c.wake()
            fail("wake while moving")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_wake_riding), e.message)
        }
        assertEquals(writes, r.fake.writes.size)
    }

    @Test
    fun wakeSendsNothingToABikeThatIsAlreadyOn() = runBlocking {
        val r = rig()
        r.fake.speed = 0
        r.fake.moduleBits = 0x01
        val c = r.connect()
        val writes = r.fake.writes.size
        c.wake()
        assertEquals(writes, r.fake.writes.size)
    }

    @Test
    fun wakeReadsTheStatusFirstWhenTheLinkHasNone() = runBlocking {
        val r = rig()
        r.fake.speed = 0
        val c = r.driver.authenticate(bike)
        c.wake()
        assertEquals("0200", r.lastWrite().substring(4, 8))
        assertEquals(
            "status read, then the wake on a fresh challenge, then the read-back",
            listOf('R' to S2Gatt.PARAMETERS, 'R' to S2Gatt.CHALLENGE, 'W' to S2Gatt.FUNCTIONS, 'R' to S2Gatt.PARAMETERS),
            r.fake.ops.takeLast(4),
        )
    }

    @Test
    fun wakeFromTrackingOrSleepingOnlyInTestMode() = runBlocking {
        for (bits in listOf(0x10, 0x20)) {
            val r = rig()
            r.fake.speed = 0
            r.fake.moduleBits = bits
            val c = r.connect()
            val writes = r.fake.writes.size
            try {
                c.wake()
                fail("wake from 0x%02x without test mode".format(bits))
            } catch (e: GattException) {
                assertEquals(msg(R.string.core_error_s2_test_mode), e.message)
            }
            assertEquals(writes, r.fake.writes.size)
            r.testMode = true
            c.wake()
            assertEquals("0200", r.lastWrite().substring(4, 8))
        }
    }

    @Test
    fun backupCodeOnlyInTestModeOnlyDigits1To9AndNeverCleared() = runBlocking {
        val r = rig()
        val c = r.connect()
        val writes = r.fake.writes.size
        suspend fun refused(code: String?, expected: String) {
            try {
                c.setBackupCode(code)
                fail("backup code $code")
            } catch (e: GattException) {
                assertEquals(expected, e.message)
            }
        }
        refused("123", msg(R.string.core_error_s2_test_mode))
        r.testMode = true
        refused(null, msg(R.string.core_error_not_on_this_bike))
        refused("120", msg(R.string.core_error_backup_digits))
        refused("12", msg(R.string.core_error_backup_digits))
        assertEquals(writes, r.fake.writes.size)
        c.setBackupCode("123")
        assertEquals("0b010203", r.lastWrite().substring(4, 12))
        assertEquals("010203", r.fake.backupCode!!.toHex())
    }

    @Test
    fun muteOnlyInTestMode() = runBlocking {
        val r = rig()
        val c = r.connect()
        try {
            c.setMutedSounds(S2Mute.LOCK_STATE or S2Mute.MODULE_STATE)
            fail("mute without test mode")
        } catch (e: GattException) {
            assertEquals(msg(R.string.core_error_s2_test_mode), e.message)
        }
        r.testMode = true
        c.setMutedSounds(S2Mute.LOCK_STATE or S2Mute.MODULE_STATE)
        // B8.5: mask 0x3300 travels as [00, cc].
        assertEquals("0600cc", r.lastWrite().substring(4, 10))
        assertEquals(0x3300, r.host.state.s2MuteMask)
    }

    @Test
    fun soundsBellAndBikeLogAreNotOnAnS2() = runBlocking {
        val r = rig()
        val c = r.connect()
        val writes = r.fake.writes.size
        val notHere = msg(R.string.core_error_not_on_this_bike)
        for (call in listOf<suspend () -> Unit>(
            { c.setBell(BellTone.BELL) },
            { c.play(Sound(0x16, "Ding", SoundGroup.HORN)) },
        )) {
            try {
                call()
                fail("sound on an S2")
            } catch (e: GattException) {
                assertEquals(notHere, e.message)
            }
        }
        var uploaded: Boolean? = null
        runCatching { c.uploadBell(ByteArray(4)) { uploaded = it } }
        assertEquals(false, uploaded)
        var log: String? = "unset"
        runCatching { c.readBikeLog { log = it } }
        assertNull(log)
        assertEquals(writes, r.fake.writes.size)
    }

    @Test
    fun everyWriteIsAWhitelistedBlockOnFunctionsAfterItsChallenge() {
        val r = rig(testMode = true)
        r.fake.speed = 0
        val c = r.connect()
        runBlocking {
            c.unlock()
            c.lock()
            c.setLight(LightMode.AUTO)
            c.setAlarm(AlarmStage.ARMED)
            c.setSpeedLimit(SpeedLimit.EU)
            c.setPowerLevel(2)
            c.setUnit(UnitSystem.METRIC)
            c.wake()
            c.setBackupCode("987")
            c.setMutedSounds(0)
            c.readDiagnostics()
            c.refresh(includeVersions = true)
        }
        val touched = r.fake.ops.map { it.second }.toSet()
        assertTrue("never a forbidden UUID: $touched", touched.none { it in S2Gatt.forbidden })
        assertTrue(r.fake.ops.filter { it.first == 'W' }.all { it.second == S2Gatt.FUNCTIONS })
        r.fake.ops.forEachIndexed { i, (op, _) ->
            if (op == 'W') assertEquals("challenge right before write $i", 'R' to S2Gatt.CHALLENGE, r.fake.ops[i - 1])
        }
        val commands = r.fake.plainWrites().map { it.substring(4, 6) }
        assertEquals(listOf("01", "03", "03", "05", "0f", "04", "04", "07", "02", "0b", "06"), commands)
    }

    // --- Test-mode frame log ----------------------------------------------------------------

    @Test
    fun testModeLogsEachChangeOfTheStatusBlockOnce() = runBlocking {
        val r = rig(testMode = true)
        val c = r.connect()
        val frameLogs = { r.host.logs.filter { it.second.startsWith("#${R.string.core_log_s2_frame}") } }
        assertEquals(1, frameLogs().size)
        assertEquals(msg(R.string.core_log_s2_frame, "00000402234247060400000c22384ee1"), frameLogs()[0].second)
        assertEquals(msg(R.string.core_detail_s2_frame_changed, (0..15).joinToString(",")), frameLogs()[0].third)
        c.poll(0)
        assertEquals("same block, no new line", 1, frameLogs().size)
        c.unlock()
        assertEquals(2, frameLogs().size)
        assertEquals(msg(R.string.core_detail_s2_frame_changed, "3"), frameLogs()[1].third)
    }

    @Test
    fun anImplausibleFirstBlockWarnsOfAWrongKeyOncePerLink() = runBlocking {
        val r = rig()
        r.fake.motorBattery = 200
        val c = r.connect()
        val alerts = { r.host.logs.filter { it.first == EventLog.Kind.ALERT }.map { it.second } }
        assertEquals(listOf(msg(R.string.core_log_s2_implausible, 200, 0x47, 0x23)), alerts())
        assertEquals("shown clamped, after the warning", 100, r.host.state.battery)
        r.fake.speed = 99
        c.poll(0)
        assertEquals("once per link", 1, alerts().size)
        r.connect()
        assertEquals("a new link checks again", 2, alerts().size)
    }

    @Test
    fun aPlausibleBlockRaisesNoAlert() {
        val r = rig()
        r.connect()
        assertTrue(r.host.logs.none { it.first == EventLog.Kind.ALERT })
    }

    @Test
    fun testModeTagsImplausibleBlocksInTheFrameLog() = runBlocking {
        val r = rig(testMode = true)
        r.fake.moduleBattery = 0xFF
        val c = r.connect()
        val frameLogs = { r.host.logs.filter { it.second.startsWith("#${R.string.core_log_s2_frame}") } }
        assertEquals(msg(R.string.core_detail_s2_frame_implausible, (0..15).joinToString(",")), frameLogs()[0].third)
        r.fake.moduleBattery = 50
        c.poll(0)
        assertEquals(msg(R.string.core_detail_s2_frame_changed, "6"), frameLogs()[1].third)
    }

    @Test
    fun noFrameLogOutsideTestMode() = runBlocking {
        val r = rig()
        val c = r.connect()
        c.unlock()
        assertTrue(r.host.logs.none { it.second.startsWith("#${R.string.core_log_s2_frame}") })
    }

    // --- Frame to BikeState (plan B5) --------------------------------------------------------

    private fun frame(vararg set: Pair<Int, Int>) = S2Frame.parse(ByteArray(16).also { b -> set.forEach { (i, v) -> b[i] = v.toByte() } })!!

    @Test
    fun moduleStateMapsToStatusInOldMoofKitsOrder() {
        val st = BikeState(family = BikeFamily.S2)
        assertEquals(BikeStatus.READY, S2State.apply(st, frame(2 to 0x01)).status)
        assertEquals(BikeStatus.RIDING, S2State.apply(st, frame(2 to 0x01, 4 to 3)).status)
        assertEquals(BikeStatus.READY, S2State.apply(st, frame(2 to 0x31, 4 to 2)).status)
        val tracking = S2State.apply(st, frame(2 to 0x30, 3 to 0x02))
        assertEquals(BikeStatus.ALARM, tracking.status)
        assertEquals(DefenceState.TRACKING, tracking.defence)
        assertEquals(BikeStatus.SLEEPING, S2State.apply(st, frame(2 to 0x20)).status)
        val standby = S2State.apply(st, frame())
        assertEquals(BikeStatus.STANDBY, standby.status)
        assertEquals(true, standby.standby)
        assertEquals(DefenceState.UNLOCKED, standby.defence)
        assertEquals(false, S2State.apply(st, frame(2 to 0x01)).standby)
    }

    @Test
    fun outOfRangeValuesAreNotInvented() {
        val st = BikeState(family = BikeFamily.S2, alarmStage = AlarmStage.ARMED, light = LightMode.ON, powerLevel = 2)
        val s = S2State.apply(st, frame(2 to (5 shl 1), 7 to 0x03, 8 to (7 shl 2), 4 to 200, 5 to 150, 6 to 101))
        assertEquals("unknown alarm code keeps the last one, as the S3 ALARM_MODE decode", AlarmStage.ARMED, s.alarmStage)
        assertNull("light code 3 is no light mode", s.light)
        assertNull("level 7 is no S2 level", s.powerLevel)
        assertEquals(99, s.speedKmh)
        assertEquals(100, s.battery)
        assertEquals(100, s.moduleBattery)
        assertEquals(emptyList<Int>(), s.faultCodes)
        assertEquals(SpeedLimit.EU, s.speedLimit)
    }

    @Test
    fun faultsUseTheFamilysOwnTable() {
        assertEquals(R.string.core_fault_s2_16_name, BikeState(family = BikeFamily.S2).describeFault(16).nameRes)
        assertEquals(R.string.core_fault_16_name, BikeState(family = BikeFamily.S3).describeFault(16).nameRes)
        assertEquals(R.string.core_fault_16_name, BikeState().describeFault(16).nameRes)
        assertEquals(R.string.core_fault_unknown_name, BikeState(family = BikeFamily.S2).describeFault(31).nameRes)
    }

    // --- Gates (plan B2, B8.12) -------------------------------------------------------------

    @Test
    fun firmwareGatesOnlyForTheS3Family() {
        val s2 = BikeState(family = BikeFamily.S2, firmware = "1.6.0")
        assertFalse(s2.firmwareHasLevel5)
        assertFalse(s2.firmwareHasNoLimit)
        for (f in listOf(BikeFamily.S3, BikeFamily.UNKNOWN)) {
            val s3 = BikeState(family = f, firmware = "1.6.0")
            assertTrue(s3.firmwareHasLevel5)
            assertTrue(s3.firmwareHasNoLimit)
        }
        // A BikeState built without a family (debug demo) keeps today's gates.
        assertTrue(BikeState(firmware = "1.6.0").firmwareHasLevel5)
        assertFalse(BikeState(firmware = "1.9.3").firmwareHasLevel5)
    }

    @Test
    fun commandGateLetsEveryS3CommandThrough() {
        val s3Needs = listOf(
            Feature.UNLOCK, Feature.REMOTE_LOCK, Feature.LIGHT, Feature.REGION, Feature.POWER_LEVEL, Feature.UNIT,
            Feature.WAKE, Feature.BACKUP_CODE_SET, Feature.BACKUP_CODE_CLEAR, Feature.BELL_TONE, Feature.SOUNDBOARD,
            Feature.CUSTOM_BELL, Feature.BIKE_LOG, null,
        )
        for (family in listOf(BikeFamily.S3, BikeFamily.UNKNOWN)) for (tm in listOf(false, true)) for (n in s3Needs) {
            assertEquals("$family $n", CommandGate.OK, CommandGate.of(family, tm, n))
        }
    }

    @Test
    fun commandGateForTheS2() {
        for (n in listOf(Feature.UNLOCK, Feature.LIGHT, Feature.REGION, Feature.POWER_LEVEL, Feature.UNIT, Feature.WAKE, null)) {
            assertEquals("$n", CommandGate.OK, CommandGate.of(BikeFamily.S2, false, n))
        }
        for (n in listOf(Feature.REMOTE_LOCK, Feature.BACKUP_CODE_SET, Feature.MUTE_SOUNDS, Feature.ALARM_THREE_WAY)) {
            assertEquals("$n", CommandGate.TEST_MODE_ONLY, CommandGate.of(BikeFamily.S2, false, n))
            assertEquals("$n", CommandGate.OK, CommandGate.of(BikeFamily.S2, true, n))
        }
        for (n in listOf(Feature.BELL_TONE, Feature.SOUNDBOARD, Feature.CUSTOM_BELL, Feature.BIKE_LOG, Feature.BACKUP_CODE_CLEAR)) {
            assertEquals("$n", CommandGate.UNSUPPORTED, CommandGate.of(BikeFamily.S2, true, n))
        }
        assertEquals(CommandGate.UNSUPPORTED, CommandGate.of(BikeFamily.S3, true, Feature.ALARM_THREE_WAY))
        assertEquals(CommandGate.UNSUPPORTED, CommandGate.of(BikeFamily.S3, true, Feature.MUTE_SOUNDS))
        assertEquals(CommandGate.UNSUPPORTED, CommandGate.of(BikeFamily.LEGACY_UNSUPPORTED, true, Feature.UNLOCK))
    }

    @Test
    fun anOlderVanMoofGetsTheSameRefusalBeforeOrAfterTheCacheRefresh() {
        // BikeController.chooseDriver and S3Driver.prepare's "not an S3" branch both use this (plan B3.3).
        assertEquals(R.string.core_error_older_vanmoof, BikeFamily.UNKNOWN.sharedServiceRefusal)
        assertEquals(R.string.core_error_legacy_unsupported, BikeFamily.LEGACY_UNSUPPORTED.sharedServiceRefusal)
        assertNull(BikeFamily.S3.sharedServiceRefusal)
        assertNull(BikeFamily.S2.sharedServiceRefusal)
    }

    @Test
    fun anS3LeftAtTheManualFormsDefaultStillGetsTheGarageHint() {
        // chooseDriver and notAnS3 read BikeCredentials.refusalFamily: a stored S3 with no profile may be an S2 whose
        // owner kept the form's default, so it refuses as UNKNOWN did; a profile or an S2 pick decides as before
        fun bike(chosen: BikeFamily?, profile: String?) =
            BikeCredentials(null, "b", null, "AA:BB:CC:DD:EE:FF", key, 1, bleProfile = profile, chosenFamily = chosen)
        assertEquals(R.string.core_error_older_vanmoof, bike(BikeFamily.S3, null).refusalFamily.sharedServiceRefusal)
        assertEquals(R.string.core_error_older_vanmoof, bike(null, null).refusalFamily.sharedServiceRefusal)
        assertEquals(BikeFamily.S3, bike(BikeFamily.S3, null).family)
        // an S3 picked over a tentative S2 profile is a real choice: no refusal, the S3 driver reports as always
        assertNull(bike(BikeFamily.S3, "ELECTRIFIED_2019").refusalFamily.sharedServiceRefusal)
        assertNull(bike(null, "ELECTRIFIED_2022").refusalFamily.sharedServiceRefusal)
        assertEquals(BikeFamily.S2, bike(BikeFamily.S2, null).refusalFamily)
        assertEquals(R.string.core_error_legacy_unsupported, bike(null, "SMARTBIKE_2018").refusalFamily.sharedServiceRefusal)
    }

    @Test
    fun s3DecodingIsUnchangedByTheNewEnumValues() {
        // ALARM_MODE: 0/1 only, anything else keeps the last value (S3Driver: `AlarmStage.from(b0) ?: st.alarmStage`).
        assertEquals(AlarmStage.DISARMED, AlarmStage.from(0))
        assertEquals(AlarmStage.ARMED, AlarmStage.from(1))
        assertNull(AlarmStage.from(2))
        assertNull(AlarmStage.from(null))
        assertTrue(AlarmStage.AUTOMATIC.armed)
        // MODULE_MODE: a byte never decodes as STANDBY.
        assertTrue((0..255).none { BikeStatus.from(it) == BikeStatus.STANDBY })
        assertEquals(featuresFor(BikeFamily.UNKNOWN, false), BikeState().features)
    }

    // --- Resources ------------------------------------------------------------------------------

    @Test
    fun s2StringsExistInAllSevenLocales() {
        val res = File("src/main/res")
        assumeTrue("run from the app module", res.isDirectory)
        val nameRe = Regex("""<string name="([^"]+)">([^<]*)</string>""")
        val locales = listOf("values", "values-de", "values-fr", "values-hr", "values-it", "values-nl", "values-ru")
        val byLocale = locales.associateWith { d ->
            nameRe.findAll(File(res, "$d/strings_s2.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        }
        val names = byLocale.getValue("values").keys
        for ((d, strings) in byLocale) {
            assertEquals(d, names, strings.keys)
            assertTrue("$d key change label carries no marker of its own", strings.getValue("core_char_s2_key_change").endsWith("(S2)"))
            for ((k, v) in strings) {
                val args = Regex("""%(\d)\$""").findAll(v).map { it.groupValues[1] }.toSet()
                val enArgs = Regex("""%(\d)\$""").findAll(byLocale.getValue("values").getValue(k)).map { it.groupValues[1] }.toSet()
                assertEquals("$d $k placeholders", enArgs, args)
            }
        }
    }

    @Test
    fun theExampleKeyIsAValidAccountKey() {
        assertTrue(BikeCipher.isValidKey(key))
        assertEquals(16, hexToBytes(key).size)
        assertArrayEquals(hexToBytes("4142434445464748494a4b4c"), hexToBytes(key).copyOf(12))
    }
}
