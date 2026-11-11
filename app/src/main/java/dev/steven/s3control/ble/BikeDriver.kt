package dev.steven.s3control.ble

import androidx.annotation.StringRes
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ride.EventLog
import kotlinx.coroutines.CoroutineScope

/*
 * The seam between BikeController and one bike family's protocol (the S2 plan B2).
 *
 * BikeController keeps the lifecycle: scan, GATT connect, the driver choice (B3), link states, reconnect and
 * backoff, the poll cadence, the notification collector, the command queue (busy flags, command lock, error
 * text, command log, feature gate) and the change records. A BikeDriver knows the characteristics: which
 * services prove the family, how to authenticate, what to subscribe, read and poll, how to decode a value into
 * BikeState, and how each command goes on the wire.
 *
 * A driver lives as long as its controller. Each authentication gives a new [BikeConnection]: the session of
 * that one link. The controller hands the link over in this order, and only then calls anything else:
 *   prepare()  ->  [link AUTHENTICATING]  ->  authenticate() = connection  ->  [link READY with onReady()]
 *   ->  [collector started]  connection.subscribe()  ->  connection.start()  ->  [polling: connection.poll(tick)]
 * A command runs on the connection that was live when it was queued, one at a time on the command lock, as
 * the S3 code always did with its session. When the link goes, the controller calls onLinkDown().
 */

/** What a driver may do to the controller: read and change the state, log, and use the command lock. */
interface DriverHost {
    /** The current state. */
    val state: BikeState

    /** A plain state change (busy flags, progress, read-back caches). Nothing is recorded in the event log. */
    fun update(reduce: (BikeState) -> BikeState)

    /**
     * One decoded value: [reduce] runs on the current state under the apply lock and its result becomes the
     * state, atomically per value. Returns (before, after); the driver passes them to [recordChanges] once its
     * own side effects for this value are done.
     */
    fun transition(reduce: (BikeState) -> BikeState): Pair<BikeState, BikeState>

    /** Logs what changed between two states: lock, status, faults, supply, charger, alarm (null-safe per field). */
    fun recordChanges(before: BikeState, after: BikeState)

    /** [transition] plus [recordChanges], for a driver with no side effects in between. */
    fun apply(reduce: (BikeState) -> BikeState) {
        val (before, after) = transition(reduce)
        recordChanges(before, after)
    }

    fun log(kind: EventLog.Kind, message: String, detail: String? = null, code: EventLog.Code? = null)

    /** The bike asked to be unlocked (bell or boost pressed while connected): Touch Unlock decides. */
    fun onUnlockRequest()

    /** Polling skips its turns while true (long transfers that must not be interleaved with reads). */
    var paused: Boolean

    /** For follow-up work that outlives the current call (for example a delayed re-read). */
    val scope: CoroutineScope

    /** Runs [block] on the command lock, queued behind any running command. */
    suspend fun <T> withCommandLock(block: suspend () -> T): T

    /**
     * S2: one decoded status block, right after it became the state. Every block, an unchanged one too, so the
     * drive diagnosis (ui/s2) sees each error code even when the bike reports it in a single block. [pushed]: the
     * bike sent it on its own (a notification), not as the answer to a read.
     */
    fun onS2Frame(frame: S2Frame, pushed: Boolean = false) {}

    /** True while the Wache is armed: the S3 driver polls its fast guard batch every turn (the guard design 2.2). */
    val guardWatch: Boolean get() = false

    /**
     * S3: one decoded value, right after it became the state. Every read and notification, unchanged ones too, so the
     * Wache can record what the bike really sends. [pushed]: a notification, not the answer to a read.
     */
    fun onValue(ch: Gatt.Char, value: ByteArray, pushed: Boolean) {}
}

/** One bike family's protocol on top of [GattClient]: the steps up to an authenticated [BikeConnection]. */
interface BikeDriver {
    val family: BikeFamily

    /** Right after the GATT connect: proves the family's services are there and prepares the link (S3: MTU). */
    suspend fun prepare()

    /** Authenticates with [bike]'s key. The connection is this link's until the controller drops it. */
    suspend fun authenticate(bike: BikeCredentials): BikeConnection

    /** Applied in the same state update that marks the link READY: drop values an earlier connection left. */
    fun onReady(st: BikeState): BikeState = st

    /** The link went down (lost, disconnected, replaced): forget the session of the last authentication. */
    fun onLinkDown() {}
}

/**
 * One authenticated link of a [BikeDriver]. Every call runs on this link's own session, also when it was
 * queued before a reconnect replaced the link. [poll] and [onNotification] must not throw.
 */
interface BikeConnection {
    val driver: BikeDriver

    /** Subscribes to what the bike pushes. Returns how many characteristics were subscribed. */
    suspend fun subscribe(): Int

    /** A raw (still encrypted) notification from the link. */
    fun onNotification(n: GattClient.Notification)

    /**
     * First sync of a connection, right after [subscribe]: connect-time writes and reads, then what [refresh]
     * reads. The version strings are read only when the state has none yet ([BikeState.versions]).
     */
    suspend fun start()

    /** Reads everything the screens show; [includeVersions] also reads the version strings. */
    suspend fun refresh(includeVersions: Boolean)

    /** One poll turn; [tick] counts the turns of this connection from 0. */
    suspend fun poll(tick: Int)

    /** Reads every characteristic the diagnosis page shows raw. */
    suspend fun readDiagnostics()

    suspend fun unlock()

    suspend fun lock()

    suspend fun setLight(mode: LightMode)

    suspend fun setAlarm(stage: AlarmStage)

    suspend fun setSpeedLimit(limit: SpeedLimit)

    suspend fun setPowerLevel(level: Int)

    suspend fun setUnit(unit: UnitSystem)

    /** Wakes the bike from standby; refused while it is moving. */
    suspend fun wake()

    /** Sets a three-digit backup code, or clears it with null. */
    suspend fun setBackupCode(code: String?)

    suspend fun setBell(tone: BellTone): Unit = unsupported()

    suspend fun play(sound: Sound): Unit = unsupported()

    /** [onDone] reports the result; a driver without custom bells reports false. */
    suspend fun uploadBell(file: ByteArray, onDone: (Boolean) -> Unit) {
        onDone(false)
        unsupported()
    }

    /** [onDone] gets the log text, or null; a driver without a bike log reports null. */
    suspend fun readBikeLog(onDone: (String?) -> Unit) {
        onDone(null)
        unsupported()
    }

    /** S2 only ([S2Mute] bits), test mode. */
    suspend fun setMutedSounds(mask: Int): Unit = unsupported()
}

/** A command this bike family does not have. */
fun unsupported(): Nothing = throw GattException(I18n.get(R.string.core_error_not_on_this_bike))

/** Strings for a driver: [I18n] in the app. JVM tests pass their own, because [I18n] needs a Context. */
interface Texts {
    fun get(@StringRes id: Int, vararg args: Any): String

    /** The app's strings, in the app's language. */
    object App : Texts {
        override fun get(id: Int, vararg args: Any): String = if (args.isEmpty()) I18n.get(id) else I18n.get(id, *args)
    }
}
