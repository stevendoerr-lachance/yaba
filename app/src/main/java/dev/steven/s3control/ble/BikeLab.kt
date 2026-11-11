package dev.steven.s3control.ble

import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.audio.ClipSlot
import dev.steven.s3control.ride.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.abs

/** One lab action in flight (for the busy indicator and to disable a control while it runs). */
enum class LabOp { READ, CLOCK, LIGHT, LAMP, BOOST, SLEEP, ALARM_STOP, SELFTEST, SIREN, SOUND, CLIP, LOGSEEK, RELOCK }

/**
 * Everything the "Bike-Lab" and "Sound-Lab" show and set, kept out of [BikeState] so the two teams
 * never collide in one data class. Live values (temps, lux, cadence, torque) arrive by notification;
 * the read-once values (cycles, clock, lamps, sound tiers) are filled by [BikeLab.readConfig]. The boost
 * flag is mirrored from the controller's 5534 decode so it never goes stale after a level change.
 */
data class LabState(
    val busy: Set<LabOp> = emptySet(),
    val error: String? = null,
    /** Which control the last error belongs to, so the UI can show it inline instead of only up top. */
    val errorOp: LabOp? = null,
    // Live telemetry (notifications; null until the first push)
    val motorTempC: Int? = null,        // 5547
    val controllerTempC: Int? = null,   // 5546
    val boardTempC: Int? = null,        // 5545, notify-only
    val luxRaw: Int? = null,            // 5584 bytes 0-1
    val lightThreshold: Int? = null,    // 5584 bytes 2-3
    val cadenceRaw: Int? = null,        // 553A
    val torqueRaw: Int? = null,         // 5539
    /** Owner-set torque reading at rest; [torqueRaw] minus this is the calibrated pedal force. */
    val torqueZero: Int? = null,
    // Read-once per connection
    val chargeCycles: Int? = null,      // 5541 bytes 2-3 LE
    val powerBankPresent: Boolean? = null,
    val powerBankCycles: Int? = null,   // 5541 bytes 7-8 LE
    val clockEpoch: Long? = null,       // 5567 read, little-endian
    /** bike clock minus phone clock at the last sync/read, seconds. */
    val clockSkewSeconds: Long? = null,
    /** 5534 byte 1, mirrored from [BikeController.BikeState.powerBoostInMotion]; null until read. */
    val boostInMotion: Boolean? = null,
    /** The 3 lamp-channel bytes read before any override — what [restoreLamps] writes back. */
    val lampStock: List<Int>? = null,
    /** The override currently written (null = stock / not overridden by us). */
    val lampOverride: List<Int>? = null,
    val soundTiers: SoundTiers? = null,
    /** Mirror of the active siren/alarm stage (2/3/4) we last asked for; 0 = none. */
    val sirenStage: Int? = null,
    /** True after an alarm stop (5523 = 0) cleared the soft-lock on a bike that was locked: offer a re-lock. */
    val relockSuggested: Boolean = false,
) {
    val calibratedTorque: Int? get() = if (torqueRaw != null && torqueZero != null) torqueRaw - torqueZero else torqueRaw
    val lampsOverridden: Boolean get() = lampOverride != null
    /** Whether the lamp channels have been read (null stock = never read; the UI must not assume 0/0/0). */
    val lampsRead: Boolean get() = lampStock != null || lampOverride != null
}

/**
 * The firmware verdicts for the three speed and odometer experiments, verified against the
 * decomp on this run. The UI shows the reason and builds no control for them.
 * (Kept as data so the Experimente screen can gate on it instead of hard-coding the outcome.)
 */
object LabExperiments {
    /** 5531 odometer: bleware's 0x5530 switch has no case for char 1 → default return -1. Not writable over BLE. */
    const val ODOMETER_WRITABLE = false
    /** 5564 wheel size is writable (single 0/1 flag): built as Speed++, see [SpeedPlus]. YABA scales the
     *  speed back, so its own readouts stay real while the bike's speedo reads low. */
    const val SPEEDPP_BUILT = true
    /** Off-road region 3 removed in 1.08.02; 1.9.3 caps BLE level at 4 and regions at 0–2. */
    const val OFFROAD_REACHABLE = false
    const val LEVEL5_REACHABLE = false
}

/**
 * Pure protocol helpers for the lab features: payload builders, read-back decoders and the
 * parked-only guard. No Android, no I/O — unit-tested directly.
 *
 * "Parked" means the bike is not actually moving (owner feedback: firmware state RIDING / 0x0C only
 * means unlocked-and-ready, it reports that standing in a kitchen). So the guard uses [BikeState.moving]
 * (speed ≥ 3 km/h or the wheel-moving bit), never status == RIDING, plus "no button held".
 */
object LabProtocol {

    fun isParked(s: BikeState): Boolean =
        s.isReady && !s.moving &&
            (s.buttons?.let { !it.horn && !it.boost && !it.powerButton } ?: true)

    /** Whether the clock may be set: the clock risk is a stuck-button mis-detect while Bell/Boost is held,
     *  not assist loss — so this only needs "no button held" (and a live link). */
    fun clockSettable(s: BikeState): Boolean =
        s.isReady && (s.buttons?.let { !it.horn && !it.boost } ?: true)

    // --- Clock 5567 (write big-endian, read little-endian) -----------------------------------------
    fun clockWriteBE(epochSeconds: Long): ByteArray = byteArrayOf(
        (epochSeconds ushr 24).toByte(), (epochSeconds ushr 16).toByte(),
        (epochSeconds ushr 8).toByte(), epochSeconds.toByte(),
    )

    fun clockWriteLE(epochSeconds: Long): ByteArray = byteArrayOf(
        epochSeconds.toByte(), (epochSeconds ushr 8).toByte(),
        (epochSeconds ushr 16).toByte(), (epochSeconds ushr 24).toByte(),
    )

    fun clockReadLE(v: ByteArray): Long? = if (v.size < 4) null else v.uLE(4)

    /** The big-endian reading of a value the bike stored little-endian (byte-swap of a u32). */
    fun clockReadBE(v: ByteArray): Long? =
        if (v.size < 4) null else ((v[0].toLong() and 0xFF) shl 24) or ((v[1].toLong() and 0xFF) shl 16) or
            ((v[2].toLong() and 0xFF) shl 8) or (v[3].toLong() and 0xFF)

    /**
     * Decide what to do with a clock read-back (pure, testable). [back] is the little-endian reading of
     * 5567 after a big-endian write of [wrote]; [skew] is |back − wrote| in seconds.
     *  - [ClockVerdict.OK]: the BE write took (within tolerance).
     *  - [ClockVerdict.SWAPPED]: the LE reading is garbage but its byte-swap ≈ what we wrote — 2.4.1 stores
     *    the other way round, so a single LE rewrite is the fix.
     *  - [ClockVerdict.UNKNOWN]: neither matches; do not leave the bike on a swapped date.
     */
    fun clockVerdict(back: Long?, wrote: Long, raw: ByteArray, toleranceSeconds: Long = 90): ClockVerdict {
        if (back != null && abs(back - wrote) <= toleranceSeconds) return ClockVerdict.OK
        val swapped = clockReadBE(raw)
        if (swapped != null && abs(swapped - wrote) <= toleranceSeconds) return ClockVerdict.SWAPPED
        return ClockVerdict.UNKNOWN
    }

    enum class ClockVerdict { OK, SWAPPED, UNKNOWN }

    // --- Light sensor 5584 (always 4 bytes; a 2-byte write would silently set the threshold to 0) ---
    /** [0x00, 0x00, thr_hi, thr_lo]. The firmware rejects 0xFFFF, so that is clamped away; 0 is allowed. */
    fun lightThreshold(value: Int): ByteArray {
        val v = value.coerceIn(0, 0xFFFE)
        return byteArrayOf(0, 0, (v ushr 8).toByte(), v.toByte())
    }

    fun decodeLightSensor(v: ByteArray): Pair<Int, Int>? =
        if (v.size < 4) null else ((v[0].toInt() and 0xFF shl 8) or (v[1].toInt() and 0xFF)) to
            ((v[2].toInt() and 0xFF shl 8) or (v[3].toInt() and 0xFF))

    // --- Lamps 5582 (3 channels, each 0 = off or non-zero = 100 %) --------------------------------
    fun lampWrite(a: Int, b: Int, c: Int): ByteArray = byteArrayOf(a.toByte(), b.toByte(), c.toByte())
    fun decodeLamps(v: ByteArray): List<Int>? =
        if (v.size < 3) null else listOf(v[0].toInt() and 0xFF, v[1].toInt() and 0xFF, v[2].toInt() and 0xFF)

    // --- Assist-cut writes -------------------------------------------------------------------------
    fun sirenPayload(stage: Int): ByteArray {
        require(stage in 2..4) { "siren stage is 2, 3 or 4" }
        return byteArrayOf(stage.toByte())
    }

    val alarmStopPayload: ByteArray get() = byteArrayOf(0)   // 5523 = 0
    val lockPayload: ByteArray get() = byteArrayOf(1)        // 5521 = 1 (re-lock after an alarm stop)
    val sleepPayload: ByteArray get() = byteArrayOf(1)       // 5562 = 1
    val selfTestPayload: ByteArray get() = byteArrayOf(3)    // 55C1 = 3

    /** 55C1 = [0x02, T as BE u32]: advance the log read cursor past every line with epoch <= T. */
    fun logSeekPayload(newestArchivedEpoch: Long): ByteArray {
        require(newestArchivedEpoch > 0) { "need the newest archived epoch, never phone time" }
        return byteArrayOf(
            0x02,
            (newestArchivedEpoch ushr 24).toByte(), (newestArchivedEpoch ushr 16).toByte(),
            (newestArchivedEpoch ushr 8).toByte(), newestArchivedEpoch.toByte(),
        )
    }

    // --- Boost-in-motion flag: 5534 = [level, flag] -----------------------------------------------
    fun boostInMotionWrite(level: Int, enabled: Boolean): ByteArray =
        byteArrayOf(level.toByte(), if (enabled) 1 else 0)

    fun decodeBoostInMotion(v: ByteArray): Boolean? = if (v.size < 2) null else v[1].toInt() != 0

    // --- Decoders for live telemetry (signed int8 temps; u16 LE cadence/torque) --------------------
    fun temp(v: ByteArray): Int? = if (v.isEmpty()) null else v[0].toInt()
    fun u16le(v: ByteArray): Int? = if (v.size < 2) null else v.uLE(2).toInt()

    /** 5541: [2..3] charge cycles u16 LE, [4] PowerBank present, [7..8] PB cycles u16 LE. */
    fun chargeCycles(v: ByteArray): Int? =
        if (v.size < 4) null else (v[2].toInt() and 0xFF) or ((v[3].toInt() and 0xFF) shl 8)

    fun powerBankCycles(v: ByteArray): Int? =
        if (v.size < 9) null else (v[7].toInt() and 0xFF) or ((v[8].toInt() and 0xFF) shl 8)

    fun powerBankPresent(v: ByteArray): Boolean? = if (v.size < 5) null else v[4].toInt() != 0
}

/**
 * The experiments and extras that sit beside the core controller: the "Fahrtenschreiber" insights,
 * live temps / light / pedal telemetry, lamp and sound-volume configuration, and the parked-only
 * device writes (clock, sleep, alarm stop, self-test, siren). Lives in its own file and talks to the
 * bike through the one authenticated session [BikeController] exposes; every read/write is serialised
 * with the core traffic by the session lock, and lab actions are serialised with each other here.
 *
 * It never writes the wheel size (5564) or odometer (5531): see [LabExperiments].
 */
class BikeLab(
    private val controller: BikeController,
    private val scope: CoroutineScope,
    private val log: EventLog,
) {
    private val _state = MutableStateFlow(LabState())
    val state: StateFlow<LabState> = _state.asStateFlow()

    private val labLock = Mutex()
    private var liveJob: Job? = null

    /**
     * True while the Wache owns the bike (set by AppGraph to [BikeGuard.state] active). The assist-cut writes that
     * would fight it (siren, alarm stop, re-lock, sleep, self-test) are refused then (the guard design 2.4).
     */
    @Volatile var guardActive: () -> Boolean = { false }
    @Volatile private var liveWanted = false

    // Characteristics not in the shared Gatt map (handled only here).
    private val CADENCE = Gatt.Char(Gatt.MOVEMENT, labUuid("553a"), R.string.lab_char_cadence)
    private val TORQUE = Gatt.Char(Gatt.MOVEMENT, labUuid("5539"), R.string.lab_char_torque)
    private val liveChars = listOf(
        Gatt.MOTOR_TELEMETRY_B, Gatt.MOTOR_TELEMETRY_A, Gatt.ENVIRONMENT, Gatt.LIGHT_SENSOR, CADENCE, TORQUE,
    )

    init {
        // Mirror the boost-in-motion flag (5534 byte 1) the controller decodes from every read and notify,
        // so the switch never shows a stale value. And clear the read-once / override fields on every new
        // connection or bike, so a restore never writes one bike's (or one moment's) values to another.
        scope.launch {
            controller.state
                .map { Triple(it.bike?.normalizedMac, it.connectedAtMillis, it.powerBoostInMotion) }
                .distinctUntilChanged()
                .collect { (_, _, boost) -> _state.update { it.copy(boostInMotion = boost ?: it.boostInMotion) } }
        }
        scope.launch {
            controller.state
                .map { it.bike?.normalizedMac to it.connectedAtMillis }
                .distinctUntilChanged()
                .collect {
                    _state.update {
                        it.copy(
                            lampStock = null, lampOverride = null, sirenStage = null, relockSuggested = false,
                            boostInMotion = null, clockEpoch = null, clockSkewSeconds = null, soundTiers = null,
                            chargeCycles = null, powerBankPresent = null, powerBankCycles = null,
                        )
                    }
                }
        }
    }

    // --- Live telemetry ---------------------------------------------------------------------------

    /** Subscribe to temps / light / pedal and keep [state] fresh. Call when the Bike-Lab is on screen. */
    fun startLive() {
        liveWanted = true
        if (liveJob == null) liveJob = scope.launch { liveLoop() }
    }

    /** Stop decoding live telemetry (the Bike-Lab left the screen). Config values stay as last read. */
    fun stopLive() {
        liveWanted = false
        liveJob?.cancel(); liveJob = null
    }

    private suspend fun liveLoop() {
        controller.state.map { it.isReady }.distinctUntilChanged().collectLatest { ready ->
            if (!ready || !liveWanted) return@collectLatest
            val s = controller.featureSession ?: return@collectLatest
            for (ch in liveChars) if (s.has(ch)) {
                runCatching { s.subscribe(ch) }
                // ENVIRONMENT (5545) is notify-only: a READ is a firmware no-op, so the sync fetch times out
                // and BikeSession retries three times under the session lock. Subscribe, but never read it.
                if (ch.id == Gatt.ENVIRONMENT.id) continue
                runCatching { applyLive(ch, s.read(ch)) }
            }
            controller.featureNotifications.collect { n ->
                val ch = liveChars.firstOrNull { it.id == n.characteristic } ?: return@collect
                runCatching { applyLive(ch, s.decode(ch, n.value)) }
            }
        }
    }

    private fun applyLive(ch: Gatt.Char, v: ByteArray) {
        _state.update {
            when (ch.id) {
                Gatt.MOTOR_TELEMETRY_B.id -> it.copy(motorTempC = LabProtocol.temp(v)?.takeIf { t -> t != 0 } ?: it.motorTempC)
                Gatt.MOTOR_TELEMETRY_A.id -> it.copy(controllerTempC = LabProtocol.temp(v))
                Gatt.ENVIRONMENT.id -> it.copy(boardTempC = LabProtocol.temp(v))
                Gatt.LIGHT_SENSOR.id -> LabProtocol.decodeLightSensor(v)?.let { (lux, thr) -> it.copy(luxRaw = lux, lightThreshold = thr) } ?: it
                CADENCE.id -> it.copy(cadenceRaw = LabProtocol.u16le(v))
                TORQUE.id -> it.copy(torqueRaw = LabProtocol.u16le(v))
                else -> it
            }
        }
    }

    /** Record the current pedal-force reading as the rest position (owner presses it while not pedalling). */
    fun calibrateTorqueZero() = _state.update { it.copy(torqueZero = it.torqueRaw ?: it.torqueZero) }

    // --- Read-once config -------------------------------------------------------------------------

    /** Reads the values that never notify: charge cycles, clock, lamp channels, sound tiers. (The boost
     *  flag is mirrored from the controller's 5534 decode, so it is not read again here.) */
    fun readConfig() = run(LabOp.READ) { s ->
        if (s.has(Gatt.MOTOR_BATTERY_LEVEL)) runCatching {
            val v = s.read(Gatt.MOTOR_BATTERY_LEVEL)
            _state.update {
                it.copy(
                    chargeCycles = LabProtocol.chargeCycles(v),
                    powerBankPresent = LabProtocol.powerBankPresent(v),
                    powerBankCycles = LabProtocol.powerBankCycles(v),
                )
            }
        }
        if (s.has(Gatt.CLOCK)) runCatching {
            val epoch = LabProtocol.clockReadLE(s.read(Gatt.CLOCK))
            val now = System.currentTimeMillis() / 1000
            _state.update { it.copy(clockEpoch = epoch, clockSkewSeconds = epoch?.let { e -> e - now }) }
        }
        if (s.has(Gatt.LED_STATE)) runCatching {
            val lamps = LabProtocol.decodeLamps(s.read(Gatt.LED_STATE))
            _state.update { it.copy(lampStock = it.lampStock ?: lamps) }
        }
        if (s.has(Gatt.SOUND_GROUPS)) runCatching {
            _state.update { it.copy(soundTiers = SoundTiers.decode(s.read(Gatt.SOUND_GROUPS)) ?: it.soundTiers) }
        }
        if (s.has(Gatt.LIGHT_SENSOR)) runCatching {
            LabProtocol.decodeLightSensor(s.read(Gatt.LIGHT_SENSOR))?.let { (lux, thr) ->
                _state.update { it.copy(luxRaw = lux, lightThreshold = thr) }
            }
        }
    }

    // --- Clock 1.2 --------------------------------------------------------------------------------

    /** Sets the bike's clock to phone time. Needs no button held (a >20 s jump with Bell/Boost held would
     *  be read as a stuck button). Writes big-endian, reads little-endian; falls back to a single LE rewrite
     *  only when the LE read-back is clearly garbage but its byte-swap matches, so the bike is never left on
     *  a swapped date. */
    fun syncClock() = run(LabOp.CLOCK) { s ->
        requireClockSettable()
        suspend fun beWrite(): Long {
            val now = System.currentTimeMillis() / 1000
            s.write(Gatt.CLOCK, LabProtocol.clockWriteBE(now))
            return now
        }
        var wrote = beWrite()
        delay(400)
        var raw = s.read(Gatt.CLOCK)
        // Skew against phone time taken at the read, to tolerate the write+read latency (challenge read,
        // write, delay, a read that may wait on the session lock, retries, a 6 s GATT timeout).
        var back = LabProtocol.clockReadLE(raw)
        when (LabProtocol.clockVerdict(back, wrote, raw, toleranceSeconds = skewToleranceSeconds(wrote))) {
            LabProtocol.ClockVerdict.OK -> Unit
            LabProtocol.ClockVerdict.SWAPPED -> {
                // 2.4.1 stores the other byte order: one LE rewrite fixes it.
                wrote = System.currentTimeMillis() / 1000
                s.write(Gatt.CLOCK, LabProtocol.clockWriteLE(wrote))
                delay(400)
                raw = s.read(Gatt.CLOCK)
                back = LabProtocol.clockReadLE(raw)
                if (LabProtocol.clockVerdict(back, wrote, raw, skewToleranceSeconds(wrote)) != LabProtocol.ClockVerdict.OK) {
                    // The LE attempt did not take either: put the BE value back, so the bike is not left swapped.
                    wrote = beWrite()
                    delay(400)
                    back = LabProtocol.clockReadLE(s.read(Gatt.CLOCK))
                }
            }
            LabProtocol.ClockVerdict.UNKNOWN -> Unit
        }
        val nowFinal = System.currentTimeMillis() / 1000
        _state.update { it.copy(clockEpoch = back, clockSkewSeconds = back?.let { e -> e - nowFinal }) }
        if (back == null || abs(back - nowFinal) > skewToleranceSeconds(nowFinal)) {
            throw GattException(I18n.get(R.string.labx_error_clock_readback))
        }
    }

    /** Latency between write and read-back can run a few seconds; give the skew check that much slack. */
    private fun skewToleranceSeconds(@Suppress("UNUSED_PARAMETER") wrote: Long): Long = 90

    // --- Light threshold 2.3 ----------------------------------------------------------------------

    /** Writes the auto-light threshold (5584, flash). Called on Save, not per slider step. Always 4 bytes.
     *  The write erases two config sectors, so the read-back is retried for about 2 s; on a stale/failed
     *  read the stored value is left unchanged and the UI reports "not confirmed". */
    fun setLightThreshold(value: Int) = run(LabOp.LIGHT) { s ->
        s.write(Gatt.LIGHT_SENSOR, LabProtocol.lightThreshold(value))
        val confirmed = awaitFlashReadback {
            LabProtocol.decodeLightSensor(s.read(Gatt.LIGHT_SENSOR))?.let { (lux, thr) ->
                _state.update { it.copy(luxRaw = lux) }
                if (thr == value) { _state.update { it.copy(lightThreshold = thr) }; true } else false
            } ?: false
        }
        if (!confirmed) throw GattException(I18n.get(R.string.labx_error_not_confirmed))
    }

    // --- Lamps 2.4 --------------------------------------------------------------------------------

    /** Overrides the three lamp channels (5582, not persisted). Re-reads the stock values right before the
     *  first override (not once-ever) so a later restore writes today's values, not daylight's. Aborts if
     *  that read fails, so no stock is remembered as 0/0/0. */
    fun setLampChannels(a: Int, b: Int, c: Int) = run(LabOp.LAMP) { s ->
        if (_state.value.lampOverride == null) {
            val stock = runCatching { LabProtocol.decodeLamps(s.read(Gatt.LED_STATE)) }.getOrNull()
                ?: throw GattException(I18n.get(R.string.labx_error_lamp_read))
            _state.update { it.copy(lampStock = stock) }
        }
        s.write(Gatt.LED_STATE, LabProtocol.lampWrite(a, b, c))
        _state.update { it.copy(lampOverride = listOf(a and 0xFF, b and 0xFF, c and 0xFF)) }
    }

    /** Writes the stock lamp values back, then reads 5582 to confirm, and forgets the override/stock so the
     *  next override re-reads. A per-app override does not self-heal — only a real state change or this
     *  restore brings a switched-off lamp back. */
    fun restoreLamps() = run(LabOp.LAMP) { s ->
        val stock = _state.value.lampStock ?: return@run
        s.write(Gatt.LED_STATE, LabProtocol.lampWrite(stock[0], stock[1], stock[2]))
        delay(300)
        // 5582 read returns the fade-engine level, not the PWM we wrote: a mismatch is a warning, not an error.
        val back = runCatching { LabProtocol.decodeLamps(s.read(Gatt.LED_STATE)) }.getOrNull()
        _state.update { it.copy(lampOverride = null, lampStock = null) }
        if (back != null && back != stock) throw GattException(I18n.get(R.string.labx_error_lamp_restore))
    }

    // --- Boost in motion 1.11 ---------------------------------------------------------------------

    /** 5534 byte 1: let a held Boost change the assist level while rolling (1) or only at a standstill (0).
     *  The write itself does not cut assist; the reason to do it at a standstill is that with 1 the level
     *  can change the moment the bike starts rolling. The write runs on the controller's command lock (via
     *  [BikeController.setBoostInMotion]) so it and a power-level change take the level from one snapshot. */
    fun setBoostInMotion(enabled: Boolean) {
        if (!LabProtocol.isParked(controller.state.value)) { setError(LabOp.BOOST, I18n.get(R.string.labx_error_boost_rolling)); return }
        if (controller.featureSession == null) { setError(LabOp.BOOST, I18n.get(R.string.core_error_not_connected_wake)); return }
        _state.update { it.copy(busy = it.busy + LabOp.BOOST, error = null, errorOp = null) }
        controller.setBoostInMotion(enabled) { err ->
            _state.update { it.copy(busy = it.busy - LabOp.BOOST) }
            if (err != null) setError(LabOp.BOOST, err)
        }
    }

    // --- Sleep 2.6 / alarm stop 1.8 / self-test 1.5 (all parked-only, assist-cut) ------------------

    fun sleepNow() = run(LabOp.SLEEP) { s ->
        requireNoGuard()
        requireParked(LabOp.SLEEP)
        s.write(Gatt.MODULE_STATE, LabProtocol.sleepPayload)
    }

    /** 5523 = 0: stop a running alarm without the backup code. It also clears the soft-lock (ctx+0x312), so
     *  a bike that was locked reads as unlocked afterwards. If it was locked before, the UI is told to offer
     *  a one-tap re-lock ([reLock]); the lock is not forced silently. */
    fun stopAlarm() = run(LabOp.ALARM_STOP) { s ->
        requireNoGuard()
        requireParked(LabOp.ALARM_STOP)
        val wasLocked = controller.state.value.lock == LockState.LOCKED
        s.write(Gatt.ALARM_STATE, LabProtocol.alarmStopPayload)
        _state.update { it.copy(sirenStage = 0) }
        delay(800)
        val lockNow = runCatching { LockState.from(s.read(Gatt.LOCK_STATE).u8()) }.getOrNull()
        _state.update { it.copy(relockSuggested = wasLocked && lockNow != LockState.LOCKED) }
    }

    /** Re-locks the bike after an alarm stop cleared the soft-lock (5521 = 1). Offered only when
     *  [LabState.relockSuggested]; parked-only like the other assist-cut writes. */
    fun reLock() = run(LabOp.RELOCK) { s ->
        requireNoGuard()
        requireParked(LabOp.ALARM_STOP)
        s.write(Gatt.LOCK_STATE, LabProtocol.lockPayload)
        delay(800)
        runCatching { s.read(Gatt.LOCK_STATE) }
        _state.update { it.copy(relockSuggested = false) }
    }

    /**
     * 55C1 = 3: run the bike's built-in diagnosis (plays the diag tone, dumps board/motor/battery state
     * to the log, then "Diag ok" or "Diag fail", then STANDBY). Parked-only, and [archivedLog] must be
     * true: the report pushes the oldest log lines out, so the log is downloaded and saved first. The
     * caller passes a record of the saved archive, not a bare Boolean (so the guard cannot be passed by a
     * partial download or an unsaved file).
     */
    fun selfTest(archivedLog: Boolean) = run(LabOp.SELFTEST) { s ->
        requireNoGuard()
        requireParked(LabOp.SELFTEST)
        if (!archivedLog) throw GattException(I18n.get(R.string.lab_error_backup_first))
        s.write(Gatt.LOG_MODE, LabProtocol.selfTestPayload)
    }

    // --- Siren 2.x experiment ---------------------------------------------------------------------

    /**
     * 5523 = 2/3/4: raise the bike's own alarm. Parked-only (it cuts assist). Each stage is a different
     * thing (states.c:392-585): 2 = a motion-watch that escalates after several shakes, 3 = ~30 s siren
     * then tracking mode (the modem then cycles every ~30 min instead of ~2 h), 4 = straight into the
     * tracking/confirmed state with the soft-lock set. It is always paired in the UI with [stopAlarm]
     * (5523 = 0), which is the way back and which also clears the soft-lock.
     */
    fun triggerSiren(stage: Int) = run(LabOp.SIREN) { s ->
        requireNoGuard()
        requireParked(LabOp.SIREN)
        val payload = LabProtocol.sirenPayload(stage)
        s.write(Gatt.ALARM_STATE, payload)
        _state.update { it.copy(sirenStage = stage) }
        log.add(EventLog.Kind.COMMAND, I18n.get(R.string.lab_log_siren, stage))
    }

    // --- Sound tiers 2.1 --------------------------------------------------------------------------

    /** Writes the per-sound volume tiers (5572, 12 bytes). On Save only — it erases two flash sectors.
     *  Reads back for about 2 s before accepting; on a stale/failed read the stored tiers are left
     *  unchanged and the UI reports "not confirmed" (never shows the target as saved). Nothing is blocked:
     *  on his own bike the owner may mute any sound. */
    fun setSoundTiers(tiers: SoundTiers) = run(LabOp.SOUND) { s ->
        s.write(Gatt.SOUND_GROUPS, tiers.encode())
        val confirmed = awaitFlashReadback {
            val back = runCatching { SoundTiers.decode(s.read(Gatt.SOUND_GROUPS)) }.getOrNull()
            if (back == tiers) { _state.update { it.copy(soundTiers = back) }; true } else false
        }
        if (!confirmed) throw GattException(I18n.get(R.string.labx_error_not_confirmed))
    }

    // --- Clip replace 2.2 -------------------------------------------------------------------------

    /**
     * Replaces one of the five safe sound clips (lock/unlock/startup/shutdown/charging). [file] must be a
     * VM_SOUND container (as [dev.steven.s3control.audio.BellConverter] makes). Keep the original locally
     * first: the slot is erased when the bike accepts the metadata and the bike cannot give it back.
     * The transfer runs as a controller command ([BikeController.uploadClip]) so it shares the command
     * lock with the bell upload and the log download — a second metadata write during a transfer would
     * reset the single OAD session. [onProgress] is 0..1; [onDone] reports success.
     */
    fun uploadClip(
        slot: ClipSlot,
        file: ByteArray,
        onProgress: (Float) -> Unit = {},
        onDone: (Boolean) -> Unit = {},
    ) {
        val metadata = try {
            ClipSlot.metadata(slot, file)   // throws on brick slot / bad clip
        } catch (e: Exception) {
            setError(LabOp.CLIP, e.message ?: I18n.get(R.string.lab_error_clip)); onDone(false); return
        }
        _state.update { it.copy(busy = it.busy + LabOp.CLIP, error = null, errorOp = null) }
        controller.uploadClip(metadata, file, onProgress) { ok ->
            _state.update { it.copy(busy = it.busy - LabOp.CLIP) }
            if (ok) {
                log.add(EventLog.Kind.COMMAND, I18n.get(R.string.lab_log_clip, slot.name))
            } else {
                setError(LabOp.CLIP, controller.state.value.lastCommandError ?: I18n.get(R.string.lab_error_clip))
            }
            onDone(ok)
        }
    }

    // --- Log seek 1.12 ----------------------------------------------------------------------------

    /** 55C1 = [2, T BE]: advance the log cursor past everything up to [newestArchivedEpoch]. Irreversible,
     *  so only after a full archive and never past the newest line you already saved. */
    fun seekLog(newestArchivedEpoch: Long) = run(LabOp.LOGSEEK) { s ->
        s.write(Gatt.LOG_MODE, LabProtocol.logSeekPayload(newestArchivedEpoch))
    }

    fun clearError() = _state.update { it.copy(error = null, errorOp = null) }

    // --- Plumbing ---------------------------------------------------------------------------------

    /** Poll a flash-backed read-back until it matches or ~2 s pass (the write first erases/programs two
     *  16 KB sectors, so an immediate read lands mid-erase and times out or gives the old shadow value). */
    private suspend fun awaitFlashReadback(check: suspend () -> Boolean): Boolean {
        delay(600)
        repeat(5) {
            if (runCatching { check() }.getOrDefault(false)) return true
            delay(350)
        }
        return false
    }

    /** Guards an assist-cut write: only while the bike is not rolling and no button is held. [op] documents
     *  the caller; the error it throws is tied to the op by [run], which rendered it inline. */
    private fun requireParked(@Suppress("UNUSED_PARAMETER") op: LabOp) {
        if (!LabProtocol.isParked(controller.state.value)) throw GattException(I18n.get(R.string.labx_error_assist_cut))
    }

    /** The Wache owns 5521/5523 while it is active: the lab's writes there would undo its watch. */
    private fun requireNoGuard() {
        if (guardActive()) throw GattException(I18n.get(R.string.guard_core_error_active))
    }

    private fun requireClockSettable() {
        if (!LabProtocol.clockSettable(controller.state.value)) throw GattException(I18n.get(R.string.labx_error_clock_jump))
    }

    private fun setError(op: LabOp?, msg: String?) = _state.update { it.copy(error = msg, errorOp = op) }

    private fun run(op: LabOp, block: suspend (BikeSession) -> Unit) {
        val s = controller.featureSession
        if (s == null) { setError(op, I18n.get(R.string.core_error_not_connected_wake)); return }
        scope.launch {
            _state.update { it.copy(busy = it.busy + op, error = null, errorOp = null) }
            try {
                labLock.withLock { block(s) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: I18n.get(R.string.lab_error_generic)
                log.add(EventLog.Kind.ERROR, msg)
                setError(op, msg)
            } finally {
                _state.update { it.copy(busy = it.busy - op) }
            }
        }
    }

    private companion object {
        fun labUuid(short: String): UUID = UUID.fromString("6acc$short-e631-4069-944d-b8ca7598ad50")
    }
}
