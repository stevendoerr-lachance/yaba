package dev.steven.s3control

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.steven.s3control.ble.ChargeState
import dev.steven.s3control.ble.DefenceState
import dev.steven.s3control.ble.AlarmStage
import dev.steven.s3control.ble.BellTone
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ble.Buttons
import dev.steven.s3control.ble.Faults
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.GuardAlarm
import dev.steven.s3control.ble.GuardEvent
import dev.steven.s3control.ble.GuardIssue
import dev.steven.s3control.ble.GuardPhase
import dev.steven.s3control.ble.GuardState
import dev.steven.s3control.ble.GuardTexts
import dev.steven.s3control.ble.GuardTrigger
import dev.steven.s3control.ble.SirenNote
import dev.steven.s3control.ble.LightMode
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.ble.ShiftCheck
import dev.steven.s3control.ble.ShiftLink
import dev.steven.s3control.ble.ShiftMode
import dev.steven.s3control.ble.ShiftPoints
import dev.steven.s3control.ble.ShiftProfile
import dev.steven.s3control.ble.ShiftProfiles
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ble.SpeedPlus
import dev.steven.s3control.ble.SpeedPlusStore
import dev.steven.s3control.ble.UnitSystem
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ride.BikeLogArchive
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ui.cockpit.CockpitRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

/**
 * Debug-only: seeds a demo bike and a canned live state, then opens the app.
 * adb shell am start -n dev.steven.s3control/.DemoActivity --es mode ready|riding|offline|empty|onboard [--es limit EU|US|JP]
 *   [--es shift standard|flat|hilly|custom|invalid|zero|odd|manual] [--es keys bell|boost|both]
 * e.g. adb shell am start -n dev.steven.s3control/.DemoActivity --es mode riding --es shift flat
 * A stand-in bike answers the shifting commands, so mode and points writes run their real reads,
 * writes and read-backs (zero = all six points 0, odd = values in a layout YABA does not know).
 *
 * Cockpit modes open the handlebar cockpit right away and keep the data fresh (a ride at 15–27 km/h):
 * cockpit, cockpit_lost (the link drops while riding), cockpit_stale (the link stays up but no new
 * values arrive), shipping (shipping mode while riding) and kicklock (coil flicker, then the lock
 * reports locked while riding). [--ei at MS] sets when the event fires after launch (default 12 s,
 * after the intro); [--ei stop MS] lets the bike stand still that long after the event (phase 2 at
 * the stop) and ride on 15 s later.
 *
 * [--ez intro true] plays the opening film (otherwise the demos mark it as seen).
 *
 * The Wache (the guard design section 8), no BLE: the bike stands locked with its own watch on (5523 = 2) and the Wache's state
 * is set by [dev.steven.s3control.ble.BikeGuard.demo] (no service, no writes, no store change):
 *   guard_armed (watching for 47 minutes, the bike confirmed its watch), guard_triggered (ALARM 25 s ago: a turning wheel,
 *   YABA's siren round 1, the bike sounds, 5523 = 3), guard_lost (link lost for a while, the bike's watch confirmed before,
 *   armed 2 hours ago), guard_unconfirmed (watching, but 5523 reads 1), guard_arming (the arm sequence runs),
 *   guard_not_locked (back from a link loss, the lock was found open and YABA wrote nothing: issue NOT_LOCKED with
 *   "Erneut versuchen").
 *   [--ez notify true] also posts the ongoing and the alarm/lost notification as they would look (needs a raw am start).
 * Every other mode sets the Wache back to its live state (normally OFF), so "--es mode ready" restores a clean Fahren.
 *
 * [--ez ridelog true] also writes a demo ride log (RideRecorder's exact CSV) and a saved bike-log
 * download into filesDir/logs, for the log viewer (Diagnose, "Ansehen"). Written once per day.
 *
 * Speed++ (Bike > Experimente, and the question in the onboarding), every state of [SpeedPlus.view]:
 *   [--es model NAME|none] the bike's model name (default "S3 2022", which settles 28″; the account really reports a SKU
 *   such as VM01-203-EU, which settles nothing; X3 settles 24″);
 *   [--es wheel 24|28|none] what 5564 reads now (default 28; none = the read on connect failed);
 *   [--es stock 24|28|none] the first read YABA remembers for the demo MAC (none = forgotten, as after a new install);
 *   [--es answer 24|28|none] the owner's answer to "28″ (S3) | 24″ (X3)" (none = not answered);
 *   [--ez onboarding true] the onboarding is not through, so it shows its question (Laufradgröße ab Werk) when it asks;
 *   [--ei wheelAt MS] the link is ready now and 5564 arrives MS later, as on a real connect (the onboarding's search step
 *   waits for it, at most 8 s).
 * Without stock/answer the stored values stay as they are. E.g. the owner after a new install with Speed++ on:
 *   --es mode ready --es model VM01-203-EU --es wheel 24 --es stock 24 --es answer none
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as S3App).graph
        val mode = intent.getStringExtra("mode") ?: "ready"
        // The opening film is out of the way for every demo (it would cover the screen for 17 s); --ez intro true plays it.
        graph.settings.update { it.copy(introSeen = !intent.getBooleanExtra("intro", false)) }
        val bike = BikeCredentials(
            id = 1001, name = "My S3", ownerName = "Alex", mac = "74:D2:85:AB:CD:EF",
            encryptionKey = "00000000000000000000000000000000", userKeyId = 1,
            modelName = intent.getStringExtra("model").let { if (it == null) "S3 2022" else it.takeUnless { m -> m == "none" } },
            colorPrimary = "#2B2E33", fromAccount = false,
        )
        // Back to the live Wache (normally OFF) unless a guard mode sets it below.
        if (mode !in GUARD_MODES) graph.guard.demo(null)
        if (mode == "empty") {
            graph.account.contents.value.bikes.forEach { graph.account.remove(it.mac) }
            graph.bike.disconnect(silent = true)
            // The first-run flow without the opening film in front of it (clear the app data to see the film).
            graph.settings.update { it.copy(introSeen = true, onboardingDone = false) }
        } else if (mode == "onboard") {
            // A key is stored, the onboarding is not through: Bluetooth access, search and first unlock run for real.
            graph.account.addManual(bike)
            graph.account.setLastBike(bike.mac)
            graph.bike.disconnect(silent = true)
            graph.settings.update { it.copy(autoConnect = false, introSeen = true, onboardingDone = false) }
        } else {
            graph.account.addManual(bike)
            graph.account.setLastBike(bike.mac)
            graph.settings.update { it.copy(autoConnect = false, introSeen = true, onboardingDone = !intent.getBooleanExtra("onboarding", false)) }
            val base = BikeState(
                bike = bike,
                link = Link.READY,
                firmware = "1.9.3",
                versionValues = listOf(
                    Gatt.BIKE_FIRMWARE to "1.9.3", Gatt.BLE_FIRMWARE to "2.4.1", Gatt.CONTROLLER_FIRMWARE to "S.0.00.22",
                    Gatt.PCBA_HARDWARE to "D", Gatt.E_SHIFTER_FIRMWARE to "0.237", Gatt.BATTERY_FIRMWARE to "1.17.1",
                    Gatt.FRAME_NUMBER to "ASY3100000",
                ),
                battery = 87, chargeState = ChargeState.NOT_CHARGING, distanceKm = 4710.5, speedKmh = 0,
                lock = LockState.LOCKED, defence = DefenceState.LOCKED, alarmStage = AlarmStage.ARMED,
                powerLevel = 4, speedLimit = SpeedLimit.US, light = LightMode.AUTO, bell = BellTone.FOGHORN,
                unit = UnitSystem.METRIC, status = BikeStatus.READY, standby = false,
                faultCodes = listOf(42), buttons = Buttons(horn = false, boost = false, powerButton = false),
                // At a standstill the bike sits in gear 1.
                supplyOk = true, gear = 1, connectedAtMillis = System.currentTimeMillis() - 93_000, reconnects = 1,
                shiftSupported = true, shiftMode = ShiftMode.AUTO, shiftCheck = ShiftCheck.OK,
                // The size the bike reads on connect (a 28" S3 at its stock size): the Bike tab shows the Speed++ switch.
                wheelSize = SpeedPlus.WHEEL_28,
            )
            val cockpit = mode in COCKPIT_MODES
            // The Wache's bike: locked, its own watch on (5523 = 2), no fault on the tube.
            val guarded = base.copy(
                lock = LockState.LOCKED, defence = DefenceState.ALARM_ONE, alarmStage = AlarmStage.ARMED, faultCodes = emptyList(),
                status = BikeStatus.READY, raw = base.raw + (Gatt.ALARM_STATE.short to byteArrayOf(2)),
            )
            val state = when (mode) {
                "riding" -> base.copy(speedKmh = 23, gear = 3, lock = LockState.UNLOCKED, status = BikeStatus.RIDING, faultCodes = listOf(48), light = LightMode.ON)
                in COCKPIT_MODES -> base.copy(
                    speedKmh = 21, lock = LockState.UNLOCKED, status = BikeStatus.RIDING, faultCodes = listOf(48),
                    light = LightMode.ON, battery = 64, connectedAtMillis = System.currentTimeMillis() - 754_000,
                    raw = mapOf(Gatt.ERRORS.short to ByteArray(8)),
                )
                "offline" -> base.copy(link = Link.LOST, linkMessage = getString(R.string.core_link_bike_not_found))
                "guard_armed", "guard_arming" -> guarded
                "guard_triggered" -> guarded.copy(defence = DefenceState.ALARM_TWO, raw = guarded.raw + (Gatt.ALARM_STATE.short to byteArrayOf(3)))
                "guard_lost" -> guarded.copy(link = Link.LOST, linkMessage = getString(R.string.core_link_bike_not_found))
                "guard_unconfirmed" -> guarded.copy(defence = DefenceState.LOCKED, raw = guarded.raw + (Gatt.ALARM_STATE.short to byteArrayOf(1)))
                "guard_not_locked" -> guarded.copy(
                    lock = LockState.UNLOCKED, defence = DefenceState.UNLOCKED, raw = guarded.raw + (Gatt.ALARM_STATE.short to byteArrayOf(0)),
                )
                else -> base
            }.let { st -> intent.getStringExtra("limit")?.let { l -> st.copy(speedLimit = SpeedLimit.valueOf(l)) } ?: st }
                .let { st -> withShifting(st, intent.getStringExtra("shift"), intent.getStringExtra("keys")) }
                .let { st -> withSpeedPlus(st, bike) }
            graph.bike.demo(state, DemoShiftBike(state))
            if (mode in GUARD_MODES) {
                graph.guard.demo(guardDemo(mode, bike), notify = intent.getBooleanExtra("notify", false))
                if (mode == "guard_triggered") {
                    graph.log.add(
                        EventLog.Kind.ALERT, GuardTexts.trigger(GuardTrigger.WHEEL),
                        GuardTexts.siren(SirenNote.YABA, 1), EventLog.Code.GUARD_ALARM,
                    )
                }
            }
            val fault = Faults.describe(42)
            graph.log.add(EventLog.Kind.LINK, getString(R.string.core_log_connected), getString(R.string.core_log_mtu, 247))
            graph.log.add(EventLog.Kind.STATE, getString(R.string.core_demo_kicklock_contact), getString(R.string.core_detail_speed, 0))
            graph.log.add(EventLog.Kind.ALERT, getString(R.string.core_log_fault, fault.code, fault.name), fault.hint, EventLog.Code.FAULT)
            graph.log.add(
                EventLog.Kind.ALERT,
                getString(R.string.core_log_link_lost_riding),
                listOf(
                    getString(R.string.core_detail_status, BikeStatus.RIDING.label),
                    getString(R.string.core_detail_battery, 81),
                    getString(R.string.core_detail_speed, 22),
                ).joinToString(", "),
                EventLog.Code.LINK_LOST_RIDING,
            )
        }
        if (mode != "empty" && intent.getBooleanExtra("ridelog", false)) {
            runCatching { DemoLogs.write(this) }
        }
        if (mode in COCKPIT_MODES) {
            CockpitRuntime.requestOpenOnLaunch()
            runCockpitScenario(graph, mode, intent.getIntExtra("at", 12_000).toLong(), intent.getIntExtra("stop", -1).toLong())
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /**
     * The Speed++ extras (see the class comment): the store gets the reads and the answer asked for, and the state the size,
     * stock and factor S3Driver would have worked out from them.
     */
    private fun withSpeedPlus(st: BikeState, bike: BikeCredentials): BikeState {
        fun wheelOf(v: String?): Int? = when (v) {
            "24" -> SpeedPlus.WHEEL_24
            "28" -> SpeedPlus.WHEEL_28
            else -> null
        }
        val store = SpeedPlusStore(this)
        intent.getStringExtra("stock")?.let { v ->
            store.forgetReads(bike)
            wheelOf(v)?.let { store.rememberRead(bike, it) }
        }
        intent.getStringExtra("answer")?.let { v -> store.setAnswer(bike, wheelOf(v)) }
        val wheel = intent.getStringExtra("wheel").let { if (it == null) st.wheelSize else wheelOf(it) }
        // The demo's speed is what the bike reports; the real one follows from the factor, as in S3Driver.
        val raw = st.copy(speedRawKmh = st.speedKmh)
        fun withWheel(s: BikeState, w: Int?): BikeState {
            val stock = if (w == null) null else store.stock(bike, deviceModel = null, current = w)
            val factor = SpeedPlus.factor(stock?.wheel, w)
            return s.copy(wheelSize = w, wheelStock = stock, speedFactor = factor, speedKmh = SpeedPlus.realKmh(s.speedRawKmh, factor))
        }
        val at = intent.getIntExtra("wheelAt", -1)
        if (at < 0) return withWheel(raw, wheel)
        // Like a real connect: the link is ready first (just now), 5564 arrives [at] ms later.
        (application as S3App).graph.let { graph ->
            graph.scope.launch {
                delay(at.toLong())
                if (wheel != null) store.rememberRead(bike, wheel)
                graph.bike.push { withWheel(it, wheel) }
            }
        }
        return withWheel(raw, null).copy(family = BikeFamily.S3, connectedAtMillis = System.currentTimeMillis())
    }

    /** The Wache's state for a guard mode (the guard design section 8), with the lines CORE would have logged. */
    private fun guardDemo(mode: String, bike: BikeCredentials): GuardState {
        val now = System.currentTimeMillis()
        val pushed = getString(R.string.guard_core_log_pushed)
        val read = getString(R.string.guard_core_log_read)
        fun value(ch: Gatt.Char, bytes: String, before: String, source: String) =
            getString(R.string.guard_core_log_value, ch.label, ch.short, bytes, before, source)
        val armedAt = now - 47 * 60_000L
        val nativeLine = GuardEvent(
            armedAt,
            getString(R.string.guard_core_log_armed_native, String.format(resources.configuration.locales[0], "%.1f", 6.5)),
            alert = false,
        )
        val watching = GuardState(
            phase = GuardPhase.WATCHING, bikeWatch = true, defence = 2, sirenImmediate = true,
            armedMac = bike.normalizedMac, armedAtMillis = armedAt,
            events = listOf(
                GuardEvent(now - 12 * 60_000L, value(Gatt.SUPPLY_OK, "01", "00", read), alert = false),
                GuardEvent(armedAt + 9_000L, value(Gatt.MODULE_MODE, "02", "01", pushed), alert = false),
                nativeLine,
            ),
        )
        return when (mode) {
            "guard_arming" -> watching.copy(phase = GuardPhase.ARMING, bikeWatch = false, defence = null, armedAtMillis = now, events = emptyList())
            "guard_triggered" -> {
                val at = now - 25_000L
                val alarm = GuardAlarm(at, GuardTrigger.WHEEL, SirenNote.YABA, 1, GuardTexts.alarm(GuardTrigger.WHEEL, SirenNote.YABA, 1))
                watching.copy(
                    phase = GuardPhase.ALARM, defence = 3, lastAlarm = alarm, sirenCycles = 1,
                    events = listOf(
                        GuardEvent(at + 700L, getString(R.string.guard_core_log_siren, "03"), alert = false),
                        GuardEvent(at, alarm.text, alert = true),
                        GuardEvent(at - 300L, value(Gatt.ERRORS, "00 00 00 00 00 00 01 00", "00 00 00 00 00 00 00 00", read), alert = false),
                        GuardEvent(at - 900L, value(Gatt.SPEED, "04 00", "00 00", pushed), alert = false),
                    ) + watching.events,
                )
            }
            "guard_lost" -> watching.copy(
                phase = GuardPhase.LOST, lostNotified = true, armedAtMillis = now - 2 * 3_600_000L,
                events = listOf(
                    GuardEvent(now - 41_000L, getString(R.string.guard_core_log_lost), alert = false),
                    nativeLine.copy(atMillis = now - 2 * 3_600_000L),
                ),
            )
            "guard_unconfirmed" -> watching.copy(
                bikeWatch = false, defence = 1, armedAtMillis = now - 3 * 60_000L,
                events = listOf(GuardEvent(now - 3 * 60_000L + 15_000L, getString(R.string.guard_core_log_armed_unconfirmed, "1"), alert = false)),
            )
            "guard_not_locked" -> {
                val at = now - 40_000L
                val alarm = GuardAlarm(at, GuardTrigger.LOCK_OPENED, SirenNote.NONE, 0, GuardTexts.alarm(GuardTrigger.LOCK_OPENED, SirenNote.NONE, 0))
                watching.copy(
                    bikeWatch = false, defence = 0, lastAlarm = alarm, issue = GuardIssue.NOT_LOCKED, issueValue = 0,
                    events = listOf(
                        GuardEvent(at, alarm.text, alert = true),
                        GuardEvent(at - 10L, getString(R.string.guard_core_log_held), alert = false),
                        GuardEvent(at - 20L, getString(R.string.guard_core_log_back, "0", "0"), alert = false),
                        GuardEvent(at - 25 * 60_000L, getString(R.string.guard_core_log_lost), alert = false),
                    ) + watching.events,
                )
            }
            else -> watching
        }
    }

    /**
     * Shift points belong to the region slot, so they are worked out after the limit extra
     * (JP gets the slot-2 Standard set). custom = valid but no profile (Eigene), invalid = hunting set.
     */
    private fun withShifting(st: BikeState, shift: String?, keys: String?): BikeState {
        val points = when (shift) {
            "flat" -> ShiftProfiles.FLAT
            "hilly" -> ShiftProfiles.HILLY
            "custom" -> ShiftPoints(11, 20, 30, 8, 16, 26)
            "invalid" -> ShiftPoints(10, 12, 24, 11, 11, 22)
            "zero" -> ShiftPoints.ZERO
            "odd" -> ShiftPoints(100, 190, 240, 80, 170, 220)
            else -> ShiftProfiles.points(ShiftProfile.STANDARD, st.speedLimit)
        }
        val buttons = when (keys) {
            "bell" -> Buttons(horn = true, boost = false, powerButton = false)
            "boost" -> Buttons(horn = false, boost = true, powerButton = false)
            "both" -> Buttons(horn = true, boost = true, powerButton = false)
            else -> st.buttons
        }
        return st.copy(
            shiftMode = if (shift == "manual") ShiftMode.MANUAL else st.shiftMode,
            shiftPoints = points,
            shiftPointsRegion = st.speedLimit,
            shiftCheck = if (shift == "odd") ShiftCheck.ODD_POINTS else st.shiftCheck,
            buttons = buttons,
            raw = st.raw + (Gatt.E_SHIFT_POINTS.short to points.encode()) +
                (Gatt.E_SHIFTER_MODE.short to byteArrayOf(if (shift == "manual") 1 else 0)),
        )
    }

    /**
     * A bike for the shifting commands in the demo: one points slot per region, the mode byte, the
     * region of the demo state. Answers like the firmware (16-byte decrypted reads, echo on write),
     * a little slower than instant so "Sending" shows.
     */
    private class DemoShiftBike(st: BikeState) : ShiftLink {
        private val region = st.speedLimit?.code ?: 0
        private var mode = if (st.shiftMode == ShiftMode.MANUAL) 1 else 0
        private val slots = Array(4) { r ->
            if (r == region) st.shiftPoints?.encode() ?: ShiftProfiles.FACTORY.encode()
            else (if (r == SpeedLimit.JP.code) ShiftProfiles.FACTORY_SLOT2 else ShiftProfiles.FACTORY).encode()
        }

        override suspend fun read(ch: Gatt.Char): ByteArray {
            delay(80)
            val out = ByteArray(16)
            when (ch) {
                Gatt.SPEED_LIMIT -> out[0] = region.toByte()
                Gatt.E_SHIFTER_MODE -> out[0] = mode.toByte()
                Gatt.E_SHIFT_POINTS -> slots[region].copyInto(out)
                else -> Unit
            }
            return out
        }

        override suspend fun write(ch: Gatt.Char, value: ByteArray) {
            delay(80)
            when (ch) {
                Gatt.E_SHIFTER_MODE -> if ((value[0].toInt() and 0xFF) < 2) mode = value[0].toInt()
                Gatt.E_SHIFT_POINTS -> slots[region] = value.copyOf(6)
                else -> Unit
            }
        }
    }

    /** A ride for the cockpit: fresh values every 1.5 s, then the mode's event at [at] ms. */
    private fun runCockpitScenario(graph: AppGraph, mode: String, at: Long, stopAfter: Long) {
        graph.scope.launch {
            val started = System.currentTimeMillis()
            val speeds = intArrayOf(21, 23, 24, 26, 27, 25, 22, 19, 17, 15, 18, 20)
            var i = 0
            var fired = false
            var linkUp = true
            while (true) {
                delay(1_500)
                val elapsed = System.currentTimeMillis() - started
                if (!fired && elapsed >= at) {
                    fired = true
                    when (mode) {
                        "shipping" -> {
                            graph.bike.push { it.copy(shippingImminent = true) }
                            graph.log.add(
                                EventLog.Kind.ALERT, getString(R.string.core_log_shipping),
                                getString(R.string.core_detail_shipping_state, 24, getString(R.string.core_lock_unlocked), getString(R.string.core_none)),
                                EventLog.Code.SHIPPING,
                            )
                        }
                        "kicklock" -> {
                            graph.bike.push { it.copy(faultCodes = listOf(42, 48), raw = it.raw + (Gatt.ERRORS.short to ByteArray(8))) }
                            delay(1_500)
                            graph.bike.push { it.copy(faultCodes = listOf(48), lock = LockState.LOCKED, raw = it.raw + (Gatt.LOCK_STATE.short to byteArrayOf(1))) }
                            graph.log.add(
                                EventLog.Kind.ALERT, getString(R.string.core_log_kicklock_riding),
                                getString(R.string.core_detail_speed, 23), EventLog.Code.KICKLOCK_RIDING,
                            )
                        }
                        "cockpit_lost" -> {
                            linkUp = false
                            graph.bike.push { it.copy(link = Link.LOST, speedKmh = null) }
                            graph.log.add(EventLog.Kind.ALERT, getString(R.string.core_log_link_lost_riding), null, EventLog.Code.LINK_LOST_RIDING)
                        }
                    }
                }
                if (mode == "shipping" && fired && linkUp && elapsed >= at + 9_000) {
                    linkUp = false
                    graph.bike.push { it.copy(link = Link.LOST, speedKmh = null, shippingImminent = false) }
                    graph.log.add(EventLog.Kind.ALERT, getString(R.string.core_log_link_lost_riding), null, EventLog.Code.LINK_LOST_RIDING)
                }
                if (!linkUp) continue
                // No new values at all: the link stays READY, the cockpit has to notice by itself.
                if (mode == "cockpit_stale" && elapsed >= at) continue
                val standing = stopAfter >= 0 && fired && elapsed >= at + stopAfter && elapsed < at + stopAfter + 15_000
                val kmh = if (standing) 0 else speeds[i++ % speeds.size]
                // Like the bike: the wheel-moving flag (48) is set while rolling and gone at a stop.
                graph.bike.push {
                    val codes = it.faultCodes - Faults.WHEEL_MOVING
                    it.copy(
                        speedKmh = kmh,
                        faultCodes = if (standing) codes else codes + Faults.WHEEL_MOVING,
                        raw = it.raw + (Gatt.SPEED.short to byteArrayOf(kmh.toByte())),
                    )
                }
            }
        }
    }

    private companion object {
        val COCKPIT_MODES = setOf("cockpit", "cockpit_lost", "cockpit_stale", "shipping", "kicklock")
        val GUARD_MODES = setOf("guard_armed", "guard_triggered", "guard_lost", "guard_unconfirmed", "guard_arming", "guard_not_locked")

        /** Debug only: sets the controller's state without the disconnect that [BikeController.demo] does first. */
        @Suppress("UNCHECKED_CAST")
        fun BikeController.push(transform: (BikeState) -> BikeState) {
            val field = BikeController::class.java.getDeclaredField("_state").apply { isAccessible = true }
            val flow = field.get(this) as MutableStateFlow<BikeState>
            flow.value = transform(flow.value)
        }
    }
}

/**
 * Debug only: yesterday's 07:52 ride as the ride logger writes it, with what the logger exists for:
 * a short fault-42 flicker, a mark, a supply dip, then the Kick Lock reading locked while riding,
 * faults 42 and 18, shipping mode and the link dropping. Plus the bike's own log of that ride.
 */
private object DemoLogs {
    private const val HEADER = "time,type,link,status,shipping_reported,lock,kmh,battery,charger,level,reset_button," +
        "supply_ok,wheel_moving,faults,faults_raw,battery_raw,message"

    fun write(a: Activity) {
        val dir = File(a.filesDir, "logs").apply { mkdirs() }
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(1).atTime(7, 52, 14).atZone(zone).toInstant().toEpochMilli() + 31
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zone).format(Instant.ofEpochMilli(start))
        val ride = File(dir, "ride-$stamp.csv")
        if (!ride.exists()) ride.writeText(ride(a, start, ride.name, zone))
        val bike = File(dir, BikeLogArchive.fileName(start + 32 * 60_000L, zone))
        if (!bike.exists()) bike.writeText(bikeLog(start / 1000))
    }

    /** The bike state the next row is written with, field by field as RideRecorder.appendLine. */
    private class St {
        var link = "READY"
        var status = "READY"
        var shipping = false
        var lock = "LOCKED"
        var kmh: Int? = 0
        var battery = 87
        var supply = true
        var faults: List<Int> = emptyList()
    }

    private fun ride(a: Activity, start: Long, fileName: String, zone: ZoneId): String {
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(zone)
        val sb = StringBuilder(HEADER).append('\n')
        val s = St()
        val rnd = Random(3)
        fun row(at: Long, type: String, message: String) {
            var mask = 0L
            s.faults.forEach { mask = mask or (1L shl it) }
            sb.append(
                listOf(
                    ts.format(Instant.ofEpochMilli(at)), type, s.link, s.status, if (s.shipping) "1" else "0", s.lock,
                    s.kmh?.toString() ?: "", s.battery.toString(), "NOT_CHARGING", "4", "0", if (s.supply) "1" else "0",
                    if ((s.kmh ?: 0) > 0) "1" else "0", s.faults.joinToString("|"), String.format(Locale.US, "%016x", mask),
                    String.format(Locale.US, "%02x", s.battery), "\"" + message.replace("\"", "'") + "\"",
                ).joinToString(","),
            ).append('\n')
        }
        // "KIND/CODE: text (detail)" as RideRecorder.messageOf writes it, so the viewer colours each event as Diagnose does.
        fun event(at: Long, kind: String, message: String, detail: String? = null, code: EventLog.Code? = null) =
            row(at, if (kind == "MARK") "mark" else "event", "${code?.let { "$kind/${it.name}" } ?: kind}: $message${detail?.let { " ($it)" } ?: ""}")
        fun sec(t: Double) = start + (t * 1000).toLong()
        val fault42 = Faults.describe(42)
        val fault18 = Faults.describe(18)

        event(start, "INFO", a.getString(R.string.core_log_ride_started), fileName)
        var t = 0.6
        var cruise = 0.0
        while (t < 1_336) {
            when {
                t >= 11 && s.lock == "LOCKED" && t < 20 -> {
                    s.lock = "UNLOCKED"; s.status = "RIDING"
                    event(sec(t - 0.3), "STATE", a.getString(R.string.core_log_lock, a.getString(R.string.core_lock_unlocked)))
                }
                t >= 840 && t < 842 -> {
                    s.faults = listOf(42)
                    event(sec(t - 0.4), "ALERT", a.getString(R.string.core_log_fault, 42, fault42.name), fault42.hint, EventLog.Code.FAULT)
                }
                t >= 848 && t < 850 -> {
                    s.faults = emptyList()
                    event(sec(t - 0.2), "STATE", a.getString(R.string.core_log_faults_cleared, "42"))
                }
                t >= 1_020 && t < 1_022 -> {
                    s.supply = false
                    event(
                        sec(t - 0.5), "ALERT", a.getString(R.string.core_log_supply_low),
                        a.getString(R.string.core_detail_supply_low_state, s.kmh ?: 0, s.battery.toString()), EventLog.Code.SUPPLY_LOW,
                    )
                }
                t >= 1_026 && t < 1_028 -> {
                    s.supply = true
                    event(sec(t - 0.5), "INFO", a.getString(R.string.core_log_supply_ok))
                }
                t >= 1_260 && t < 1_262 -> {
                    s.lock = "LOCKED"
                    event(
                        sec(t - 0.7), "ALERT", a.getString(R.string.core_log_kicklock_riding),
                        a.getString(R.string.core_detail_speed, s.kmh ?: 0), EventLog.Code.KICKLOCK_RIDING,
                    )
                }
                t >= 1_262 && t < 1_264 -> s.faults = listOf(42)
                t >= 1_264 && t < 1_266 -> s.faults = listOf(18, 42)
                t >= 1_266 && t < 1_268 -> {
                    s.shipping = true
                    event(
                        sec(t - 0.3), "ALERT", a.getString(R.string.core_log_shipping),
                        a.getString(R.string.core_detail_shipping_state, s.kmh ?: 0, a.getString(R.string.core_lock_locked), "18, 42"),
                        EventLog.Code.SHIPPING,
                    )
                }
                t >= 1_276 && s.link == "READY" -> {
                    s.link = "LOST"; s.kmh = null
                    event(
                        sec(t - 0.9), "ALERT", a.getString(R.string.core_log_link_lost_riding),
                        listOf(
                            a.getString(R.string.core_detail_status, BikeStatus.RIDING.label),
                            a.getString(R.string.core_detail_battery, s.battery),
                            a.getString(R.string.core_detail_speed, 17),
                        ).joinToString(", "),
                        EventLog.Code.LINK_LOST_RIDING,
                    )
                }
                t >= 1_290 && s.link == "LOST" -> s.link = "SEARCHING"
            }
            if (t >= 480 && t < 482) {
                event(
                    sec(t - 1.1), "MARK", a.getString(R.string.cockpit_mark_event, 1),
                    a.getString(R.string.core_detail_speed, s.kmh ?: 0) + ", " + a.getString(R.string.core_detail_battery, s.battery),
                )
            }
            // Speed: away from the lock, two red lights, then the motor cutting out after shipping.
            if (s.link == "READY") {
                val stopped = t < 14 || t in 300.0..330.0 || t in 660.0..700.0
                cruise = when {
                    stopped -> 0.0
                    t > 1_266 -> (cruise - 1.0).coerceAtLeast(15.0)
                    else -> (cruise + 2.5).coerceAtMost(23.5 + 2.5 * kotlin.math.sin(t / 47.0))
                }
                s.kmh = if (cruise <= 0.0) 0 else (cruise + rnd.nextGaussian() * 0.9).toInt().coerceAtLeast(1)
                s.battery = 87 - (t / 1_260.0 * 13).toInt().coerceAtMost(13)
            }
            row(sec(t), "sample", "")
            t += 2.0
        }
        event(sec(t), "INFO", a.getString(R.string.core_log_ride_stopped))
        return sb.toString()
    }

    /** The bike's own log of that ride: boot, 30-second telemetry, the shipping line, no stamp on the wake reason. */
    private fun bikeLog(startEpoch: Long): String {
        val sb = StringBuilder()
        var t = startEpoch - 40
        fun line(msg: String) { sb.append(t).append(' ').append(msg).append("\r\n") }
        sb.append("Wake Reason: WAKE_SRC_KICKLOCK\r\n")
        line("TIME;LiPOSOC;BMSSOC;BATTEMP;BATVOLTAGE;BATCURRENT;MOTORCURRENT;MOTORTMP;DRIVERTMP;SPEED;ODO;BOOST;LUX;BATDSG")
        t += 1
        line("Wake counter 19 of 168, type 3")
        line("Set power state to PWR_NORMAL (Current limit: 30.0 A, SOC: 87 %)")
        t += 1
        line("MT zeropos")
        var odo = 4_708.2
        val rnd = Random(5)
        for (i in 0 until 43) {
            val speed = if (i == 0 || i == 10 || i == 22) 0.0 else 21.0 + rnd.nextGaussian() * 2.0
            odo += speed * 30 / 3600
            val soc = 87 - i * 13 / 42
            if (i == 28) line("Error E42 KICKLOCK_COIL")
            line(
                String.format(
                    Locale.US, ";%.1f%%;%d;%.1f;%.2f;%.2f;%.1f;%d;%d;%.1f;%.1f;%d;%d;1",
                    81.0 - i * 0.2, soc, 14.0 + i * 0.05, 33.0 + soc / 100.0 * 8.5, -(4.0 + speed / 6), 3.0 + speed / 3,
                    20 + i / 2, 23 + i / 3, speed, odo, 0, 180,
                ),
            )
            t += 30
        }
        line("Error E18 BAT_KEY_IN")
        line("BIKE_SHIPPING")
        line("Set power state to PWR_SHIPPING")
        return sb.toString()
    }
}
