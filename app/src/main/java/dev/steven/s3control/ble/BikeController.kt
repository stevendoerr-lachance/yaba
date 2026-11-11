package dev.steven.s3control.ble

import android.content.Context
import androidx.annotation.StringRes
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ride.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class Link { OFFLINE, SEARCHING, CONNECTING, AUTHENTICATING, READY, LOST }

enum class Op { LOCK, UNLOCK, SPEED, POWER, LIGHT, ALARM, BELL, SOUND, UNIT, WAKE, UPLOAD, REFRESH, BACKUP_CODE, BIKE_LOG, SHIFT, MUTE, GUARD }

/**
 * One decoded S3 value as the Wache sees it ([BikeController.valueTap]): every read and every notification while the
 * Wache is armed, unchanged ones too. [pushed]: the bike sent it on its own (a notification), not as a read's answer.
 */
class ValueTap(val atMillis: Long, val ch: Gatt.Char, val value: ByteArray, val pushed: Boolean)

/**
 * One decoded S2 status block and the phone's wall-clock time it was applied ([BikeController.s2Blocks]).
 * [pushed]: the bike sent it on its own (a notification), not as the answer to a read. [mac]: the bike it came from.
 */
class S2Block(val atMillis: Long, val frame: S2Frame, val pushed: Boolean = false, val mac: String? = null)

/**
 * Who wants the bike read more often than every [slowMs], by owner (the ride logger, the cockpit, the S2 drive page,
 * the wiggle test); the fastest claim wins and a released claim no longer counts. Before the registry each of them
 * wrote the interval itself, so whoever wrote last won. Pure, so it is unit-tested.
 */
class PollClaims(private val slowMs: Long) {
    private val claims = LinkedHashMap<String, Long>()

    /** [ms] null releases [owner]'s claim. Returns the interval that applies now. */
    @Synchronized
    fun set(owner: String, ms: Long?): Long {
        if (ms == null) claims.remove(owner) else claims[owner] = ms
        return current()
    }

    @Synchronized
    fun current(): Long = minOf(claims.values.minOrNull() ?: slowMs, slowMs)
}

data class BikeState(
    val bike: BikeCredentials? = null,
    /**
     * The generation: from the account profile until connected, then the family of the driver on the link (the
     * GATT services decide, plan B3). UNKNOWN behaves exactly like an S3.
     */
    val family: BikeFamily = BikeFamily.UNKNOWN,
    /** What the screens may offer ([featuresFor] the family and the S2 test mode); commands check it too. */
    val features: Set<Feature> = featuresFor(BikeFamily.UNKNOWN, s2TestMode = false),
    val link: Link = Link.OFFLINE,
    val linkMessage: String? = null,
    val firmware: String? = null,
    /** Version characteristics read once per connection, with their value. */
    val versionValues: List<Pair<Gatt.Char, String>> = emptyList(),
    /** S2: Device Information (180A) strings read once per connection, by characteristic ([S2Gatt.deviceInfo]). */
    val deviceInfo: List<Pair<UUID, String>> = emptyList(),
    val battery: Int? = null,
    /** S2: the smart module's own battery (status block byte 6). */
    val moduleBattery: Int? = null,
    val chargeState: ChargeState? = null,
    val distanceKm: Double? = null,
    /** Real speed: the bike's value scaled back by [speedFactor] while Speed++ is on. */
    val speedKmh: Int? = null,
    /** SPEED (5532) exactly as the bike reports it. */
    val speedRawKmh: Int? = null,
    /** WHEEL_SIZE (5564): 0 = 24", 1 = 28". */
    val wheelSize: Int? = null,
    /** 1 normally; [SpeedPlus.FACTOR] while a 28" bike runs on the 24" setting. */
    val speedFactor: Float = 1f,
    /** The factory wheel size [speedFactor] rests on, set with [wheelSize] (S3 only; null until 5564 is read). */
    val wheelStock: WheelStock? = null,
    val lock: LockState? = null,
    /** ALARM_STATE (5523), read-only lock/alarm phase. */
    val defence: DefenceState? = null,
    val alarmStage: AlarmStage? = null,
    val powerLevel: Int? = null,
    /** POWER_LEVEL (5534) byte 1: the stored "change the assist level while rolling" flag (ctx+0x3CB).
     *  Decoded from every 5534 read and notify so a power-level write can preserve it (see [setPowerLevel]). */
    val powerBoostInMotion: Boolean? = null,
    val speedLimit: SpeedLimit? = null,
    /** SPEED_LIMIT byte 1: 0 = free, 1 = off-road disabled, 2 = locked (firmware 1.07.06 semantics). */
    val regionLock: Int? = null,
    val light: LightMode? = null,
    val bell: BellTone? = null,
    val unit: UnitSystem? = null,
    val status: BikeStatus? = null,
    /** MODULE_STATE: true while the bike reports standby. */
    val standby: Boolean? = null,
    /** MODULE_STATE pushed 2: the bike is entering shipping mode right now. */
    val shippingImminent: Boolean = false,
    /** All set bits of the 64-bit fault pair, matrix order. Includes status bit 48 (wheel moving). S2: its one error code ([S2Faults]). */
    val faultCodes: List<Int> = emptyList(),
    val buttons: Buttons? = null,
    val supplyOk: Boolean? = null,
    /** E_SHIFTER_GEAR (5536): the gear the bike last commanded (1..4), not one the shifter confirmed. */
    val gear: Int? = null,
    /** The bike offers both E_SHIFTER_MODE and E_SHIFT_POINTS (set in refreshAll). */
    val shiftSupported: Boolean = false,
    /** E_SHIFTER_MODE (5538). */
    val shiftMode: ShiftMode? = null,
    /** E_SHIFT_POINTS (5537) as read; they belong to [shiftPointsRegion]. */
    val shiftPoints: ShiftPoints? = null,
    /** speedLimit at the moment 5537 was decoded: each region slot keeps its own points. */
    val shiftPointsRegion: SpeedLimit? = null,
    /** Whether 5538/5537 read as the layout YABA knows; null until checked on this connection. */
    val shiftCheck: ShiftCheck? = null,
    /** Set on connect when the bike no longer reports what YABA wrote last time. */
    val shiftNotice: String? = null,
    /** S2: muted system sounds as [S2Mute] bits (status block byte 10). */
    val s2MuteMask: Int? = null,
    /** S2: status block byte 15 bits 0..2, kept raw until a real S2 shows what they mean (plan A6.4). */
    val s2RunBits: Int? = null,
    /** Latest raw (decrypted) bytes per characteristic short id, for the diagnosis page. S2: "1a54", the status block. */
    val raw: Map<String, ByteArray> = emptyMap(),
    val busy: Set<Op> = emptySet(),
    /** 0..1 while a custom bell uploads. */
    val upload: Float? = null,
    /** 0..1 while the bike's internal log downloads. */
    val logProgress: Float? = null,
    val lastCommandError: String? = null,
    val connectedAtMillis: Long? = null,
    val reconnects: Int = 0,
    /** The link was already open on this phone (another app held it) and YABA joined it. */
    val sharedLink: Boolean = false,
) {
    val isReady: Boolean get() = link == Link.READY
    /** [versionValues] (and an S2's [deviceInfo]) as (localised label, value), resolved on access so it follows the app language. */
    val versions: List<Pair<String, String>>
        get() = versionValues.map { (ch, v) -> ch.label to v } + deviceInfo.map { (id, v) -> (GattClient.charLabel(id) ?: id.toString()) to v }
    val faults: List<Int> get() = Faults.faultsOnly(faultCodes)
    val wheelMoving: Boolean get() = Faults.WHEEL_MOVING in faultCodes
    val alarmArmed: Boolean? get() = alarmStage?.armed
    val charging: Boolean? get() = chargeState?.charging
    /** The bike has no charger-plug bit; a plugged charger only shows while it charges. */
    val chargerPlugged: Boolean? get() = chargeState?.charging
    /** False while the pack-present line (BAT_KEY_IN) reads low: one of the shipping-mode conditions. */
    val batteryDetected: Boolean? get() = chargeState?.batteryDetected
    /**
     * Really rolling. Not [BikeStatus.RIDING]: the firmware reports that (state 0x0C) for any unlocked,
     * ready-to-ride bike, standing in a kitchen included.
     */
    val moving: Boolean get() = (speedKmh ?: 0) >= 3 || wheelMoving

    /** The shift points of the region in use; null right after a region change until 5537 is read again. */
    val shiftPointsCurrent: ShiftPoints? get() = shiftPoints?.takeIf { shiftPointsRegion == speedLimit }
    /** The profile the bike's points match; null = "Eigene" (or not read yet). */
    val shiftProfile: ShiftProfile? get() = shiftPointsCurrent?.let { ShiftProfiles.match(it, speedLimit) }
    /** The owner wants mode and points changeable while riding too; the firmware applies them at the next shift. */
    val canChangeShifting: Boolean
        get() = isReady
    /** Shifting writes are offered: connected, both characteristics there, and their values look as expected. */
    val shiftWritable: Boolean
        get() = canChangeShifting && shiftSupported && shiftCheck == ShiftCheck.OK
    /** Gear not engaged (43) or shifter not responding (44). */
    val shifterFault: Int? get() = faults.firstOrNull { it == 43 || it == 44 }

    /** A fault code in this family's own table: the S2 numbers mean other things than the S3 bits. */
    fun describeFault(code: Int): Faults.Fault = if (family == BikeFamily.S2) S2Faults.describe(code) else Faults.describe(code)

    /**
     * 37 km/h existed up to 1.7.6, power level 5 below 1.7.6 (mooovy's gate); off-road left BLE in 1.8.2.
     * On 1.7.6 itself the region-lock byte decides (1.07.06: level 5 only when free, off-road unless locked).
     * S3 version numbers: an S2's firmware says nothing about them ([BikeFamily.usesS3FirmwareGates]).
     */
    val firmwareHasNoLimit: Boolean
        get() = family.usesS3FirmwareGates && firmware?.let { compareVersions(it, "1.7.6") <= 0 && regionLock != 2 } ?: false
    val firmwareHasLevel5: Boolean
        get() = family.usesS3FirmwareGates && firmware?.let {
            val cmp = compareVersions(it, "1.7.6")
            (cmp < 0 && (regionLock == null || regionLock == 0)) || (cmp == 0 && regionLock == 0)
        } ?: false
}

/**
 * Owns the link to one bike for the whole app process: finds it, connects, picks the bike family's
 * [BikeDriver] by the GATT services and lets it authenticate, keeps state fresh (notifications + polling),
 * reconnects when the bike drops away, records what changed into the [EventLog], and runs commands one at a
 * time. Everything that knows characteristics is in the drivers ([S3Driver], [S2Driver]); this class is the
 * lifecycle and the drivers' [DriverHost]. The public command API is unchanged and runs on the connection
 * that was live when the command was queued; a command the family lacks is refused ([CommandGate]).
 */
class BikeController(
    context: Context,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {
    private val gatt = GattClient(context.applicationContext, onAdapterBackOn = { retryNow() })
    private val _state = MutableStateFlow(BikeState())
    val state: StateFlow<BikeState> = _state.asStateFlow()

    private val _s2Blocks = MutableSharedFlow<S2Block>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * S2: every decoded status block in arrival order, unchanged ones too, for the drive diagnosis (ui/s2). The
     * state keeps only the latest block; this hands each one over. Nothing is replayed to a late collector.
     */
    val s2Blocks: SharedFlow<S2Block> = _s2Blocks.asSharedFlow()

    /**
     * The authenticated link (the old `session` field): set right after authentication, cleared when the link
     * goes. Each authentication makes a new one, so the poll loop and the collector know theirs, and a command
     * keeps the one it was queued on.
     */
    @Volatile private var connection: BikeConnection? = null

    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var notifyJob: Job? = null
    private val commandLock = Mutex()
    private val applyLock = Any()
    @Volatile private var wantConnected = false
    @Volatile private var paused = false

    /** What the drivers may do to this controller. */
    private val host = object : DriverHost {
        override val state: BikeState get() = _state.value

        override fun update(reduce: (BikeState) -> BikeState) = _state.update(reduce)

        override fun transition(reduce: (BikeState) -> BikeState): Pair<BikeState, BikeState> =
            synchronized(applyLock) {
                val b = _state.value
                val a = reduce(b)
                _state.value = a
                b to a
            }

        override fun recordChanges(before: BikeState, after: BikeState) = this@BikeController.recordChanges(before, after)

        override fun log(kind: EventLog.Kind, message: String, detail: String?, code: EventLog.Code?) =
            this@BikeController.log.add(kind, message, detail, code)

        override fun onUnlockRequest() = this@BikeController.onUnlockRequest()

        override var paused: Boolean
            get() = this@BikeController.paused
            set(value) { this@BikeController.paused = value }

        override val scope: CoroutineScope get() = this@BikeController.scope

        override suspend fun <T> withCommandLock(block: suspend () -> T): T = commandLock.withLock { block() }

        override fun onS2Frame(frame: S2Frame, pushed: Boolean) {
            _s2Blocks.tryEmit(S2Block(System.currentTimeMillis(), frame, pushed, _state.value.bike?.normalizedMac))
        }

        override val guardWatch: Boolean get() = guardArmed

        override fun onValue(ch: Gatt.Char, value: ByteArray, pushed: Boolean) {
            if (guardArmed) _valueTap.tryEmit(ValueTap(System.currentTimeMillis(), ch, value, pushed))
        }
    }

    /**
     * True while the Wache owns the bike (every [GuardPhase] but OFF, set by [BikeGuard]). Touch Unlock never unlocks
     * then, the commands in [GUARD_REFUSED] are refused, and the S3 poll reads the Wache's fast batch
     * (the guard design 2.2, 2.4).
     */
    @Volatile var guardArmed: Boolean = false

    private val _valueTap = MutableSharedFlow<ValueTap>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every decoded S3 value while [guardArmed] (nothing otherwise), for the Wache's record of the first real test. */
    val valueTap: SharedFlow<ValueTap> = _valueTap.asSharedFlow()

    /** The S3/X3 driver; also home to shifting, Speed++ and the Bike-Lab session. */
    private val s3 = S3Driver(context, gatt, host)

    /** S2 test mode: the unverified S2 commands run only while it is on (plan A1). */
    val s2Prefs = S2Prefs(context.applicationContext)

    /** The S2/X2 driver (experimental, plan B4). */
    private val s2 = S2Driver(GattClientPort(gatt), host, testMode = { s2Prefs.testMode.value })

    init {
        // Test mode changes what an S2 offers; an S3's features never depend on it.
        scope.launch {
            s2Prefs.testMode.collect { on ->
                _state.update { st -> if (st.family == BikeFamily.S2) st.copy(features = featuresFor(BikeFamily.S2, on)) else st }
            }
        }
    }

    // --- Feature-module hooks (ble/BikeLab.kt is a separate file; see the firmware notes) ---
    /** The authenticated S3 session while the link is READY, for feature modules that live in other files. */
    val featureSession: BikeSession? get() = s3.session.takeIf { _state.value.isReady }
    /** Raw (still encrypted) GATT notifications, so a feature module can watch its own characteristics. */
    val featureNotifications: kotlinx.coroutines.flow.SharedFlow<GattClient.Notification> get() = gatt.notifications
    /** Negotiated ATT MTU, for a feature module that streams blocks (clip replace). */
    val mtu: Int get() = gatt.mtu

    private val pollClaims = PollClaims(SLOW_POLL_MS)
    private val _pollInterval = MutableStateFlow(SLOW_POLL_MS)

    /** The poll period that applies now: the fastest claim ([requestPoll]), else every [SLOW_POLL_MS]. */
    val pollInterval: StateFlow<Long> = _pollInterval.asStateFlow()

    /** Poll period right now ([pollInterval]). */
    val pollIntervalMs: Long get() = _pollInterval.value

    /**
     * [owner] wants the bike read every [ms] (null: no longer). The fastest claim wins, so the ride logger (1.5 s),
     * the cockpit (1.5 s) and the S2 drive diagnosis (1 s, the wiggle test 0.7 s) never undo each other.
     */
    fun requestPoll(owner: String, ms: Long?) {
        _pollInterval.value = pollClaims.set(owner, ms)
    }

    /** The bike whose connect ended in a [BikeRefusedException]; a model change for it in the garage reconnects. */
    @Volatile private var refusedMac: String? = null

    /** Touch Unlock: when the bike asks (bell/boost pressed while we are connected), unlock it. */
    @Volatile var touchUnlock: Boolean = false

    /**
     * [bike]'s factory wheel size for Speed++ ([SpeedPlus.stock]): the live one while the state holds that bike and has read
     * its size, else what the model name, the stored reads and the owner's answer say.
     */
    fun wheelStock(bike: BikeCredentials?): WheelStock {
        val st = _state.value
        val same = bike != null && st.bike?.normalizedMac == bike.normalizedMac
        if (same) st.wheelStock?.let { return it }
        val deviceModel = if (same) st.versionValues.firstOrNull { it.first == Gatt.MODEL }?.second else null
        return s3.wheelStock(bike, deviceModel, current = null)
    }

    /** [bike]'s wheel size as the state holds it, else as YABA read it last (the greyed Speed++ switch while not connected). */
    fun lastWheelSize(bike: BikeCredentials?): Int? {
        val st = _state.value
        if (bike != null && st.bike?.normalizedMac == bike.normalizedMac) st.wheelSize?.let { return it }
        return s3.lastWheelSize(bike)
    }

    /** The owner's answer to "28″ (S3) or 24″ (X3)" for the bike in the state ([S3Driver.setStockWheel]); nothing is sent. */
    fun setStockWheel(wheel: Int) {
        val bike = _state.value.bike ?: return
        s3.setStockWheel(bike, wheel)
    }

    /** Read-only overview of the connected bike's GATT layout (S2 test mode); null while not connected. */
    fun describeServices(): String? = gatt.describeServices()

    fun connect(bike: BikeCredentials) {
        val cur = _state.value
        if (wantConnected && cur.bike == bike && cur.link != Link.OFFLINE) return
        disconnect(silent = true)
        s3.demoShiftLink = null
        refusedMac = null
        // SmartBike and S/X 1: there is nothing YABA could say to them, so it does not even search (no retries).
        if (bike.family == BikeFamily.LEGACY_UNSUPPORTED) {
            val msg = I18n.get(R.string.core_error_legacy_unsupported)
            _state.value = BikeState(bike = bike, family = bike.family, features = featuresOf(bike.family), link = Link.LOST, linkMessage = msg)
            log.add(EventLog.Kind.ERROR, msg, bike.name)
            return
        }
        if (!BikeCipher.isValidKey(bike.encryptionKey.trim()) || bike.userKeyId !in 0..255) {
            _state.value = BikeState(
                bike = bike, family = bike.family, features = featuresOf(bike.family),
                link = Link.OFFLINE, linkMessage = I18n.get(R.string.core_link_key_invalid),
            )
            log.add(EventLog.Kind.ERROR, I18n.get(R.string.core_log_key_invalid, bike.name))
            return
        }
        wantConnected = true
        _state.value = BikeState(bike = bike, family = bike.family, features = featuresOf(bike.family), link = Link.SEARCHING)
        log.add(EventLog.Kind.LINK, I18n.get(R.string.core_log_searching, bike.name), bike.advertisedNames.first())
        connectJob = scope.launch { connectLoop(bike) }
    }

    fun disconnect(silent: Boolean = false) {
        wantConnected = false
        connectJob?.cancel(); connectJob = null
        stopLiveJobs()
        dropConnection()
        gatt.close()
        val bike = _state.value.bike
        // Keep the last known values so Diagnose still shows what the bike reported before.
        _state.update {
            it.copy(link = Link.OFFLINE, linkMessage = null, busy = emptySet(), upload = null, logProgress = null, speedKmh = null, shippingImminent = false)
        }
        if (!silent && bike != null) log.add(EventLog.Kind.LINK, I18n.get(R.string.core_log_disconnected))
    }

    /**
     * Keeps the controller's credentials in sync with the vault (login, refresh, import, rename).
     * A changed key or user key id reconnects with the new credentials, and so does a model change to or from
     * S2/X2 (another scan and driver).
     */
    fun updateCredentials(bikes: List<BikeCredentials>) {
        val cur = _state.value.bike ?: return
        val fresh = bikes.firstOrNull { it.normalizedMac == cur.normalizedMac } ?: return
        if (fresh == cur) return
        val keyChanged = !fresh.encryptionKey.equals(cur.encryptionKey, ignoreCase = true) || fresh.userKeyId != cur.userKeyId
        val s2Changed = fresh.family != cur.family && (fresh.family == BikeFamily.S2 || cur.family == BikeFamily.S2)
        // The model was set in the garage for the bike that was just refused for its unknown model: try again.
        val retryRefused = s2Changed && refusedMac == cur.normalizedMac
        if ((keyChanged || s2Changed) && (wantConnected || retryRefused)) {
            connect(fresh)
        } else {
            _state.update { it.copy(bike = fresh) }
        }
    }

    /** Retry now instead of waiting for the backoff (e.g. after the user woke the bike). */
    fun retryNow() {
        val bike = _state.value.bike ?: return
        if (_state.value.link == Link.READY || !wantConnected && _state.value.link == Link.OFFLINE) return
        connectJob?.cancel()
        gatt.close()
        wantConnected = true
        connectJob = scope.launch { connectLoop(bike) }
    }

    private suspend fun connectLoop(bike: BikeCredentials) {
        var attempt = 0
        while (wantConnected && coroutineContext.isActive) {
            try {
                establish(bike, attempt)
                attempt = 0
                gatt.link.first { it == GattClient.Link.Disconnected }
                onLinkLost()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BikeRefusedException) {
                // The bike answered and was refused for its model: the next attempt would end the same way. Stop, and
                // keep the reason on screen (wakeHint would replace it with "not found" from the second attempt on).
                wantConnected = false
                refusedMac = bike.normalizedMac
                val msg = e.message ?: I18n.get(R.string.core_error_connect_failed)
                log.add(EventLog.Kind.ERROR, msg)
                _state.update { it.copy(link = Link.LOST, linkMessage = msg) }
            } catch (e: Exception) {
                attempt++
                val msg = e.message ?: I18n.get(R.string.core_error_connect_failed)
                log.add(EventLog.Kind.ERROR, msg)
                _state.update { it.copy(link = Link.LOST, linkMessage = wakeHint(msg, attempt)) }
            } finally {
                stopLiveJobs()
                dropConnection()
            }
            if (!wantConnected) break
            delay(backoffMs(attempt))
            _state.update { it.copy(link = Link.SEARCHING) }
        }
    }

    private suspend fun establish(bike: BikeCredentials, attempt: Int) {
        _state.update { it.copy(link = Link.SEARCHING) }
        if (bike.family == BikeFamily.LEGACY_UNSUPPORTED) throw BikeRefusedException(I18n.get(R.string.core_error_legacy_unsupported))
        if (!gatt.bluetoothOn) throw GattException(I18n.get(R.string.core_error_bluetooth_off))
        val spec = ScanSpec.forBike(bike)
        // Already linked to this phone (the VanMoof app in the background)? Join that link instead of scanning.
        val shared = gatt.alreadyConnected(spec)
        if (shared != null) log.add(EventLog.Kind.INFO, I18n.get(R.string.official_joined_link))
        _state.update { it.copy(sharedLink = shared != null) }
        // The account MAC is not always the advertised address: scanning by name finds it either way.
        val device = shared
            ?: (if (gatt.canScan) gatt.scan(spec, timeoutMs = if (attempt < 2) 12_000 else 20_000) else null)
            ?: gatt.deviceFor(bike.normalizedMac)
            ?: throw GattException(I18n.get(R.string.core_error_invalid_mac, bike.mac))
        _state.update { it.copy(link = Link.CONNECTING) }
        gatt.connect(device)
        try {
            val d = chooseDriver(bike)
            d.prepare()
            _state.update { it.copy(link = Link.AUTHENTICATING) }
            val c = d.authenticate(bike)
            connection = c
            val now = System.currentTimeMillis()
            _state.update {
                d.onReady(
                    it.copy(
                        link = Link.READY,
                        linkMessage = null,
                        shippingImminent = false,
                        connectedAtMillis = now,
                        reconnects = if (it.connectedAtMillis != null) it.reconnects + 1 else it.reconnects,
                        family = d.family,
                        features = featuresOf(d.family),
                    ),
                )
            }
            log.add(EventLog.Kind.LINK, I18n.get(R.string.core_log_connected), I18n.get(R.string.core_log_mtu, gatt.mtu))
            startNotifications(c)
            c.start()
            startPolling(c)
        } catch (t: Throwable) {
            gatt.close()
            throw t
        }
    }

    /**
     * Which driver gets this link (plan B3). The GATT services decide; the account profile is only a hint.
     * The S3 service (6acc5500) always means the S3 driver, whatever the profile says. An S2 profile gets the
     * S2 driver otherwise (it checks for 8e7f1a50 itself). Without an S2 profile, 8e7f1a50 (shared by S1, S2 and
     * SmartBike) is refused: YABA does not guess between their protocols. Everything else goes to the S3 driver,
     * which refreshes the cache once and reports "not an S3", as always (or this refusal, if 8e7f1a50 only shows
     * up after its refresh).
     */
    private suspend fun chooseDriver(bike: BikeCredentials): BikeDriver {
        val family = bike.family
        if (family == BikeFamily.S2 && !gatt.hasService(Gatt.SECURITY) && !gatt.hasService(S2Gatt.SERVICE)) {
            // Neither family's service: drop Android's GATT cache and look once more before deciding.
            gatt.refreshCache()
            gatt.rediscover()
        }
        if (gatt.hasService(Gatt.SECURITY)) {
            if (family == BikeFamily.S2) log.add(EventLog.Kind.INFO, I18n.get(R.string.core_log_s3_despite_profile, bike.bleProfile ?: UNKNOWN))
            return s3
        }
        if (family == BikeFamily.S2) return s2
        if (gatt.hasService(S2Gatt.SERVICE)) bike.refusalFamily.sharedServiceRefusal?.let { throw BikeRefusedException(I18n.get(it)) }
        return s3
    }

    /** The link is gone: forget its connection (the old `session = null`), in every driver. */
    private fun dropConnection() {
        connection = null
        s3.onLinkDown()
        s2.onLinkDown()
    }

    private fun featuresOf(family: BikeFamily): Set<Feature> = featuresFor(family, s2Prefs.testMode.value)

    private fun onLinkLost() {
        val st = _state.value
        val moving = st.moving || st.shippingImminent
        val detail = buildList {
            add(I18n.get(R.string.core_detail_status, st.status?.label ?: UNKNOWN))
            if (st.shippingImminent) add(I18n.get(R.string.core_detail_shipping))
            st.battery?.let { add(I18n.get(R.string.core_detail_battery, it)) }
            st.speedKmh?.let { add(I18n.get(R.string.core_detail_speed, it)) }
            st.supplyOk?.let { add(I18n.get(if (it) R.string.core_detail_supply_ok else R.string.core_detail_supply_low)) }
            st.lock?.let { add(I18n.get(R.string.core_detail_lock, lockLabel(it))) }
            if (st.faults.isNotEmpty()) add(I18n.get(R.string.core_detail_faults, st.faults.joinToString()))
        }.joinToString(", ")
        log.add(
            if (moving) EventLog.Kind.ALERT else EventLog.Kind.LINK,
            I18n.get(if (moving) R.string.core_log_link_lost_riding else R.string.core_log_link_lost),
            detail,
            if (moving) EventLog.Code.LINK_LOST_RIDING else null,
        )
        _state.update { it.copy(link = Link.LOST, speedKmh = null, speedRawKmh = null) }
    }

    private fun stopLiveJobs() {
        pollJob?.cancel(); pollJob = null
        notifyJob?.cancel(); notifyJob = null
    }

    private suspend fun startNotifications(c: BikeConnection) {
        notifyJob?.cancel()
        notifyJob = scope.launch {
            gatt.notifications.collect { n -> c.onNotification(n) }
        }
        val count = c.subscribe()
        log.add(EventLog.Kind.INFO, I18n.plural(R.plurals.core_log_subscribed, count, count))
    }

    private fun startPolling(c: BikeConnection) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            val s2 = c.driver.family == BikeFamily.S2
            while (isActive && connection === c) {
                val waitMs = pollIntervalMs
                if (s2) {
                    // S2: a faster claim (the drive page, the wiggle test) cuts the current wait short, so its first
                    // block does not trail up to 5 s behind. The S3 keeps its cadence exactly as before.
                    withTimeoutOrNull(waitMs) { _pollInterval.first { it < waitMs } }
                } else {
                    delay(waitMs)
                }
                if (paused) continue
                c.poll(tick)
                tick++
            }
        }
    }

    fun refresh() = command(Op.REFRESH, I18n.get(R.string.core_cmd_refresh), logIt = false) { c -> c.refresh(includeVersions = true) }

    /** Reads every diagnosis characteristic once and stores the raw bytes. */
    fun readDiagnostics() = command(Op.REFRESH, I18n.get(R.string.core_cmd_read_raw)) { c -> c.readDiagnostics() }

    private fun recordChanges(before: BikeState, after: BikeState) {
        val moving = after.moving
        if (!before.shippingImminent && after.shippingImminent) {
            log.add(
                EventLog.Kind.ALERT,
                I18n.get(R.string.core_log_shipping),
                I18n.get(
                    R.string.core_detail_shipping_state,
                    after.speedKmh ?: 0,
                    after.lock?.let(::lockLabel) ?: UNKNOWN,
                    after.faults.joinToString().ifEmpty { I18n.get(R.string.core_none) },
                ),
                EventLog.Code.SHIPPING,
            )
        }
        if (before.lock != after.lock && after.lock != null) {
            // The kick-lock switch (PC8) reading "locked" while riding is exactly what LOCK_STATE = 1 shows.
            if (after.lock == LockState.LOCKED && moving) {
                log.add(
                    EventLog.Kind.ALERT,
                    I18n.get(R.string.core_log_kicklock_riding),
                    I18n.get(R.string.core_detail_speed, after.speedKmh ?: 0),
                    EventLog.Code.KICKLOCK_RIDING,
                )
            } else {
                log.add(EventLog.Kind.STATE, I18n.get(R.string.core_log_lock, lockLabel(after.lock)))
            }
        }
        if (before.status != after.status && after.status != null) {
            val shipping = after.status == BikeStatus.SHIPPING
            val alarming = shipping || (moving && after.status == BikeStatus.SLEEPING)
            log.add(
                if (alarming) EventLog.Kind.ALERT else EventLog.Kind.STATE,
                I18n.get(R.string.core_log_status, after.status.label),
                I18n.get(R.string.core_detail_status_before, before.status?.label ?: UNKNOWN),
                if (shipping && !after.shippingImminent) EventLog.Code.SHIPPING else null,
            )
        }
        val newFaults = after.faults - before.faults.toSet()
        for (code in newFaults) {
            val f = after.describeFault(code)
            log.add(EventLog.Kind.ALERT, I18n.get(R.string.core_log_fault, code, f.name), f.hint, EventLog.Code.FAULT)
        }
        val cleared = before.faults - after.faults.toSet()
        if (cleared.isNotEmpty() && before.faultCodes.isNotEmpty()) log.add(EventLog.Kind.STATE, I18n.get(R.string.core_log_faults_cleared, cleared.joinToString()))
        val btnBefore = before.buttons?.powerButton
        val btnAfter = after.buttons?.powerButton
        if (btnBefore == false && btnAfter == true && moving) {
            log.add(
                EventLog.Kind.ALERT,
                I18n.get(R.string.core_log_power_button_riding),
                I18n.get(R.string.core_detail_speed, after.speedKmh ?: 0),
            )
        }
        if (before.supplyOk == true && after.supplyOk == false) {
            log.add(
                EventLog.Kind.ALERT,
                I18n.get(R.string.core_log_supply_low),
                I18n.get(R.string.core_detail_supply_low_state, after.speedKmh ?: 0, after.battery?.toString() ?: UNKNOWN),
                EventLog.Code.SUPPLY_LOW,
            )
        }
        if (before.supplyOk == false && after.supplyOk == true) log.add(EventLog.Kind.STATE, I18n.get(R.string.core_log_supply_ok))
        val plugBefore = before.chargerPlugged
        val plugAfter = after.chargerPlugged
        if (plugBefore != null && plugAfter != null && plugBefore != plugAfter) {
            log.add(EventLog.Kind.STATE, I18n.get(if (plugAfter) R.string.core_log_charger_in else R.string.core_log_charger_out))
        }
        if (before.batteryDetected == true && after.batteryDetected == false) {
            log.add(
                EventLog.Kind.ALERT,
                I18n.get(R.string.core_log_battery_missing),
                I18n.get(R.string.core_detail_supply_low_state, after.speedKmh ?: 0, after.battery?.toString() ?: UNKNOWN),
            )
        }
        if (before.batteryDetected == false && after.batteryDetected == true) log.add(EventLog.Kind.STATE, I18n.get(R.string.core_log_battery_back))
        if (before.defence != null && after.defence != null && before.defence != after.defence && after.defence.alarming) {
            // While the Wache is armed, 5523 = 2 is its normal watching state, not an incident.
            val watching = guardArmed && after.defence == DefenceState.ALARM_ONE
            log.add(if (watching) EventLog.Kind.STATE else EventLog.Kind.ALERT, I18n.get(R.string.core_log_alarm, after.defence.label))
        }
        if (before.alarmStage != null && after.alarmStage != null && before.alarmStage != after.alarmStage) {
            log.add(EventLog.Kind.STATE, I18n.get(if (after.alarmStage.armed) R.string.core_alarm_on else R.string.core_alarm_off))
        }
    }

    private fun onUnlockRequest() {
        log.add(EventLog.Kind.STATE, I18n.get(R.string.core_log_unlock_request))
        // While the Wache is armed a request is a motion report (the firmware asks during its PRE_M1 settle): never unlock.
        if (guardArmed) return
        if (touchUnlock && _state.value.lock != LockState.UNLOCKED) unlock()
    }

    // --- Commands (each one runs on the connection live when it was queued; see BikeDriver, S3Driver, S2Driver) ---

    fun unlock() = command(Op.UNLOCK, I18n.get(R.string.core_cmd_unlock), needs = Feature.UNLOCK) { c -> c.unlock() }

    fun lock() = command(Op.LOCK, I18n.get(R.string.core_cmd_lock), needs = Feature.REMOTE_LOCK) { c -> c.lock() }

    /** The S2 driver itself keeps off-road (37 km/h) to test mode; the S3 offers what its firmware has. */
    fun setSpeedLimit(limit: SpeedLimit) = command(Op.SPEED, I18n.get(R.string.core_cmd_speed_limit, limit.kmh), needs = Feature.REGION) { c ->
        c.setSpeedLimit(limit)
    }

    /** Speed++ on ([SpeedPlus.WHEEL_24]) or back to stock; S3 only ([S3Driver.setWheelSize]). */
    fun setWheelSize(wheel: Int) = s3Command(Op.SPEED, I18n.get(R.string.speedplus_cmd, if (wheel == SpeedPlus.WHEEL_24) 24 else 28)) { c ->
        c.setWheelSize(wheel)
    }

    fun setPowerLevel(level: Int) = command(Op.POWER, I18n.get(R.string.core_cmd_level, level), needs = Feature.POWER_LEVEL) { c -> c.setPowerLevel(level) }

    /**
     * POWER_LEVEL (5534) byte 1 for the [BikeLab] (S3 only, [S3Driver.setBoostInMotion]). Runs on the command
     * lock so it and [setPowerLevel] never race over the same characteristic. [onDone] gets null on success.
     */
    fun setBoostInMotion(enabled: Boolean, onDone: (String?) -> Unit = {}) =
        s3Command(
            Op.POWER,
            I18n.get(if (enabled) R.string.labx_cmd_boost_rolling_on else R.string.labx_cmd_boost_rolling_off),
            onNotConnected = { onDone(I18n.get(R.string.core_error_not_connected_wake)) },
            onUnsupported = { onDone(I18n.get(R.string.core_error_not_on_this_bike)) },
        ) { c ->
            c.setBoostInMotion(enabled, onDone)
        }

    /**
     * E_SHIFTER_MODE (5538): automatic or manual, riding or not (the owner's call). The firmware echoes
     * and persists the byte (one flash write); see [ShiftCommands] for the reads and limits around it.
     */
    fun setShiftMode(mode: ShiftMode, onDone: (Boolean) -> Unit = {}) = shiftCommand(
        s3.shiftWhat(I18n.get(R.string.shift_cmd_mode, mode.label)),
        onDone,
    ) { link, mac ->
        s3.setShiftMode(link, mac, mode)
    }

    /** E_SHIFT_POINTS (5537): a profile, resolved inside the command for the region the bike reports then. */
    fun setShiftProfile(profile: ShiftProfile, onDone: (Boolean) -> Unit = {}) = shiftCommand(
        s3.shiftWhat(I18n.get(R.string.shift_cmd_points, profile.label)),
        onDone,
    ) { link, mac ->
        s3.setShiftPoints(link, mac, ShiftTarget.Profile(profile))
    }

    /**
     * E_SHIFT_POINTS (5537): the rider's own set, applied while the app showed [region]. Only sets that
     * pass [ShiftRules] (the firmware checks nothing); refused when the bike is in another region by now.
     */
    fun setShiftPoints(points: ShiftPoints, region: SpeedLimit?, onDone: (Boolean) -> Unit = {}) = shiftCommand(
        s3.shiftWhat(
            I18n.get(R.string.shift_cmd_points, ShiftProfiles.match(points, region)?.label ?: I18n.get(R.string.shift_profile_custom)),
        ),
        onDone,
    ) { link, mac ->
        s3.setShiftPoints(link, mac, ShiftTarget.Custom(points, region))
    }

    /** Runs a shifting command on the S3 session (or the demo stand-in); [onDone] reports success either way. */
    private fun shiftCommand(what: String, onDone: (Boolean) -> Unit, block: suspend (ShiftLink, String?) -> Unit) {
        if (refusedOffS3(what) { onDone(false) }) return
        val link: ShiftLink? = s3.shiftLink()
        launchCommand(Op.SHIFT, what, logIt = true, onNotConnected = { onDone(false) }, link = link) { l ->
            try {
                block(l, _state.value.bike?.normalizedMac)
                _state.update { it.copy(shiftNotice = null) }
                onDone(true)
            } catch (e: Exception) {
                onDone(false)
                throw e
            }
        }
    }

    fun setLight(mode: LightMode) = command(Op.LIGHT, I18n.get(R.string.core_cmd_light, mode.label), needs = Feature.LIGHT) { c -> c.setLight(mode) }

    /**
     * Alarm on/off (an S2 takes "on" as manual). The core never writes ALARM_STATE (5523); only the Bike-Lab
     * experiments do, parked-only.
     */
    fun setAlarm(on: Boolean) = command(Op.ALARM, I18n.get(if (on) R.string.core_alarm_on else R.string.core_alarm_off)) { c ->
        c.setAlarm(if (on) AlarmStage.ARMED else AlarmStage.DISARMED)
    }

    /** S2: off, manual ([AlarmStage.ARMED]) or automatic. [AlarmStage.AUTOMATIC] needs the S2's three-way alarm. */
    fun setAlarmStage(stage: AlarmStage) = command(
        Op.ALARM,
        I18n.get(R.string.core_cmd_alarm_stage, stage.label),
        needs = if (stage == AlarmStage.AUTOMATIC) Feature.ALARM_THREE_WAY else null,
    ) { c -> c.setAlarm(stage) }

    fun setBell(tone: BellTone) = command(Op.BELL, I18n.get(R.string.core_cmd_bell, tone.label), needs = Feature.BELL_TONE) { c -> c.setBell(tone) }

    fun play(sound: Sound) = command(Op.SOUND, sound.label, logIt = false, needs = Feature.SOUNDBOARD) { c -> c.play(sound) }

    fun setUnit(unit: UnitSystem) = command(Op.UNIT, I18n.get(R.string.core_cmd_unit, unit.label), needs = Feature.UNIT) { c -> c.setUnit(unit) }

    /** Wakes the bike from standby. Refused while riding. */
    fun wakeUp() = command(Op.WAKE, I18n.get(R.string.core_cmd_wake), needs = Feature.WAKE) { c -> c.wake() }

    /** Backup code: three digits 1–9, or null to clear it (S3 only). */
    fun setBackupCode(code: String?) = command(
        Op.BACKUP_CODE,
        I18n.get(if (code == null) R.string.core_cmd_backup_cleared else R.string.core_cmd_backup_set),
        needs = if (code == null) Feature.BACKUP_CODE_CLEAR else Feature.BACKUP_CODE_SET,
    ) { c -> c.setBackupCode(code) }

    /** S2 test mode: mutes the system sounds in [mask] ([S2Mute] bits); 0 unmutes them all. */
    fun setMutedSounds(mask: Int) = command(Op.MUTE, I18n.get(R.string.core_cmd_s2_mute), needs = Feature.MUTE_SOUNDS) { c ->
        c.setMutedSounds(mask)
    }

    /** Downloads the bike's own log; [onDone] gets the text, or null. Read it right after a shutdown. */
    fun readBikeLog(onDone: (String?) -> Unit) = command(
        Op.BIKE_LOG, I18n.get(R.string.core_cmd_bike_log), needs = Feature.BIKE_LOG, onNotConnected = { onDone(null) },
    ) { c ->
        c.readBikeLog(onDone)
    }

    /** Custom bell into the Foghorn slot; [onDone] reports success. */
    fun uploadBell(file: ByteArray, onDone: (Boolean) -> Unit = {}) = command(
        Op.UPLOAD, I18n.get(R.string.core_cmd_custom_bell), needs = Feature.CUSTOM_BELL, onNotConnected = { onDone(false) },
    ) { c ->
        c.uploadBell(file, onDone)
    }

    /**
     * Replaces one sound clip for the [BikeLab] (S3 only, [S3Driver.uploadClip]). Runs as a controller command
     * (Op.UPLOAD, command lock, polling paused) so it never interleaves with a bell upload or a log download.
     */
    fun uploadClip(
        metadata: ByteArray,
        file: ByteArray,
        onProgress: (Float) -> Unit = {},
        onDone: (Boolean) -> Unit = {},
    ) = s3Command(Op.UPLOAD, I18n.get(R.string.core_cmd_custom_bell), onNotConnected = { onDone(false) }) { c ->
        c.uploadClip(metadata, file, onProgress, onDone)
    }

    fun clearCommandError() = _state.update { it.copy(lastCommandError = null) }

    /**
     * Debug builds only: show a canned state without a bike (used by the demo activity). [shiftLink]
     * stands in for the bike so the shifting commands run their real reads, writes and read-backs.
     */
    /** Debug builds only: one S2 status block as if the bike had sent it (the S2 demo activity). */
    internal fun demoS2Frame(frame: S2Frame, pushed: Boolean = false) {
        _state.update { S2State.apply(it, frame) }
        host.onS2Frame(frame, pushed)
    }

    internal fun demo(state: BikeState, shiftLink: ShiftLink? = null) {
        disconnect(silent = true)
        s3.demo(shiftLink)
        _state.value = state
    }

    /**
     * One command on the connection live right now (captured here, at queue time). [needs]: the feature the
     * family must offer, else it is refused up front with [onUnsupported] ([CommandGate]); an S3 offers all of
     * its own, so its commands always go through, exactly as before.
     */
    private fun command(
        op: Op,
        what: String,
        logIt: Boolean = true,
        needs: Feature? = null,
        onNotConnected: () -> Unit = {},
        onUnsupported: () -> Unit = onNotConnected,
        block: suspend (BikeConnection) -> Unit,
    ) {
        if (refusedByGuard(op, what, onUnsupported)) return
        val c = connection
        if (c != null && needs != null && _state.value.isReady) {
            val gate = CommandGate.of(c.driver.family, s2Prefs.testMode.value, needs)
            if (gate != CommandGate.OK) return refuse(what, gate, onUnsupported)
        }
        launchCommand(op, what, logIt, onNotConnected, c, block)
    }

    /**
     * A command only the S3 driver has (Speed++, the Bike-Lab boost flag and clip upload). With another
     * driver on the link it is refused with "not on this bike" and [onUnsupported].
     */
    private fun s3Command(
        op: Op,
        what: String,
        logIt: Boolean = true,
        onNotConnected: () -> Unit = {},
        onUnsupported: () -> Unit = onNotConnected,
        block: suspend (S3Driver.Connection) -> Unit,
    ) {
        if (refusedByGuard(op, what, onUnsupported)) return
        if (refusedOffS3(what, onUnsupported)) return
        launchCommand(op, what, logIt, onNotConnected, connection) { c ->
            block(c as? S3Driver.Connection ?: unsupported())
        }
    }

    /** True (and refused) when another family's driver holds the READY link; an S3 link is never refused. */
    private fun refusedOffS3(what: String, onUnsupported: () -> Unit): Boolean {
        val c = connection
        if (c == null || c is S3Driver.Connection || !_state.value.isReady) return false
        refuse(what, CommandGate.UNSUPPORTED, onUnsupported)
        return true
    }

    /**
     * True (and refused, nothing sent) while the Wache owns the bike and [op] would undo its watch or blind it: unlock,
     * alarm, wake, backup code, and the transfers that pause polling for minutes (the guard design 2.4).
     */
    private fun refusedByGuard(op: Op, what: String, onRefused: () -> Unit): Boolean {
        if (!guardArmed || op !in GUARD_REFUSED) return false
        val msg = I18n.get(R.string.guard_core_error_active)
        log.add(EventLog.Kind.ERROR, "$what: $msg")
        _state.update { it.copy(lastCommandError = msg) }
        onRefused()
        return true
    }

    /**
     * One Wache sequence ([Op.GUARD]) on the command lock, on the S3 connection live now; [block] gets the only three
     * characteristics the Wache may write (5521, 5523, 5524) and the reads it needs, each read applied to the state.
     * Bypasses the Wache's own refusals and does not touch [BikeState.lastCommandError]. Throws [GattException] when
     * not READY, when the link is not an S3, or when the link changed while the sequence waited for the lock.
     */
    suspend fun <T> guardCommand(what: String, block: suspend (GuardOps) -> T): T {
        val c = connection
        if (c == null || !_state.value.isReady) throw GattException(I18n.get(R.string.core_error_not_connected_wake))
        val s3c = c as? S3Driver.Connection ?: throw GattException(I18n.get(R.string.guard_core_error_not_s3))
        _state.update { it.copy(busy = it.busy + Op.GUARD) }
        try {
            return commandLock.withLock {
                if (connection !== c || !_state.value.isReady) throw GattException(I18n.get(R.string.core_error_not_connected_wake))
                block(object : GuardOps {
                    override suspend fun read(ch: GuardChar): Int? {
                        if (connection !== c) throw GattException(I18n.get(R.string.core_error_not_connected_wake))
                        return s3c.guardRead(gattOf(ch)).u8()
                    }

                    override suspend fun write(ch: GuardChar, value: Int) {
                        require(ch.writable) { "$what: ${ch.short} is not writable" }
                        if (connection !== c) throw GattException(I18n.get(R.string.core_error_not_connected_wake))
                        s3c.guardWrite(gattOf(ch), value.toByte())
                    }

                    override suspend fun pause(ms: Long) = delay(ms)
                })
            }
        } finally {
            _state.update { it.copy(busy = it.busy - Op.GUARD) }
        }
    }

    private fun gattOf(ch: GuardChar): Gatt.Char = when (ch) {
        GuardChar.LOCK_STATE -> Gatt.LOCK_STATE
        GuardChar.ALARM_STATE -> Gatt.ALARM_STATE
        GuardChar.ALARM_MODE -> Gatt.ALARM_MODE
        GuardChar.MODULE_MODE -> Gatt.MODULE_MODE
    }

    /** A command the family lacks (or has only in S2 test mode): nothing is sent. */
    private fun refuse(what: String, gate: CommandGate, onUnsupported: () -> Unit) {
        val msg = I18n.get(if (gate == CommandGate.TEST_MODE_ONLY) R.string.core_error_s2_test_mode else R.string.core_error_not_on_this_bike)
        log.add(EventLog.Kind.ERROR, "$what: $msg")
        _state.update { it.copy(lastCommandError = msg) }
        onUnsupported()
    }

    /** One command on [link] (a connection, or a stand-in), queued behind the others on the command lock. */
    private fun <L : Any> launchCommand(
        op: Op,
        what: String,
        logIt: Boolean,
        onNotConnected: () -> Unit,
        link: L?,
        block: suspend (L) -> Unit,
    ) {
        if (link == null || !_state.value.isReady) {
            _state.update { it.copy(lastCommandError = I18n.get(R.string.core_error_not_connected_wake)) }
            onNotConnected()
            return
        }
        scope.launch {
            _state.update { it.copy(busy = it.busy + op, lastCommandError = null) }
            try {
                commandLock.withLock { block(link) }
                if (logIt) log.add(EventLog.Kind.COMMAND, what)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: I18n.get(R.string.core_cmd_failed, what)
                log.add(EventLog.Kind.ERROR, "$what: $msg")
                _state.update { it.copy(lastCommandError = msg) }
            } finally {
                _state.update { it.copy(busy = it.busy - op) }
            }
        }
    }

    private fun backoffMs(attempt: Int): Long = when {
        attempt <= 1 -> 1_500
        attempt <= 3 -> 4_000
        else -> 10_000
    }

    private fun wakeHint(msg: String, attempt: Int): String = when {
        !gatt.bluetoothOn -> I18n.get(R.string.core_error_bluetooth_off)
        !gatt.canScan && attempt >= 2 -> I18n.get(R.string.core_link_location_off)
        attempt >= 2 -> I18n.get(R.string.core_link_bike_not_found)
        else -> msg
    }

    companion object {
        /** Placeholder for a value the bike has not reported. */
        private const val UNKNOWN = "?"

        /** The poll period while nobody claims a faster one ([requestPoll]). */
        const val SLOW_POLL_MS = 5_000L

        /** Refused while the Wache owns the bike ([guardArmed]); LOCK, LIGHT, SPEED, POWER, BELL, SOUND, UNIT, SHIFT, REFRESH, MUTE stay. */
        val GUARD_REFUSED: Set<Op> = setOf(Op.UNLOCK, Op.ALARM, Op.WAKE, Op.BACKUP_CODE, Op.UPLOAD, Op.BIKE_LOG)

        @StringRes
        fun lockLabelRes(l: LockState): Int = when (l) {
            LockState.UNLOCKED -> R.string.core_lock_unlocked
            LockState.LOCKED -> R.string.core_lock_locked
            LockState.PIN_ENTRY -> R.string.core_lock_pin_entry
        }

        /** Lock state as a lower-case word for use inside sentences. */
        fun lockLabel(l: LockState): String = I18n.get(lockLabelRes(l))
    }
}
