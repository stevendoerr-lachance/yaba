package dev.steven.s3control

import android.app.Application
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeGuard
import dev.steven.s3control.ble.BikeLab
import dev.steven.s3control.data.AccountRepository
import dev.steven.s3control.data.Settings
import dev.steven.s3control.data.VanMoofApi
import dev.steven.s3control.data.Vault
import dev.steven.s3control.ride.EventLog
import dev.steven.s3control.ride.RideRecorder
import dev.steven.s3control.ui.s2.S2DriveWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class S3App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        graph = AppGraph(this)
        // S2 drive diagnosis: catch every S2 error code from the start, not only while its screen is open.
        S2DriveWatch.attach(this, graph)
    }
}

/** Process-wide singletons. The bike link lives here so it survives fold/unfold and rotation. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = EventLog()
    val vault = Vault(app)
    val api = VanMoofApi()
    val account = AccountRepository(vault, api)
    val settings = Settings(app)
    val bike = BikeController(app, log, scope)
    val lab = BikeLab(bike, scope, log)
    val recorder = RideRecorder(app, bike, log, scope)
    /** The Wache (the guard design): armed by a tap, it watches the bike and sounds its siren on movement. */
    val guard = BikeGuard(app, bike, log, scope) {
        // An unreadable vault is not "no bikes": the armed Wache then waits in its store instead of ending.
        account.contents.value.bikes.takeIf { account.vaultError.value == null }
    }

    init {
        scope.launch { settings.state.collect { bike.touchUnlock = it.touchUnlock } }
        // Login, refresh, import and rename change credentials; the live link must use the new ones.
        scope.launch { account.contents.collect { bike.updateCredentials(it.bikes) } }
        // The lab's assist-cut writes (siren, alarm stop, re-lock, sleep, self-test) stay off while the Wache owns the bike.
        lab.guardActive = { guard.state.value.active }
        // An armed Wache survives app kill and phone reboot: resume it (the service is started by the view model or the boot receiver).
        guard.restore()
    }
}
