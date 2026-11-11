package dev.steven.s3control

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.steven.s3control.audio.BellConverter
import dev.steven.s3control.audio.BellSlotRecord
import dev.steven.s3control.audio.BellSlotStore
import dev.steven.s3control.audio.KeptBell
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeGuard
import dev.steven.s3control.ble.BikeLab
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.GuardState
import dev.steven.s3control.ble.LabState
import dev.steven.s3control.data.AccountBikes
import dev.steven.s3control.data.AppSettings
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.data.OfficialApp
import dev.steven.s3control.data.Share
import dev.steven.s3control.data.Vault
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ride.RideRecorder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One async account operation at a time; the UI shows [running], [error] or [info]. */
data class AccountOp(val running: Boolean = false, val error: String? = null, val info: String? = null)

data class SharesState(
    val loading: Boolean = false,
    val shares: List<Share>? = null,
    val error: String? = null,
    val info: String? = null,
)

sealed interface BellJob {
    data object Idle : BellJob
    data object Converting : BellJob

    /** [bundledId]: the [dev.steven.s3control.audio.BundledBell] this file came from, null for a picked file. */
    data class Ready(val result: BellConverter.Result, val name: String, val bundledId: String? = null) : BellJob

    /**
     * [ready]: the converted file when the transfer failed, so it can be sent again without converting;
     * null when the conversion itself failed. [sentFraction]: how far the transfer got (0..1), null when no
     * byte of the file went out (the bike then never erased its slot).
     */
    data class Failed(val message: String, val ready: Ready? = null, val sentFraction: Float? = null) : BellJob
    data object Uploaded : BellJob
}

/**
 * The single façade the UI talks to. Bike commands go straight to [bike];
 * account, sharing and bell conversion are wrapped with UI-friendly state here.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as S3App).graph

    val bike: BikeController get() = graph.bike
    val bikeState: StateFlow<BikeState> = graph.bike.state

    /** Bike-Lab / Sound-Lab / Experimente (ble/BikeLab.kt). UI calls these for the lab screens. */
    val lab: BikeLab get() = graph.lab
    val labState: StateFlow<LabState> = graph.lab.state

    /** The Wache (ble/BikeGuard.kt, the guard design): arm, disarm, silence; [guardState] for the screens. */
    val guard: BikeGuard get() = graph.guard
    val guardState: StateFlow<GuardState> = graph.guard.state
    val vault: StateFlow<Vault.Contents> = graph.account.contents
    val events: StateFlow<List<EventLog.Event>> = graph.log.events
    val recorder: StateFlow<RideRecorder.Status> = graph.recorder.status
    val settings: StateFlow<AppSettings> = graph.settings.state
    /** Non-null when the key vault could not be read or written (nothing is overwritten then). */
    val vaultError: StateFlow<String?> = graph.account.vaultError

    private val _account = MutableStateFlow(AccountOp())
    val account: StateFlow<AccountOp> = _account.asStateFlow()

    private val _shares = MutableStateFlow(SharesState())
    val shares: StateFlow<SharesState> = _shares.asStateFlow()

    private val _bell = MutableStateFlow<BellJob>(BellJob.Idle)
    val bell: StateFlow<BellJob> = _bell.asStateFlow()

    val isLoggedIn: Boolean get() = graph.account.isLoggedIn

    // --- Account -------------------------------------------------------------------------------

    fun login(email: String, password: String, onSuccess: (AccountBikes) -> Unit = {}) = accountOp {
        val result = graph.account.login(email, password)
        val info = buildString {
            append(I18n.plural(R.plurals.core_account_bikes_loaded, result.bikes.size, result.bikes.size))
            if (result.skipped.isNotEmpty()) append(" ").append(I18n.get(R.string.core_account_without_key, result.skipped.joinToString()))
        }
        onSuccess(result)
        info
    }

    fun refreshBikes() = accountOp {
        val r = graph.account.refreshBikes()
        I18n.plural(R.plurals.core_account_bikes_refreshed, r.bikes.size, r.bikes.size)
    }

    fun logout() {
        graph.account.logout()
        _account.value = AccountOp(info = I18n.get(R.string.core_account_logged_out))
    }

    fun addManualBike(bike: BikeCredentials) {
        graph.account.addManual(bike)
        _account.value = AccountOp(info = I18n.get(R.string.core_account_bike_added, bike.name))
    }

    fun removeBike(mac: String) {
        if (bikeState.value.bike?.normalizedMac == dev.steven.s3control.data.normalizeMac(mac)) graph.bike.disconnect()
        graph.account.remove(mac)
    }

    fun renameBike(mac: String, name: String) = graph.account.rename(mac, name)

    /** Garage: S3/X3 or S2/X2 for a bike the account does not describe. A live link reconnects with the new driver. */
    fun setBikeModel(mac: String, model: BikeFamily) = graph.account.setModel(mac, model)

    fun exportKeys(): String = graph.account.exportKeys()

    fun importKeys(text: String) {
        _account.value = runCatching { graph.account.importKeys(text) }.fold(
            onSuccess = {
                val n = I18n.plural(R.plurals.core_account_imported, it.imported, it.imported)
                val skipped = I18n.plural(R.plurals.core_account_import_skipped, it.skipped, it.skipped)
                AccountOp(info = if (it.skipped > 0) "$n $skipped" else n)
            },
            onFailure = { AccountOp(error = it.message ?: I18n.get(R.string.core_account_import_failed)) },
        )
    }

    fun clearAccountMessage() = _account.update { it.copy(error = null, info = null) }

    private fun accountOp(block: suspend () -> String?) {
        if (_account.value.running) return
        _account.value = AccountOp(running = true)
        viewModelScope.launch {
            _account.value = runCatching { block() }.fold(
                onSuccess = { AccountOp(info = it) },
                onFailure = { AccountOp(error = it.message ?: I18n.get(R.string.core_error_unknown)) },
            )
        }
    }

    // --- Bike link -----------------------------------------------------------------------------

    fun connect(bike: BikeCredentials) {
        graph.account.setLastBike(bike.mac)
        graph.bike.connect(bike)
    }

    /** The bike to open on launch: the last one used, else the only one. */
    fun preferredBike(): BikeCredentials? {
        val c = vault.value
        return c.bikes.firstOrNull { it.normalizedMac == c.lastBikeMac } ?: c.bikes.singleOrNull()
    }

    // --- Sharing -------------------------------------------------------------------------------

    fun loadShares(info: String? = null) {
        val id = bikeState.value.bike?.id ?: return
        _shares.update { it.copy(loading = true, error = null, info = info) }
        viewModelScope.launch {
            _shares.value = runCatching { graph.account.shares(id) }.fold(
                onSuccess = { SharesState(shares = it, info = info) },
                onFailure = { SharesState(shares = _shares.value.shares, error = it.message, info = info) },
            )
        }
    }

    fun clearSharesMessage() = _shares.update { it.copy(error = null, info = null) }

    fun share(email: String, durationSeconds: Long?) {
        val id = bikeState.value.bike?.id ?: return
        _shares.update { it.copy(loading = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching { graph.account.share(id, email, durationSeconds) }.fold(
                onSuccess = { loadShares(info = I18n.get(R.string.core_share_shared_with, email.trim())) },
                onFailure = { e -> _shares.update { it.copy(loading = false, error = e.message) } },
            )
        }
    }

    fun revokeShare(guid: String) {
        _shares.update { it.copy(loading = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching { graph.account.revoke(guid) }.fold(
                onSuccess = { loadShares(info = I18n.get(R.string.core_share_revoked)) },
                onFailure = { e -> _shares.update { it.copy(loading = false, error = e.message) } },
            )
        }
    }

    // --- Custom bell ---------------------------------------------------------------------------

    /**
     * [keepLevel]: send the file as recorded, without normalising (a bell that is clipped on purpose).
     * [bundledId]: the bundled bell's enum name, so the slot record can name it after the upload.
     */
    fun convertBell(uri: Uri, displayName: String, keepLevel: Boolean = false, bundledId: String? = null) {
        _bell.value = BellJob.Converting
        viewModelScope.launch {
            _bell.value = runCatching { BellConverter.convert(getApplication(), uri, keepLevel) }.fold(
                onSuccess = { BellJob.Ready(it, displayName, bundledId) },
                onFailure = { BellJob.Failed(it.message ?: I18n.get(R.string.core_bell_convert_failed)) },
            )
        }
    }

    /** A bell sent before, from the copy on this phone: no conversion, straight to Ready. */
    fun chooseKeptBell(id: String) {
        val kept = keptBells.value.firstOrNull { it.id == id } ?: return
        _bell.value = BellJob.Converting
        viewModelScope.launch {
            _bell.value = runCatching { withContext(Dispatchers.IO) { kept.file.readBytes() } }.fold(
                onSuccess = { bytes ->
                    val result = BellConverter.Result(bytes, BellSlotStore.sampleRate(bytes), kept.seconds, trimmed = false)
                    BellJob.Ready(result, kept.name, kept.bundledId)
                },
                onFailure = { BellJob.Failed(I18n.get(R.string.core_bell_cannot_open)) },
            )
        }
    }

    private var bellWatch: Job? = null

    fun uploadBell() {
        val ready = _bell.value as? BellJob.Ready ?: return
        val mac = bikeState.value.bike?.normalizedMac
        // The driver clears state.upload before it reports a failure: remember how far it got.
        val reached = AtomicReference<Float?>(null)
        bellWatch?.cancel()
        bellWatch = viewModelScope.launch { bikeState.collect { s -> s.upload?.let { reached.set(it) } } }
        graph.bike.uploadBell(ready.result.file) { ok ->
            bellWatch?.cancel()
            val sent = (graph.bike.state.value.upload ?: reached.get())?.takeIf { it > 0f }
            _bell.value = if (ok) {
                BellJob.Uploaded
            } else {
                BellJob.Failed(I18n.get(R.string.core_bell_upload_aborted), ready, sent)
            }
            if (mac != null && (ok || sent != null)) recordBellSlot(mac, ready, ok)
        }
    }

    /** The failed file once more: back to Ready, then the same upload. */
    fun retryBell() {
        val failed = _bell.value as? BellJob.Failed ?: return
        val ready = failed.ready ?: return
        _bell.value = ready
        uploadBell()
    }

    fun resetBell() { _bell.value = BellJob.Idle }

    private val bellSlots = BellSlotStore(app)
    private val bellSlotRevision = MutableStateFlow(0)

    /** A complete upload replaces the bike's record; one that broke off after the erase leaves no record. */
    private fun recordBellSlot(mac: String, ready: BellJob.Ready, ok: Boolean) {
        graph.scope.launch(Dispatchers.IO) {
            runCatching {
                if (ok) {
                    val record = BellSlotRecord(
                        name = ready.name,
                        bundledId = ready.bundledId,
                        seconds = ready.result.seconds,
                        wave = dev.steven.s3control.ui.components.waveformFrame(ready.result.file, 36).columns,
                        sentAt = System.currentTimeMillis(),
                    )
                    bellSlots.save(mac, record, ready.result.file)
                } else {
                    bellSlots.clear(mac)
                }
            }
            bellSlotRevision.update { it + 1 }
        }
    }

    /** What the custom bell slot of the connected bike (else the preferred one) holds, as far as YABA sent it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val bellSlot: StateFlow<BellSlotRecord?> = combine(
        bikeState.map { it.bike?.normalizedMac }.distinctUntilChanged(),
        vault.map { c -> (c.bikes.firstOrNull { it.normalizedMac == c.lastBikeMac } ?: c.bikes.singleOrNull())?.normalizedMac }
            .distinctUntilChanged(),
        bellSlotRevision,
    ) { live, preferred, _ -> live ?: preferred }
        .mapLatest { mac -> mac?.let { withContext(Dispatchers.IO) { runCatching { bellSlots.load(it) }.getOrNull() } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Bell files that arrived in full before, newest first (the chooser offers them again). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val keptBells: StateFlow<List<KeptBell>> = bellSlotRevision
        .mapLatest { withContext(Dispatchers.IO) { runCatching { bellSlots.kept() }.getOrDefault(emptyList()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // --- Official VanMoof app ------------------------------------------------------------------

    fun officialAppInstalled(): Boolean = OfficialApp.installed(getApplication())

    /** Push the VanMoof app out of the background, then look for the bike again. */
    fun quitOfficialApp() {
        OfficialApp.pushOutOfBackground(getApplication())
        val s = bikeState.value
        val bike = s.bike ?: return
        if (s.link == dev.steven.s3control.ble.Link.OFFLINE) connect(bike) else graph.bike.retryNow()
    }

    /** Its App info page (Force stop, Permissions → Nearby devices). False if it could not be opened. */
    fun openOfficialAppInfo(): Boolean = OfficialApp.openAppInfo(getApplication())

    // --- Intro ----------------------------------------------------------------------------------

    private val _introReplay = MutableStateFlow(0)
    /** Bumped when the user asks to see the opening again; MainActivity plays it on change. */
    val introReplay: StateFlow<Int> = _introReplay.asStateFlow()

    fun replayIntro() { _introReplay.value++ }

    // --- Bike log -------------------------------------------------------------------------------

    /** Result of the last internal-log download; survives tab switches and fold/unfold. */
    data class BikeLog(
        val text: String?,
        val atMillis: Long,
        val failed: Boolean,
        /** The download requested every block up to the bike's count (no early stop after empty answers).
         *  Read by the Fahrtenschreiber's log-seek guard (ui/screens/LogInsightsScreen.kt). */
        val complete: Boolean = false,
    )

    private val _bikeLog = MutableStateFlow<BikeLog?>(null)
    val bikeLog: StateFlow<BikeLog?> = _bikeLog.asStateFlow()

    fun readBikeLog() = graph.bike.readBikeLog { text ->
        val now = System.currentTimeMillis()
        // Called before readBikeLog's finally clears logProgress: 1f means the loop reached the last block.
        val complete = text != null && (graph.bike.state.value.logProgress ?: 0f) >= 1f
        _bikeLog.value = BikeLog(text, now, failed = text == null, complete = complete)
        // On disk at once: the lines before a shutdown are the evidence, and process death would lose them.
        if (!text.isNullOrEmpty()) {
            graph.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(getApplication<Application>().filesDir, "logs").apply { mkdirs() }
                    val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(now))
                    java.io.File(dir, "bikelog-$stamp.txt").writeText(text)
                }
            }
        }
    }

    fun clearBikeLog() { _bikeLog.value = null }

    /**
     * Mac the shell already auto-connected to in this process. Lives here (not in saved state) so a
     * manual disconnect stays a disconnect, while a restart after process death connects again.
     */
    var autoConnectTriedMac: String? = null

    // --- Ride logger & settings ----------------------------------------------------------------

    fun startRideLogger() = graph.recorder.start()
    fun stopRideLogger() = graph.recorder.stop()
    fun rideLogs() = graph.recorder.files()
    fun shareRideLog(file: java.io.File) = graph.recorder.shareIntent(file)
    fun deleteRideLog(file: java.io.File) = graph.recorder.delete(file)
    fun clearEvents() = graph.log.clear()

    fun updateSettings(transform: (AppSettings) -> AppSettings) = graph.settings.update(transform)

    // --- Cockpit (handlebar view, ui/cockpit) ----------------------------------------------------

    private val cockpit = dev.steven.s3control.ui.cockpit.CockpitController(graph, viewModelScope, bikeLog)
    private val cockpitPrefsStore = dev.steven.s3control.ui.cockpit.CockpitRuntime.prefs(app)

    /** True while the full-screen cockpit replaces both shells. */
    val cockpitOpen: StateFlow<Boolean> = cockpit.open

    /** Session, bike state, logger, bike log and a 1 s clock folded into one value; null while closed. */
    val cockpitView: StateFlow<dev.steven.s3control.ui.cockpit.CockpitView?> = cockpit.view

    val cockpitPrefs: StateFlow<dev.steven.s3control.ui.cockpit.CockpitPrefsState> = cockpitPrefsStore.state

    fun updateCockpitPrefs(transform: (dev.steven.s3control.ui.cockpit.CockpitPrefsState) -> dev.steven.s3control.ui.cockpit.CockpitPrefsState) =
        cockpitPrefsStore.update(transform)

    /** Raw cause of the last failed logger start from the cockpit or Diagnose (null once one worked). */
    val cockpitLoggerError: StateFlow<String?> = dev.steven.s3control.ui.cockpit.CockpitRuntime.loggerError

    /** Starts the ride logger and records the outcome for [cockpitLoggerError]. */
    fun startRideLoggerReporting(): Boolean {
        val error = runCatching { graph.recorder.start() }.exceptionOrNull()
        dev.steven.s3control.ui.cockpit.CockpitRuntime.loggerError.value = error?.let { it.message ?: it.javaClass.simpleName }
        return error == null
    }

    /** New session, live feed and the fast poll claim. */
    fun openCockpit() = cockpit.open()

    /**
     * Entry from the riding suggestion: the logger only starts when nothing has to be asked,
     * so no permission dialog ever shows up while riding.
     */
    fun openCockpitFromRiding() {
        if (cockpitPrefsStore.state.value.startsLogger && !recorder.value.active &&
            dev.steven.s3control.ui.BlePermissions.notificationsGranted(getApplication())
        ) {
            startRideLoggerReporting()
        }
        cockpit.open()
    }

    /** Restores the poll interval; marks and a dismissed takeover stay for the next opening. */
    fun closeCockpit() = cockpit.close()

    /** "Marke n" into the event log and the running ride log; null within 1.5 s of the last mark. */
    fun markMoment(viaKey: Boolean = false): Int? = cockpit.mark(viaKey)

    fun tagSituation(tag: dev.steven.s3control.ui.cockpit.SituationTag) = cockpit.tag(tag)

    /** "Verstanden" in takeover phase 1 (ignored for the first 1.5 s). */
    fun cockpitAcknowledge() = cockpit.acknowledge()

    /** The big capsule in takeover phase 1: sets the moment's mark once, then acknowledges. */
    fun cockpitTakeoverTap() = cockpit.takeoverTap()

    fun cockpitDismissTakeover() = cockpit.dismissTakeover()

    fun cockpitReopenTakeover() = cockpit.reopenTakeover()

    /** What the back gesture does inside the cockpit; CLOSE means the screen should close now. */
    fun cockpitBack(): dev.steven.s3control.ui.cockpit.BackResult = cockpit.back()

    /** The riding suggestion's "Nicht jetzt", for the rest of the process. */
    var cockpitSuggestDismissed: Boolean
        get() = dev.steven.s3control.ui.cockpit.CockpitRuntime.suggestDismissed
        set(value) { dev.steven.s3control.ui.cockpit.CockpitRuntime.suggestDismissed = value }

    init {
        dev.steven.s3control.ui.cockpit.CockpitRuntime.ensureBatteryWatch(graph)
        // An armed Wache restored at process start gets its foreground service now (the app is in the foreground).
        graph.guard.ensureService()
        if (dev.steven.s3control.ui.cockpit.CockpitRuntime.consumeOpenOnLaunch()) cockpit.open()
    }

    override fun onCleared() {
        cockpit.close()
        super.onCleared()
    }
}
