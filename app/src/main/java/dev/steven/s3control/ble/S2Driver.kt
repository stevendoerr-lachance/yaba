package dev.steven.s3control.ble

import dev.steven.s3control.R
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ride.EventLog
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * The VanMoof Electrified S2/X2 driver (service 8e7f1a50, [S2Gatt]; the S2 plan B4/B5). EXPERIMENTAL: built
 * from OldMoofKit's Electrified2018Profile and not yet run against a real S2.
 *
 * The whole state is one encrypted 16-byte status block ([S2Frame]): read after authentication, pushed by the
 * bike, polled, and read back after every command. Commands not yet confirmed on a real S2 (remote lock, mute,
 * backup code, the off-road region, the alarm; plan A1 "Test") are refused unless [testMode] is on, here as well
 * as in the controller's feature gate (the alarm on/off switch has no feature there, so only here). In test mode
 * every change of the status block is logged in full, so the ride logger's CSV holds the whole test session.
 */
class S2Driver(
    private val port: GattPort,
    private val host: DriverHost,
    private val testMode: () -> Boolean,
    private val text: Texts = Texts.App,
    private val timing: Timing = Timing(),
) : BikeDriver {

    /** Waits around commands (plan B4); tests pass zeros. */
    data class Timing(
        /** After a command, before the status block is read back. */
        val settleMs: Long = 300,
        /** After LOCK (unlock or lock), as the S3 driver waits for its lock. */
        val lockMs: Long = 800,
        /** After the wake command, as the S3 driver waits. */
        val wakeMs: Long = 500,
        /** Read-back retries for region and level, as the S3 driver's region loop. */
        val retryMs: Long = 350,
        val retries: Int = 6,
    )

    override val family: BikeFamily get() = BikeFamily.S2

    /** The authenticated session while the link is up; null between links. */
    @Volatile var session: S2Session? = null
        private set

    /** The S2 service with its three characteristics; one cache refresh before giving up, as the S3 driver does. */
    override suspend fun prepare() {
        if (!complete()) {
            port.refreshCache()
            port.rediscover()
            if (!complete()) throw GattException(text.get(R.string.core_error_not_s2))
        }
        // No MTU request: one 16-byte block fits the default MTU (plan B4).
    }

    private fun complete(): Boolean = port.hasService(S2Gatt.SERVICE) &&
        listOf(S2Gatt.CHALLENGE, S2Gatt.FUNCTIONS, S2Gatt.PARAMETERS).all { port.hasCharacteristic(S2Gatt.SERVICE, it) }

    override suspend fun authenticate(bike: BikeCredentials): Connection {
        val s = S2Session(port, bike.encryptionKey.trim(), text)
        s.authenticate()
        session = s
        host.log(EventLog.Kind.INFO, text.get(R.string.core_log_s2_driver))
        return Connection(s)
    }

    override fun onLinkDown() {
        session = null
    }

    /** One authenticated S2 link; every call runs on [session]. */
    inner class Connection internal constructor(val session: S2Session) : BikeConnection {
        override val driver: BikeDriver get() = this@S2Driver

        /** The last status block of this link, for the test-mode change log. */
        private var lastFrame: S2Frame? = null
        private val frameLock = Any()

        override suspend fun subscribe(): Int = if (session.subscribe()) 1 else 0

        override fun onNotification(n: GattClient.Notification) {
            if (n.characteristic != S2Gatt.PARAMETERS) return
            val f = session.decode(n.value) ?: return
            runCatching { apply(f, pushed = true) }
        }

        /** No connect-time writes: Device Information (once per bike), then the status block (OldMoofKit setupConnection). */
        override suspend fun start() = refresh(includeVersions = host.state.deviceInfo.isEmpty())

        override suspend fun refresh(includeVersions: Boolean) {
            if (includeVersions) readDeviceInfo()
            runCatching { readFrame() }
        }

        /** The status block holds everything, so one read per turn. */
        override suspend fun poll(tick: Int) {
            runCatching { readFrame() }
        }

        override suspend fun readDiagnostics() {
            readDeviceInfo()
            runCatching { readFrame() }
        }

        /** LOCK 0. The kick lock opens once the rider rolls the bike while the unlock animation runs (M). */
        override suspend fun unlock() {
            session.send(S2Codec.unlock())
            delay(timing.lockMs)
            apply(frame(session.readFrame()))
        }

        /**
         * LOCK 1: effect unverified (L), test mode only, and never while the bike rolls: an unknown command on a
         * moving bike could sound the alarm or cut the assist in traffic (and would muddle the dropout diagnosis).
         */
        override suspend fun lock() {
            requireTestMode()
            if (host.state.moving) throw GattException(text.get(R.string.s2fix_error_lock_riding))
            session.send(S2Codec.lock())
            delay(timing.lockMs)
            apply(frame(session.readFrame()))
        }

        override suspend fun setLight(mode: LightMode) {
            apply(frame(session.sendThenRead(S2Codec.light(mode), timing.settleMs)))
        }

        /**
         * ALARM 0x0F: [AlarmStage.ARMED] is the S2's "manual". Test mode only until test step 11 confirms it:
         * 0x0F has a single source (OldMoofKit E18:52; vanbike-lib's commands end at 0x0D), writing it is M (A6.7).
         */
        override suspend fun setAlarm(stage: AlarmStage) {
            requireTestMode()
            val mode = when (stage) {
                AlarmStage.DISARMED -> S2Alarm.OFF
                AlarmStage.ARMED -> S2Alarm.MANUAL
                AlarmStage.AUTOMATIC -> S2Alarm.AUTOMATIC
            }
            apply(frame(session.sendThenRead(S2Codec.alarm(mode), timing.settleMs)))
        }

        /**
         * ASSIST_REGION with the level the bike reports: the S2 sets both in one command, so a region change
         * needs the level (OldMoofKit ignores the call then; YABA says why). Off-road (37 km/h) is test mode only.
         * The level must read back unchanged too ([checkPair]).
         */
        override suspend fun setSpeedLimit(limit: SpeedLimit) {
            val region = S2Region.of(limit)
            if (region == S2Region.OFFROAD) requireTestMode()
            val level = host.state.powerLevel ?: throw GattException(text.get(R.string.core_error_s2_level_unknown))
            apply(frame(session.sendThenRead(S2Codec.assistRegion(level, region), timing.settleMs)))
            awaitState { it.speedLimit == limit && it.powerLevel == level }
            val got = host.state.speedLimit
            if (got != limit) {
                throw GattException(text.get(R.string.core_error_speed_rejected, limit.kmh, got?.kmh?.toString() ?: UNKNOWN))
            }
            checkPair(limit, level)
        }

        /**
         * ASSIST_REGION with the region the bike reports. Levels 0..4. Re-sending a reported off-road region is
         * writing off-road, so it needs test mode as [setSpeedLimit] does: byte 8's layout is open (A6.3), and a
         * level misread as region 2 must not turn a level tap into 37 km/h. The region must read back unchanged.
         */
        override suspend fun setPowerLevel(level: Int) {
            if (level !in 0..S2Codec.MAX_LEVEL) {
                throw GattException(text.get(R.string.core_error_level_rejected, level, host.state.powerLevel?.toString() ?: UNKNOWN))
            }
            val region = host.state.speedLimit?.let(S2Region::of) ?: throw GattException(text.get(R.string.core_error_s2_region_unknown))
            if (region == S2Region.OFFROAD && !testMode()) throw GattException(text.get(R.string.core_error_s2_offroad_level))
            apply(frame(session.sendThenRead(S2Codec.assistRegion(level, region), timing.settleMs)))
            awaitState { it.powerLevel == level && it.speedLimit == region.limit }
            val got = host.state.powerLevel
            if (got != level) throw GattException(text.get(R.string.core_error_level_rejected, level, got?.toString() ?: UNKNOWN))
            checkPair(region.limit, level)
        }

        override suspend fun setUnit(unit: UnitSystem) {
            apply(frame(session.sendThenRead(S2Codec.unit(unit), timing.settleMs)))
        }

        /**
         * MODULE_STATE 0 (on), the only module state YABA sends. Refused while moving, as on the S3. Sent from
         * standby, as OldMoofKit does (Bike.swift:459-465); a bike that is already on needs nothing. What "on"
         * does to a tracking or sleeping S2 is unverified, so from there it is test mode only.
         */
        override suspend fun wake() {
            if (host.state.moving) throw GattException(text.get(R.string.core_error_wake_riding))
            val current = synchronized(frameLock) { lastFrame } ?: frame(session.readFrame()).also { apply(it) }
            when (current.moduleState) {
                S2ModuleState.STANDBY -> Unit
                S2ModuleState.ON -> return
                S2ModuleState.TRACKING, S2ModuleState.SLEEPING -> requireTestMode()
            }
            session.send(S2Codec.wake())
            delay(timing.wakeMs)
            runCatching { apply(frame(session.readFrame())) }
        }

        /** BACKUP_CODE 0x0B: three digits 1..9, test mode only (its meaning is disputed). The S2 cannot clear it. */
        override suspend fun setBackupCode(code: String?) {
            if (code == null) notOnThisBike()
            requireTestMode()
            if (!S2Codec.isValidBackupCode(code)) throw GattException(text.get(R.string.core_error_backup_digits))
            session.send(S2Codec.backupCode(code))
            delay(timing.settleMs)
            runCatching { apply(frame(session.readFrame())) }
        }

        /** MUTE 0x06 with [S2Mute] bits; test mode only (vanbike-lib calls 0x06 "pair remote"). */
        override suspend fun setMutedSounds(mask: Int) {
            requireTestMode()
            apply(frame(session.sendThenRead(S2Codec.mute(mask), timing.settleMs)))
        }

        override suspend fun setBell(tone: BellTone): Unit = notOnThisBike()

        override suspend fun play(sound: Sound): Unit = notOnThisBike()

        override suspend fun uploadBell(file: ByteArray, onDone: (Boolean) -> Unit) {
            onDone(false)
            notOnThisBike()
        }

        override suspend fun readBikeLog(onDone: (String?) -> Unit) {
            onDone(null)
            notOnThisBike()
        }

        private suspend fun readFrame() = apply(frame(session.readFrame()))

        /** Re-reads the status block until [done] holds, as the S3 driver's region loop does. */
        private suspend fun awaitState(done: (BikeState) -> Boolean) {
            var retries = 0
            while (!done(host.state) && retries++ < timing.retries) {
                delay(timing.retryMs)
                apply(frame(session.readFrame()))
            }
        }

        /** Device Information strings (plan B4): the versions the bike page lists, firmware from 2A26. */
        private suspend fun readDeviceInfo() {
            val values = session.readDeviceInfo()
            val strings = values.mapNotNull { (id, v) -> v.versionString()?.takeIf { it.isNotBlank() }?.let { id to it } }
            val fw = strings.firstOrNull { it.first == S2Gatt.FIRMWARE_REVISION }?.second
            host.update { st ->
                st.copy(
                    deviceInfo = strings,
                    firmware = fw ?: st.firmware,
                    raw = st.raw + values.map { (id, v) -> rawKey(id) to v },
                )
            }
            fw?.let { host.log(EventLog.Kind.INFO, text.get(R.string.core_log_firmware, it)) }
        }

        /**
         * One status block into the state; in test mode, a change of it is logged in full first, tagged when it is
         * not [S2Frame.plausible]. The first block of a link is checked before anything is clamped: the bike never
         * acknowledges PASSCODE, so an implausible first block is the one sign of a wrong key. [pushed]: it came as a
         * notification, not as the answer to a read (the error catcher reports how often the bike pushes).
         */
        private fun apply(f: S2Frame, pushed: Boolean = false) {
            val previous = synchronized(frameLock) { lastFrame.also { lastFrame = f } }
            if (previous == null && !f.plausible) {
                host.log(EventLog.Kind.ALERT, text.get(R.string.core_log_s2_implausible, f.motorBattery, f.moduleBattery, f.speed))
            }
            val (before, after) = host.transition { S2State.apply(it, f) }
            host.onS2Frame(f, pushed)
            if (testMode() && previous != f) {
                val changed = f.changedBytes(previous).joinToString(",")
                host.log(
                    EventLog.Kind.INFO,
                    text.get(R.string.core_log_s2_frame, f.hex),
                    text.get(if (f.plausible) R.string.core_detail_s2_frame_changed else R.string.core_detail_s2_frame_implausible, changed),
                )
            }
            host.recordChanges(before, after)
        }

        /**
         * Region and level travel together, so both halves must read back as sent. If one moved, byte 8 is not
         * laid out as the decoder assumes (A6.3), and the rider learns that the other setting changed too.
         */
        private fun checkPair(limit: SpeedLimit, level: Int) {
            val st = host.state
            if (st.speedLimit == limit && st.powerLevel == level) return
            throw GattException(
                text.get(
                    R.string.core_error_s2_pair_mismatch,
                    limit.kmh, level, st.speedLimit?.kmh?.toString() ?: UNKNOWN, st.powerLevel?.toString() ?: UNKNOWN,
                ),
            )
        }

        private fun frame(f: S2Frame?): S2Frame = f ?: throw GattException(text.get(R.string.core_error_s2_frame))

        private fun requireTestMode() {
            if (!testMode()) throw GattException(text.get(R.string.core_error_s2_test_mode))
        }

        private fun notOnThisBike(): Nothing = throw GattException(text.get(R.string.core_error_not_on_this_bike))
    }

    private companion object {
        const val UNKNOWN = "?"
    }
}

/** The S2 status block as [BikeState] (plan B5). Pure, so it is unit-tested. */
object S2State {
    /** [BikeState.raw] key of the status block, the characteristic's short id like the S3 keys. */
    val RAW_KEY: String = rawKey(S2Gatt.PARAMETERS)

    fun apply(st: BikeState, f: S2Frame): BikeState {
        val speed = f.speed.coerceIn(0, 99)
        return st.copy(
            status = when (f.moduleState) {
                S2ModuleState.ON -> if (speed >= 3) BikeStatus.RIDING else BikeStatus.READY
                S2ModuleState.TRACKING -> BikeStatus.ALARM
                S2ModuleState.SLEEPING -> BikeStatus.SLEEPING
                S2ModuleState.STANDBY -> BikeStatus.STANDBY
            },
            standby = f.moduleState == S2ModuleState.STANDBY,
            defence = when {
                f.moduleState == S2ModuleState.TRACKING -> DefenceState.TRACKING
                f.locked -> DefenceState.LOCKED
                else -> DefenceState.UNLOCKED
            },
            alarmStage = when (f.alarm) {
                S2Alarm.OFF -> AlarmStage.DISARMED
                S2Alarm.MANUAL -> AlarmStage.ARMED
                S2Alarm.AUTOMATIC -> AlarmStage.AUTOMATIC
                null -> st.alarmStage
            },
            lock = if (f.locked) LockState.LOCKED else LockState.UNLOCKED,
            speedRawKmh = speed,
            speedKmh = speed,
            battery = f.motorBattery.coerceAtMost(100),
            moduleBattery = f.moduleBattery.coerceAtMost(100),
            light = f.light,
            unit = f.unit,
            powerLevel = f.powerLevel,
            speedLimit = f.region.limit,
            s2MuteMask = f.muteMask,
            s2RunBits = f.runBits,
            distanceKm = f.distanceKm,
            faultCodes = f.faultCodes,
            raw = st.raw + (RAW_KEY to f.bytes),
        )
    }
}

/** A characteristic's short id ("1a54", "2a26"), as [Gatt.Char.short]. */
internal fun rawKey(id: UUID): String = id.toString().substring(4, 8)
