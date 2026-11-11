package dev.steven.s3control.ui.components

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.steven.s3control.R
import dev.steven.s3control.S3App
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.BikeStatus
import dev.steven.s3control.ui.theme.S3
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Grummeln: the one way YABA ever swears.
 *
 * The bike calls a Dutch word on its top tube, rarely, and every call is staged: breathe in, go dark,
 * run-up, build-up, strike, a held frame, silence, and only then the calm sentence. The call is hard and
 * the return is soft: everything up to the strike is a hard cut or a linear ramp, only the way back runs
 * with the 520 ms FastOutSlowIn of the rest of the app. Four things move: dot size, glow, a recoil of the
 * whole field in whole columns, and the sweep. The colour stays led.
 *
 * Layers, from the bottom:
 *  - [Grummler]         the choreography as a coroutine (timings in ms from t = 0 of the performance)
 *  - [OutburstPolicy]   the gate: switch, caps, spacing, session, drive lock ([DriveLock])
 *  - [OutburstStage]    one tube plus its policy checks; [rememberOutburstStage], [OutburstTube],
 *                       [coverUntilSentence], [skipOnTouch] are what a screen uses
 *  - [ErrorOrServerNotice], [OutburstSettings]: the finished pieces for the server moments and the settings
 *
 * All timings are starting values to be calibrated on the Find N6.
 */

// -------------------------------------------------------------------------------------------------
// Vocabulary
// -------------------------------------------------------------------------------------------------

/** How hard a call is: a sigh (level 1), a curse (level 2) or thunder (level 3, rehearsal only). */
enum class Heat { Sigh, Curse, Thunder }

/**
 * One word split into beats (one to three). A sigh has one beat, a curse two or three, the last beat is
 * the strike. [relief] turns a sigh around: the brightness rises instead of falling.
 */
class Outburst(val id: String, val heat: Heat, val beats: List<String>, val relief: Boolean = false) {
    init {
        require(beats.isNotEmpty() && beats.size <= 3) { "An outburst has one to three beats: $id" }
        require(heat != Heat.Sigh || beats.size == 1) { "A sigh is a single beat: $id" }
        require(heat == Heat.Sigh || beats.size >= 2) { "A curse needs a build-up beat and a strike: $id" }
    }

    /** The word as written, hyphenated beats joined and the rest separated by spaces ("DE PIJP UIT"). */
    val word: String
        get() = beats.foldIndexed("") { i, acc, b ->
            when {
                i == 0 -> b
                acc.endsWith("-") -> acc.dropLast(1) + b
                else -> "$acc $b"
            }
        }
}

/** Beats come from the resources separated by "|": `outburst_fault42` = "VER-|DORIE". */
fun outburstOf(id: String, heat: Heat, raw: String, relief: Boolean = false) =
    Outburst(id, heat, raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }, relief)

/**
 * The places where the bike may call out. [cap] is the most counted performances per installation;
 * together they make 11 in all, 5 of them curses. The rehearsal in the settings is not a moment.
 */
enum class Moment(val key: String, val heat: Heat, val cap: Int) {
    Welcome("welcome", Heat.Curse, 1),
    NotFound("notfound", Heat.Sigh, 2),
    Relief("relief", Heat.Sigh, 2),
    Fault42("fault42", Heat.Curse, 2),
    ServerFirst("server_first", Heat.Sigh, 2),
    ServerThird("server_third", Heat.Curse, 1),
    ServerDead("server_dead", Heat.Curse, 1),
    ;

    /** Level 2: the ones that count against the five-curse budget and the one-per-session rule. */
    val curse: Boolean get() = heat == Heat.Curse
}

/** The word for a moment, from `values/strings_outbursts.xml`. [vanMoofApp] swaps BALEN for GEDOE (never both). */
@Composable
fun outburstFor(moment: Moment, vanMoofApp: Boolean = false): Outburst = when (moment) {
    Moment.Welcome -> outburstOf("welcome", Heat.Curse, stringResource(R.string.outburst_welcome))
    Moment.NotFound ->
        if (vanMoofApp) outburstOf("vanmoof_app", Heat.Sigh, stringResource(R.string.outburst_vanmoof_app))
        else outburstOf("notfound", Heat.Sigh, stringResource(R.string.outburst_notfound))
    Moment.Relief -> outburstOf("relief", Heat.Sigh, stringResource(R.string.outburst_relief), relief = true)
    Moment.Fault42 -> outburstOf("fault42", Heat.Curse, stringResource(R.string.outburst_fault42))
    Moment.ServerFirst -> outburstOf("server_first", Heat.Sigh, stringResource(R.string.outburst_server_first))
    Moment.ServerThird -> outburstOf("server_third", Heat.Curse, stringResource(R.string.outburst_server_third))
    Moment.ServerDead -> outburstOf("server_dead", Heat.Curse, stringResource(R.string.outburst_server_dead))
}

/** The YABA word mark, 31 × 7 in the heavy cut (brand spec: masks, bit 0 = top row). The state of the Welcome tube. */
val YabaWordmark: MatrixFrame = MatrixFrame(
    intArrayOf(
        0x03, 0x07, 0x7C, 0x78, 0x7C, 0x07, 0x03, 0x00,
        0x7E, 0x7F, 0x11, 0x11, 0x11, 0x7F, 0x7E, 0x00,
        0x7F, 0x7F, 0x49, 0x49, 0x49, 0x7F, 0x36, 0x00,
        0x7E, 0x7F, 0x11, 0x11, 0x11, 0x7F, 0x7E,
    ),
)

// -------------------------------------------------------------------------------------------------
// Choreography
// -------------------------------------------------------------------------------------------------

/** The four haptic cues a performance uses. [Haptics] provides them through [cues]; tests fake them. */
interface HapticCues {
    fun tick()
    fun ramp()
    fun thud()
    fun confirm()
}

fun Haptics.cues(): HapticCues {
    val h = this
    return object : HapticCues {
        override fun tick() { h.tick() }
        override fun ramp() { h.ramp() }
        override fun thud() { h.thud() }
        override fun confirm() { h.confirm() }
    }
}

/** Timings in ms from the start of a performance; see the brand spec "Die Animation Grummeln". */
private class Script(
    val dark: Int,
    val inhale: Int,
    val inhaleDot: Float,
    val stau: Int,
    val glowPeak: Float,
    val dotPeak: Float,
    val ticks: Int,
    val tickGap: Int,
    val shudder: IntArray,
    val thuds: Int,
    val hold: Int,
    val clear: Int,
    val silence: Int,
)

private val CURSE = Script(320, 160, 0.8f, 400, 1.8f, 1.08f, 6, 60, intArrayOf(0, -1, 1, -1, 1, 0), 1, 900, 160, 300)
private val THUNDER = Script(480, 200, 0.75f, 560, 2.2f, 1.12f, 8, 70, intArrayOf(0, -1, 1, -1, 1, -1, 1, 0), 2, 1100, 200, 400)

/** The strike: dot r 0.41 p, the heavy cut of the brand mark (0.41 / 0.34). */
internal const val STRIKE_DOT = 1.206f

/** The sentence beat: the tube sweeps back to its state and the paragraph is uncovered in the same stroke. */
private const val SENTENCE_MS = 520

/** Two frames are the same when they light the same dots (a new [MatrixFrame] with equal columns is no change). */
private fun <T : MatrixFrame?> frameEquality() = object : SnapshotMutationPolicy<T> {
    override fun equivalent(a: T, b: T): Boolean = a === b || (a != null && b != null && a.columns.contentEquals(b.columns))
}

/**
 * How long a call waits for an open dialog (or the opening film, which counts as one) to close before the gate decides; the film
 * is 17.6 s long, so a screen that stands behind it keeps its call instead of burning it (see [OutburstStage.perform]).
 */
private const val DIALOG_GRACE_MS = 20_000L

/**
 * Plays one outburst on a tube. It owns the frame, the sweep settings, the [look] and the uncover progress
 * of the paragraph; [OutburstStage] puts those on screen.
 *
 * Everything is scheduled against the start of the performance rather than chained, so the strike is at
 * 720 ms (two beats) and does not drift by the frames the animations need. [nowMs] is injectable for tests.
 */
@Stable
class Grummler(
    private val haptics: HapticCues,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** The frame the tube should show while [active]. */
    var frame by mutableStateOf(MatrixFrame.Empty)
        private set

    /** 0 is a hard cut. */
    var sweepMs by mutableIntStateOf(SENTENCE_MS)
        private set

    /** True for the clearing sweep: linear instead of FastOutSlowIn. */
    var linear by mutableStateOf(false)
        private set

    /** Recoil of the field in whole columns. */
    var shift by mutableIntStateOf(0)
        private set

    /** True from the first cue of a performance until its sentence is uncovered. */
    var active by mutableStateOf(false)
        private set

    /** True from the moment the tube sweeps back to its state (the sentence beat). */
    var inSentence by mutableStateOf(false)
        private set

    /**
     * True while the call itself is on the tube: from the cut to dark (the word's sweep for a sigh) until the sentence.
     * While breathing in, the running display still stands, in its own colour.
     */
    var calling by mutableStateOf(false)
        private set

    val dot = Animatable(1f)
    val glow = Animatable(1f)
    val lit = Animatable(1f)

    /** 0 = paragraph covered, 1 = visible. */
    val uncover = Animatable(1f)

    val look = DotLook({ dot.value }, { glow.value }, { lit.value }, { shift })

    private var phases: Job? = null

    private fun cut(f: MatrixFrame) { sweepMs = 0; linear = false; frame = f }
    private fun sweep(f: MatrixFrame, ms: Int, lin: Boolean) { sweepMs = ms; linear = lin; frame = f }
    private fun text(beat: String) = MatrixFrame.text(beat)

    private suspend fun until(t0: Long, ms: Int) {
        val wait = t0 + ms - nowMs()
        if (wait > 0) delay(wait)
    }

    /**
     * Runs the whole performance of [o]. [calm] is read again at the sentence beat, so a state that changed in
     * the meantime wins. [current] is what the tube shows when the performance starts. [onStrike] fires in the
     * strike frame (for a sigh: when its word stands), which is the moment the counter may rise: a performance that
     * is cancelled before it never counts. With [sentence] false the paragraph is left alone (the rehearsal).
     */
    suspend fun play(
        o: Outburst,
        calm: () -> MatrixFrame,
        onStrike: () -> Unit = {},
        current: MatrixFrame = calm(),
        sentence: Boolean = true,
    ) {
        if (active) return
        active = true
        inSentence = false
        calling = false
        sweepMs = SENTENCE_MS; linear = false; shift = 0
        frame = current
        try {
            if (sentence) uncover.snapTo(0f)
            coroutineScope {
                val job = launch {
                    when (o.heat) {
                        Heat.Sigh -> sigh(o, onStrike)
                        Heat.Curse -> loud(o, CURSE, onStrike)
                        Heat.Thunder -> loud(o, THUNDER, onStrike)
                    }
                }
                phases = job
                job.join()
            }
            phases = null
            rest()
            sweep(calm(), SENTENCE_MS, false)
            inSentence = true
            calling = false
            if (sentence) uncover.animateTo(1f, tween(SENTENCE_MS, easing = FastOutSlowInEasing)) else delay(SENTENCE_MS.toLong())
        } finally {
            withContext(NonCancellable) {
                phases = null
                rest()
                uncover.snapTo(1f)
                sweepMs = SENTENCE_MS; linear = false
                inSentence = false
                calling = false
                active = false
            }
        }
    }

    /** A tap: jump to the sentence. The counter only rose if the strike frame had already stood. */
    fun skip() {
        phases?.cancel()
    }

    /** Leaves the performance state at once: everything back to rest and the paragraph visible. */
    suspend fun skipTo(calm: MatrixFrame) {
        phases?.cancel()
        rest()
        sweep(calm, SENTENCE_MS, false)
        uncover.snapTo(1f)
    }

    private suspend fun rest() {
        dot.snapTo(1f); glow.snapTo(1f); lit.snapTo(1f); shift = 0
    }

    /** Level 2 and 3: two or three beats, a strike on the last one. */
    private suspend fun loud(o: Outburst, s: Script, onStrike: () -> Unit) = coroutineScope {
        val t0 = nowMs()
        val n = o.beats.size
        // Einatmen: the running display stands, dots shrink, the glow runs out.
        launch { glow.animateTo(0f, tween(s.inhale, easing = LinearEasing)) }
        launch { dot.animateTo(s.inhaleDot, tween(s.inhale, easing = LinearEasing)) }
        until(t0, s.inhale)
        // Dunkel: a cut to empty, only ghost dots. From here on the tube shows the call, always in led.
        calling = true
        cut(MatrixFrame.Empty); dot.snapTo(1f); glow.snapTo(1f)
        until(t0, s.dark)
        // Anlauf: a three-beat word shows its first beat for 140 ms + 30 ms per letter.
        var tStau = s.dark
        if (n == 3) {
            cut(text(o.beats[0])); haptics.tick()
            tStau += 140 + 30 * o.beats[0].length
            until(t0, tStau)
        }
        // Stau: nothing blinks, glow and dots rise linearly, the haptics ramp up.
        cut(text(o.beats[n - 2])); if (n == 2) haptics.tick()
        launch {
            until(t0, tStau + 40)
            repeat(s.ticks) { i ->
                until(t0, tStau + 40 + i * s.tickGap)
                haptics.ramp()
            }
        }
        launch { dot.animateTo(s.dotPeak, tween(s.stau, easing = LinearEasing)) }
        launch { glow.animateTo(s.glowPeak, tween(s.stau, easing = LinearEasing)) }
        val tStrike = tStau + s.stau
        until(t0, tStrike)
        // Schlag: the last beat in one cut, the heavy dot, the recoil, the thud.
        cut(text(o.beats.last())); dot.snapTo(STRIKE_DOT); glow.snapTo(s.glowPeak)
        onStrike()
        haptics.thud()
        if (s.thuds == 2) launch { until(t0, tStrike + 140); haptics.thud() }
        launch { s.shudder.forEachIndexed { i, st -> until(t0, tStrike + 35 * i); shift = st } }
        launch { until(t0, tStrike + 90); dot.animateTo(1f, tween(160, easing = LinearOutSlowInEasing)) }
        launch { until(t0, tStrike + 100); glow.animateTo(1f, tween(800, easing = LinearEasing)) }
        // The frame stands, then it is cleared, then nothing happens: the silence is the punchline.
        until(t0, tStrike + s.hold)
        sweep(MatrixFrame.Empty, s.clear, true)
        until(t0, tStrike + s.hold + s.clear + s.silence)
    }

    /** Level 1: one beat, no strike, no recoil. A relief runs the other way round. */
    private suspend fun sigh(o: Outburst, onStrike: () -> Unit) = coroutineScope {
        val t0 = nowMs()
        launch { dot.animateTo(0.85f, tween(120, easing = LinearEasing)) }
        until(t0, 120)
        dot.snapTo(1f)
        if (o.relief) lit.snapTo(0.6f)
        calling = true
        sweep(text(o.beats.single()), 520, false)
        until(t0, 640)
        onStrike()
        if (o.relief) {
            launch { lit.animateTo(1f, tween(600, easing = LinearEasing)); haptics.confirm() }
        } else {
            haptics.tick()
            launch { lit.animateTo(0.6f, tween(600, easing = LinearEasing)) }
        }
        until(t0, 1540)
        sweep(MatrixFrame.Empty, 160, true)
        until(t0, 1900)
        lit.snapTo(1f)
    }
}

// -------------------------------------------------------------------------------------------------
// The gate
// -------------------------------------------------------------------------------------------------

/** Small key-value storage behind the policy: [PrefsOutburstStore] on the phone, [MemoryOutburstStore] in tests. */
interface OutburstStore {
    fun getInt(key: String, default: Int = 0): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long = 0L): Long
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class MemoryOutburstStore : OutburstStore {
    private val map = HashMap<String, Any>()
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
}

class PrefsOutburstStore(private val prefs: SharedPreferences) : OutburstStore {
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}

/** What is true on the screen right now. The drive lock comes from [DriveLock], the rest from the screen. */
data class OutburstSnapshot(
    val talkBack: Boolean = false,
    val reducedMotion: Boolean = false,
    val driveLock: Boolean = false,
    val dialogOpen: Boolean = false,
    val screenAgeMillis: Long = Long.MAX_VALUE,
    val visitPlayed: Boolean = false,
)

/** A failed request to VanMoof's server, as far as the app can tell. Wrong credentials (4xx) are none of these. */
sealed interface ServerFailure {
    /** The server answered, with a 5xx. */
    data class Http(val code: Int) : ServerFailure

    /** The server did not answer in time. */
    data object Timeout : ServerFailure

    /** No connection to the server at all (DNS, refused). */
    data object Unreachable : ServerFailure
}

/** [moment] is the call this failure may trigger (still to pass the gate); [days] counts the days without any answer. */
class ServerVerdict(val moment: Moment?, val days: Int)

/**
 * Decides whether a moment may be staged, and counts what was staged. One instance per process: the
 * session rules (one curse per session, the sigh before a relief, the server escalation) live in memory,
 * everything that must outlive the app (the caps, the spacing, the switch) lives in [store].
 */
class OutburstPolicy(
    private val store: OutburstStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Denial {
        SwitchOff, Accessibility, DriveLock, Dialog, TooEarly, VisitUsed,
        MomentCap, TotalCap, CurseCap, Spacing, CurseThisSession, NoSighBefore,
    }

    /**
     * Which start of the app this is (1 = the first one ever). Counted when the policy is created.
     * The Welcome is no longer tied to it: its cap of one is enough, and a person who clears the data
     * (or whose first start never reached the Welcome) may still hear it once.
     */
    val launch: Int = store.getInt(K_LAUNCHES) + 1

    private var curseThisSession = false
    private var lastMoment: Moment? = null
    private var serverFailures = 0

    /** True after BALEN or GEDOE ran in this session and until the next relief. */
    var sighPending = false
        private set

    init {
        store.putInt(K_LAUNCHES, launch)
    }

    /** The switch "Dutch exclamations" in the settings. On by default. */
    var enabled: Boolean
        get() = store.getBoolean(K_ENABLED, true)
        set(value) = store.putBoolean(K_ENABLED, value)

    /** "Have a good ride." is said once per installation, at the very first unlock. */
    var firstRideDone: Boolean
        get() = store.getBoolean(K_FIRST_RIDE, false)
        set(value) = store.putBoolean(K_FIRST_RIDE, value)

    fun count(moment: Moment): Int = store.getInt(K_COUNT + moment.key)
    val totalCount: Int get() = Moment.entries.sumOf { count(it) }
    val curseCount: Int get() = Moment.entries.filter { it.curse }.sumOf { count(it) }

    /** Null = the moment may be staged now. The first reason that applies otherwise. */
    fun verdict(moment: Moment, snap: OutburstSnapshot): Denial? {
        if (!enabled) return Denial.SwitchOff
        if (snap.talkBack || snap.reducedMotion) return Denial.Accessibility
        if (snap.driveLock) return Denial.DriveLock
        if (snap.dialogOpen) return Denial.Dialog
        if (snap.screenAgeMillis < MIN_SCREEN_AGE_MS) return Denial.TooEarly
        if (snap.visitPlayed) return Denial.VisitUsed
        if (count(moment) >= moment.cap) return Denial.MomentCap
        if (totalCount >= MAX_TOTAL) return Denial.TotalCap
        if (moment.curse && curseCount >= MAX_CURSES) return Denial.CurseCap
        if (moment.curse && curseThisSession) return Denial.CurseThisSession
        if (!spacingOk(moment)) return Denial.Spacing
        if (moment == Moment.Relief && !sighPending) return Denial.NoSighBefore
        return null
    }

    fun allow(moment: Moment, snap: OutburstSnapshot): Boolean = verdict(moment, snap) == null

    /** The rehearsal ignores caps, spacing and session, but not the switch, the drive lock or reduced motion. */
    fun probeAllowed(snap: OutburstSnapshot): Boolean =
        enabled && !snap.talkBack && !snap.reducedMotion && !snap.driveLock && !snap.dialogOpen

    /** Called in the strike frame. A performance that never got there does not count. */
    fun commit(moment: Moment) {
        store.putInt(K_COUNT + moment.key, count(moment) + 1)
        store.putLong(K_LAST_AT, clock())
        lastMoment = moment
        if (moment.curse) curseThisSession = true
        if (moment == Moment.NotFound) sighPending = true
        if (moment == Moment.Relief) sighPending = false
    }

    private fun spacingOk(moment: Moment): Boolean {
        val last = store.getLong(K_LAST_AT)
        val now = clock()
        if (last == 0L || now < last || now - last >= SPACING_MS) return true
        // The only exemption: the third server failure answers the first one (1st: NOU JA, 2nd: nothing, 3rd: BEK DICHT).
        return moment == Moment.ServerThird && lastMoment == Moment.ServerFirst
    }

    /** VanMoof's server answered (even with a 4xx): it is alive, so nothing is "without answer" any more. */
    fun serverAnswered() {
        store.putInt(K_UNANSWERED, 0)
        store.putLong(K_UNANSWERED_SINCE, 0L)
    }

    /**
     * Books one failed request that the person caused and says which moment it may trigger.
     * [internet] is whether the phone has working internet at all (null = unknown): without it the server
     * is not to blame, and "no answer for days" only counts when the rest of the internet works.
     */
    fun serverFailed(failure: ServerFailure, internet: Boolean?): ServerVerdict {
        val now = clock()
        if (failure is ServerFailure.Http) {
            serverAnswered()
        } else if (internet == true) {
            val since = store.getLong(K_UNANSWERED_SINCE)
            store.putInt(K_UNANSWERED, store.getInt(K_UNANSWERED) + 1)
            if (since == 0L) store.putLong(K_UNANSWERED_SINCE, now)
        }
        val since = store.getLong(K_UNANSWERED_SINCE)
        val days = if (since == 0L || now < since) 0 else ((now - since) / DAY_MS).toInt()
        val dead = failure !is ServerFailure.Http && internet == true &&
            store.getInt(K_UNANSWERED) >= DEAD_REQUESTS && since != 0L && now - since >= DEAD_AFTER_MS
        val counts = failure is ServerFailure.Http || (failure is ServerFailure.Timeout && internet != false)
        if (counts) serverFailures++
        val moment = when {
            dead -> Moment.ServerDead
            !counts -> null
            serverFailures == 1 -> Moment.ServerFirst
            serverFailures == 3 -> Moment.ServerThird
            else -> null
        }
        return ServerVerdict(moment, days)
    }

    companion object {
        const val MIN_SCREEN_AGE_MS = 1_000L
        const val SPACING_MS = 10 * 60_000L
        const val MAX_TOTAL = 11
        const val MAX_CURSES = 5
        const val DAY_MS = 24 * 3_600_000L
        const val DEAD_REQUESTS = 3
        const val DEAD_AFTER_MS = 7 * DAY_MS

        private const val K_ENABLED = "enabled"
        private const val K_LAUNCHES = "launches"
        private const val K_LAST_AT = "lastAt"
        private const val K_COUNT = "count."
        private const val K_FIRST_RIDE = "firstRideDone"
        private const val K_UNANSWERED = "unanswered"
        private const val K_UNANSWERED_SINCE = "unansweredSince"
    }
}

/** What [DriveLock] needs to know about the bike, reduced to plain values. */
data class DriveInput(
    val ready: Boolean,
    val speedKmh: Int?,
    val riding: Boolean,
    val alarmOrShipping: Boolean,
    val shippingImminent: Boolean,
    val wheelMoving: Boolean,
)

fun BikeState.driveInput() = DriveInput(
    ready = isReady,
    speedKmh = speedKmh,
    // RIDING (0x0C) only means unlocked and ready; what matters for the drive lock is rolling.
    riding = moving,
    alarmOrShipping = status == BikeStatus.ALARM || status == BikeStatus.SHIPPING,
    shippingImminent = shippingImminent,
    wheelMoving = wheelMoving,
)

/**
 * The drive lock: nothing is ever called out while the bike rides. Locked while the status is riding, alarm or
 * shipping, while shipping mode is imminent, for 10 s after the last speed of 1 km/h or more, and for 10 minutes
 * after the link dropped during a ride (that moment is kept in [store], so a restart does not forget it).
 */
class DriveLock(
    private val store: OutburstStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var lastSpeedAt = 0L
    @Volatile private var activeNow = false
    @Volatile private var dropAt = store?.getLong(K_DROP_AT) ?: 0L
    private var wasReady = false
    private var wasMoving = false

    @Synchronized
    fun observe(input: DriveInput) {
        val now = clock()
        if ((input.speedKmh ?: 0) >= 1) lastSpeedAt = now
        activeNow = input.ready && (input.riding || input.alarmOrShipping || input.shippingImminent)
        val moving = input.ready && ((input.speedKmh ?: 0) >= 1 || input.riding || input.wheelMoving || input.shippingImminent)
        if (wasReady && !input.ready && (wasMoving || (lastSpeedAt != 0L && now - lastSpeedAt < SPEED_WINDOW_MS))) {
            dropAt = now
            store?.putLong(K_DROP_AT, now)
        }
        wasReady = input.ready
        wasMoving = moving
    }

    fun locked(): Boolean {
        val now = clock()
        return activeNow ||
            (lastSpeedAt != 0L && now - lastSpeedAt < SPEED_WINDOW_MS) ||
            (dropAt != 0L && now - dropAt in 0 until DROP_WINDOW_MS)
    }

    companion object {
        const val SPEED_WINDOW_MS = 10_000L
        const val DROP_WINDOW_MS = 10 * 60_000L
        private const val K_DROP_AT = "dropAt"
    }
}

/**
 * The process-wide pieces: the policy, the drive lock (fed from the bike state for as long as the app lives),
 * the count of open dialogs and the record of server failures.
 */
object Outbursts {
    private var policyRef: OutburstPolicy? = null
    private var lockRef: DriveLock? = null
    private val dialogs = AtomicInteger(0)
    private val recent = ArrayDeque<Pair<WeakReference<Any>, ServerRecord>>()

    /** Dialogs and permission prompts that are open right now. While there is one, nothing is called out. */
    val dialogsOpen: Int get() = dialogs.get()

    fun dialogOpened() { dialogs.incrementAndGet() }
    fun dialogClosed() { dialogs.decrementAndGet() }

    @Synchronized
    fun policy(context: Context): OutburstPolicy {
        ensure(context)
        return policyRef!!
    }

    @Synchronized
    fun driveLock(context: Context): DriveLock {
        ensure(context)
        return lockRef!!
    }

    private fun ensure(context: Context) {
        if (policyRef != null) return
        val app = context.applicationContext
        val store = PrefsOutburstStore(app.getSharedPreferences("outbursts", Context.MODE_PRIVATE))
        val lock = DriveLock(store)
        policyRef = OutburstPolicy(store)
        lockRef = lock
        // The lock must see the bike for as long as the process lives, not only while a screen with a tube is open.
        (app as? S3App)?.graph?.let { graph ->
            graph.scope.launch { graph.bike.state.collect { lock.observe(it.driveInput()) } }
        }
    }

    /**
     * Books a failed server request once per [event] (the state object the error came from; the same instance
     * is never counted twice, however many screens show it) and returns what to show for it.
     */
    @Synchronized
    fun recordServerFailure(context: Context, event: Any, failure: ServerFailure): ServerRecord {
        recent.firstOrNull { it.first.get() === event }?.let { return it.second }
        val verdict = policy(context).serverFailed(failure, internetReachable(context))
        val record = ServerRecord(failure, System.currentTimeMillis(), verdict.moment, verdict.days)
        recent.addFirst(WeakReference(event) to record)
        while (recent.size > 8) recent.removeLast()
        return record
    }

    /** A request that VanMoof's server answered: the "no answer for days" count starts over. */
    @Synchronized
    fun recordServerAnswer(context: Context) {
        policy(context).serverAnswered()
    }
}

/** What the person is shown for one failed server request. */
class ServerRecord(val failure: ServerFailure, val at: Long, val moment: Moment?, val days: Int)

/**
 * Whether the phone has working internet (true, false), or unknown (null): the permission for the check is
 * a normal one and has to be in the manifest. Without it "no answer for 7 days" can never be proven.
 */
internal fun internetReachable(context: Context): Boolean? = runCatching {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) return null
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrNull()

/** TalkBack (or any spoken-feedback service) is on. */
internal fun talkBackOn(context: Context): Boolean = runCatching {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    am.isEnabled && (
        am.isTouchExplorationEnabled ||
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
        )
}.getOrDefault(false)

/**
 * Tells the policy that something covers the screen for as long as [open] is true: nothing is called out behind it.
 * Put it next to every `AlertDialog`. It is also what keeps a call from playing behind the opening film: the film is
 * an overlay on top of the composed app, so `MainActivity` (or the film itself) has to say `OutburstBlockWhile(!introDone)`.
 */
@Composable
fun OutburstBlockWhile(open: Boolean) {
    DisposableEffect(open) {
        if (open) Outbursts.dialogOpened()
        onDispose { if (open) Outbursts.dialogClosed() }
    }
}

// -------------------------------------------------------------------------------------------------
// The stage: one tube, its policy checks and what a screen reads
// -------------------------------------------------------------------------------------------------

/** One call to make when [moment] happens: [token] tells one occurrence from the next (it is part of the key). */
@Immutable
class OutburstCue(val moment: Moment, val outburst: Outburst, val token: Any = 0) {
    val key: String get() = "${moment.key}#$token"
}

/**
 * The state of one tube that may call out. A screen reads [frame], [sweepMillis], [sweepEasing] and [look] (or
 * simply uses [OutburstTube]), covers its explanatory paragraph with [coverUntilSentence], and gives the tube
 * its colour from [showingState] (the call is always led; a fault code turns signal only when the state is back).
 */
@Stable
class OutburstStage internal constructor(
    private val grummler: Grummler,
    private val policy: OutburstPolicy,
    private val context: Context,
    private val played: MutableState<String>,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var enteredAt = elapsed()

    /** When the screen counts as entered, in the clock of [elapsed] (tests read it). */
    internal val enteredAtMillis: Long get() = enteredAt

    private var holdState by mutableStateOf(false)

    /**
     * True while something covers the screen that the call must not start behind (the opening film). While held,
     * nothing is decided and nothing is counted: the tube stays on [before], the paragraph stays covered, [markPlayed]
     * is never reached. On the edge back to false the film's last second counts as the screen's first second, so a
     * call that was waiting starts at once and does not wait for its "never in the first second" rule again.
     */
    internal var hold: Boolean
        get() = holdState
        set(value) {
            if (holdState && !value) enteredAt = elapsed() - OutburstPolicy.MIN_SCREEN_AGE_MS
            holdState = value
        }

    // What [frame] and [pending] read is state, not plain fields: the tube reads them in its own composition, and a screen
    // whose tube keeps the same stage, description and modifier would otherwise never learn that its state changed (two
    // frames that differ only in content, such as the Bluetooth glyph with and without the search word).
    internal var calm: MatrixFrame by mutableStateOf(MatrixFrame.Empty, frameEquality())
    internal var before: MatrixFrame? by mutableStateOf(null, frameEquality())

    /** The last state that was shown while no call was waiting: what the tube keeps showing until the call starts. */
    internal var idle: MatrixFrame? by mutableStateOf(null, frameEquality())
    internal var reduced = false
    internal var dialogOpen = false
    internal var cueKey: String? by mutableStateOf(null)
    internal var cueMoment: Moment? = null
    private var gateOpenState by mutableStateOf(false)

    /** Read in composition and in the draw phase of the tube and the paragraph, so it is state, not a plain field. */
    internal var gateOpen: Boolean
        get() = gateOpenState
        set(value) { gateOpenState = value }
    private var visitPlayed by mutableStateOf(false)
    private var still by mutableStateOf<MatrixFrame?>(null)
    private var rehearsal: Job? = null

    private fun hasPlayed(key: String) = key in played.value.split('|')
    private fun markPlayed(key: String) { if (!hasPlayed(key)) played.value = played.value + "|" + key }

    /** A cue is waiting for its performance (or for the hold to end); its paragraph stays covered. */
    internal val pending: Boolean get() = cueKey?.let { (hold || gateOpen) && !hasPlayed(it) } ?: false

    /** A performance is running (from the first cue until the sentence is uncovered). */
    val performing: Boolean get() = grummler.active

    /**
     * False while the call is on the tube (always led), true when the tube shows its state: before the call, while
     * breathing in (the running display stands in its own colour) and from the sentence on.
     */
    val showingState: Boolean get() = !grummler.calling

    val frame: MatrixFrame
        get() = still ?: when {
            grummler.active -> grummler.frame
            // The running display stands until the call starts; the new state (E42, the Bluetooth glyph) only
            // shows in the sentence beat. Without a call waiting, the tube simply follows its state.
            pending -> before ?: idle ?: calm
            else -> calm
        }

    val sweepMillis: Int get() = if (grummler.active) grummler.sweepMs else 520
    val sweepEasing: Easing get() = if (grummler.active && grummler.linear) LinearEasing else FastOutSlowInEasing
    val look: DotLook get() = grummler.look

    /** How much of the paragraph is visible, 0..1. Read it in the draw phase ([coverUntilSentence]). */
    val cover: Float
        get() = when {
            grummler.active -> grummler.uncover.value
            pending -> 0f
            else -> 1f
        }

    /** A tap jumps to the sentence. */
    fun skip() = grummler.skip()

    private fun snapshot(ageMillis: Long) = OutburstSnapshot(
        talkBack = talkBackOn(context),
        reducedMotion = reduced,
        driveLock = Outbursts.driveLock(context).locked(),
        dialogOpen = dialogOpen || Outbursts.dialogsOpen > 0,
        screenAgeMillis = ageMillis,
        visitPlayed = visitPlayed,
    )

    /** The gate as far as it can be known when the cue is composed (the first second of the screen is waited out). */
    internal fun gate(moment: Moment): Boolean = policy.verdict(moment, snapshot(Long.MAX_VALUE)) == null

    internal suspend fun perform(cue: OutburstCue) {
        val key = cue.key
        if (hasPlayed(key) || hold) return
        val age = elapsed() - enteredAt
        if (age < OutburstPolicy.MIN_SCREEN_AGE_MS) delay(OutburstPolicy.MIN_SCREEN_AGE_MS - age)
        // A dialog or a permission prompt that is open for a moment is no reason to give the call up for good
        // (a denial below is final: the key is marked as played). Wait it out, but not for ever.
        var waited = 0L
        while (Outbursts.dialogsOpen > 0 && waited < DIALOG_GRACE_MS) {
            delay(100)
            waited += 100
        }
        if (hold) return
        if (!policy.allow(cue.moment, snapshot(elapsed() - enteredAt))) {
            markPlayed(key)
            return
        }
        visitPlayed = true
        var struck = false
        try {
            grummler.play(
                cue.outburst,
                calm = { calm },
                current = frame,
                onStrike = { struck = true; policy.commit(cue.moment) },
            )
            markPlayed(key)
        } finally {
            if (struck) markPlayed(key) else visitPlayed = false
        }
    }

    /**
     * The rehearsal: plays [o] without counting and without asking for caps or spacing. Drive lock, reduced
     * motion, TalkBack, an open dialog and the switch still apply; then the word just stands still for a moment.
     * With [sentence] the paragraph under the tube is uncovered afterwards, as in a real moment.
     */
    suspend fun rehearse(o: Outburst, sentence: Boolean = false) {
        still = null
        if (!policy.probeAllowed(snapshot(Long.MAX_VALUE))) {
            still = MatrixFrame.text(o.beats.last())
            delay(2_400)
            still = null
            return
        }
        grummler.play(o, calm = { calm }, current = frame, sentence = sentence)
    }

    /** [rehearse] in [scope], replacing a rehearsal that is still running. */
    fun rehearseIn(scope: CoroutineScope, o: Outburst, sentence: Boolean = false) {
        val previous = rehearsal
        rehearsal = scope.launch {
            previous?.cancelAndJoin()
            rehearse(o, sentence)
        }
    }
}

/**
 * The stage for one tube. [calm] is what the tube shows when nothing is called out (its state). When [cue] is
 * set, the call is staged once per cue key, if the gate allows it; otherwise the state shows at once and the
 * paragraph is simply there. [before] is what the tube shows until the call starts (the Welcome tube is dark).
 * [blocked] is true while a dialog of this screen is open. Everything that does not count as a performance
 * (reduced motion, TalkBack, switch off, drive lock) leaves the screen exactly as it was without this.
 *
 * [hold] keeps the whole stage waiting while the opening film plays over the screen: the tube shows [before], the
 * paragraph stays covered, and nothing is decided or counted until [hold] turns false (then the gate is asked, and the
 * film's last second counts as the screen's first). A screen without a film passes nothing and sees no difference.
 */
@Composable
fun rememberOutburstStage(
    calm: MatrixFrame,
    cue: OutburstCue? = null,
    before: MatrixFrame? = null,
    blocked: Boolean = false,
    hold: Boolean = false,
): OutburstStage {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val played = rememberSaveable { mutableStateOf("") }
    val stage = remember(haptics) { OutburstStage(Grummler(haptics.cues()), Outbursts.policy(context), context.applicationContext, played) }
    stage.calm = calm
    stage.before = before
    stage.reduced = reduced
    stage.dialogOpen = blocked
    stage.cueKey = cue?.key
    stage.cueMoment = cue?.moment
    stage.hold = hold
    // Asked once per cue and hold, synchronously: a screen without a hold sees its answer in the first frame.
    stage.gateOpen = remember(cue?.key, hold) { cue != null && !hold && stage.gate(cue.moment) }
    if (!stage.pending && !stage.performing) stage.idle = calm

    val owner = LocalLifecycleOwner.current
    val latest = rememberUpdatedState(cue)
    LaunchedEffect(cue?.key, hold) {
        if (hold) return@LaunchedEffect
        val c = latest.value ?: return@LaunchedEffect
        // A call that is cut off by the background or a dark display does not count; it may run again later.
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { stage.perform(c) }
    }
    return stage
}

/** The top tube of a stage: [BikeTube] with the sweep and look of the performance. A tap on it skips to the sentence. */
@Composable
fun OutburstTube(
    stage: OutburstStage,
    lightsOn: Boolean,
    description: String?,
    modifier: Modifier = Modifier,
    columns: Int = 36,
) {
    BikeTube(
        frame = stage.frame,
        lightsOn = lightsOn,
        modifier = modifier.skipOnTouch(stage),
        columns = columns,
        description = description,
        sweepMillis = stage.sweepMillis,
        sweepEasing = stage.sweepEasing,
        look = stage.look,
    )
}

/**
 * Hides what is drawn from the right until the sentence beat uncovers it from the left (no fade). Semantics are
 * untouched, so TalkBack reads the text from the first frame. With [forMoment] only the paragraph that belongs to
 * the stage's current cue waits; any other paragraph on the same screen is left alone.
 */
fun Modifier.coverUntilSentence(stage: OutburstStage, forMoment: Moment? = null): Modifier = drawWithContent {
    val p = if (forMoment != null && stage.cueMoment != forMoment) 1f else stage.cover
    if (p >= 1f) {
        drawContent()
    } else {
        clipRect(right = size.width * p.coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
    }
}

/** A touch anywhere on this element skips a running performance to the sentence. It consumes nothing. */
fun Modifier.skipOnTouch(stage: OutburstStage): Modifier = pointerInput(stage) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (stage.performing) stage.skip()
    }
}

// -------------------------------------------------------------------------------------------------
// Server failures: the tube, the sentence and the retry
// -------------------------------------------------------------------------------------------------

/**
 * Tells a server failure from the message the app showed for it. Only failures that are VanMoof's server's own
 * are returned: a 5xx, a timeout, no connection. Wrong credentials, rate limits and every other 4xx give null.
 * (The view model hands out the message only; if it ever hands out the status, use that instead.)
 */
fun classifyServerError(res: Resources, message: String?): ServerFailure? {
    if (message.isNullOrBlank()) return null
    if (message == res.getString(R.string.core_api_timeout) || message == res.getString(R.string.core_api_connection_dropped)) {
        return ServerFailure.Timeout
    }
    if (message == res.getString(R.string.core_api_unreachable)) return ServerFailure.Unreachable
    // "VanMoof’s server answered with error 503." with the number cut out of the template.
    val marker = 700_123
    val template = res.getString(R.string.core_api_server_error, marker)
    val cut = template.indexOf(marker.toString())
    if (cut >= 0) {
        val prefix = template.substring(0, cut)
        val suffix = template.substring(cut + marker.toString().length)
        if (message.startsWith(prefix) && message.endsWith(suffix) && message.length > prefix.length + suffix.length) {
            val code = message.substring(prefix.length, message.length - suffix.length).toIntOrNull()
            if (code != null && code in 500..599) return ServerFailure.Http(code)
        }
    }
    return null
}

/**
 * An error line that knows about VanMoof's server. For a server failure it shows the tube (the call, then the
 * code), the title, the paragraph and a retry button; for everything else it is the plain error line in [color].
 * [event] is the state object the error came from, so that one failure is counted once however often it is shown.
 */
@Composable
fun ErrorOrServerNotice(
    error: String?,
    event: Any?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = S3.colors.signal,
) {
    if (error == null) return
    val context = LocalContext.current
    val failure = remember(error) { classifyServerError(context.resources, error) }
    if (failure == null || event == null) {
        Hint(error, modifier, color)
        return
    }
    val record = remember(event) { Outbursts.recordServerFailure(context, event, failure) }
    // "Cannot be reached, check your internet connection" is the right message for a phone without a connection;
    // only after seven days of silence is it the server that gets the notice (and DE PIJP UIT).
    if (failure is ServerFailure.Unreachable && record.moment != Moment.ServerDead) {
        Hint(error, modifier, color)
        return
    }
    ServerNotice(record, onRetry, modifier)
}

@Composable
private fun ServerNotice(record: ServerRecord, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val time = remember(record.at) { DateFormat.getTimeFormat(context).format(Date(record.at)) }
    val dead = record.moment == Moment.ServerDead
    val failure = record.failure
    val title = stringResource(
        when {
            dead -> R.string.core_server_dead_title
            failure is ServerFailure.Http -> R.string.core_server_error_title
            else -> R.string.core_server_timeout_title
        },
    )
    val text = when {
        dead -> stringResource(R.string.core_server_dead_text, record.days)
        failure is ServerFailure.Http -> stringResource(R.string.core_server_error_text, time, failure.code)
        else -> stringResource(R.string.core_server_timeout_text, time)
    }
    val daysFrame = stringResource(R.string.curse_matrix_days, record.days)
    val calm = remember(dead, failure, daysFrame) {
        MatrixFrame.text(
            when {
                dead -> daysFrame
                failure is ServerFailure.Http -> failure.code.toString()
                else -> "10 S"
            },
        )
    }
    val cue = record.moment?.let { OutburstCue(it, outburstFor(it), record.at) }
    val stage = rememberOutburstStage(calm = calm, cue = cue)
    Column(
        modifier
            .fillMaxWidth()
            .skipOnTouch(stage),
        verticalArrangement = Arrangement.spacedBy(S3.m),
    ) {
        OutburstTube(stage, lightsOn = false, description = title)
        ShellNotice(
            title = title,
            text = text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            textModifier = Modifier.coverUntilSentence(stage),
        )
        QuietButton(stringResource(R.string.core_server_retry), onRetry, Modifier.fillMaxWidth())
    }
}

// -------------------------------------------------------------------------------------------------
// Settings: the switch, the word list, the rehearsal
// -------------------------------------------------------------------------------------------------

internal class LexiconWord(val label: String, val outburst: Outburst, @StringRes val meaning: Int)

/** The nine words and nothing else. Dutch stays Dutch in every UI language. */
@Composable
internal fun rememberLexicon(): List<LexiconWord> {
    val balen = outburstFor(Moment.NotFound, vanMoofApp = false)
    val gedoe = outburstFor(Moment.NotFound, vanMoofApp = true)
    val nouJa = outburstFor(Moment.ServerFirst)
    val heHe = outburstFor(Moment.Relief)
    val verdorie = outburstFor(Moment.Fault42)
    val pijp = outburstFor(Moment.Welcome)
    val bek = outburstFor(Moment.ServerThird)
    val domme = outburstOf("probe3", Heat.Thunder, stringResource(R.string.outburst_probe3))
    return remember(balen.word, gedoe.word, nouJa.word, heHe.word, verdorie.word, pijp.word, bek.word, domme.word) {
        listOf(
            LexiconWord("Auw", Outburst("auw", Heat.Sigh, listOf("AUW")), R.string.bike_outburst_meaning_auw),
            LexiconWord("Balen", balen, R.string.bike_outburst_meaning_balen),
            LexiconWord("Gedoe", gedoe, R.string.bike_outburst_meaning_gedoe),
            LexiconWord("Nou ja", nouJa, R.string.bike_outburst_meaning_nou_ja),
            LexiconWord("Hè hè", heHe, R.string.bike_outburst_meaning_he_he),
            LexiconWord("Verdorie", verdorie, R.string.bike_outburst_meaning_verdorie),
            LexiconWord("De pijp uit", pijp, R.string.bike_outburst_meaning_de_pijp_uit),
            LexiconWord("Bek dicht", bek, R.string.bike_outburst_meaning_bek_dicht),
            LexiconWord("Verdomme", domme, R.string.bike_outburst_meaning_verdomme),
        )
    }
}

/**
 * The switch "Dutch exclamations" and, folded away, the list "What the bike calls out": each word with what it
 * means, and a press and hold on a word plays it on the display (the only place where VERDOMME ever runs). The list
 * folds as a row under the switch, a rule between them, like the rows of the settings above it.
 */
@Composable
fun OutburstSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val policy = remember { Outbursts.policy(context) }
    var on by remember { mutableStateOf(policy.enabled) }
    Column(modifier.fillMaxWidth()) {
        SwitchRow(
            label = stringResource(R.string.bike_outbursts_title),
            caption = stringResource(R.string.bike_outbursts_caption),
            checked = on,
            onCheckedChange = { value ->
                on = value
                policy.enabled = value
            },
        )
        Hairline()
        DisclosureSection(stringResource(R.string.bike_outbursts_list_title), foldRow = true) { OutburstWordList() }
    }
}

@Composable
private fun OutburstWordList() {
    val stage = rememberOutburstStage(calm = YabaWordmark)
    val scope = rememberCoroutineScope()
    val words = rememberLexicon()
    Hint(stringResource(R.string.bike_outbursts_list_hint))
    val display = remember { BringIntoViewRequester() }
    OutburstTube(
        stage,
        lightsOn = false,
        description = stringResource(R.string.bike_outbursts_list_title),
        modifier = Modifier.bringIntoViewRequester(display),
    )
    Spacer(Modifier.height(S3.xs))
    Column(Modifier.fillMaxWidth()) {
        words.forEach { word ->
            WordRow(word) {
                // A word far down the list would play on a display that has scrolled away: bring it back first.
                scope.launch { display.bringIntoView() }
                stage.rehearseIn(scope, word.outburst)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordRow(word: LexiconWord, onProbe: () -> Unit) {
    val action = stringResource(R.string.curse_probe_show)
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onProbe, onLongClickLabel = action, role = Role.Button)
            .heightIn(min = 52.dp)
            .padding(vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(word.label, style = S3.type.bodyStrong, color = S3.colors.paint)
        Hint(stringResource(word.meaning))
    }
    Hairline()
}
