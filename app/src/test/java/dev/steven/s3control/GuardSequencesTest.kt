package dev.steven.s3control

import dev.steven.s3control.ble.ArmOutcome
import dev.steven.s3control.ble.ArmPath
import dev.steven.s3control.ble.GuardChar
import dev.steven.s3control.ble.GuardOps
import dev.steven.s3control.ble.GuardSequences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wache's write sequences (the guard design 1.3, 3.4, 3.5, 5.1) against a fake bike (test list 9.5). */
class GuardSequencesTest {

    /**
     * A small model of the S3's defence state as the decomp describes it: 5521 = 1 locks (5523 reads 1), 5521 = 2
     * unlocks, 5523 = 0 stops everything and clears the software lock, 5523 = 2/3/4 enter watch, siren or tracking.
     */
    private class FakeBike(var mode: Int? = 0, var lock: Int? = 0, var def: Int? = 0, var status: Int? = 0) : GuardOps {
        /** 5523 turns 2 by itself after this many 5523 reads following a lock write (null: never, PC8 bridged). */
        var nativeAfter: Int? = null
        var acceptMode = true
        var acceptLock = true
        var acceptUnlock = true
        var acceptDirect = true
        /** Called on every 5523 read after the lock write, with the count; may change the state (the bike escalating). */
        var onDefenceRead: (FakeBike, Int) -> Unit = { _, _ -> }
        /** The next this many 5521 = 1 writes throw (a GATT error) without reaching the bike. */
        var lockWriteFailures = 0

        val writes = ArrayList<Pair<GuardChar, Int>>()
        val trace = ArrayList<String>()
        private var readsSinceLock = -1

        override suspend fun read(ch: GuardChar): Int? {
            trace += "R${ch.short}"
            return when (ch) {
                GuardChar.ALARM_MODE -> mode
                GuardChar.LOCK_STATE -> lock
                GuardChar.MODULE_MODE -> status
                GuardChar.ALARM_STATE -> {
                    if (readsSinceLock >= 0) {
                        readsSinceLock++
                        if (nativeAfter != null && readsSinceLock >= nativeAfter!! && def == 1) def = 2
                        onDefenceRead(this, readsSinceLock)
                    }
                    def
                }
            }
        }

        override suspend fun write(ch: GuardChar, value: Int) {
            require(ch.writable) { "${ch.short} is read only" }
            if (ch == GuardChar.LOCK_STATE && value == 1 && lockWriteFailures > 0) {
                lockWriteFailures--
                writes += ch to value
                trace += "X${ch.short}=$value"
                throw IllegalStateException("GATT 133")
            }
            writes += ch to value
            trace += "W${ch.short}=$value"
            when (ch) {
                GuardChar.ALARM_MODE -> if (acceptMode) mode = value
                GuardChar.LOCK_STATE -> when (value) {
                    1 -> if (acceptLock) {
                        lock = 1
                        if (def == 0 || def == null) def = 1
                        readsSinceLock = 0
                    }
                    2 -> if (acceptUnlock) {
                        lock = 0
                        def = 0
                    }
                }
                GuardChar.ALARM_STATE -> when (value) {
                    0 -> {
                        def = 0
                        lock = 0
                    }
                    2 -> if (acceptDirect) def = 2
                    else -> def = value
                }
                GuardChar.MODULE_MODE -> error("not writable")
            }
        }

        override suspend fun pause(ms: Long) {
            trace += "P$ms"
        }

        fun w(vararg pairs: Pair<GuardChar, Int>) = pairs.toList()
    }

    private val bikes = ArrayList<FakeBike>()

    private fun bike(mode: Int? = 0, lock: Int? = 0, def: Int? = 0, status: Int? = 0) =
        FakeBike(mode, lock, def, status).also { bikes += it }

    /** 7. No sequence ever writes anything but 5521, 5523 and 5524. */
    @After
    fun onlyTheThreeGuardCharacteristicsWereWritten() {
        for (b in bikes) for ((ch, _) in b.writes) assertTrue(ch.short in setOf("5521", "5523", "5524"))
    }

    @Test
    fun theWriteApiCannotNameModuleState() {
        assertEquals(setOf("5521", "5523", "5524"), GuardChar.entries.filter { it.writable }.map { it.short }.toSet())
        assertTrue(GuardChar.entries.none { it.short == "5562" })
    }

    private val MODE = GuardChar.ALARM_MODE
    private val LOCK = GuardChar.LOCK_STATE
    private val DEF = GuardChar.ALARM_STATE

    // --- Arming ---------------------------------------------------------------------------------

    @Test
    fun nativeArming() = runBlocking {
        val b = bike(mode = 0).apply { nativeAfter = 4 }
        val r = GuardSequences.arm(b)
        assertEquals(ArmOutcome.WATCHING, r.outcome)
        assertEquals(ArmPath.NATIVE, r.path)
        assertTrue(r.modeWasOff)
        assertEquals(b.w(MODE to 1, LOCK to 1), b.writes)
        assertEquals(800L + 4 * 500L, r.waitedMs)
        assertEquals(0, r.mode0)
    }

    @Test
    fun directArming() = runBlocking {
        val b = bike(mode = 0)
        val r = GuardSequences.arm(b)
        assertEquals(ArmOutcome.WATCHING, r.outcome)
        assertEquals(ArmPath.DIRECT, r.path)
        assertEquals(b.w(MODE to 1, LOCK to 1, DEF to 2), b.writes)
    }

    @Test
    fun alarmModeAlreadyOnIsNotWrittenNorRestored() = runBlocking {
        val b = bike(mode = 1)
        val r = GuardSequences.arm(b)
        assertFalse(r.modeWasOff)
        assertEquals(b.w(LOCK to 1, DEF to 2), b.writes)
    }

    @Test
    fun unconfirmedArming() = runBlocking {
        val b = bike(mode = 0).apply { acceptDirect = false }
        val r = GuardSequences.arm(b)
        assertEquals(ArmOutcome.UNCONFIRMED, r.outcome)
        assertEquals(1, r.value)
        assertEquals(1, r.lock)
        assertNull(r.path)
        // Bounded: native wait plus direct wait, as the UI promises.
        val waited = b.trace.filter { it.startsWith("P") }.sumOf { it.drop(1).toLong() }
        assertTrue(waited <= 20_000L)
    }

    @Test
    fun aSirenDuringTheSettleIsStoppedAndTheModeRestored() = runBlocking {
        val b = bike(mode = 0).apply { onDefenceRead = { bk, n -> if (n == 2) bk.def = 3 } }
        val r = GuardSequences.arm(b)
        assertEquals(ArmOutcome.ALARM_DURING, r.outcome)
        assertEquals(3, r.value)
        assertEquals(b.w(MODE to 1, LOCK to 1, DEF to 0, LOCK to 1, MODE to 0), b.writes)
    }

    @Test
    fun aRefusedLockRestoresTheMode() = runBlocking {
        val b = bike(mode = 0).apply { acceptLock = false }
        val r = GuardSequences.arm(b)
        assertEquals(ArmOutcome.LOCK_REFUSED, r.outcome)
        assertEquals(0, r.value)
        assertEquals(b.w(MODE to 1, LOCK to 1, MODE to 0), b.writes)
    }

    @Test
    fun aRefusedAlarmModeWritesNothingElse() = runBlocking {
        val b = bike(mode = 0).apply { acceptMode = false }
        val r = GuardSequences.arm(b)
        assertEquals(ArmOutcome.ALARM_MODE_REFUSED, r.outcome)
        assertEquals(0, r.value)
        assertEquals(b.w(MODE to 1), b.writes)
    }

    @Test
    fun anUpdatingOrShippingBikeIsNotTouched() = runBlocking {
        for (status in listOf(3, 4)) {
            val b = bike(status = status)
            assertEquals(ArmOutcome.BIKE_STATE, GuardSequences.arm(b).outcome)
            assertTrue(b.writes.isEmpty())
        }
    }

    @Test
    fun aSirenFromBeforeIsStoppedFirst() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 3)
        GuardSequences.arm(b)
        assertEquals(DEF to 0, b.writes.first())
    }

    @Test
    fun alreadyWatchingOnlyMakesSureItIsLocked() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 2)
        val r = GuardSequences.arm(b)
        assertEquals(ArmOutcome.WATCHING, r.outcome)
        assertTrue(b.writes.isEmpty())
    }

    // --- Re-arm, siren, verify -------------------------------------------------------------------

    @Test
    fun rearmLocksRightAfterTheStopThenWritesTwoForDirect() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 4)
        val rb = GuardSequences.rearm(b, ArmPath.DIRECT)
        assertEquals(b.w(DEF to 0, LOCK to 1, DEF to 2), b.writes)
        // No pause (and no read) between the stop and the lock: the bike is open for one write only.
        val stop = b.trace.indexOf("W5523=0")
        assertEquals("W5521=1", b.trace[stop + 1])
        assertEquals(2, rb.defence)
        assertEquals(1, rb.lock)
        assertEquals(3, rb.writes)
    }

    @Test
    fun rearmTriesTheLockOnceMoreWhenItsWriteThrows() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 4).apply { lockWriteFailures = 1 }
        val rb = GuardSequences.rearm(b, ArmPath.DIRECT)
        assertEquals(b.w(DEF to 0, LOCK to 1, LOCK to 1, DEF to 2), b.writes)
        assertEquals(listOf("W5523=0", "X5521=1", "P${GuardSequences.LOCK_RETRY_MS}", "W5521=1"), b.trace.take(4))
        assertEquals(1, rb.lock)
    }

    @Test
    fun rearmGivesUpWhenTheLockWriteThrowsTwice() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 4).apply { lockWriteFailures = 2 }
        val failed = runCatching { GuardSequences.rearm(b, ArmPath.DIRECT) }
        assertTrue(failed.isFailure)
        // It stops there: no 5523 = 2 on an open bike; the state machine puts the lock back (re-lock).
        assertEquals(b.w(DEF to 0, LOCK to 1, LOCK to 1), b.writes)
    }

    @Test
    fun relockWritesOnlyTheLockAndReadsBack() = runBlocking {
        val b = bike(mode = 1, lock = 0, def = 0)
        val rb = GuardSequences.relock(b)
        assertEquals(b.w(LOCK to 1), b.writes)
        assertEquals(1, rb.lock)
        assertEquals(1, rb.defence)
        assertEquals(1, rb.writes)
    }

    @Test
    fun rearmOnTheNativePathWaitsBeforeWriting() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 4).apply { nativeAfter = 3 }
        val rb = GuardSequences.rearm(b, ArmPath.NATIVE)
        assertEquals(b.w(DEF to 0, LOCK to 1), b.writes)
        assertEquals(2, rb.defence)
    }

    @Test
    fun rearmWithoutAKnownPathGoesDirect() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 1)
        GuardSequences.rearm(b, null)
        assertEquals(b.w(DEF to 0, LOCK to 1, DEF to 2), b.writes)
    }

    @Test
    fun sirenWritesThreeAndReadsBack() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 2)
        val rb = GuardSequences.siren(b)
        assertEquals(b.w(DEF to 3), b.writes)
        assertEquals(3, rb.defence)
        assertEquals(1, rb.writes)
    }

    @Test
    fun verifyOnlyReads() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 2, status = 2)
        val rb = GuardSequences.verify(b)
        assertTrue(b.writes.isEmpty())
        assertEquals(2, rb.defence)
        assertEquals(1, rb.lock)
        assertEquals(2, rb.status)
    }

    // --- Disarm ------------------------------------------------------------------------------------

    @Test
    fun disarmStopsUnlocksAndLeavesTheModeWhenItWasOnBefore() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 3)
        val rb = GuardSequences.disarm(b, restoreAlarmMode = false)
        assertEquals(b.w(DEF to 0, LOCK to 2), b.writes)
        assertTrue(rb.ok)
        assertEquals(1, b.mode)
    }

    @Test
    fun disarmSwitchesTheModeBackOffWhenTheWacheSwitchedItOn() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 2)
        val rb = GuardSequences.disarm(b, restoreAlarmMode = true)
        assertEquals(b.w(DEF to 0, LOCK to 2, MODE to 0), b.writes)
        assertTrue(rb.ok)
        assertEquals(0, b.mode)
    }

    @Test
    fun disarmTriesOnceMoreAndReportsWhatStayed() = runBlocking {
        val b = bike(mode = 1, lock = 1, def = 2).apply { acceptUnlock = false }
        // 5523 = 0 also clears the software lock in the model; make the lock stick to see the retry.
        val sticky = object : GuardOps by b {
            override suspend fun write(ch: GuardChar, value: Int) {
                b.write(ch, value)
                if (ch == GuardChar.ALARM_STATE && value == 0) b.lock = 1
            }
        }
        val rb = GuardSequences.disarm(sticky, restoreAlarmMode = false)
        assertEquals(b.w(DEF to 0, LOCK to 2, DEF to 0, LOCK to 2), b.writes)
        assertFalse(rb.ok)
        assertEquals(1, rb.lock)
    }
}
