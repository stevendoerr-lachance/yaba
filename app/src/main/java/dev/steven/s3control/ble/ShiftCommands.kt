package dev.steven.s3control.ble

import dev.steven.s3control.I18n
import dev.steven.s3control.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The part of a bike link the shifting commands use: [BikeSession] in the app, a fake in tests and the demo. */
interface ShiftLink {
    /** The decrypted value. */
    suspend fun read(ch: Gatt.Char): ByteArray

    suspend fun write(ch: Gatt.Char, value: ByteArray)
}

/** What a points write should leave on the bike. */
sealed interface ShiftTarget {
    /** A profile, resolved for the region the bike reports inside the command (Standard differs per slot). */
    data class Profile(val profile: ShiftProfile) : ShiftTarget

    /** The rider's own set, applied while the app showed [region]; refused if the bike is in another one. */
    data class Custom(val points: ShiftPoints, val region: SpeedLimit?) : ShiftTarget
}

/** A finished points command: [written] is false when the bike already had exactly these points. */
data class ShiftWrite(val points: ShiftPoints, val region: SpeedLimit, val written: Boolean)

/** Why a shifting command stopped. The text is resolved only when shown, so tests never need resources. */
class ShiftFailure(val reason: Reason, private val args: List<Any?> = emptyList(), cause: Throwable? = null) : Exception(cause) {
    enum class Reason {
        READ_FAILED, ODD_VALUES, NO_REGION, REGION_MOVED, REGION_CHANGED, INVALID, TOO_MANY, MODE_REJECTED, POINTS_REJECTED,
    }

    override val message: String
        get() = when (reason) {
            Reason.READ_FAILED -> I18n.get(R.string.shift_error_read)
            Reason.ODD_VALUES -> I18n.get(R.string.shift_check_failed, args.getOrNull(0) ?: "?", args.getOrNull(1) ?: "")
            Reason.NO_REGION -> I18n.get(R.string.shift_error_no_region)
            Reason.REGION_MOVED -> I18n.get(R.string.shift_error_region_moved)
            Reason.REGION_CHANGED -> I18n.get(R.string.shift_error_region_changed)
            Reason.INVALID -> I18n.get(R.string.shift_error_points_invalid)
            Reason.TOO_MANY -> I18n.get(R.string.shift_error_too_many)
            Reason.MODE_REJECTED -> I18n.get(
                R.string.shift_error_mode_rejected,
                (args.getOrNull(0) as? ShiftMode)?.label ?: "?",
                (args.getOrNull(1) as? ShiftMode)?.label ?: "?",
            )
            Reason.POINTS_REJECTED -> I18n.get(R.string.shift_error_points_rejected, (args.getOrNull(0) as? ShiftPoints)?.short() ?: "?")
        }
}

/**
 * The two shifting writes (5538 mode, 5537 points), free of Android so they run against a fake bike.
 * The caller runs them one at a time (the controller's command lock).
 *
 * Every decision is made on fresh reads taken inside the command, never on cached state: the region
 * (which slot and which Standard), the current values (to skip a write that changes nothing) and their
 * plausibility ([ShiftCheck]). A read that fails stops the command before anything is sent.
 * Each write persists two 16 KB flash sectors on the main board: at least 3 s apart, at most 30 per connection.
 */
class ShiftCommands(
    private val state: () -> BikeState,
    private val apply: (Gatt.Char, ByteArray) -> Unit,
    private val onCheck: (ShiftCheck) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private var lastWriteAt: Long? = null

    /** Writes sent on this connection. */
    var writes = 0
        private set

    fun newConnection() {
        writes = 0
    }

    /** Sets automatic or manual. Returns false when the bike was already in [mode] (nothing sent). */
    suspend fun setMode(link: ShiftLink, mode: ShiftMode): Boolean {
        val modeRaw = fresh(link, Gatt.E_SHIFTER_MODE, 1)
        val pointsRaw = fresh(link, Gatt.E_SHIFT_POINTS, 6)
        requirePlausible(modeRaw, pointsRaw)
        if (ShiftMode.from(modeRaw.u8()) == mode) return false
        guard()
        link.write(Gatt.E_SHIFTER_MODE, byteArrayOf(mode.code.toByte()))
        counted()
        sleep(MODE_SETTLE_MS)
        var got = readBackMode(link)
        var retries = 0
        while (got != mode && retries++ < MODE_RETRIES) {
            sleep(RETRY_MS)
            got = readBackMode(link)
        }
        if (got != mode) throw ShiftFailure(ShiftFailure.Reason.MODE_REJECTED, listOf(mode, got))
        return true
    }

    /** Writes [target] into the region slot the bike uses right now, then reads it back. */
    suspend fun setPoints(link: ShiftLink, target: ShiftTarget): ShiftWrite {
        if (target is ShiftTarget.Custom && !ShiftRules.isValid(target.points)) throw ShiftFailure(ShiftFailure.Reason.INVALID)
        val region = SpeedLimit.from(fresh(link, Gatt.SPEED_LIMIT, 1).u8()) ?: throw ShiftFailure(ShiftFailure.Reason.NO_REGION)
        val points = when (target) {
            is ShiftTarget.Profile -> ShiftProfiles.points(target.profile, region)
            is ShiftTarget.Custom -> when (target.region) {
                null -> throw ShiftFailure(ShiftFailure.Reason.NO_REGION)
                region -> target.points
                else -> throw ShiftFailure(ShiftFailure.Reason.REGION_MOVED)
            }
        }
        if (!ShiftRules.isValid(points)) throw ShiftFailure(ShiftFailure.Reason.INVALID)
        val modeRaw = fresh(link, Gatt.E_SHIFTER_MODE, 1)
        val pointsRaw = fresh(link, Gatt.E_SHIFT_POINTS, 6)
        requirePlausible(modeRaw, pointsRaw)
        if (ShiftPoints.decode(pointsRaw) == points) return ShiftWrite(points, region, written = false)
        guard()
        link.write(Gatt.E_SHIFT_POINTS, points.encode())
        counted()
        // The main board persists two flash sectors and stalls like a region change: give it time.
        sleep(POINTS_SETTLE_MS)
        var got = readBackPoints(link)
        var retries = 0
        while (got != points && state().speedLimit == region && retries++ < POINTS_RETRIES) {
            sleep(RETRY_MS)
            got = readBackPoints(link)
        }
        if (state().speedLimit != region) throw ShiftFailure(ShiftFailure.Reason.REGION_CHANGED)
        if (got != points) throw ShiftFailure(ShiftFailure.Reason.POINTS_REJECTED, listOf(got))
        return ShiftWrite(points, region, written = true)
    }

    /** A read the command depends on: failure or a short answer stops it; the cache is never a fallback. */
    private suspend fun fresh(link: ShiftLink, ch: Gatt.Char, minSize: Int): ByteArray {
        val v = try {
            link.read(ch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ShiftFailure(ShiftFailure.Reason.READ_FAILED, cause = e)
        }
        if (v.size < minSize) throw ShiftFailure(ShiftFailure.Reason.READ_FAILED)
        apply(ch, v)
        return v
    }

    private fun requirePlausible(mode: ByteArray, points: ByteArray) {
        val check = ShiftCheck.of(mode, points)
        onCheck(check)
        if (check != ShiftCheck.OK) {
            throw ShiftFailure(ShiftFailure.Reason.ODD_VALUES, listOf(state().firmware, ShiftCheck.detail(mode, points)))
        }
    }

    private suspend fun readBackMode(link: ShiftLink): ShiftMode? =
        link.read(Gatt.E_SHIFTER_MODE).also { apply(Gatt.E_SHIFTER_MODE, it) }.let { ShiftMode.from(it.u8()) }

    private suspend fun readBackPoints(link: ShiftLink): ShiftPoints? =
        link.read(Gatt.E_SHIFT_POINTS).also { apply(Gatt.E_SHIFT_POINTS, it) }.let { ShiftPoints.decode(it) }

    /** Waits out the spacing (never drops a write) and refuses past the per-connection cap. */
    private suspend fun guard() {
        if (writes >= MAX_WRITES) throw ShiftFailure(ShiftFailure.Reason.TOO_MANY)
        val last = lastWriteAt ?: return
        val wait = last + SPACING_MS - now()
        if (wait > 0) sleep(wait)
    }

    private fun counted() {
        lastWriteAt = now()
        writes++
    }

    companion object {
        const val SPACING_MS = 3_000L
        const val MAX_WRITES = 30
        const val MODE_SETTLE_MS = 400L
        const val POINTS_SETTLE_MS = 2_000L
        const val RETRY_MS = 350L
        const val MODE_RETRIES = 6
        const val POINTS_RETRIES = 4
    }
}
