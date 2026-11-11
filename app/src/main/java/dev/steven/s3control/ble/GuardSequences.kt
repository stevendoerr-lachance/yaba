package dev.steven.s3control.ble

import kotlinx.coroutines.CancellationException

/**
 * The characteristics a Wache sequence may touch (the guard design 9.3). Only 5521, 5523 and 5524 are writable;
 * 5561 is read only. MODULE_STATE (5562) is deliberately absent: the Wache cannot even name it.
 */
enum class GuardChar(val short: String, val writable: Boolean) {
    LOCK_STATE("5521", true),
    ALARM_STATE("5523", true),
    ALARM_MODE("5524", true),
    MODULE_MODE("5561", false),
}

/** What a Wache sequence may do on the bike, inside one [BikeController.guardCommand]. A fake in tests. */
interface GuardOps {
    /** Fresh read, applied to the state; byte 0 (null for an empty value). */
    suspend fun read(ch: GuardChar): Int?

    /** One byte; `require(ch.writable)`. */
    suspend fun write(ch: GuardChar, value: Int)

    /** A delay; a fake in tests. */
    suspend fun pause(ms: Long)
}

/** How the bike reached 5523 = 2 when the Wache was armed. */
enum class ArmPath {
    /** By itself after alarm on + lock (ALARM_DELAY_ON -> PRE_M1 -> M1). */
    NATIVE,

    /** Only after YABA wrote 5523 = 2. */
    DIRECT,
}

/** The arming sequence's result kind (the guard design 1.3, 1.4). */
enum class ArmOutcome { WATCHING, UNCONFIRMED, ALARM_DURING, ALARM_MODE_REFUSED, LOCK_REFUSED, BIKE_STATE, FAILED }

data class ArmResult(
    val outcome: ArmOutcome,
    /** ALARM_MODE was off before arming: disarm switches it back off ([GuardStore] restoreAlarmMode). */
    val modeWasOff: Boolean = false,
    val path: ArmPath? = null,
    /** The value the outcome names (5524, 5521 or 5523), if any. */
    val value: Int? = null,
    /** 5523 and 5521 as last read. */
    val defence: Int? = null,
    val lock: Int? = null,
    /** Sum of the pauses after the lock write (the log line "after 6.5 seconds"). */
    val waitedMs: Long = 0,
    /** The values read at the start, for the log line. */
    val mode0: Int? = null,
    val lock0: Int? = null,
    val defence0: Int? = null,
    /** The error text of [ArmOutcome.FAILED]. */
    val error: String? = null,
    /** How many writes the sequence made. */
    val writes: Int = 0,
)

/** What a re-arm, siren, verify or disarm read back at its end. */
data class SequenceReadback(
    val defence: Int?,
    val lock: Int?,
    val status: Int? = null,
    val writes: Int = 0,
    /** Disarm: 5523 in {0, 1} and 5521 = 0. Others: true. */
    val ok: Boolean = true,
)

/**
 * The Wache's write sequences over [GuardOps], pure and unit-tested with a fake (GuardSequencesTest). They write only
 * 5521, 5523 and 5524. The runner ([BikeGuard]) runs each inside one [BikeController.guardCommand].
 */
object GuardSequences {

    /** Counts writes, so the runner can charge automatic sequences to the write budget. */
    private class Counting(private val ops: GuardOps) : GuardOps {
        var writes = 0
        override suspend fun read(ch: GuardChar): Int? = ops.read(ch)
        override suspend fun write(ch: GuardChar, value: Int) {
            require(ch.writable) { "${ch.short} is not writable" }
            writes++
            ops.write(ch, value)
        }
        override suspend fun pause(ms: Long) = ops.pause(ms)
    }

    /** the guard design 1.3. Exceptions propagate; the runner turns them into [ArmOutcome.FAILED]. */
    suspend fun arm(raw: GuardOps): ArmResult {
        val ops = Counting(raw)
        val mode0 = ops.read(GuardChar.ALARM_MODE)
        val lock0 = ops.read(GuardChar.LOCK_STATE)
        var def0 = ops.read(GuardChar.ALARM_STATE)
        val status0 = ops.read(GuardChar.MODULE_MODE)
        fun result(outcome: ArmOutcome, modeWasOff: Boolean = false, path: ArmPath? = null, value: Int? = null,
                   defence: Int? = null, lock: Int? = null, waited: Long = 0) =
            ArmResult(outcome, modeWasOff, path, value, defence, lock, waited, mode0, lock0, def0, writes = ops.writes)
        if (status0 == BikeStatus.UPDATING.code || status0 == BikeStatus.SHIPPING.code) return result(ArmOutcome.BIKE_STATE, value = status0)
        if (def0 == 3 || def0 == 4) {
            // A siren or tracking from before: stop it first.
            ops.write(GuardChar.ALARM_STATE, 0)
            ops.pause(800)
            def0 = ops.read(GuardChar.ALARM_STATE)
        }
        val modeWasOff = mode0 != 1
        if (modeWasOff) {
            ops.write(GuardChar.ALARM_MODE, 1)
            ops.pause(300)
            val mode = ops.read(GuardChar.ALARM_MODE)
            if (mode != 1) return result(ArmOutcome.ALARM_MODE_REFUSED, modeWasOff, value = mode, defence = def0)
        }
        suspend fun restoreMode() { if (modeWasOff) ops.write(GuardChar.ALARM_MODE, 0) }
        if (def0 == 2) {
            // Already watching (left over from before): make sure it is locked, nothing else.
            var lock = ops.read(GuardChar.LOCK_STATE)
            if (lock != 1) {
                ops.write(GuardChar.LOCK_STATE, 1)
                ops.pause(800)
                lock = ops.read(GuardChar.LOCK_STATE)
            }
            return result(ArmOutcome.WATCHING, modeWasOff, ArmPath.NATIVE, defence = 2, lock = lock)
        }
        ops.write(GuardChar.LOCK_STATE, 1)
        ops.pause(800)
        var waited = 800L
        val lock = ops.read(GuardChar.LOCK_STATE)
        if (lock != 1) {
            restoreMode()
            return result(ArmOutcome.LOCK_REFUSED, modeWasOff, value = lock, defence = def0, lock = lock, waited = waited)
        }
        suspend fun stopAndRelock() {
            ops.write(GuardChar.ALARM_STATE, 0)
            ops.pause(500)
            ops.write(GuardChar.LOCK_STATE, 1)
            ops.pause(800)
        }
        // Native path: alarm on + lock should run ALARM_DELAY_ON -> PRE_M1 (5 s settle) -> M1. Unknown with PC8 bridged.
        var def: Int? = def0
        var nativeWaited = 0L
        while (nativeWaited < GuardRules.ARM_NATIVE_WAIT_MS) {
            ops.pause(500)
            nativeWaited += 500
            waited += 500
            def = ops.read(GuardChar.ALARM_STATE)
            if (def == 2) return result(ArmOutcome.WATCHING, modeWasOff, ArmPath.NATIVE, defence = 2, lock = 1, waited = waited)
            if (def == 3 || def == 4) {
                stopAndRelock()
                restoreMode()
                return result(ArmOutcome.ALARM_DURING, modeWasOff, value = def, defence = def, lock = 1, waited = waited)
            }
        }
        // Direct path.
        ops.write(GuardChar.ALARM_STATE, 2)
        var directWaited = 0L
        while (directWaited < GuardRules.ARM_DIRECT_WAIT_MS) {
            ops.pause(1_000)
            directWaited += 1_000
            waited += 1_000
            def = ops.read(GuardChar.ALARM_STATE)
            if (def == 2) return result(ArmOutcome.WATCHING, modeWasOff, ArmPath.DIRECT, defence = 2, lock = 1, waited = waited)
            if (def == 3 || def == 4) {
                stopAndRelock()
                restoreMode()
                return result(ArmOutcome.ALARM_DURING, modeWasOff, value = def, defence = def, lock = 1, waited = waited)
            }
        }
        val lockEnd = ops.read(GuardChar.LOCK_STATE)
        if (lockEnd != 1) {
            restoreMode()
            return result(ArmOutcome.LOCK_REFUSED, modeWasOff, value = lockEnd, defence = def, lock = lockEnd, waited = waited)
        }
        return result(ArmOutcome.UNCONFIRMED, modeWasOff, null, value = def, defence = def, lock = lockEnd, waited = waited)
    }

    /**
     * the guard design 3.5: 5523 = 0 (siren off, standby, clears the software lock), at once 5521 = 1 (no pause: the bike
     * is open for one encrypted write), then wait for 2 (native) or write 5523 = 2. Also "Sirene aus" and "Erneut versuchen".
     */
    suspend fun rearm(raw: GuardOps, path: ArmPath?): SequenceReadback {
        val ops = Counting(raw)
        ops.write(GuardChar.ALARM_STATE, 0)
        try {
            ops.write(GuardChar.LOCK_STATE, 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 5523 = 0 has opened the software lock: one more try at once (a lost link fails again and propagates).
            ops.pause(LOCK_RETRY_MS)
            ops.write(GuardChar.LOCK_STATE, 1)
        }
        ops.pause(800)
        if (path == ArmPath.NATIVE) {
            var waited = 0L
            while (waited < GuardRules.ARM_NATIVE_WAIT_MS) {
                ops.pause(500)
                waited += 500
                if (ops.read(GuardChar.ALARM_STATE) == 2) return readback(ops)
            }
        }
        ops.write(GuardChar.ALARM_STATE, 2)
        var waited = 0L
        while (waited < REARM_DIRECT_WAIT_MS) {
            ops.pause(1_000)
            waited += 1_000
            if (ops.read(GuardChar.ALARM_STATE) == 2) break
        }
        return readback(ops)
    }

    private suspend fun readback(ops: Counting): SequenceReadback {
        val def = ops.read(GuardChar.ALARM_STATE)
        val lock = ops.read(GuardChar.LOCK_STATE)
        return SequenceReadback(defence = def, lock = lock, writes = ops.writes)
    }

    /**
     * Puts back the lock a re-arm's 5523 = 0 opened when its 5521 = 1 failed or was not taken: 5521 = 1 only, then the
     * lock and 5523 read back. Never touches 5523.
     */
    suspend fun relock(raw: GuardOps): SequenceReadback {
        val ops = Counting(raw)
        ops.write(GuardChar.LOCK_STATE, 1)
        ops.pause(800)
        val lock = ops.read(GuardChar.LOCK_STATE)
        val def = ops.read(GuardChar.ALARM_STATE)
        return SequenceReadback(defence = def, lock = lock, writes = ops.writes)
    }

    /** the guard design 3.4: the siren (5523 = 3), then one read. */
    suspend fun siren(raw: GuardOps): SequenceReadback {
        val ops = Counting(raw)
        ops.write(GuardChar.ALARM_STATE, 3)
        ops.pause(600)
        val def = ops.read(GuardChar.ALARM_STATE)
        return SequenceReadback(defence = def, lock = null, writes = ops.writes)
    }

    /** the guard design 4: after a reconnect, read before writing. Read only. */
    suspend fun verify(ops: GuardOps): SequenceReadback {
        val def = ops.read(GuardChar.ALARM_STATE)
        val lock = ops.read(GuardChar.LOCK_STATE)
        val status = ops.read(GuardChar.MODULE_MODE)
        return SequenceReadback(defence = def, lock = lock, status = status, writes = 0)
    }

    /** the guard design 5.1: 5523 = 0, unlock, one more try if needed, alarm mode back if it was off before arming. */
    suspend fun disarm(raw: GuardOps, restoreAlarmMode: Boolean): SequenceReadback {
        val ops = Counting(raw)
        ops.write(GuardChar.ALARM_STATE, 0)
        ops.pause(500)
        ops.write(GuardChar.LOCK_STATE, 2)
        ops.pause(800)
        var lock = ops.read(GuardChar.LOCK_STATE)
        var def = ops.read(GuardChar.ALARM_STATE)
        if ((def != 0 && def != 1) || lock != 0) {
            ops.write(GuardChar.ALARM_STATE, 0)
            ops.pause(500)
            ops.write(GuardChar.LOCK_STATE, 2)
            ops.pause(800)
            lock = ops.read(GuardChar.LOCK_STATE)
            def = ops.read(GuardChar.ALARM_STATE)
        }
        if (restoreAlarmMode) {
            ops.write(GuardChar.ALARM_MODE, 0)
            ops.pause(300)
            ops.read(GuardChar.ALARM_MODE)
        }
        return SequenceReadback(def, lock, writes = ops.writes, ok = (def == 0 || def == 1) && lock == 0)
    }

    /** The re-arm's wait for 2 after writing it (shorter than arming: the bike already knows the way). */
    const val REARM_DIRECT_WAIT_MS = 4_000L

    /** The re-arm's second try at 5521 = 1 after the first one threw. */
    const val LOCK_RETRY_MS = 300L
}
