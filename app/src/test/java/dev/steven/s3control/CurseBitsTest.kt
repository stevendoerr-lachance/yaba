package dev.steven.s3control

import androidx.compose.runtime.MonotonicFrameClock
import dev.steven.s3control.ui.components.DriveInput
import dev.steven.s3control.ui.components.DriveLock
import dev.steven.s3control.ui.components.Grummler
import dev.steven.s3control.ui.components.HapticCues
import dev.steven.s3control.ui.components.Heat
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.MemoryOutburstStore
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.Outburst
import dev.steven.s3control.ui.components.OutburstPolicy
import dev.steven.s3control.ui.components.OutburstCue
import dev.steven.s3control.ui.components.OutburstPolicy.Denial
import dev.steven.s3control.ui.components.OutburstStage
import dev.steven.s3control.ui.components.OutburstSnapshot
import dev.steven.s3control.ui.components.ServerFailure
import dev.steven.s3control.ui.components.YabaWordmark
import dev.steven.s3control.ui.components.outburstOf
import java.io.File
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate (caps, spacing, session, drive lock, escalation) and the timeline of the call, in virtual time. */
class CurseBitsTest {
    private val minute = 60_000L
    private val day = 24 * 3_600_000L

    // ---- helpers ------------------------------------------------------------------------------------

    private class Clock(var now: Long = 1_000_000_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun policy(store: MemoryOutburstStore = MemoryOutburstStore(), clock: Clock = Clock()) =
        OutburstPolicy(store, clock = { clock.now })

    private val ok = OutburstSnapshot()

    // ---- the gate -----------------------------------------------------------------------------------

    @Test
    fun capsAddUpToElevenWithFiveCurses() {
        assertEquals(11, Moment.entries.sumOf { it.cap })
        assertEquals(5, Moment.entries.filter { it.curse }.sumOf { it.cap })
    }

    @Test
    fun momentsHaveTheirCaps() {
        val clock = Clock()
        val p = policy(clock = clock)
        // Fault42: two per installation. Each call is a curse, so a new session (policy) is needed for each one.
        val store = MemoryOutburstStore()
        repeat(2) {
            val session = policy(store, clock)
            clock.advance(11 * minute)
            assertNull(session.verdict(Moment.Fault42, ok))
            session.commit(Moment.Fault42)
        }
        val third = policy(store, clock)
        clock.advance(11 * minute)
        assertEquals(Denial.MomentCap, third.verdict(Moment.Fault42, ok))
        // the other moments are untouched
        assertNull(p.verdict(Moment.NotFound, ok))
    }

    @Test
    fun theSwitchTalkBackReducedMotionDriveLockAndDialogsStopEverything() {
        val p = policy()
        assertNull(p.verdict(Moment.NotFound, ok))
        assertEquals(Denial.Accessibility, p.verdict(Moment.NotFound, ok.copy(talkBack = true)))
        assertEquals(Denial.Accessibility, p.verdict(Moment.NotFound, ok.copy(reducedMotion = true)))
        assertEquals(Denial.DriveLock, p.verdict(Moment.NotFound, ok.copy(driveLock = true)))
        assertEquals(Denial.Dialog, p.verdict(Moment.NotFound, ok.copy(dialogOpen = true)))
        assertEquals(Denial.TooEarly, p.verdict(Moment.NotFound, ok.copy(screenAgeMillis = 999)))
        assertNull(p.verdict(Moment.NotFound, ok.copy(screenAgeMillis = 1000)))
        assertEquals(Denial.VisitUsed, p.verdict(Moment.NotFound, ok.copy(visitPlayed = true)))
        p.enabled = false
        assertEquals(Denial.SwitchOff, p.verdict(Moment.NotFound, ok))
        assertFalse(p.probeAllowed(ok))
        p.enabled = true
        assertTrue(p.probeAllowed(ok))
        assertFalse(p.probeAllowed(ok.copy(driveLock = true)))
        assertFalse(p.probeAllowed(ok.copy(reducedMotion = true)))
    }

    @Test
    fun tenMinutesBetweenTwoCalls() {
        val clock = Clock()
        val p = policy(clock = clock)
        p.commit(Moment.NotFound)
        clock.advance(10 * minute - 1)
        assertEquals(Denial.Spacing, p.verdict(Moment.ServerFirst, ok))
        clock.advance(1)
        assertNull(p.verdict(Moment.ServerFirst, ok))
    }

    @Test
    fun oneCursePerSessionButANewSessionMayCurseAgain() {
        val clock = Clock()
        val store = MemoryOutburstStore()
        val first = policy(store, clock)
        first.commit(Moment.Fault42)
        clock.advance(11 * minute)
        assertEquals(Denial.CurseThisSession, first.verdict(Moment.ServerThird, ok))
        // a sigh is no curse
        assertNull(first.verdict(Moment.ServerFirst, ok))
        // the next start of the app is a new session
        val second = policy(store, clock)
        assertNull(second.verdict(Moment.ServerThird, ok))
    }

    @Test
    fun theWelcomeIsNotTiedToTheFirstStartAnyMoreButItsCapOfOneStays() {
        val store = MemoryOutburstStore()
        val first = policy(store)
        assertEquals(1, first.launch)
        assertNull(first.verdict(Moment.Welcome, ok))
        // The first start never reached the Welcome (the film ran, the app was killed): the second start may still call.
        val second = policy(store)
        assertEquals(2, second.launch)
        assertNull(second.verdict(Moment.Welcome, ok))
        // Once it counted, it is over for good, on any later start.
        second.commit(Moment.Welcome)
        val third = policy(store)
        assertEquals(Denial.MomentCap, third.verdict(Moment.Welcome, ok))
    }

    @Test
    fun reliefOnlyAfterABikeNotFoundInTheSameSession() {
        val clock = Clock()
        val p = policy(clock = clock)
        assertEquals(Denial.NoSighBefore, p.verdict(Moment.Relief, ok))
        p.commit(Moment.NotFound)
        clock.advance(11 * minute)
        assertNull(p.verdict(Moment.Relief, ok))
        p.commit(Moment.Relief)
        clock.advance(11 * minute)
        assertEquals(Denial.NoSighBefore, p.verdict(Moment.Relief, ok))
    }

    @Test
    fun notFoundAndVanMoofAppShareOneCap() {
        // GEDOE and BALEN are the same moment: two per installation in all.
        val clock = Clock()
        val p = policy(clock = clock)
        repeat(2) {
            clock.advance(11 * minute)
            assertNull(p.verdict(Moment.NotFound, ok))
            p.commit(Moment.NotFound)
        }
        clock.advance(11 * minute)
        assertEquals(Denial.MomentCap, p.verdict(Moment.NotFound, ok))
    }

    @Test
    fun theWholeBudgetStopsAtElevenAndFiveCurses() {
        val clock = Clock()
        val store = MemoryOutburstStore()
        var counted = 0
        var curses = 0

        fun session(vararg moments: Moment) {
            val session = policy(store, clock)
            for (moment in moments) {
                clock.advance(11 * minute)
                assertNull("$moment", session.verdict(moment, ok))
                session.commit(moment)
                counted++
                if (moment.curse) curses++
            }
        }

        // Every moment as often as its cap allows; each curse in a session of its own (one curse per session).
        session(Moment.Welcome)                      // the first start
        session(Moment.NotFound, Moment.Relief)      // the relief follows a bike that was not found, in the same session
        session(Moment.NotFound, Moment.Relief)
        session(Moment.Fault42)
        session(Moment.Fault42)
        session(Moment.ServerFirst)
        session(Moment.ServerFirst)
        session(Moment.ServerThird)
        session(Moment.ServerDead)

        assertEquals(11, counted)
        assertEquals(5, curses)
        val last = policy(store, clock)
        clock.advance(11 * minute)
        for (moment in Moment.entries) assertNotEquals("$moment", null, last.verdict(moment, ok))
        // and a twelfth call is out of reach even for a moment with room left in its own cap
        assertEquals(11, last.totalCount)
        assertEquals(5, last.curseCount)
    }

    // ---- the server -----------------------------------------------------------------------------------

    @Test
    fun serverEscalationIsNouJaThenNothingThenBekDicht() {
        val clock = Clock()
        val p = policy(clock = clock)
        val http = ServerFailure.Http(503)

        assertEquals(Moment.ServerFirst, p.serverFailed(http, true).moment)
        p.commit(Moment.ServerFirst)
        clock.advance(30_000)
        assertNull(p.serverFailed(http, true).moment)
        clock.advance(30_000)
        assertEquals(Moment.ServerThird, p.serverFailed(http, true).moment)
        // the escalation is the one exception to the ten minutes
        assertNull(p.verdict(Moment.ServerThird, ok))
        p.commit(Moment.ServerThird)
        clock.advance(30_000)
        assertNull(p.serverFailed(http, true).moment)
    }

    @Test
    fun theEscalationIsNotExemptWhenSomethingElseCalledInBetween() {
        val clock = Clock()
        val p = policy(clock = clock)
        val http = ServerFailure.Http(500)
        p.serverFailed(http, true)
        p.commit(Moment.ServerFirst)
        clock.advance(minute)
        p.commit(Moment.NotFound)
        clock.advance(minute)
        p.serverFailed(http, true)
        clock.advance(minute)
        assertEquals(Moment.ServerThird, p.serverFailed(http, true).moment)
        assertEquals(Denial.Spacing, p.verdict(Moment.ServerThird, ok))
    }

    @Test
    fun aTimeoutCountsLikeAnErrorButOnlyWithInternet() {
        val p = policy()
        // no internet: the phone is to blame, not the server
        assertNull(p.serverFailed(ServerFailure.Timeout, false).moment)
        assertEquals(Moment.ServerFirst, p.serverFailed(ServerFailure.Timeout, true).moment)
    }

    @Test
    fun noConnectionAtAllIsNeverANouJa() {
        val p = policy()
        assertNull(p.serverFailed(ServerFailure.Unreachable, true).moment)
        assertNull(p.serverFailed(ServerFailure.Unreachable, true).moment)
    }

    @Test
    fun sevenDaysWithoutAnAnswerAfterThreeRequestsIsDePijpUit() {
        val clock = Clock()
        val p = policy(clock = clock)
        assertNull(p.serverFailed(ServerFailure.Unreachable, true).moment)
        clock.advance(3 * day)
        assertNull(p.serverFailed(ServerFailure.Unreachable, true).moment)
        clock.advance(3 * day)
        // 6 days, three requests: not yet
        assertNull(p.serverFailed(ServerFailure.Unreachable, true).moment)
        clock.advance(day + 1)
        val verdict = p.serverFailed(ServerFailure.Unreachable, true)
        assertEquals(Moment.ServerDead, verdict.moment)
        assertEquals(7, verdict.days)
    }

    @Test
    fun withoutProvenInternetNoServerIsEverDead() {
        val clock = Clock()
        val p = policy(clock = clock)
        repeat(5) {
            assertNull(p.serverFailed(ServerFailure.Unreachable, null).moment)
            assertNull(p.serverFailed(ServerFailure.Unreachable, false).moment)
            clock.advance(2 * day)
        }
    }

    @Test
    fun anAnswerStartsTheSevenDaysOver() {
        val clock = Clock()
        val p = policy(clock = clock)
        p.serverFailed(ServerFailure.Unreachable, true)
        clock.advance(3 * day)
        p.serverFailed(ServerFailure.Unreachable, true)
        clock.advance(3 * day)
        p.serverFailed(ServerFailure.Unreachable, true)
        p.serverAnswered()
        clock.advance(2 * day)
        assertNull(p.serverFailed(ServerFailure.Unreachable, true).moment)
    }

    @Test
    fun aFiveHundredAnswerIsAnAnswer() {
        val clock = Clock()
        val p = policy(clock = clock)
        p.serverFailed(ServerFailure.Timeout, true)
        clock.advance(8 * day)
        // the server answers with a 503: it is alive, so this is no "seven days"
        val v = p.serverFailed(ServerFailure.Http(503), true)
        assertNotEquals(Moment.ServerDead, v.moment)
    }

    // ---- the drive lock ---------------------------------------------------------------------------------

    private fun still(ready: Boolean = true) =
        DriveInput(ready, speedKmh = 0, riding = false, alarmOrShipping = false, shippingImminent = false, wheelMoving = false)

    @Test
    fun speedLocksForTenSeconds() {
        val clock = Clock()
        val lock = DriveLock(clock = { clock.now })
        assertFalse(lock.locked())
        lock.observe(still().copy(speedKmh = 1))
        lock.observe(still())
        assertTrue(lock.locked())
        clock.advance(9_999)
        assertTrue(lock.locked())
        clock.advance(1)
        assertFalse(lock.locked())
    }

    @Test
    fun ridingAlarmShippingAndImminentShippingLockWhileTheyLast() {
        val clock = Clock()
        val lock = DriveLock(clock = { clock.now })
        for (input in listOf(
            still().copy(riding = true),
            still().copy(alarmOrShipping = true),
            still().copy(shippingImminent = true),
        )) {
            lock.observe(input)
            assertTrue(lock.locked())
            lock.observe(still())
            assertFalse(lock.locked())
        }
    }

    @Test
    fun aLinkThatDropsDuringARideLocksForTenMinutes() {
        val clock = Clock()
        val store = MemoryOutburstStore()
        val lock = DriveLock(store, clock = { clock.now })
        lock.observe(still().copy(speedKmh = 18, riding = true))
        clock.advance(2_000)
        lock.observe(still(ready = false).copy(speedKmh = null))
        clock.advance(11_000)
        assertTrue(lock.locked())
        clock.advance(10 * minute - 12_000)
        assertTrue(lock.locked())
        clock.advance(12_000)
        assertFalse(lock.locked())
    }

    @Test
    fun theDropIsRememberedAcrossAProcessRestart() {
        val clock = Clock()
        val store = MemoryOutburstStore()
        val lock = DriveLock(store, clock = { clock.now })
        lock.observe(still().copy(speedKmh = 20))
        lock.observe(still(ready = false))
        clock.advance(5 * minute)
        assertTrue(DriveLock(store, clock = { clock.now }).locked())
    }

    @Test
    fun aLinkThatDropsWhileTheBikeStandsLocksNothing() {
        val clock = Clock()
        val lock = DriveLock(MemoryOutburstStore(), clock = { clock.now })
        lock.observe(still())
        clock.advance(60_000)
        lock.observe(still(ready = false))
        assertFalse(lock.locked())
    }

    // ---- the vocabulary -----------------------------------------------------------------------------------

    private fun outbursts(): Map<String, String> {
        val file = listOf("src/main/res/values/strings_outbursts.xml", "app/src/main/res/values/strings_outbursts.xml")
            .map { File(it) }.first { it.exists() }
        return Regex("""<string name="(\w+)"[^>]*>([^<]*)</string>""").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun everyBeatFitsWithAFreeColumnOnEachSideOfThirtyFour() {
        val widths = mapOf(
            "AUW" to 17, "BALEN" to 29, "GEDOE" to 29, "NOU JA" to 32, "HÈ HÈ" to 26, "VER-" to 23, "DORIE" to 27,
            "DE" to 11, "PIJP" to 21, "UIT" to 15, "BEK" to 17, "DICHT" to 27, "DOMME" to 29,
        )
        for ((beat, w) in widths) assertEquals(beat, w, MatrixFont.render(beat).size)
        val words = outbursts().filterKeys { it.startsWith("outburst_") }
        assertEquals(9, words.size)
        for ((key, raw) in words) {
            val beats = raw.split('|')
            assertTrue(key, beats.size in 1..3)
            for (beat in beats) {
                assertTrue("$key $beat", beat.all { it in 'A'..'Z' || it == ' ' || it == '-' || it == 'È' })
                assertTrue("$key $beat", MatrixFont.render(beat).size <= 32)
            }
        }
    }

    @Test
    fun theWordsMatchTheLexicon() {
        val w = outbursts()
        assertEquals("DE|PIJP|UIT", w["outburst_welcome"])
        assertEquals("BALEN", w["outburst_notfound"])
        assertEquals("GEDOE", w["outburst_vanmoof_app"])
        assertEquals("HÈ HÈ", w["outburst_relief"])
        assertEquals("VER-|DORIE", w["outburst_fault42"])
        assertEquals("NOU JA", w["outburst_server_first"])
        assertEquals("BEK|DICHT", w["outburst_server_third"])
        assertEquals("DE|PIJP|UIT", w["outburst_server_dead"])
        assertEquals("VER-|DOMME", w["outburst_probe3"])
        assertEquals("VERDORIE", outburstOf("x", Heat.Curse, "VER-|DORIE").word)
        assertEquals("DE PIJP UIT", outburstOf("x", Heat.Curse, "DE|PIJP|UIT").word)
    }

    @Test
    fun theWordMarkIsThirtyOneColumnsAndFitsAThirtySixColumnTube() {
        assertEquals(31, YabaWordmark.width)
    }

    // ---- the timeline ---------------------------------------------------------------------------------------

    /** A single-threaded dispatcher on virtual milliseconds, so that a performance can be inspected at any instant. */
    @OptIn(InternalCoroutinesApi::class)
    private class VirtualTime : CoroutineDispatcher(), Delay {
        private class Task(val time: Long, val seq: Long, val block: Runnable)

        private var seq = 0L
        var now = 0L
            private set
        private val queue = PriorityQueue<Task>(compareBy<Task>({ it.time }, { it.seq }))

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(Task(now, seq++, block))
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = Task(now + timeMillis, seq++, Runnable { with(continuation) { resumeUndispatched(Unit) } })
            queue.add(task)
            continuation.invokeOnCancellation { queue.remove(task) }
        }

        fun advanceTo(target: Long) {
            while (true) {
                val next = queue.peek() ?: break
                if (next.time > target) break
                queue.poll()
                now = next.time
                next.block.run()
            }
            now = target
        }
    }

    /** A frame every 16 ms of virtual time, like a 60 Hz display. */
    private class FrameClock(private val time: VirtualTime) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val next = (time.now / 16 + 1) * 16
            delay(next - time.now)
            return onFrame(time.now * 1_000_000L)
        }
    }

    private class Rig {
        val time = VirtualTime()
        val log = ArrayList<Pair<Long, String>>()
        val haptics = object : HapticCues {
            override fun tick() { log += time.now to "tick" }
            override fun ramp() { log += time.now to "ramp" }
            override fun thud() { log += time.now to "thud" }
            override fun confirm() { log += time.now to "confirm" }
        }
        val grummler = Grummler(haptics, nowMs = { time.now })
        val scope = CoroutineScope(time + FrameClock(time) + Job())
        var strikeAt = -1L
        val calm = MatrixFrame.text("E42")

        fun play(o: Outburst): Job = scope.launch {
            grummler.play(o, calm = { calm }, onStrike = { strikeAt = time.now })
        }

        fun at(ms: Long) = time.advanceTo(ms)
        fun times(what: String) = log.filter { it.second == what }.map { it.first }
        fun shows(text: String) = grummler.frame.columns.contentEquals(MatrixFont.render(text))
        fun empty() = grummler.frame.columns.isEmpty()
    }

    @Test
    fun aTwoBeatCurseFollowsTheSpecToTheMillisecond() {
        val rig = Rig()
        rig.play(outburstOf("fault42", Heat.Curse, "VER-|DORIE"))
        val g = rig.grummler

        // 0 to 160: breathing in, the running display stands
        rig.at(1); assertTrue(g.active)
        rig.at(100); assertTrue(rig.shows("E42")); assertTrue(g.dot.value < 1f && g.dot.value > 0.8f)
        assertTrue(g.glow.value < 1f)
        // while breathing in the running display keeps its own colour; the call (led) starts with the dark
        assertFalse(g.calling)
        // 160 to 320: dark
        rig.at(170); assertTrue(rig.empty()); assertEquals(0, g.sweepMs); assertTrue(g.calling)
        assertEquals(1f, g.dot.value, 0f); assertEquals(1f, g.glow.value, 0f)
        rig.at(319); assertTrue(rig.empty())
        // 320: run-up, first beat by cut, tick
        rig.at(321); assertTrue(rig.shows("VER-")); assertEquals(0, g.sweepMs)
        assertEquals(listOf(320L), rig.times("tick"))
        // 320 to 720: build-up, glow and dots rise linearly, six ramps from 360 every 60 ms
        rig.at(560)
        assertTrue(g.glow.value > 1.4f && g.glow.value < 1.8f)
        assertTrue(g.dot.value > 1.03f && g.dot.value < 1.08f)
        rig.at(719); assertTrue(rig.shows("VER-")); assertEquals(-1L, rig.strikeAt)
        assertEquals(listOf(360L, 420L, 480L, 540L, 600L, 660L), rig.times("ramp"))
        assertTrue(rig.times("thud").isEmpty())
        // 720: the strike, thud exactly then, the counter may rise, dot r 0.41 p, glow 1.8
        rig.at(720)
        assertEquals(720L, rig.strikeAt)
        assertEquals(listOf(720L), rig.times("thud"))
        rig.at(725)
        assertTrue(rig.shows("DORIE")); assertEquals(0, g.sweepMs)
        assertEquals(1.206f, g.dot.value, 0f); assertEquals(1.8f, g.glow.value, 0f)
        // the field jumps in whole columns: 0, -1, +1, -1, +1, 0 with 35 ms each
        rig.at(740); assertEquals(0, g.shift)
        rig.at(770); assertEquals(-1, g.shift)
        rig.at(800); assertEquals(1, g.shift)
        rig.at(840); assertEquals(-1, g.shift)
        rig.at(880); assertEquals(1, g.shift)
        rig.at(920); assertEquals(0, g.shift)
        // 810 to 970 the dot falls to 1.0, 820 to 1620 the glow falls to 1.0, the frame stands until 1620
        rig.at(900); assertTrue(g.dot.value < 1.206f && g.dot.value > 1f)
        rig.at(1000); assertEquals(1f, g.dot.value, 0.001f); assertTrue(g.glow.value < 1.8f && g.glow.value > 1f)
        rig.at(1619); assertTrue(rig.shows("DORIE"))
        // 1620 to 1780: swept away to the right, linear, 160 ms
        rig.at(1625); assertTrue(rig.empty()); assertEquals(160, g.sweepMs); assertTrue(g.linear)
        // 1780 to 2080: silence, nothing moves, the paragraph is still covered
        rig.at(2000); assertTrue(rig.empty()); assertEquals(0f, g.uncover.value, 0f); assertFalse(g.inSentence)
        rig.at(2079); assertTrue(rig.empty())
        // 2080 to 2600: the sentence, the state returns with the 520 ms sweep and the paragraph is uncovered
        rig.at(2085); assertTrue(rig.shows("E42")); assertEquals(520, g.sweepMs); assertFalse(g.linear); assertTrue(g.inSentence)
        assertFalse(g.calling)
        rig.at(2340); assertTrue(g.uncover.value > 0f && g.uncover.value < 1f)
        rig.at(2700); assertEquals(1f, g.uncover.value, 0f); assertFalse(g.active)
        assertEquals(1f, g.dot.value, 0f); assertEquals(1f, g.glow.value, 0f); assertEquals(0, g.shift)
        // one tick, six ramps, one thud: nothing else ever buzzes
        assertEquals(8, rig.log.size)
    }

    @Test
    fun aThreeBeatCurseRunsAboutTwoPointEightSeconds() {
        val rig = Rig()
        rig.play(outburstOf("welcome", Heat.Curse, "DE|PIJP|UIT"))
        val g = rig.grummler
        rig.at(330); assertTrue(rig.shows("DE")); assertEquals(listOf(320L), rig.times("tick"))
        // the first beat stands 140 ms + 30 ms per letter: 200 ms for "DE"
        rig.at(519); assertTrue(rig.shows("DE"))
        rig.at(521); assertTrue(rig.shows("PIJP"))
        assertEquals(1, rig.times("tick").size)
        assertEquals(6, run { rig.at(919); rig.times("ramp").size })
        assertEquals(-1L, rig.strikeAt)
        rig.at(921); assertEquals(920L, rig.strikeAt); assertTrue(rig.shows("UIT"))
        assertEquals(listOf(920L), rig.times("thud"))
        rig.at(2799 - 16 - 1); assertTrue(g.active)
        rig.at(2830); assertFalse(g.active)
    }

    @Test
    fun aSighIsOneBeatWithoutStrikeOrRecoil() {
        val rig = Rig()
        rig.play(outburstOf("notfound", Heat.Sigh, "BALEN"))
        val g = rig.grummler
        rig.at(60); assertTrue(g.dot.value < 1f && g.dot.value >= 0.85f)
        // 120 to 640: the normal sweep with the normal dot
        rig.at(130); assertTrue(rig.shows("BALEN")); assertEquals(520, g.sweepMs); assertFalse(g.linear); assertEquals(1f, g.dot.value, 0f)
        rig.at(639); assertTrue(rig.times("tick").isEmpty())
        rig.at(640); assertEquals(640L, rig.strikeAt); assertEquals(listOf(640L), rig.times("tick"))
        // 640 to 1240: the brightness falls once from 1.0 to 0.6
        rig.at(900); assertTrue(g.lit.value < 1f && g.lit.value > 0.6f)
        rig.at(1300); assertEquals(0.6f, g.lit.value, 0.001f)
        // 1540 to 1700 swept away, 1900 the sentence
        rig.at(1545); assertTrue(rig.empty()); assertEquals(160, g.sweepMs); assertTrue(g.linear)
        rig.at(1899); assertTrue(rig.empty())
        rig.at(1905); assertTrue(rig.shows("E42")); assertEquals(1f, g.lit.value, 0f)
        rig.at(2500); assertFalse(g.active)
        assertEquals(0, g.shift)
        assertEquals(listOf("tick"), rig.log.map { it.second })
    }

    @Test
    fun aReliefBreathesOutAndConfirms() {
        val rig = Rig()
        rig.play(outburstOf("relief", Heat.Sigh, "HÈ HÈ", relief = true))
        val g = rig.grummler
        rig.at(130); assertTrue(rig.shows("HÈ HÈ")); assertEquals(0.6f, g.lit.value, 0f)
        rig.at(900); assertTrue(g.lit.value > 0.6f && g.lit.value < 1f)
        assertTrue(rig.times("confirm").isEmpty())
        rig.at(1300); assertEquals(1f, g.lit.value, 0.001f)
        val confirm = rig.times("confirm")
        assertEquals(1, confirm.size)
        assertTrue("confirm when 1.0 is reached, not before: ${confirm[0]}", confirm[0] in 1240L..1270L)
        assertTrue(rig.times("tick").isEmpty())
    }

    @Test
    fun thunderIsOnlyTheRehearsalWithTwoThudsAndEightRamps() {
        val rig = Rig()
        rig.play(outburstOf("probe3", Heat.Thunder, "VER-|DOMME"))
        val g = rig.grummler
        rig.at(210); assertTrue(rig.empty())
        rig.at(490); assertTrue(rig.shows("VER-")); assertEquals(listOf(480L), rig.times("tick"))
        rig.at(1039); assertEquals(8, rig.times("ramp").size); assertEquals(-1L, rig.strikeAt)
        assertEquals(480L + 40 + 70 * 7, rig.times("ramp").last())
        rig.at(1045); assertEquals(1040L, rig.strikeAt); assertTrue(rig.shows("DOMME")); assertEquals(1.206f, g.dot.value, 0f)
        assertEquals(2.2f, g.glow.value, 0f)
        rig.at(1200); assertEquals(listOf(1040L, 1180L), rig.times("thud"))
        // the recoil has eight steps: 0, -1, +1, -1, +1, -1, +1, 0
        rig.at(1040 + 35 * 6 + 5); assertEquals(1, g.shift)
        rig.at(1040 + 35 * 7 + 5); assertEquals(0, g.shift)
        // hold 1100, clear 200, silence 400
        rig.at(1040 + 1100 - 1); assertTrue(rig.shows("DOMME"))
        rig.at(1040 + 1100 + 5); assertTrue(rig.empty()); assertEquals(200, g.sweepMs)
        rig.at(1040 + 1100 + 200 + 400 - 1); assertTrue(rig.empty())
        rig.at(1040 + 1100 + 200 + 400 + 5); assertTrue(rig.shows("E42"))
    }

    @Test
    fun aCallThatIsCutOffBeforeTheStrikeNeverCountsAndLeavesNothingBehind() {
        val rig = Rig()
        val job = rig.play(outburstOf("fault42", Heat.Curse, "VER-|DORIE"))
        val g = rig.grummler
        rig.at(500)
        assertTrue(g.active)
        job.cancel()
        rig.at(600)
        assertEquals(-1L, rig.strikeAt)
        assertFalse(g.active)
        assertEquals(1f, g.dot.value, 0f); assertEquals(1f, g.glow.value, 0f); assertEquals(1f, g.lit.value, 0f)
        assertEquals(0, g.shift); assertEquals(1f, g.uncover.value, 0f)
        assertTrue(rig.times("thud").isEmpty())
    }

    @Test
    fun aTapJumpsToTheSentenceAndTheCounterOnlyRisesIfTheStrikeHadStood() {
        val early = Rig()
        early.play(outburstOf("fault42", Heat.Curse, "VER-|DORIE"))
        early.at(500)
        early.grummler.skip()
        early.at(510)
        assertTrue(early.shows("E42")); assertEquals(520, early.grummler.sweepMs); assertTrue(early.grummler.inSentence)
        assertEquals(-1L, early.strikeAt)
        early.at(1200)
        assertFalse(early.grummler.active); assertEquals(1f, early.grummler.uncover.value, 0f)
        assertTrue(early.times("thud").isEmpty())

        val late = Rig()
        late.play(outburstOf("fault42", Heat.Curse, "VER-|DORIE"))
        late.at(900)
        late.grummler.skip()
        late.at(1000)
        assertEquals(720L, late.strikeAt)
        assertTrue(late.shows("E42"))
        assertEquals(1f, late.grummler.dot.value, 0f)
    }

    @Test
    fun theRehearsalLeavesTheParagraphAlone() {
        val rig = Rig()
        rig.scope.launch {
            rig.grummler.play(outburstOf("probe3", Heat.Thunder, "VER-|DOMME"), calm = { rig.calm }, sentence = false)
        }
        rig.at(1000)
        assertEquals(1f, rig.grummler.uncover.value, 0f)
        rig.at(3500)
        assertFalse(rig.grummler.active)
        assertEquals(1f, rig.grummler.uncover.value, 0f)
    }

    // ---- the stage while the opening film covers it ---------------------------------------------------

    private class StageRig {
        var now = 5_000L
        val played = androidx.compose.runtime.mutableStateOf("")
        val policy = policy0()
        val stage = OutburstStage(
            Grummler(object : HapticCues {
                override fun tick() {}
                override fun ramp() {}
                override fun thud() {}
                override fun confirm() {}
            }),
            policy,
            android.content.ContextWrapper(null),
            played,
            elapsed = { now },
        )

        init {
            stage.calm = YabaWordmark
            stage.before = MatrixFrame.Empty
            stage.cueKey = "welcome#0"
            stage.cueMoment = Moment.Welcome
        }

        private fun policy0() = OutburstPolicy(MemoryOutburstStore())
    }

    @Test
    fun aHeldStageDecidesNothingCountsNothingAndKeepsTheParagraphCovered() {
        val rig = StageRig()
        val stage = rig.stage
        stage.hold = true
        stage.gateOpen = false // what rememberOutburstStage computes while held
        // five seconds of film later: still the dark tube, still covered, nothing played
        rig.now += 5_000
        assertTrue(stage.pending)
        assertTrue(stage.frame.columns.isEmpty())
        assertEquals(0f, stage.cover, 0f)
        assertEquals("", rig.played.value)
        assertEquals(0, rig.policy.count(Moment.Welcome))
        // perform returns at once while held and marks nothing
        kotlinx.coroutines.runBlocking { stage.perform(OutburstCue(Moment.Welcome, outburstOf("welcome", Heat.Curse, "DE|PIJP|UIT"), 0)) }
        assertEquals("", rig.played.value)
    }

    @Test
    fun releasingTheHoldCreditsTheFilmsLastSecondAsTheScreensFirst() {
        val rig = StageRig()
        val stage = rig.stage
        stage.hold = true
        rig.now += 17_000
        stage.hold = false
        // entered "one second ago": the call may start at once instead of waiting out the first second again
        assertEquals(rig.now - OutburstPolicy.MIN_SCREEN_AGE_MS, stage.enteredAtMillis)
        // when the gate says no after the release (switch off, reduced motion, TalkBack), nothing is pending and YABA stands
        stage.gateOpen = false
        assertFalse(stage.pending)
        assertTrue(stage.frame.columns.contentEquals(YabaWordmark.columns))
        assertEquals(1f, stage.cover, 0f)
    }

    @Test
    fun aStageThatWasNeverHeldKeepsItsOwnFirstSecond() {
        val rig = StageRig()
        val entered = rig.stage.enteredAtMillis
        rig.stage.hold = false
        assertEquals(entered, rig.stage.enteredAtMillis)
    }
}
