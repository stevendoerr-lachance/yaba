package dev.steven.s3control.ble

import android.content.Context
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ride.EventLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The S3/X3 driver (6acc55xx services, [Gatt]): everything [BikeController] did that is specific to this
 * protocol, moved here without changing it (the S2 plan B2, step 3). The service check, MTU and the
 * [BikeSession] authentication; the subscriptions, the poll batches and the refresh; the decoding of every
 * characteristic; every command. Shifting ([ShiftCommands], [ShiftJournal]), Speed++ ([SpeedPlusStore]) and
 * the session the Bike-Lab uses ([BikeController.featureSession]) live here too.
 *
 * One instance per controller for the whole process, like the fields it came from: [ShiftCommands] spaces its
 * flash writes across reconnects, and the debug demo runs the shifting commands (and so the decoding) with no
 * link at all. [session] is the controller's old `session` field: set by each authentication, cleared when the
 * link goes ([onLinkDown]). Each link's [Connection] keeps its own session, so the collector, the poll loop and
 * every queued command run on the session they started with, exactly as the controller's lambdas did.
 */
class S3Driver(context: Context, private val gatt: GattClient, private val host: DriverHost) : BikeDriver {

    override val family: BikeFamily get() = BikeFamily.S3

    /** The authenticated session while the link is up; null between links (the controller's old field). */
    @Volatile var session: BikeSession? = null
        private set

    /** Mode and shift point writes (spacing, cap, fresh reads), testable without Android. */
    private val shifting = ShiftCommands(
        state = { host.state },
        apply = { ch, v -> apply(ch, v) },
        onCheck = { check -> host.update { it.copy(shiftCheck = check) } },
    )
    private val shiftJournal = ShiftJournal.get(context)

    /** Debug builds: a stand-in bike for the shifting commands while a demo state is shown. */
    @Volatile var demoShiftLink: ShiftLink? = null

    private val speedPlusStore = SpeedPlusStore(context.applicationContext)

    /** The bike's factory wheel size for Speed++ ([SpeedPlus.stock]); [deviceModel] is its 5549, [current] its 5564. */
    fun wheelStock(bike: BikeCredentials?, deviceModel: String?, current: Int?): WheelStock =
        speedPlusStore.stock(bike, deviceModel, current)

    /** The wheel size YABA read last from [bike], on any connection. */
    fun lastWheelSize(bike: BikeCredentials?): Int? = speedPlusStore.lastRead(bike)

    /**
     * The owner's answer to "28″ (S3) or 24″ (X3)" ([SpeedPlusView.ASK]): stored per bike, and the stock and the speed factor
     * of a state that holds this bike follow at once. Writes nothing to the bike.
     */
    fun setStockWheel(bike: BikeCredentials, wheel: Int) {
        speedPlusStore.setAnswer(bike, wheel)
        // Under the apply lock, like a decoded 5564: a read arriving at the same moment cannot undo the answer.
        host.transition { st ->
            if (st.bike?.normalizedMac != bike.normalizedMac) return@transition st
            val next = withWheel(st, st.wheelSize)
            // A link that is down shows no speed; the answer does not bring one back.
            if (st.speedKmh == null) next.copy(speedKmh = null) else next
        }
    }

    /** [wheel] as the bike's 5564 with the stock and the factor that go with it, and the speed scaled by that factor. */
    private fun withWheel(st: BikeState, wheel: Int?): BikeState {
        val deviceModel = st.versionValues.firstOrNull { it.first == Gatt.MODEL }?.second
        val stock = speedPlusStore.stock(st.bike, deviceModel, wheel)
        val factor = SpeedPlus.factor(stock.wheel, wheel)
        return st.copy(wheelSize = wheel, wheelStock = stock, speedFactor = factor, speedKmh = SpeedPlus.realKmh(st.speedRawKmh, factor))
    }

    // --- Connection ---------------------------------------------------------------------------

    override suspend fun prepare() {
        if (!gatt.hasService(Gatt.SECURITY) || !gatt.hasService(Gatt.MOVEMENT)) {
            gatt.refreshCache()
            gatt.rediscover()
            if (!gatt.hasService(Gatt.SECURITY)) throw notAnS3()
        }
        gatt.requestMtu(517)
    }

    /**
     * The error once the refreshed services still lack the S3 one: "not an S3", as always, unless an older
     * VanMoof's 8e7f1a50 only showed up after the refresh. That bike gets the driver choice's message for its
     * profile ([BikeFamily.sharedServiceRefusal], plan B3.3). Failure branch only; nothing is read or written.
     */
    private fun notAnS3(): Exception {
        val refusal = if (gatt.hasService(S2Gatt.SERVICE)) host.state.bike?.refusalFamily?.sharedServiceRefusal else null
        // A refusal is final for this bike ([BikeRefusedException]); "not an S3" stays the ordinary, retried error.
        return if (refusal != null) BikeRefusedException(I18n.get(refusal)) else GattException(I18n.get(R.string.core_error_not_s3))
    }

    override suspend fun authenticate(bike: BikeCredentials): Connection {
        val s = BikeSession(gatt, bike)
        // The bike drops unauthenticated links after 30 s, so authenticate before anything else.
        s.authenticate()
        session = s
        shifting.newConnection()
        return Connection(s)
    }

    override fun onLinkDown() {
        session = null
    }

    /** Shifting values from an earlier session are not shown as current: read them fresh. */
    override fun onReady(st: BikeState): BikeState = st.copy(
        gear = null, shiftMode = null, shiftPoints = null, shiftPointsRegion = null,
        shiftCheck = null, shiftNotice = null,
    )

    /**
     * One authenticated S3 link. Everything runs on [session], the session this link authenticated: the
     * collector, the poll loop and each command queued while it was live (the controller's old lambdas).
     */
    inner class Connection internal constructor(val session: BikeSession) : BikeConnection {
        override val driver: BikeDriver get() = this@S3Driver

        override suspend fun subscribe(): Int = this@S3Driver.subscribe(session)

        override fun onNotification(n: GattClient.Notification) = this@S3Driver.onNotification(session, n)

        override suspend fun start() = this@S3Driver.start(session)

        override suspend fun refresh(includeVersions: Boolean) = refreshAll(session, includeVersions = includeVersions)

        override suspend fun poll(tick: Int) = this@S3Driver.poll(session, tick)

        override suspend fun readDiagnostics() = this@S3Driver.readDiagnostics(session)

        override suspend fun unlock() = this@S3Driver.unlock(session)

        override suspend fun lock() = this@S3Driver.lock(session)

        override suspend fun setLight(mode: LightMode) = this@S3Driver.setLight(session, mode)

        override suspend fun setAlarm(stage: AlarmStage) = this@S3Driver.setAlarm(session, stage)

        override suspend fun setSpeedLimit(limit: SpeedLimit) = this@S3Driver.setSpeedLimit(session, limit)

        override suspend fun setPowerLevel(level: Int) = this@S3Driver.setPowerLevel(session, level)

        override suspend fun setUnit(unit: UnitSystem) = this@S3Driver.setUnit(session, unit)

        override suspend fun wake() = this@S3Driver.wake(session)

        override suspend fun setBackupCode(code: String?) = this@S3Driver.setBackupCode(session, code)

        override suspend fun setBell(tone: BellTone) = this@S3Driver.setBell(session, tone)

        override suspend fun play(sound: Sound) = this@S3Driver.play(session, sound)

        override suspend fun uploadBell(file: ByteArray, onDone: (Boolean) -> Unit) = this@S3Driver.uploadBell(session, file, onDone)

        override suspend fun readBikeLog(onDone: (String?) -> Unit) = this@S3Driver.readBikeLog(session, onDone)

        /** Speed++ ([SpeedPlus]); S3 only. */
        suspend fun setWheelSize(wheel: Int) = this@S3Driver.setWheelSize(session, wheel)

        /** POWER_LEVEL byte 1 for the [BikeLab]; S3 only. */
        suspend fun setBoostInMotion(enabled: Boolean, onDone: (String?) -> Unit) =
            this@S3Driver.setBoostInMotion(session, enabled, onDone)

        /** One sound clip for the [BikeLab]; S3 only. */
        suspend fun uploadClip(metadata: ByteArray, file: ByteArray, onProgress: (Float) -> Unit, onDone: (Boolean) -> Unit) =
            this@S3Driver.uploadClip(session, metadata, file, onProgress, onDone)

        /** The Wache's fresh read ([BikeController.guardCommand]): read, apply to the state, hand the bytes back. */
        suspend fun guardRead(ch: Gatt.Char): ByteArray {
            val v = session.read(ch)
            apply(ch, v)
            return v
        }

        /**
         * The Wache's write: only LOCK_STATE (5521), ALARM_STATE (5523) and ALARM_MODE (5524), one byte. MODULE_STATE
         * (5562) and everything else is refused here, whatever the caller asks.
         */
        suspend fun guardWrite(ch: Gatt.Char, value: Byte) {
            require(ch == Gatt.LOCK_STATE || ch == Gatt.ALARM_STATE || ch == Gatt.ALARM_MODE) { "guard writes 5521, 5523 or 5524 only" }
            session.write(ch, byteArrayOf(value))
        }
    }

    private suspend fun subscribe(s: BikeSession): Int {
        var count = 0
        for (ch in Gatt.live) if (s.subscribe(ch)) count++
        return count
    }

    private fun onNotification(s: BikeSession, n: GattClient.Notification) {
        val ch = Gatt.find(n.characteristic) ?: return
        runCatching { apply(ch, s.decode(ch, n.value), pushed = true) }
    }

    private suspend fun start(s: BikeSession) {
        // Ask the bike to hand its log lines to the app link (log_by_app), so the internal log keeps filling.
        if (s.has(Gatt.LOG_MODE)) runCatching { s.write(Gatt.LOG_MODE, byteArrayOf(1)) }
        refreshAll(s, includeVersions = host.state.versions.isEmpty(), onConnect = true)
    }

    private suspend fun poll(s: BikeSession, tick: Int) {
        // ERRORS is read actively: the bike holds its notification back up to 8 s while bits 18/20 are set.
        // Gear and shift mode notifications are gated in firmware (GPIO + throttle): poll them too.
        // Shift points (5537) are never polled; they only change through a write or a region change.
        // While the Wache is armed: 5523 and the battery line every turn, whether or not they notify (the guard design 2.2).
        val fast = if (host.guardWatch) GUARD_FAST else listOf(Gatt.ERRORS, Gatt.SPEED, Gatt.MODULE_MODE, Gatt.LOCK_STATE, Gatt.SUPPLY_OK, Gatt.BUTTONS, Gatt.E_SHIFTER_GEAR)
        val slow = listOf(Gatt.MOTOR_BATTERY_LEVEL, Gatt.DISTANCE, Gatt.MOTOR_BATTERY_STATE, Gatt.ALARM_MODE, Gatt.ALARM_STATE, Gatt.LIGHT_MODE, Gatt.MODULE_STATE, Gatt.E_SHIFTER_MODE)
        val batch = if (tick % 6 == 0) fast + slow else fast
        for (ch in batch) {
            if (!s.has(ch)) continue
            runCatching { apply(ch, s.read(ch)) }
        }
    }

    private suspend fun refreshAll(s: BikeSession, includeVersions: Boolean, onConnect: Boolean = false) {
        if (includeVersions) {
            val versions = ArrayList<Pair<Gatt.Char, String>>()
            for (ch in Gatt.versions) {
                if (!s.has(ch)) continue
                val v = runCatching { s.read(ch) }.getOrNull() ?: continue
                val text = v.versionString()
                if (!text.isNullOrBlank()) versions += ch to text
            }
            val fw = versions.firstOrNull { it.first == Gatt.BIKE_FIRMWARE }?.second
            host.update { it.copy(versionValues = versions, firmware = fw ?: it.firmware) }
            fw?.let { host.log(EventLog.Kind.INFO, I18n.get(R.string.core_log_firmware, it)) }
        }
        // First, so the real speed is right from the first SPEED value (Speed++ survives app restarts).
        if (onConnect && s.has(Gatt.WHEEL_SIZE)) runCatching { apply(Gatt.WHEEL_SIZE, s.read(Gatt.WHEEL_SIZE)) }
        for (ch in Gatt.live) {
            if (!s.has(ch) || ch == Gatt.UNLOCK_REQUEST || ch == Gatt.OAD_STATUS) continue
            runCatching { apply(ch, s.read(ch)) }
        }
        // After the live loop, so speedLimit is known: 5537 belongs to the region slot in use.
        // The bike's state is the only truth: read, match, show. Never write on our own.
        val supported = s.has(Gatt.E_SHIFTER_MODE) && s.has(Gatt.E_SHIFT_POINTS)
        host.update { it.copy(shiftSupported = supported) }
        if (supported) {
            val modeRaw = runCatching { s.read(Gatt.E_SHIFTER_MODE) }.getOrNull()?.also { apply(Gatt.E_SHIFTER_MODE, it) }
            val pointsRaw = runCatching { s.read(Gatt.E_SHIFT_POINTS) }.getOrNull()?.also { apply(Gatt.E_SHIFT_POINTS, it) }
            val check = ShiftCheck.of(modeRaw, pointsRaw)
            host.update { it.copy(shiftCheck = check) }
            val st = host.state
            val unknown = I18n.get(R.string.shift_unknown)
            val points = st.shiftPointsCurrent?.let { p -> st.shiftProfile?.label ?: p.short() } ?: unknown
            host.log(EventLog.Kind.INFO, shiftWhat(I18n.get(R.string.shift_log_state, st.shiftMode?.label ?: unknown, points)))
            if (check != ShiftCheck.OK) {
                host.log(EventLog.Kind.ALERT, I18n.get(R.string.shift_check_failed, st.firmware ?: unknown, ShiftCheck.detail(modeRaw, pointsRaw)))
            } else if (onConnect) {
                checkLastShiftWrite(st)
            }
        }
    }

    /**
     * A read-back right after a write only proves RAM: the firmware ignores whether its flash write
     * worked. The first read of a later connection is the check, compared with what YABA wrote then.
     */
    private fun checkLastShiftWrite(st: BikeState) {
        val mac = st.bike?.normalizedMac ?: return
        val written = shiftJournal.get(mac) ?: return
        val kept = checkKept(written, st.shiftMode, st.speedLimit, st.shiftPointsCurrent)
        val fw = st.firmware ?: UNKNOWN
        val notices = buildList {
            if (kept.mode == false) {
                add(I18n.get(R.string.shift_persist_mode_lost, written.mode?.label ?: UNKNOWN, st.shiftMode?.label ?: UNKNOWN))
            }
            if (kept.points == false) {
                add(I18n.get(R.string.shift_persist_points_lost, written.points?.short() ?: UNKNOWN, st.shiftPointsCurrent?.short() ?: UNKNOWN))
            }
        }
        if (kept.mode == true || kept.points == true) host.log(EventLog.Kind.INFO, I18n.get(R.string.shift_persist_ok, fw))
        for (n in notices) host.log(EventLog.Kind.ALERT, n, I18n.get(R.string.core_log_firmware, fw))
        if (notices.isNotEmpty()) host.update { it.copy(shiftNotice = notices.joinToString(" ")) }
        shiftJournal.settle(mac, kept)
    }

    /** A shifting log line with the bike's firmware: the handlers were only ever read for 1.07.06. */
    fun shiftWhat(text: String): String =
        host.state.firmware?.let { I18n.get(R.string.shift_log_fw, text, it) } ?: text

    /** Re-reads 5537 (cheap: a read never touches flash). */
    private suspend fun readShiftPoints(s: BikeSession) {
        if (s.has(Gatt.E_SHIFT_POINTS)) runCatching { apply(Gatt.E_SHIFT_POINTS, s.read(Gatt.E_SHIFT_POINTS)) }
    }

    /** Reads every diagnosis characteristic once and stores the raw bytes. */
    private suspend fun readDiagnostics(s: BikeSession) {
        for (ch in Gatt.diagnostics) {
            if (!s.has(ch)) continue
            runCatching { apply(ch, s.read(ch)) }
        }
    }

    // --- Decoding -----------------------------------------------------------------------------

    private fun decodeInto(st: BikeState, ch: Gatt.Char, v: ByteArray, pushed: Boolean): BikeState {
        val b0 = v.u8()
        val next = when (ch) {
            Gatt.LOCK_STATE -> st.copy(lock = LockState.from(b0 ?: 0))
            Gatt.ALARM_STATE -> st.copy(defence = DefenceState.from(b0 ?: 0))
            Gatt.ALARM_MODE -> st.copy(alarmStage = AlarmStage.from(b0 ?: 0) ?: st.alarmStage)
            Gatt.SPEED -> v.uLE(minOf(v.size, 4)).toInt().coerceIn(0, 99).let { raw ->
                st.copy(speedRawKmh = raw, speedKmh = SpeedPlus.realKmh(raw, st.speedFactor))
            }
            Gatt.WHEEL_SIZE -> b0?.let { wheel ->
                speedPlusStore.rememberRead(st.bike, wheel)
                withWheel(st, wheel)
            } ?: st
            Gatt.DISTANCE -> if (v.size >= 4) st.copy(distanceKm = v.uLE(4) / 10.0) else st
            Gatt.POWER_LEVEL -> st.copy(powerLevel = b0 ?: 0, powerBoostInMotion = v.getOrNull(1)?.let { it.toInt() != 0 } ?: st.powerBoostInMotion)
            Gatt.SPEED_LIMIT -> st.copy(speedLimit = SpeedLimit.from(b0 ?: 0), regionLock = v.getOrNull(1)?.toInt()?.and(0xFF) ?: st.regionLock)
            Gatt.MOTOR_BATTERY_LEVEL -> st.copy(battery = b0?.coerceAtMost(100) ?: st.battery)
            Gatt.MOTOR_BATTERY_STATE -> st.copy(chargeState = ChargeState.from(b0) ?: st.chargeState)
            Gatt.MODULE_MODE -> st.copy(status = BikeStatus.from(b0 ?: 0) ?: BikeStatus.OTHER)
            Gatt.MODULE_STATE -> when (b0 ?: 0) {
                1 -> st.copy(standby = true)
                2 -> if (pushed) st.copy(shippingImminent = true, standby = false) else st
                else -> st.copy(standby = false)
            }
            Gatt.ERRORS -> st.copy(faultCodes = Faults.decode(v))
            Gatt.BUTTONS -> st.copy(buttons = Buttons.from(v) ?: st.buttons)
            Gatt.SUPPLY_OK -> st.copy(supplyOk = (b0 ?: 0) == 1)
            Gatt.LIGHT_MODE -> st.copy(light = LightMode.from(b0 ?: 0))
            Gatt.BELL_SOUND -> st.copy(bell = BellTone.from(b0) ?: st.bell)
            Gatt.UNIT_SYSTEM -> st.copy(unit = UnitSystem.from(b0 ?: 0))
            Gatt.E_SHIFTER_GEAR, Gatt.E_SHIFTER_MODE, Gatt.E_SHIFT_POINTS -> decodeShifting(st, ch, v) ?: st
            else -> st
        }
        return next.copy(raw = next.raw + (ch.short to v))
    }

    /** Applies a decrypted value to the state and records meaningful changes, atomically per value. */
    private fun apply(ch: Gatt.Char, v: ByteArray, pushed: Boolean = false) {
        val (before, after) = host.transition { decodeInto(it, ch, v, pushed) }
        host.onValue(ch, v, pushed)
        val b0 = v.u8()
        if (ch == Gatt.UNLOCK_REQUEST && pushed && (b0 ?: 0) == 1) host.onUnlockRequest()
        if (ch == Gatt.OAD_STATUS && pushed) host.log(EventLog.Kind.INFO, I18n.get(R.string.core_log_transfer, oadStatus(b0)))
        // Another region means another shift point slot: read 5537 again once the bike has
        // finished persisting the region (about 2 s; reads in between may still give the old slot).
        if (ch == Gatt.SPEED_LIMIT && pushed && before.speedLimit != after.speedLimit && after.shiftSupported) {
            val s = session
            if (s != null) host.scope.launch {
                delay(2_500)
                if (session === s) host.withCommandLock { readShiftPoints(s) }
            }
        }
        host.recordChanges(before, after)
    }

    // --- Commands -----------------------------------------------------------------------------

    private suspend fun unlock(s: BikeSession) {
        s.write(Gatt.LOCK_STATE, byteArrayOf(2))
        delay(800)
        apply(Gatt.LOCK_STATE, s.read(Gatt.LOCK_STATE))
    }

    private suspend fun lock(s: BikeSession) {
        s.write(Gatt.LOCK_STATE, byteArrayOf(1))
        delay(800)
        apply(Gatt.LOCK_STATE, s.read(Gatt.LOCK_STATE))
    }

    private suspend fun setSpeedLimit(s: BikeSession, limit: SpeedLimit) {
        apply(Gatt.SPEED_LIMIT, s.writeThenRead(Gatt.SPEED_LIMIT, byteArrayOf(limit.code.toByte(), 1), waitMs = 400, reads = 2))
        // A region change makes the main board persist its config to flash and reconfigure the
        // motor first; meanwhile the BLE chip can still answer with the old region. Give it about
        // two more seconds before calling the change rejected.
        var retries = 0
        while (host.state.speedLimit != limit && retries++ < 6) {
            delay(350)
            apply(Gatt.SPEED_LIMIT, s.read(Gatt.SPEED_LIMIT))
        }
        val got = host.state.speedLimit
        if (got != limit) {
            val reported = got?.kmh?.toString() ?: UNKNOWN
            throw GattException(
                when (val lock = host.state.regionLock) {
                    2 -> I18n.get(R.string.core_error_region_locked, limit.kmh)
                    null -> I18n.get(R.string.core_error_speed_rejected, limit.kmh, reported)
                    else -> I18n.get(R.string.core_error_speed_rejected_lock, limit.kmh, reported, lock)
                },
            )
        }
        // The new region has its own shift point slot.
        if (host.state.shiftSupported) readShiftPoints(s)
    }

    /**
     * Speed++ on ([SpeedPlus.WHEEL_24]) or back to stock. Standing only: the bike hands the flag to the
     * motor controller through its config lines (ble.c case 0x5564 strobes PB9) and saves it to flash.
     */
    private suspend fun setWheelSize(s: BikeSession, wheel: Int) {
        if (host.state.moving) throw GattException(I18n.get(R.string.speedplus_error_rolling))
        apply(Gatt.WHEEL_SIZE, s.writeThenRead(Gatt.WHEEL_SIZE, byteArrayOf(wheel.toByte()), waitMs = 400, reads = 2))
        // Like a region change it goes to flash first; give the read-back a little time.
        var retries = 0
        while (host.state.wheelSize != wheel && retries++ < 6) {
            delay(350)
            apply(Gatt.WHEEL_SIZE, s.read(Gatt.WHEEL_SIZE))
        }
        if (host.state.wheelSize != wheel) throw GattException(I18n.get(R.string.speedplus_error_rejected))
    }

    private suspend fun setPowerLevel(s: BikeSession, level: Int) {
        // Byte 1 is the stored boost-in-motion flag: send back what the bike last reported, so a plain
        // level change does not silently turn it on again (firmware takes the flag from every 5534 write,
        // mw/ble.c:457). Only send 0 when we positively know it is off; default to 1 otherwise.
        val flag: Byte = if (host.state.powerBoostInMotion == false) 0 else 1
        val back = s.writeThenRead(Gatt.POWER_LEVEL, byteArrayOf(level.toByte(), flag), waitMs = 150)
        apply(Gatt.POWER_LEVEL, back)
        val got = host.state.powerLevel
        if (got != level) throw GattException(I18n.get(R.string.core_error_level_rejected, level, got?.toString() ?: UNKNOWN))
    }

    /**
     * POWER_LEVEL (5534) byte 1 (feature module [BikeLab], 1.11): change the stored boost-in-motion flag
     * without touching the assist level. Runs on the command lock so it and [setPowerLevel] never race over
     * the same characteristic, and takes the level from one snapshot. Refuses when the level is unknown
     * (sending a guessed level would change the assist level as a side effect). [onDone] gets null on success.
     */
    private suspend fun setBoostInMotion(s: BikeSession, enabled: Boolean, onDone: (String?) -> Unit) {
        val level = host.state.powerLevel ?: run {
            onDone(I18n.get(R.string.labx_error_level_unknown))
            throw GattException(I18n.get(R.string.labx_error_level_unknown))
        }
        apply(Gatt.POWER_LEVEL, s.writeThenRead(Gatt.POWER_LEVEL, byteArrayOf(level.toByte(), if (enabled) 1 else 0), waitMs = 200))
        onDone(null)
    }

    // --- Shifting (E_SHIFTER_MODE 5538, E_SHIFT_POINTS 5537; see [ShiftCommands]) ----------------

    /** The link the shifting commands use: the session while the link is up, else the demo stand-in. */
    fun shiftLink(): ShiftLink? {
        val s = session
        return if (s != null) SessionShiftLink(s) else demoShiftLink
    }

    /** Debug builds: the demo state is shown; [link] stands in for the bike. */
    fun demo(link: ShiftLink?) {
        demoShiftLink = link
        shifting.newConnection()
    }

    suspend fun setShiftMode(link: ShiftLink, mac: String?, mode: ShiftMode) {
        if (shifting.setMode(link, mode) && mac != null) shiftJournal.recordMode(mac, mode)
    }

    suspend fun setShiftPoints(link: ShiftLink, mac: String?, target: ShiftTarget) {
        recordPoints(mac, shifting.setPoints(link, target))
    }

    private fun recordPoints(mac: String?, w: ShiftWrite) {
        if (w.written && mac != null) shiftJournal.recordPoints(mac, w.region, w.points)
    }

    private class SessionShiftLink(private val s: BikeSession) : ShiftLink {
        override suspend fun read(ch: Gatt.Char): ByteArray = s.read(ch)
        override suspend fun write(ch: Gatt.Char, value: ByteArray) = s.write(ch, value)
    }

    private suspend fun setLight(s: BikeSession, mode: LightMode) {
        apply(Gatt.LIGHT_MODE, s.writeThenRead(Gatt.LIGHT_MODE, byteArrayOf(mode.code.toByte()), waitMs = 200))
    }

    /** Alarm on/off through ALARM_MODE (5524): anything but [AlarmStage.DISARMED] switches it on. The core
     *  driver never writes ALARM_STATE (5523); only the Bike-Lab experiments do (siren / alarm stop), parked-only. */
    private suspend fun setAlarm(s: BikeSession, stage: AlarmStage) {
        val on = stage.armed
        apply(Gatt.ALARM_MODE, s.writeThenRead(Gatt.ALARM_MODE, byteArrayOf(if (on) 1 else 0), waitMs = 200))
        runCatching { apply(Gatt.ALARM_STATE, s.read(Gatt.ALARM_STATE)) }
    }

    private suspend fun setBell(s: BikeSession, tone: BellTone) {
        s.write(Gatt.BELL_SOUND, byteArrayOf(tone.code.toByte(), 1))
        host.update { it.copy(bell = tone) }
        runCatching { apply(Gatt.BELL_SOUND, s.read(Gatt.BELL_SOUND)) }
    }

    private suspend fun play(s: BikeSession, sound: Sound) {
        s.write(Gatt.PLAY_SOUND, byteArrayOf(sound.id.toByte(), 1))
    }

    private suspend fun setUnit(s: BikeSession, unit: UnitSystem) {
        apply(Gatt.UNIT_SYSTEM, s.writeThenRead(Gatt.UNIT_SYSTEM, byteArrayOf(unit.code.toByte()), waitMs = 200))
    }

    /** MODULE_STATE = 0. Refused while riding: with the lock switch reading "locked" it triggers AUTOWAKEUP. */
    private suspend fun wake(s: BikeSession) {
        if (host.state.moving) throw GattException(I18n.get(R.string.core_error_wake_riding))
        s.write(Gatt.MODULE_STATE, byteArrayOf(ModuleCommand.WAKE.toByte()))
        delay(500)
        runCatching { apply(Gatt.MODULE_MODE, s.read(Gatt.MODULE_MODE)) }
    }

    /**
     * Backup code: three digits 1–9 sent in typed order. The BLE chip packs them big-endian and relays
     * them little-endian, so mainware sees them reversed and stores p0 + 10*p1 + 100*p2 = the typed number.
     * Setting a code switches ALARM_MODE on; clearing (FF FF FF) switches it off.
     */
    private suspend fun setBackupCode(s: BikeSession, code: String?) {
        val payload = if (code == null) {
            byteArrayOf(-1, -1, -1)
        } else {
            require(code.length == 3 && code.all { it in '1'..'9' }) { I18n.get(R.string.core_error_backup_digits) }
            byteArrayOf((code[0] - '0').toByte(), (code[1] - '0').toByte(), (code[2] - '0').toByte())
        }
        s.write(Gatt.BACKUP_CODE, payload)
        delay(200)
        runCatching { apply(Gatt.ALARM_MODE, s.read(Gatt.ALARM_MODE)) }
    }

    /**
     * Downloads the bike's own log (maintenance service). LOG_SIZE is a big-endian u32 count of
     * 16-byte blocks; each request is [offset BE u32 in blocks, block count] (chwdt read_logs.py,
     * bleware log_gatt.c). Read it right after a shutdown, before disconnecting the battery.
     */
    private suspend fun readBikeLog(s: BikeSession, onDone: (String?) -> Unit) {
        host.paused = true
        try {
            if (!s.has(Gatt.LOG_SIZE) || !s.has(Gatt.LOG_BLOCK)) throw GattException(I18n.get(R.string.core_error_no_bike_log))
            if (s.has(Gatt.LOG_MODE)) runCatching { s.write(Gatt.LOG_MODE, byteArrayOf(1)) }
            val sizeRaw = s.read(Gatt.LOG_SIZE)
            val blocks = if (sizeRaw.size >= 4) {
                ((sizeRaw[0].toLong() and 0xFF) shl 24) or ((sizeRaw[1].toLong() and 0xFF) shl 16) or
                    ((sizeRaw[2].toLong() and 0xFF) shl 8) or (sizeRaw[3].toLong() and 0xFF)
            } else 0L
            val total = blocks.coerceIn(0, 0x20000L / 16)
            val out = StringBuilder()
            var offset = 0L
            var empty = 0
            host.update { it.copy(logProgress = 0f) }
            while (offset < total && empty < 3) {
                val count = minOf(0xFFL, total - offset).toInt()
                val req = byteArrayOf((offset ushr 24).toByte(), (offset ushr 16).toByte(), (offset ushr 8).toByte(), offset.toByte(), count.toByte())
                val raw = s.writeThenRead(Gatt.LOG_BLOCK, req)
                if (raw.isEmpty()) { empty++; offset++; continue }
                out.append(
                    String(raw.trimTrailingZeros().filter { it != 0xFF.toByte() && it != 0.toByte() }.toByteArray(), Charsets.US_ASCII)
                        .filter { it == '\n' || it == '\r' || it >= ' ' },
                )
                offset += maxOf(1, raw.size / 16)
                host.update { it.copy(logProgress = (offset.toFloat() / total).coerceAtMost(1f)) }
            }
            onDone(out.toString())
        } catch (e: Exception) {
            onDone(null)
            throw e
        } finally {
            host.paused = false
            host.update { it.copy(logProgress = null) }
        }
    }

    /**
     * Custom bell: encrypted metadata [0x19, size BE, crc32 BE], then plain blocks of up to 240 bytes,
     * then select the Foghorn slot, which the upload replaces (mooovy UploadStep.tsx; OAD status on 5513).
     */
    private suspend fun uploadBell(s: BikeSession, file: ByteArray, onDone: (Boolean) -> Unit) {
        host.paused = true
        try {
            val chunk = minOf(240, (gatt.mtu - 3).coerceAtLeast(20))
            val size = file.size
            val header = byteArrayOf(
                0x19,
                (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
            ) + crc32BigEndian(file)
            host.update { it.copy(upload = 0f) }
            s.write(Gatt.FIRMWARE_METADATA, header)
            var offset = 0
            while (offset < size) {
                val end = minOf(offset + chunk, size)
                s.writeRaw(Gatt.FIRMWARE_BLOCK, file.copyOfRange(offset, end))
                offset = end
                host.update { it.copy(upload = offset / size.toFloat()) }
            }
            delay(300)
            s.write(Gatt.BELL_SOUND, byteArrayOf(BellTone.FOGHORN.code.toByte(), 1))
            host.update { it.copy(bell = BellTone.FOGHORN, upload = null) }
            onDone(true)
        } catch (e: Exception) {
            host.update { it.copy(upload = null) }
            onDone(false)
            throw e
        } finally {
            host.paused = false
        }
    }

    /**
     * Replaces one sound clip (feature module [BikeLab], 2.2): encrypted [metadata] on 5511, then plain
     * [file] blocks on 5512. Runs as a controller command (Op.UPLOAD, command lock, polling paused) so it
     * never interleaves with a bell upload or a log download — a second 5511 during a transfer would reset
     * the single OAD session and land blocks in the wrong slot. [metadata] is built by the caller
     * (ClipSlot.metadata), which refuses the brick slot. [onDone] reports success after the last block.
     */
    private suspend fun uploadClip(
        s: BikeSession,
        metadata: ByteArray,
        file: ByteArray,
        onProgress: (Float) -> Unit,
        onDone: (Boolean) -> Unit,
    ) {
        host.paused = true
        try {
            val chunk = minOf(240, (gatt.mtu - 3).coerceAtLeast(20))
            host.update { it.copy(upload = 0f) }
            onProgress(0f)
            s.write(Gatt.FIRMWARE_METADATA, metadata)
            var offset = 0
            while (offset < file.size) {
                val end = minOf(offset + chunk, file.size)
                s.writeRaw(Gatt.FIRMWARE_BLOCK, file.copyOfRange(offset, end))
                offset = end
                val p = offset / file.size.toFloat()
                host.update { it.copy(upload = p) }
                onProgress(p)
            }
            delay(300)
            host.update { it.copy(upload = null) }
            onDone(true)
        } catch (e: Exception) {
            host.update { it.copy(upload = null) }
            onDone(false)
            throw e
        } finally {
            host.paused = false
        }
    }

    private fun oadStatus(b: Int?): String = when (b) {
        0x12 -> I18n.get(R.string.core_oad_started)
        0x13 -> I18n.get(R.string.core_oad_done)
        0x14 -> I18n.get(R.string.core_oad_block_ok)
        0x16 -> I18n.get(R.string.core_oad_bad_checksum)
        0x17 -> I18n.get(R.string.core_oad_not_writable)
        0xF8 -> I18n.get(R.string.core_oad_bad_type)
        0xFF -> I18n.get(R.string.core_oad_too_large)
        else -> I18n.get(R.string.core_oad_code, b?.toString() ?: UNKNOWN)
    }

    private companion object {
        /** Placeholder for a value the bike has not reported. */
        const val UNKNOWN = "?"

        /** The poll's fast batch while the Wache is armed: gear dropped, alarm state and battery line added. */
        val GUARD_FAST = listOf(
            Gatt.ERRORS, Gatt.SPEED, Gatt.MODULE_MODE, Gatt.LOCK_STATE, Gatt.ALARM_STATE, Gatt.MOTOR_BATTERY_STATE, Gatt.SUPPLY_OK, Gatt.BUTTONS,
        )
    }
}
