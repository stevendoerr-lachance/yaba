package dev.steven.s3control.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class FilmTimelineTest {
    private val cover = Stage(0f, 0f, 1140f, 2616f)
    private val inner = Stage(0f, 0f, 2248f, 2480f)

    private fun face(t: Int, ouch: Boolean = true, lockup: Boolean = false) = TubeFace.at(t.toFloat(), ouch, lockup)

    // ---------------------------------------------------------------------------------------------------------
    // 1. Constants and windows
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun constantsAreTheSpecs() {
        assertEquals(14_000, Film.T_CUT)
        assertEquals(14_520, Film.T_END)
        assertEquals(Film.T_CUT + Film.REVEAL_MS, Film.T_END)
        assertEquals(Film.T_END - Film.T_CUT, Film.REVEAL_MS)
        assertEquals(520, Film.T_LOCKUP_END - Film.T_LOCKUP_WIPE)
        assertEquals(Film.T_TABLEAU - 60, Film.T_DECIDE)
        assertTrue(Film.T_END in 12_000..15_200)
        assertTrue(!Film.FLIP_WHOLE_STAGE)
    }

    @Test
    fun theAnchorsAreTheSpecs() {
        assertEquals(listOf(3_780, 5_100, 7_520), listOf(Film.T_S1, Film.T_S2, Film.T_S3))
        assertEquals(listOf(100, 140, 220), listOf(Film.HIT_STOP_1, Film.HIT_STOP_2, Film.HIT_STOP_3))
        assertEquals(7_740, Film.T_POP)
        assertEquals(8_540, Film.T_HANG)
        // the hang is 300 ms and the tableau 900 ms: the resurrection is 6.3 s of the film, not 6.8
        assertEquals(8_840, Film.T_DROP)
        assertEquals(Film.T_HANG + 300, Film.T_DROP)
        assertEquals(9_740, Film.T_SILENCE)
        assertEquals(listOf(10_340, 10_620, 10_900), listOf(Film.T_LIGHT_1, Film.T_LIGHT_2, Film.T_LIGHT_3))
        assertEquals(10_900, Film.T_HOMEFLIGHT)
        assertEquals(12_200, Film.T_TABLEAU)
        assertEquals(listOf(11_336, 12_340, 12_140), listOf(Film.T_HALO, Film.T_GLYPH, Film.T_DECIDE))
        assertEquals(13_100, Film.T_HANDOFF)
        assertEquals(Film.T_TABLEAU + 900, Film.T_HANDOFF)
        assertEquals(13_100, TubeFace.LOCKUP_SWEEP)
        assertEquals(listOf(12_800, 13_320), listOf(Film.LOCKUP_TEXT_FROM, Film.LOCKUP_TEXT_TO))
        assertEquals(listOf(14_200, 14_720), listOf(Film.T_LOCKUP_WIPE, Film.T_LOCKUP_END))
        assertEquals(listOf(5_300, 5_980, 6_940), listOf(Film.T_DIM_1, Film.T_DIM_2, Film.T_DIM_3))
        assertEquals(12_160, Film.T_DOTS_HOME)
        assertEquals(Film.T_DROP + 570, Film.THUD_TUBE_LANDS)
        // the insert starts on the second ON (the error blinks once in the wide shot) and carries all three toe taps
        assertEquals(5_980, Film.T_INSERT)
        assertEquals(Film.T_BLINK_ON_2, Film.T_INSERT)
        assertEquals(1_300, Film.T_TOTALE - Film.T_INSERT)
        assertEquals(listOf(6_120, 6_660, 7_040), listOf(Film.T_TAP_1, Film.T_TAP_2, Film.T_TAP_3))
        for (tap in listOf(Film.T_TAP_1, Film.T_TAP_2, Film.T_TAP_3)) assertTrue("tap $tap in the insert", tap >= Film.T_INSERT && tap < Film.T_TOTALE)
        assertEquals(40, Film.SQUASH_MS)
    }

    @Test
    fun everyWindowStartsBeforeItEnds() {
        for (w in Film.windows + Film.stillness) assertTrue("${w.name} ${w.from} < ${w.to}", w.from < w.to)
        assertEquals(0, Film.windows.first().from)
        assertEquals(Film.T_END, Film.windows.last().to)
        assertEquals(22, Film.windows.size)
    }

    @Test
    fun theFilmIsAboutAThirdStillness() {
        // stand 460, hit-stops 100 / 140 / 220, the blink beat 1,980, the apex 160, the hang 300, the silence 600, the tableau 900
        val still = Film.stillness.sumOf { it.to - it.from }
        assertEquals(4_860, still)
        assertTrue(still.toFloat() / Film.T_CUT in 0.33f..0.40f)
        assertEquals(listOf(460, 100, 140, 1_980, 160, 220, 300, 600, 900), Film.stillness.map { it.to - it.from })
    }

    @Test
    fun noFrameOfTheOpeningIsBareAsphalt() {
        // the ground is drawn from t = 0 and the bike is rolling in before the hairline has crossed the window
        assertEquals(0, Film.windows.first().from)
        assertTrue(Film.T_ROLL_START < Film.T_GROUND_END)
        assertTrue(Film.stillness.none { it.from == 0 })
    }

    @Test
    fun easingsAreMonotoneWithFixedEnds() {
        var last = -1f
        for (i in 0..100) {
            val v = Ease.fastOutSlowIn(i / 100f)
            assertTrue(v >= last - 1e-5f)
            last = v
        }
        assertEquals(0f, Ease.fastOutSlowIn(0f), 0f)
        assertEquals(1f, Ease.fastOutSlowIn(1f), 0f)
        assertEquals(0.5f, Ease.inOut(0.5f), 1e-5f)
        // the app's FastOutSlowIn: (0.4, 0, 0.2, 1) is 0.2 at about 0.25
        assertTrue(Ease.fastOutSlowIn(0.25f) in 0.15f..0.30f)
    }

    @Test
    fun theBlastIsHardAndOvershootsBeforeItHangs() {
        // 95 % of the way in 100 ms, a little past the place, exactly there by 800 ms
        assertEquals(0f, Ease.pop(0f), 0f)
        assertTrue(Ease.pop(100f) > 0.9f)
        val peak = (0..800 step 5).maxOf { Ease.pop(it.toFloat()) }
        assertTrue("parts overshoot $peak", peak in 1.03f..1.10f)
        assertEquals(1f, Ease.pop(800f), 0f)
        assertEquals(1f, Ease.pop(2_000f), 0f)
        // the dots are lighter: they go further past their place
        val dotPeak = (0..800 step 5).maxOf { Ease.popDot(it.toFloat()) }
        assertTrue("dots overshoot $dotPeak", dotPeak in 1.08f..1.20f)
        assertEquals(1f, Ease.popDot(800f), 0f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 3. The boot: three stomps, each harder and slower
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun threeStompsEachHarderAndSlower() {
        assertTrue(Film.T_S1 < Film.T_S2 && Film.T_S2 < Film.T_S3)
        // falls of 140, 100 and 80 ms
        assertEquals(listOf(140, 100, 80), listOf(Film.T_S1 - Film.T_FALL_1, Film.T_S2 - Film.T_FALL_2, Film.T_S3 - Film.T_FALL_3))
        // at each hit the sole is on the contact, flat, at the toe the table says
        val s1 = Boots.pose(Film.T_S1.toFloat())!!
        val s2 = Boots.pose(Film.T_S2.toFloat())!!
        val s3 = Boots.pose(Film.T_S3.toFloat())!!
        assertEquals(-107.6f, s1.soleY, 0.5f); assertEquals(0f, s1.angle, 0.5f); assertEquals(20f, s1.toeX, 0.5f)
        assertEquals(-96f, s2.soleY, 0.5f); assertEquals(0f, s2.angle, 0.5f); assertEquals(18f, s2.toeX, 0.5f)
        assertEquals(-93f, s3.soleY, 0.5f); assertEquals(0f, s3.angle, 0.5f); assertEquals(22f, s3.toeX, 0.5f)
        // each wind-up goes higher than the one before: -176, -192, -204
        assertEquals(-176f, Boots.pose(Film.T_FALL_1.toFloat())!!.soleY, 0.5f)
        assertEquals(-192f, Boots.pose(Film.T_FALL_2.toFloat())!!.soleY, 0.5f)
        assertEquals(-204f, Boots.pose(Film.T_FALL_3.toFloat())!!.soleY, 0.5f)
        assertEquals(-204f, Boots.pose(Film.T_STUCK.toFloat())!!.soleY, 0.5f)
        // the falls end by pressing the bike down: 9 u into the saddle, 10 u into the capsule, and the slam through to the squashed saddle
        assertEquals(-98.7f, Boots.pose(Film.T_S1 + 60f)!!.soleY, 0.05f)
        assertEquals(-86.4f, Boots.pose(Film.T_S2 + 60f)!!.soleY, 0.05f)
        assertEquals(-43.2f, Boots.pose(Film.T_S3 + 40f)!!.soleY, 0.05f)
        // hit-stops: the boot does not move for the last 40, 80 and 180 ms
        assertEquals(Boots.pose(Film.T_S1 + 60f)!!.soleY, Boots.pose(Film.T_S1 + 99f)!!.soleY, 1e-4f)
        assertEquals(Boots.pose(Film.T_S2 + 60f)!!.soleY, Boots.pose(Film.T_S2 + 139f)!!.soleY, 1e-4f)
        assertEquals(Boots.pose(Film.T_S3 + 40f)!!.soleY, Boots.pose(Film.T_POP - 1f)!!.soleY, 1e-4f)
    }

    @Test
    fun theBootsShinKeepsItsLengthAndTheLegComesFromAbove() {
        assertNull(Boots.pose(2_599f))
        assertNull(Boots.pose(8_121f))
        assertNotNull(Boots.pose(2_600f))
        assertNotNull(Boots.pose(8_120f))
        var t = Boots.T_FIRST.toFloat()
        while (t <= Boots.T_LAST) {
            val b = Boots.pose(t)!!
            val ankleX = b.toeX + Boots.ANKLE_X * b.scale
            val ankleY = b.soleY + Boots.ANKLE_Y * b.scale * b.squashY
            val shin = hypot(b.kneeX - ankleX, b.kneeY - ankleY)
            assertEquals("shin at $t", Boots.SHIN * b.scale, shin, 0.5f)
            // the knee is ahead of the foot, the hip behind the knee
            assertTrue("knee ahead at $t", b.kneeX > ankleX)
            assertEquals(b.kneeX - 80f, b.hipX, 1e-3f)
            t += 40f
        }
        // it enters high above the frame, far above the bike
        assertTrue(Boots.pose(Film.T_BOOT_IN + 1f)!!.soleY < -500f)
        // and leaves through the top, never a side: the toe stays between 18 and 22 the whole time (over the saddle and the capsule)
        t = Boots.T_FIRST.toFloat()
        while (t <= Boots.T_LAST) {
            assertTrue("toe at $t", Boots.pose(t)!!.toeX in 18f..22f)
            t += 20f
        }
        assertTrue(Boots.pose(Boots.T_LAST.toFloat())!!.soleY <= -600f)
    }

    @Test
    fun theBootsPathIsContinuous() {
        // no jump of more than 120 u in one 10 ms step except the falls, which are the point (the third is 2.8 u/ms at its end)
        var last = Boots.pose(Boots.T_FIRST.toFloat())!!
        var t = Boots.T_FIRST + 10f
        while (t <= Boots.T_LAST) {
            val b = Boots.pose(t)!!
            assertTrue("sole jumps at $t", abs(b.soleY - last.soleY) < 50f)
            assertTrue("toe jumps at $t", abs(b.toeX - last.toeX) < 5f)
            assertTrue("angle jumps at $t", abs(b.angle - last.angle) < 12f)
            last = b
            t += 10f
        }
        // the toe taps of the long pause: the first two are peaks of the nod inside 5,900..7,040, the third is its last sample (the push-off)
        val taps = (5_900..7_040 step 20).map { Boots.pose(it.toFloat())!!.angle }
        var peaks = 0
        for (i in 1 until taps.size - 1) if (taps[i] > 18f && taps[i] >= taps[i - 1] && taps[i] > taps[i + 1]) peaks++
        assertEquals(2, peaks)
        assertEquals(Boots.TAP_ANGLE, Boots.pose(Film.T_TAP_3.toFloat())!!.angle, 1e-3f)
    }

    @Test
    fun eachToeTapTouchesTheCapsulesRimAndTheHoverDoesNot() {
        // the capsule's top after the second stomp's sag (6 u, nose down 4 degrees) is -90.5 where the toe lands; the toe tip (after the
        // nod) is on it at each tap
        for (at in listOf(Film.T_TAP_1, Film.T_TAP_2, Film.T_TAP_3)) {
            val b = Boots.pose(at.toFloat())!!
            val tip = b.toeTip()
            assertEquals("tip at tap $at", Boots.RIM_TAP, tip.second, 1.2f)
            // the sole has dropped 14.9 u from the hover and the toe is nodded to 20 degrees
            assertEquals(Boots.HOVER_SOLE + Boots.TAP_DROP, b.soleY, 1e-3f)
            assertEquals(Boots.TAP_ANGLE, b.angle, 1e-3f)
            // and the tip lies over the capsule (x from -38 to 46), not over the road
            assertTrue("tip x ${tip.first}", tip.first > -38f && tip.first < 46f)
        }
        // between the taps the toe tip hovers at least 8 u over the rim
        for (at in listOf(5_900, 6_300, 6_500, 6_900)) {
            val b = Boots.pose(at.toFloat())!!
            assertTrue("hover tip at $at ${b.toeTip().second}", b.toeTip().second < Boots.RIM_TAP - 8f)
        }
        // each tap falls for 80 ms and comes off in 120 ms, the foot never goes below the rim in between
        var t = 5_900f
        while (t < Film.T_TAP_3) {
            val b = Boots.pose(t)!!
            assertTrue("through the rim at $t: ${b.toeTip().second}", b.toeTip().second <= Boots.RIM_TAP + 1.2f)
            t += 10f
        }
        // the taps come quicker: 540 then 380 ms apart
        assertEquals(540, Film.T_TAP_2 - Film.T_TAP_1)
        assertEquals(380, Film.T_TAP_3 - Film.T_TAP_2)
    }

    @Test
    fun theBootComesAtTheCameraEachStompAndGivesOnTheThird() {
        // 1.0 at the top of a wind-up, 1.18 at contact, per stomp
        for (apex in listOf(Film.T_FALL_1, Film.T_FALL_2, Film.T_FALL_3)) assertEquals("apex $apex", 1.0f, Boots.pose(apex.toFloat())!!.scale, 1e-3f)
        for (hit in listOf(Film.T_S1, Film.T_S2, Film.T_S3)) assertEquals("hit $hit", 1.18f, Boots.pose(hit.toFloat())!!.scale, 1e-3f)
        // growing all the way down (never shrinking during a fall)
        for (fall in listOf(Film.T_FALL_1 to Film.T_S1, Film.T_FALL_2 to Film.T_S2, Film.T_FALL_3 to Film.T_S3)) {
            var last = 0f
            for (t in fall.first..fall.second step 10) {
                val sc = Boots.pose(t.toFloat())!!.scale
                assertTrue("scale falls at $t", sc >= last - 1e-5f)
                last = sc
            }
        }
        // the sole stays on the line it is pressed from, whatever the scale (grown about the toe)
        assertEquals(-107.6f, Boots.pose(Film.T_S1.toFloat())!!.soleY, 0.5f)
        // every contact squashes the boot: 0.94 for 60 ms at the first two (from 20 ms after the hit), 0.92 for 100 ms from 40 ms after the
        // third; and every fall stretches it to 1.05 over its last 50 ms
        for (hit in listOf(Film.T_S1, Film.T_S2)) {
            assertEquals(1.05f, Boots.pose(hit.toFloat())!!.squashY, 1e-4f)
            assertEquals(0.94f, Boots.pose(hit + 20f)!!.squashY, 1e-4f)
            assertEquals(0.94f, Boots.pose(hit + 80f)!!.squashY, 1e-4f)
            assertEquals(1f, Boots.pose(hit + 140f)!!.squashY, 1e-4f)
            assertEquals(1f, Boots.pose(hit - 50f)!!.squashY, 1e-4f)
        }
        assertEquals(1.05f, Boots.pose(Film.T_S3.toFloat())!!.squashY, 1e-4f)
        assertEquals(0.92f, Boots.pose(Film.T_S3 + 40f)!!.squashY, 1e-4f)
        assertEquals(0.92f, Boots.pose(Film.T_S3 + 140f)!!.squashY, 1e-4f)
        assertEquals(1f, Boots.pose(Film.T_POP.toFloat())!!.squashY, 1e-4f)
        // it is 1 outside the three contacts (hovering, tapping)
        for (t in listOf(2_800f, 4_300f, 6_300f, 7_100f)) assertEquals(1f, Boots.pose(t)!!.squashY, 1e-4f)
        // the hover is nearer than the apex and farther than contact
        assertEquals(Boots.SCALE_HOVER, Boots.pose(6_300f)!!.scale, 1e-3f)
    }

    @Test
    fun theShadowGrowsTowardEachStompAndIsGoneAtThePop() {
        assertNull(Boots.shadow(Film.T_BOOT_IN - 1f))
        assertNull(Boots.shadow(Film.T_POP.toFloat()))
        val apex = Boots.shadow(Film.T_FALL_2.toFloat())!!
        val hit = Boots.shadow(Film.T_S2.toFloat())!!
        assertTrue(apex.third < hit.third)
        assertEquals(1f, hit.third, 1e-3f)
        assertEquals(4f, hit.second, 0f)
        assertEquals(Boots.pose(Film.T_S2.toFloat())!!.toeX - 44f, hit.first, 1e-3f)
        assertTrue(Boots.shadow(Film.T_S3 + 10f)!!.third > 0.99f)
        assertTrue(apex.third in 0.3f..0.6f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 5. The blinking error and the matrix
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun theBlinkFollowsItsSchedule() {
        assertEquals(listOf(5_300, 5_680, 5_980, 6_300, 6_520, 6_780, 6_940, 7_160, 7_280), Film.BLINK.toList())
        // on at the even edges, off at the odd ones
        for ((i, at) in Film.BLINK.withIndex()) {
            val on = i % 2 == 0
            assertEquals("edge $i at $at", on, face(at).shown == FrameId.E42)
            assertEquals("just before edge $i", !on, face(at - 1).shown == FrameId.E42)
            // the OFF frames are the empty capsule, swept in fully
            if (!on) {
                assertEquals(FrameId.Empty, face(at).shown)
                assertEquals(1f, face(at).progress, 0f)
            }
        }
        // every E42 frame is signal red, never led; the capsule is lit by nothing else
        for (ti in 5_300..7_739) if (face(ti).shown == FrameId.E42) assertEquals("tone at $ti", Tone.Signal, face(ti).tone)
        // the periods are 680, 540, 420 and 340 ms: never above 3 Hz
        val onEdges = Film.BLINK.filterIndexed { i, _ -> i % 2 == 0 }
        val periods = onEdges.zipWithNext { a, b -> b - a }
        assertEquals(listOf(680, 540, 420, 340), periods)
        assertTrue(periods.min() >= 334)
        // the stuck ON stays through the third stomp to the pop
        for (ti in listOf(7_280, 7_400, 7_519, 7_520, 7_700, 7_739)) assertEquals(FrameId.E42, face(ti).shown)
        assertEquals(FrameId.Empty, face(Film.T_POP + 10).shown)
        // blink faster and faster, never a flicker: the shortest ON or OFF is 120 ms
        var run = 0
        var prev = face(Film.T_E42).shown == FrameId.E42
        var shortest = Int.MAX_VALUE
        for (ti in Film.T_E42 + 1 until Film.T_STUCK) {
            val on = face(ti).shown == FrameId.E42
            if (on == prev) run++ else { shortest = minOf(shortest, run + 1); run = 0; prev = on }
        }
        assertTrue("shortest state $shortest ms", shortest >= 120)
    }

    @Test
    fun theFirstOnHasTheStrikeLookTheOthersArePlain() {
        val on1 = face(Film.T_E42)
        assertEquals(TubeFace.STRIKE_DOT, on1.dot, 1e-3f)
        assertEquals(1.8f, on1.glow, 1e-3f)
        // the recoil of the field in 35 ms steps: 0, -1, +1, -1, +1, 0
        assertEquals(listOf(0, -1, 1, -1, 1, 0), listOf(5_300, 5_335, 5_370, 5_405, 5_440, 5_475).map { face(it).shift })
        assertEquals(0, face(5_520).shift)
        // dot 1.206 falls to 1.0 over +90 to +250 ms, glow 1.8 to 1.0 over +100 to +380 ms
        assertEquals(1f, face(5_300 + 250).dot, 1e-3f)
        assertEquals(1f, face(5_300 + 380).glow, 1e-3f)
        assertTrue(face(5_300 + 200).dot in 1f..TubeFace.STRIKE_DOT)
        // ON 2 to ON 5: dot 1.0, glow 1.0, no recoil
        for (at in listOf(5_980, 6_520, 6_940, 7_280)) {
            val f = face(at)
            assertEquals("dot at $at", 1f, f.dot, 0f)
            assertEquals("glow at $at", 1f, f.glow, 0f)
            assertEquals("shift at $at", 0, f.shift)
        }
        // the third stomp switches the stuck ON to the strike look and holds it to the pop
        val strike = face(Film.T_S3 + 1)
        assertEquals(FrameId.E42, strike.shown)
        assertEquals(TubeFace.STRIKE_DOT, strike.dot, 1e-3f)
        assertEquals(1.8f, strike.glow, 1e-3f)
        assertEquals(1.8f, face(Film.T_POP - 1).glow, 1e-3f)
    }

    @Test
    fun theErrorIsAStateAndTheCallIsNot() {
        // no call allowed: the first stomp is answered by the closed lock (no word), on the same beat and with the same recoil
        for (ti in listOf(4_020, 4_300, 4_700)) assertEquals("at $ti", FrameId.Locked, face(ti, ouch = false).shown)
        assertEquals(FrameId.Empty, face(Film.T_S1 + 100, ouch = false).shown)
        assertEquals(FrameId.Empty, face(Film.T_AUW - 1, ouch = false).shown)
        assertEquals(listOf(0, -1, 1, -1, 1, 0), listOf(4_020, 4_055, 4_090, 4_125, 4_160, 4_195).map { face(it, ouch = false).shift })
        assertEquals(face(4_020).dot, face(4_020, ouch = false).dot, 0f)
        assertEquals(face(4_300).glow, face(4_300, ouch = false).glow, 0f)
        assertEquals(Tone.Led, face(4_020, ouch = false).tone)
        // it sweeps out like the call, and nothing is left until the error
        assertEquals(FrameId.Empty, face(4_880, ouch = false).shown)
        assertEquals(FrameId.Locked, face(4_880, ouch = false).prev)
        assertEquals(face(4_880).progress, face(4_880, ouch = false).progress, 0f)
        for (ti in listOf(4_960, 5_100, 5_290)) assertEquals("at $ti", FrameId.Empty, face(ti, ouch = false).shown)
        // the call itself is unchanged
        assertEquals(FrameId.Auw, face(4_300).shown)
        assertEquals(FrameId.Auw, face(4_880).prev)
        // the error is the same either way
        for (ti in listOf(5_350, 6_000, 7_300)) assertEquals(FrameId.E42, face(ti, ouch = false).shown)
        assertEquals(FrameId.Empty, face(5_700, ouch = false).shown)
    }

    @Test
    fun theTubeFollowsItsScript() {
        assertEquals(FrameId.Empty, face(100).shown)
        // the readout follows the roll-in's slope: 12 8 5 3 1 0, cut at u = 0.18, 0.357, 0.5, 0.714, 0.889 of 360..2,140
        assertEquals(FrameId.S12, face(500).shown)
        assertEquals(FrameId.S8, face(800).shown)
        assertEquals(FrameId.S5, face(1_100).shown)
        assertEquals(FrameId.S3, face(1_400).shown)
        assertEquals(FrameId.S1, face(1_800).shown)
        assertEquals(FrameId.S0, face(2_100).shown)
        // the open lock sweeps in over the last reading
        assertEquals(FrameId.Unlocked, face(2_660).shown)
        assertEquals(FrameId.Unlocked, face(2_300).shown)
        assertEquals(FrameId.S0, face(2_300).prev)
        // it dims while the boot looms: 1.0 at 2,600, 0.7 at the first stomp
        assertEquals(FrameId.Unlocked, face(Film.T_S1 - 1).shown)
        assertEquals(0.7f, face(Film.T_S1 - 1).lit, 0.01f)
        assertEquals(1f, face(2_600).lit, 1e-3f)
        // the blank beat of 240 ms, then AUW as a hard cut with the strike look and the recoil
        assertEquals(FrameId.Empty, face(Film.T_S1 + 100).shown)
        assertEquals(FrameId.Empty, face(Film.T_AUW - 1).shown)
        val auw = face(4_020)
        assertEquals(FrameId.Auw, auw.shown)
        assertEquals(TubeFace.STRIKE_DOT, auw.dot, 1e-3f)
        assertEquals(1.8f, auw.glow, 1e-3f)
        assertEquals(Tone.Led, auw.tone)
        assertEquals(listOf(0, -1, 1, -1, 1, 0), listOf(4_020, 4_055, 4_090, 4_125, 4_160, 4_195).map { face(it).shift })
        assertEquals(0, face(4_300).shift)
        // the sigh: the glow decays over 4,120 to 4,920, the brightness 1.0 to 0.6 over 4,120 to 4,800
        assertEquals(1f, face(4_120).lit, 1e-3f)
        assertEquals(0.6f, face(4_799).lit, 0.01f)
        assertTrue(face(4_500).glow < 1.8f && face(4_500).glow > 1f)
        // AUW sweeps out in 160 ms (4,800 to 4,960), then nothing until the error
        val out = face(4_880)
        assertEquals(FrameId.Empty, out.shown)
        assertEquals(FrameId.Auw, out.prev)
        assertEquals(0.5f, out.progress, 0.01f)
        assertEquals(FrameId.Empty, face(4_960).shown)
        assertEquals(FrameId.Empty, face(5_100).shown)
        assertEquals(FrameId.Empty, face(5_100).prev)
        // E42 blinks from 5,300: on, off, on
        assertEquals(FrameId.E42, face(5_350).shown)
        assertEquals(FrameId.Empty, face(5_700).shown)
        assertEquals(FrameId.E42, face(7_300).shown)
        // the lit dots of the error leave their sockets at the pop (they fly on their own); the capsule keeps its dark ones
        assertEquals(FrameId.Empty, face(Film.T_POP + 10).shown)
        assertEquals(FrameId.Empty, face(11_000).shown)
        assertEquals(FrameId.Empty, face(Film.T_GLYPH - 1).shown)
        // the open lock lights before the cut (swept in over 520 ms, then glowing harder than the instrument does)
        assertEquals(FrameId.Unlocked, face(Film.T_GLYPH + 600).shown)
        assertEquals(TubeFace.GLYPH_GLOW, face(Film.T_GLYPH + 600).glow, 1e-3f)
        assertEquals(Tone.Led, face(Film.T_GLYPH + 600).tone)
        // the lockup ending boots the word mark over it; the handoff ending leaves the lock until the cut, then the tube is dark
        assertEquals(FrameId.Unlocked, face(13_000, lockup = true).shown)
        assertEquals(FrameId.Yaba, face(13_900, lockup = true).shown)
        assertEquals(FrameId.Unlocked, face(Film.T_HANDOFF - 1, lockup = false).shown)
        assertEquals(FrameId.Empty, face(Film.T_HANDOFF, lockup = false).shown)
    }

    @Test
    fun theSceneIsAPureFunctionOfTime() {
        for (t in listOf(0f, 1_000f, 3_950f, 5_100f, 6_000f, 7_520f, 8_000f, 12_000f, 14_000f)) {
            val a = Camera.at(t, cover)
            val b = Camera.at(t, cover)
            assertEquals(a.k, b.k, 0f); assertEquals(a.cx, b.cx, 0f); assertEquals(a.groundY, b.groundY, 0f)
            val f1 = TubeFace.at(t, ouch = true, lockup = false)
            val f2 = TubeFace.at(t, ouch = true, lockup = false)
            assertEquals(f1.shown, f2.shown); assertEquals(f1.progress, f2.progress, 0f); assertEquals(f1.shift, f2.shift)
            assertEquals(StageLight.dim(t), StageLight.dim(t), 0f)
        }
    }

    @Test
    fun lampsAreOnWhenTheBikeIsAlive() {
        assertEquals(0f, TubeFace.lamp(0f), 0f)
        assertEquals(1f, TubeFace.lamp(2_000f), 0f)
        assertEquals(1f, TubeFace.lamp(Film.T_S3 - 1f), 0f)
        // the lamps die with the squash of the third stomp, in one cut, and stay dead until the tableau
        assertEquals(0f, TubeFace.lamp(Film.T_S3.toFloat()), 0f)
        assertEquals(0f, TubeFace.lamp(Film.T_HANG + 1_000f), 0f)
        assertEquals(1f, TubeFace.lamp(Film.T_TABLEAU + 1_000f), 0f)
        // off again at the handoff, so the bars and the spill are off at the cut
        assertEquals(0f, TubeFace.lamp(Film.T_CUT.toFloat(), handoff = true), 0f)
        // the lockup ending keeps them on
        assertEquals(1f, TubeFace.lamp(Film.T_CUT.toFloat()), 0f)
        assertEquals(1f, TubeFace.lamp(Film.T_S3.toFloat(), strip = true), 0f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 6. Camera
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun cameraKeysAreTheSpecs() {
        val k0 = cover.k0
        // a tall stage is fitted by width, the road at 0.60 of its height, and the roll-in is shot at 1.25 (the bike overflows the edges)
        assertEquals(1140f / 215f, k0, 1e-4f)
        assertEquals(1f, cover.tall, 1e-4f)
        assertEquals(0.60f, cover.gf0, 1e-4f)
        assertEquals(k0 * 1.25f, Camera.at(0f, cover).k, 1e-4f)
        assertEquals(0.60f * 2616f, Camera.at(0f, cover).groundY, 1e-2f)
        // the pull back to the stomp shot: 0.88, centred 4 u to the right, the road where it was
        val stomp = Camera.at(Film.T_HOVER + 100f, cover)
        assertEquals(k0 * Camera.Z_STOMP, stomp.k, 1e-3f)
        assertEquals(Camera.CX_STOMP, stomp.cx, 1e-3f)
        assertEquals(0.60f * 2616f, stomp.groundY, 1e-2f)
        // tableau
        assertEquals(k0, Camera.at(13_000f, cover).k, 1e-3f)
        assertEquals(0.63f * 2616f, Camera.at(13_000f, cover).groundY, 1e-2f)
        // the cut back to the wide shot after the insert: the road has sunk 0.05, the zoom is the stomp's
        val wide = Camera.at(Film.T_TOTALE + 1f, cover)
        assertEquals(k0 * 0.88f, wide.k, 1e-3f)
        assertEquals((0.60f + 0.05f) * 2616f, wide.groundY, 1e-2f)
        // after the pop: the pullback to 0.82 and the exploded view's centre
        val pulled = Camera.at(Film.T_POP + Camera.BLAST_PULLBACK_MS + 1f, cover)
        assertEquals(k0 * Camera.Z_WIDE, pulled.k, 1e-3f)
        assertEquals(Camera.CX_WIDE, pulled.cx, 1e-3f)
    }

    @Test
    fun theInsertFillsNineTenthsOfATallStageAndStaysLegibleOnTheInnerOne() {
        for ((stage, minShare) in listOf(cover to 0.85f, inner to 0.80f)) {
            for (t in listOf(Film.T_INSERT + 1f, 6_800f, Film.T_TOTALE - 1f)) {
                val cam = Camera.at(t, stage)
                assertTrue(Camera.inInsert(t))
                assertEquals(stage.k0 * 2.3f, cam.k, 1e-3f)
                val capsule = Geo.TUBE_W * cam.k
                assertTrue("capsule ${capsule / stage.width} of the stage at $t", capsule / stage.width >= minShare)
                // the focus (-100) is on the stage's middle, and the whole capsule is in the picture
                assertEquals(stage.centerY, cam.sy(-100f), 1e-2f)
                assertTrue(cam.sx(Geo.TUBE_L) > 0f && cam.sx(Geo.TUBE_L + Geo.TUBE_W) < stage.width)
                assertTrue(cam.sy(Geo.TUBE_T) > 0f && cam.sy(Geo.TUBE_T + Geo.TUBE_H) < stage.height)
            }
        }
        assertTrue(!Camera.inInsert(Film.T_INSERT - 1f))
        assertTrue(!Camera.inInsert(Film.T_TOTALE.toFloat()))
        // the capsule on the cover covers 1,024 px of 1,140 (90 %)
        assertEquals(1_024f, Geo.TUBE_W * Camera.at(6_800f, cover).k, 2f)
        // E42 (17 columns) is then about 380 px wide
        assertEquals(380f, 17f * Geo.PITCH * Camera.at(6_800f, cover).k, 12f)
        // the wide shot carries only the first blink (5,300 to 5,980); the insert starts with the second ON and carries all three taps
        assertTrue(Camera.inInsert(Film.T_TAP_1.toFloat()) && Camera.inInsert(Film.T_TAP_2.toFloat()) && Camera.inInsert(Film.T_TAP_3.toFloat()))
        assertTrue(!Camera.inInsert(5_900f) && Camera.inInsert(Film.T_INSERT.toFloat()))
        assertEquals(Camera.at(5_900f, cover).k, cover.k0 * Camera.Z_STOMP, 1e-3f)
    }

    @Test
    fun theSilenceIsShotWithTheInsertsLensOnTheFallenTube() {
        for ((stage, minShare) in listOf(cover to 0.85f, inner to 0.80f)) {
            for (t in listOf(Film.T_SILENCE.toFloat(), 10_300f, Film.T_LIGHT_1 - 1f)) {
                assertTrue(Camera.inSilenceShot(t))
                val cam = Camera.at(t, stage)
                assertEquals(stage.k0 * Camera.INSERT_Z, cam.k, 1e-3f)
                // the fallen capsule (84 u, tilted 3 degrees) is in the picture, filling the width as the insert's does
                assertTrue(Geo.TUBE_W * cam.k / stage.width >= minShare)
                val rest = Part.Tube.restX
                val y = Geo.restY(Part.Tube)
                assertEquals(stage.centerX, cam.sx(rest), 1e-2f)
                assertEquals(stage.centerY, cam.sy(y), 1e-2f)
                assertTrue(cam.sx(rest - Geo.TUBE_W / 2f + 3f) > 0f && cam.sx(rest + Geo.TUBE_W / 2f - 3f) < stage.width)
                // the road is below it, the whole width of the picture
                assertTrue(cam.groundY < stage.height)
            }
        }
        // hard cuts: the totale before (the drop's lens), the wide shot after
        assertTrue(!Camera.inSilenceShot(Film.T_SILENCE - 1f) && !Camera.inSilenceShot(Film.T_LIGHT_1.toFloat()))
        assertEquals(cover.k0 * Camera.Z_WIDE, Camera.at(Film.T_SILENCE - 1f, cover).k, 1e-3f)
        assertEquals(cover.k0 * Camera.Z_WIDE, Camera.at(Film.T_LIGHT_1.toFloat(), cover).k, 1e-3f)
        // no shake in the silence shot
        assertEquals(0f, Camera.shake(10_300f).second, 0f)
    }

    @Test
    fun theWreckHasAMarginOnBothSidesOfTheWideShotOnBothStages() {
        for (stage in listOf(cover, inner)) {
            val cam = Camera.at(Film.T_SILENCE - 100f, stage)
            assertEquals(stage.k0 * 0.76f, cam.k, 1e-3f)
            // the wreck spans -141 to 121: at least 5 u (about 20 px on the cover) clear of the edge on each side
            val left = cam.sx(-141f)
            val right = stage.width - cam.sx(121f)
            assertTrue("left margin $left on ${stage.width}", left >= 5f * cam.k)
            assertTrue("right margin $right on ${stage.width}", right >= 5f * cam.k)
        }
        assertEquals(0.76f, Camera.Z_WIDE, 0f)
    }

    @Test
    fun theWholeBikeIsInFrameAtTheFirstStompOnBothStages() {
        for (stage in listOf(cover, inner)) {
            val cam = Camera.at(Film.T_S1.toFloat(), stage)
            assertTrue("rear ${cam.sx(-93f)}", cam.sx(-93f) > 0f)
            assertTrue("front ${cam.sx(93f)}", cam.sx(93f) < stage.width)
        }
        // and at the stand on the cover (the bike alone is 186 u)
        val stand = Camera.at(2_500f, cover)
        assertTrue(stand.sx(-93f) > 0f && stand.sx(93f) < 1140f)
    }

    @Test
    fun theSkyAboveTheStompShotIsTallEnoughForTheBoot() {
        assertTrue(inner.skyU(Camera.Z_STOMP) >= 204f)
        assertTrue(cover.skyU(Camera.Z_STOMP) >= 204f)
        // the apex sole of the third stomp (-204) is inside the top edge on every stage
        assertTrue(inner.skyU(Camera.Z_STOMP) > 204f)
    }

    @Test
    fun aTallStageIsShotCloserAndAnOrdinaryOneIsNot() {
        // roll-in 1.25, the stand 1.08, then the pull back to the stomp shot (0.88 on every stage)
        assertEquals(cover.k0 * 1.25f, Camera.at(1_000f, cover).k, 1e-3f)
        assertEquals(cover.k0 * 1.08f, Camera.at(2_660f, cover).k, 1e-3f)
        assertEquals(cover.k0 * 0.88f, Camera.at(4_000f, cover).k, 1e-3f)
        assertEquals(inner.k0 * 0.88f, Camera.at(4_000f, inner).k, 1e-3f)
        // the inner screen is shot exactly as before
        assertEquals(inner.k0, Camera.at(1_000f, inner).k, 1e-3f)
        assertEquals(inner.k0, Camera.at(2_660f, inner).k, 1e-3f)
        // the road stands higher on a tall stage; the explosion is stretched upward (and only there)
        assertTrue(cover.gf0 < inner.gf0 - 0.2f)
        assertEquals(1.35f, cover.explodeStretch, 1e-4f)
        assertEquals(1f, inner.explodeStretch, 1e-4f)
    }

    @Test
    fun aSquareStageIsFittedByHeightAndHasMoreSkyBelowTheRoad() {
        // the inner screen: 2248 x 2480
        assertEquals(2480f / 250f, inner.k0, 1e-4f)
        assertTrue(inner.k0 < 2248f / 215f)
        assertTrue(inner.gf0 > 0.80f)
        // the bike, its lift and the halo (200 u) are inside the camera at the tableau
        val cam = Camera.at(13_000f, inner)
        assertTrue(cam.sy(-200f) > 0f)
        assertTrue(cam.sx(-93f) > 0f && cam.sx(93f) < 2248f)
        // and the stage is centred: the roll-in starts outside both of its edges
        assertTrue(FilmWorld.ROLL_FROM + 93f < -inner.width / inner.k0 / 2f)
    }

    @Test
    fun theStompsShakeTheCameraForSixtyMillisecondsAndNothingElseDoes() {
        val amplitudes = listOf(
            Film.T_S1 to Pair(1.6f, 4.0f), Film.T_S2 to Pair(2.8f, 6.8f), Film.T_S3 to Pair(3.5f, 12.5f), Film.T_POP to Pair(6.5f, 12.5f),
        )
        for ((start, amp) in amplitudes) {
            val steps = (0..59).map { Camera.shake(start + it.toFloat()) }
            assertTrue("shake at $start", steps.any { it.first != 0f || it.second != 0f })
            assertEquals(0f, Camera.shake(start + 61f).first, 0f)
            assertEquals(0f, Camera.shake(start - 1f).second, 0f)
            assertEquals("x amplitude at $start", amp.first, steps.maxOf { abs(it.first) }, amp.first * 0.001f + 1e-4f)
            assertTrue("y amplitude at $start", steps.maxOf { abs(it.second) } <= amp.second + 1e-4f)
        }
        assertEquals(0f, Camera.shake(7_000f).first, 0f)
        // no shake in the insert, and the insert camera has none
        assertEquals(0f, Camera.shake(6_500f).second, 0f)
        // each stomp is harder than the one before; the pop is the hardest
        val hardness = amplitudes.map { (start, _) -> (0..59).maxOf { abs(Camera.shake(start + it.toFloat()).second) } }
        assertTrue(hardness[0] < hardness[1] && hardness[1] < hardness[2])
        assertTrue((0..59).maxOf { abs(Camera.shake(Film.T_POP + it.toFloat()).first) } > (0..59).maxOf { abs(Camera.shake(Film.T_S3 + it.toFloat()).first) })
        // the stomps register on the cover screen (the peak, in px): the first moves the picture 13 px (it was 6.5), the second 22
        // (it was 11), the third 41 (it was 16)
        val k = cover.k0 * Camera.Z_STOMP
        assertEquals(13f, hardness[0] * k, 0.6f)
        assertEquals(22f, hardness[1] * k, 0.8f)
        assertEquals(41f, hardness[2] * k, 1.2f)
    }

    @Test
    fun handoffCameraEndsOnTheSlot() {
        val slot = Stage(55f, 330f, 1085f, 330f + 2f * 49f + 7f * ((1030f - 2f * 110f) / 36f))
        val end = Camera.at(Film.T_CUT.toFloat(), cover, slot)
        // the capsule (84 u wide, its middle at (4, -85.1)) is exactly as wide as the slot and centred on it
        assertEquals(slot.width, Geo.TUBE_W * end.k, 0.5f)
        assertEquals(slot.centerX, end.sx(Geo.TUBE_CX), 0.5f)
        assertEquals(slot.centerY, end.sy(Geo.TUBE_CY), 0.5f)
        // without a slot the camera stays at the tableau
        assertEquals(cover.k0, Camera.at(Film.T_CUT.toFloat(), cover, null).k, 1e-3f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 7. Light
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun lightDimsOnTheBlinkAndComesBackInThreeCuts() {
        // 0 before the first blink, then 0.18 (ON 1), 0.34 (ON 2), 0.50 (ON 4)
        assertEquals(
            listOf(0f, 0f, 0.18f, 0.18f, 0.34f, 0.34f, 0.50f, 0.50f),
            listOf(5_000, 5_299, 5_300, 5_979, 5_980, 6_939, 6_940, Film.T_POP - 1).map { StageLight.dim(it.toFloat()) },
        )
        // the bang is a lit stage: the parts are at full paint for 400 ms from the pop, then the dark is back in one cut
        assertEquals(0f, StageLight.dim(Film.T_POP.toFloat()), 0f)
        assertEquals(0f, StageLight.dim(Film.T_POP + StageLight.BLAST_LIT_MS - 1f), 0f)
        // ... but only 0.22 dark for the hang (the E42 in the air has to be read), 0.5 from the drop, 0.36 for the silence
        assertEquals(0.22f, StageLight.dim(Film.T_POP + StageLight.BLAST_LIT_MS.toFloat()), 0f)
        assertEquals(0.22f, StageLight.dim(Film.T_HANG.toFloat()), 0f)
        assertEquals(0.22f, StageLight.dim(Film.T_DROP - 1f), 0f)
        assertEquals(0.50f, StageLight.dim(Film.T_DROP.toFloat()), 0f)
        assertEquals(0.50f, StageLight.dim(Film.T_SILENCE - 1f), 0f)
        assertEquals(0.36f, StageLight.dim(Film.T_SILENCE.toFloat()), 0f)
        assertEquals(0.36f, StageLight.dim(Film.T_LIGHT_1 - 1f), 0f)
        // back in three cuts, as before (the silence is never darker than the first light)
        assertEquals(listOf(0.34f, 0.18f, 0f), listOf(Film.T_LIGHT_1, Film.T_LIGHT_2, Film.T_LIGHT_3).map { StageLight.dim(it.toFloat()) })
        var last = 1f
        for (t in Film.T_DROP..Film.T_END step 10) {
            val d = StageLight.dim(t.toFloat())
            assertTrue("the dark only lifts from the drop on, at $t: $d after $last", d <= last + 1e-6f)
            last = d
        }
        assertEquals(listOf(0, 1, 2, 3), listOf(Film.T_SILENCE, Film.T_LIGHT_1, Film.T_LIGHT_2, Film.T_LIGHT_3).map { StageLight.steps(it.toFloat()) })
        assertTrue((0..Film.T_END step 10).all { StageLight.steps(it.toFloat()) <= 3 })
    }

    @Test
    fun theBikeIsNeverDarkerThanThirtyPercentAndTheBackdropTakesTheRest() {
        assertEquals(0.30f, StageLight.BIKE_DIM_MAX, 0f)
        for (t in 0..Film.T_END step 10) {
            val dim = StageLight.dim(t.toFloat())
            val bike = StageLight.bikeDim(dim)
            val back = StageLight.backdropDim(dim)
            assertTrue("bike dim $bike at $t", bike <= 0.30f + 1e-6f && bike <= dim + 1e-6f)
            // the backdrop's dim and the bike's over it add up to the dim of the stage: 1 - (1 - back)(1 - bike) = dim
            assertEquals("stage dim at $t", dim, 1f - (1f - back) * (1f - bike), 1e-5f)
            assertTrue(back >= 0f && back < 1f)
        }
        // the stomps' dark: the bike is at 0.18, then 0.30 (it was 0.34 and 0.50), the road and the sky at 0.34 and 0.50
        assertEquals(0.18f, StageLight.bikeDim(StageLight.dim(Film.T_E42.toFloat())), 1e-6f)
        assertEquals(0.30f, StageLight.bikeDim(StageLight.dim(Film.T_BLINK_ON_2.toFloat())), 1e-6f)
        assertEquals(0.30f, StageLight.bikeDim(StageLight.dim(Film.T_POP - 1f)), 1e-6f)
        assertEquals(0f, StageLight.backdropDim(0.18f), 1e-6f)
        assertEquals(0.50f, 1f - (1f - StageLight.backdropDim(0.50f)) * 0.70f, 1e-5f)
    }

    @Test
    fun exactlyTwoImpactFramesOfEightyMilliseconds() {
        val flashes = (0..Film.T_END).count { StageLight.impact(it.toFloat()) }
        assertEquals(160, flashes)
        assertTrue(StageLight.impact(Film.T_S2.toFloat()) && StageLight.impact(Film.T_S2 + 79f) && !StageLight.impact(Film.T_S2 + 80f))
        // the third one starts when the squash is done (40 ms after the hit): never a flat bike under a floating boot, never the fall to it
        val s3 = Film.T_S3 + Film.SQUASH_MS
        assertTrue(!StageLight.impact(Film.T_S3.toFloat()) && !StageLight.impact(s3 - 1f))
        assertTrue(StageLight.impact(s3.toFloat()) && StageLight.impact(s3 + 79f) && !StageLight.impact(s3 + 80f))
        assertEquals(Film.T_S3 + 120, s3 + 80)
        // the first stomp has none
        assertTrue(!StageLight.impact(Film.T_S1.toFloat()))
    }

    /** The highest point (smallest y) of [p]'s hull in the world at [t]: the discs moved by the part's pose (about its pivot). */
    private fun topOf(world: FilmWorld, p: Part, t: Float): Float {
        val pose = world.pose(p, t)
        val r = Math.toRadians(pose.rot.toDouble())
        val c = kotlin.math.cos(r).toFloat()
        val sn = kotlin.math.sin(r).toFloat()
        return Geo.hull(p).minOf { d ->
            val lx = (d.x - p.px) * pose.sx
            val ly = (d.y - p.py) * pose.sy
            pose.y + lx * sn + ly * c - d.r * pose.sy
        }
    }

    @Test
    fun theSoleNeverHangsOverAFlatBike() {
        // at every instant of the third stomp the highest point of the squashing bike under the boot (the saddle, the capsule) is
        // never more than 3 u below the sole: the sole is on the bike all the way down, not 47 u above it
        val world = FilmWorld.build()
        var t = Film.T_S3.toFloat()
        while (t <= Film.T_S3 + 60f) {
            val sole = Boots.pose(t)!!.soleY
            val top = minOf(topOf(world, Part.Saddle, t), topOf(world, Part.Tube, t))
            assertTrue("the bike's top $top is ${top - sole} u under the sole $sole at $t", top - sole <= 3f)
            t += 5f
        }
        // and the flat bike's top is where the boot comes to rest: within 5 u of the squashed sole
        val flat = minOf(topOf(world, Part.Saddle, Film.T_S3 + 60f), topOf(world, Part.Tube, Film.T_S3 + 60f))
        assertEquals(Boots.SQUASHED_3, flat, 5f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 10. Haptics
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun hapticCuesAreSortedAndCounted() {
        val cues = Film.cues
        assertEquals(cues.sortedBy { it.at }.map { it.at }, cues.map { it.at })
        assertTrue(cues.all { it.at in 0..Film.T_CUT })
        assertEquals(6, cues.count { it.kind == Film.Haptic.Thud })
        assertEquals(6, cues.count { it.kind == Film.Haptic.Ramp })
        assertEquals(1, cues.count { it.kind == Film.Haptic.Confirm })
        assertEquals(17, cues.count { it.kind == Film.Haptic.Tick })
        assertEquals(30, cues.size)
        // stomp 1, stomp 2 and its rebound, stomp 3, the pop, the tube landing: the escalation is felt
        assertEquals(listOf(3_780, 5_100, 5_240, 7_520, 7_740, Film.T_DROP + 570), cues.filter { it.kind == Film.Haptic.Thud }.map { it.at })
        val d = Film.T_DROP
        assertEquals(
            listOf(
                2_660, 4_020, 5_300, 6_120, 6_520, 6_660, 7_040, 7_280, d + 440, d + 620, d + 740, d + 820, Film.T_LIFE_HOME,
                Film.T_LIGHT_1, Film.T_LIGHT_2, Film.T_LIGHT_3, Film.T_TABLEAU - 230,
            ),
            cues.filter { it.kind == Film.Haptic.Tick }.map { it.at },
        )
        assertEquals((0 until 6).map { Film.T_HOMEFLIGHT + 70 + 130 * it }, cues.filter { it.kind == Film.Haptic.Ramp }.map { it.at })
        assertEquals(Film.T_TABLEAU, cues.first { it.kind == Film.Haptic.Confirm }.at)
        // every toe tap on the capsule's rim is felt (a tick on the instant the toe touches it)
        for (tap in listOf(Film.T_TAP_1, Film.T_TAP_2, Film.T_TAP_3)) assertEquals(Film.Haptic.Tick, cues.first { it.at == tap }.kind)
    }

    // ---------------------------------------------------------------------------------------------------------
    // The rest of the instruments
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun haloLiftAndLifeSign() {
        assertEquals(0, Halo.count(11_000f))
        assertEquals(1, Halo.count(Film.T_HALO.toFloat()))
        assertEquals(Film.HALO_DOTS, Halo.count(Film.T_TABLEAU.toFloat()))
        // the ring stands over the middle of the bike (the tube's centre), clear of the handlebar's top (-122.4) and its lift
        assertEquals(Geo.TUBE_CX, Halo.cx, 0f)
        assertEquals(35f, Halo.rx, 0f)
        assertEquals(-150f, Halo.cy, 0f)
        assertTrue(Halo.cy + Halo.ry < -122.4f - 14f)
        // the bike is lifted 36 u at the tableau, from 460 ms before it
        assertEquals(36f, Lift.UP, 0f)
        assertEquals(0f, Lift.at(Film.T_TABLEAU - 460f, false), 0f)
        assertEquals(36f, Lift.at(Film.T_TABLEAU.toFloat(), false), 1e-3f)
        // the life sign is the crossbar of the 4 (cell 17, 4): it lights, hops home and breathes
        assertEquals(17, LifeSign.COL)
        assertEquals(4, LifeSign.ROW)
        assertEquals(0f, LifeSign.glow(Film.T_SILENCE.toFloat()), 0f)
        assertEquals(0.35f, LifeSign.glow(Film.T_LIFE_HOME.toFloat()), 1e-3f)
        assertEquals(1f, LifeSign.glow(Film.T_LIFE_HOME + 800f), 1e-3f)
        assertEquals(0f, LifeSign.glow(Film.T_LIGHT_3.toFloat()), 0f)
        assertEquals(10f, LifeSign.hop((Film.T_LIFE_HOP + Film.T_LIFE_HOME) / 2f), 1e-3f)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 11. The stomps' answers, as pure functions of t
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun theSpringIsZeroHeldAndRebounds() {
        val h = 1_000
        assertEquals(0f, Reaction.spring(h - 1f, h, 100), 0f)
        assertEquals(0f, Reaction.spring(h.toFloat(), h, 100), 0f)
        assertEquals(1f, Reaction.spring(h + 60f, h, 100), 1e-4f)
        assertEquals(1f, Reaction.spring(h + 160f, h, 100), 1e-4f)
        val end = h + 160f
        assertEquals(-0.35f, Reaction.spring(end + 140f, h, 100), 1e-4f)
        assertEquals(0.12f, Reaction.spring(end + 260f, h, 100), 1e-4f)
        assertEquals(-0.04f, Reaction.spring(end + 380f, h, 100), 1e-4f)
        assertEquals(0f, Reaction.spring(end + 500f, h, 100), 1e-4f)
        assertEquals(0f, Reaction.spring(end + 2_000f, h, 100), 0f)
        // the permanent damage arrives in 60 ms and stays
        assertEquals(0f, Reaction.perm(h.toFloat(), h), 0f)
        assertEquals(1f, Reaction.perm(h + 60f, h), 0f)
        assertEquals(1f, Reaction.perm(h + 5_000f, h), 0f)
    }

    @Test
    fun theDamageOfTheStompsIsPerPartAndStays() {
        // before the first stomp nothing is moved
        for (p in Part.entries) {
            val d = Reaction.delta(p, Film.T_S1 - 1f)
            assertEquals(0f, d.rot, 0f); assertEquals(0f, d.dy, 0f); assertEquals(1f, d.sy, 0f)
        }
        // the first stomp is felt in the whole body: the frame drops 5.5 u, the wheels squash by 15 %, the fenders and the lock go with it
        for (p in listOf(Part.Tube, Part.Saddle, Part.Cockpit, Part.RearTri, Part.DownTube, Part.Fork, Part.RearFender, Part.FrontFender, Part.Lock)) {
            val d = Reaction.delta(p, Film.T_S1 + 100f).dy
            // the saddle is pressed 3 u more and sunk 2.5 u (it stays so), the bars hinge about the foot of the stem (a few u more)
            val extra = when (p) { Part.Saddle -> 3f + 2.5f; Part.Cockpit -> 8.5f; else -> 0f }
            assertEquals("${p.name} drop at stomp 1", 5.5f + extra, d, 0.4f)
            if (p != Part.Saddle && p != Part.Cockpit) assertEquals("${p.name} is back after the rebound", 0f, Reaction.delta(p, Film.T_S1 + 1_000f).dy, 1e-3f)
        }
        // the bars are bent back by 32 degrees by the first stomp, and stay so through the second (hinged at the stem's foot)
        assertEquals(-32f, Reaction.delta(Part.Cockpit, Film.T_S1 + 1_000f).rot, 1f)
        assertEquals(-32f, Reaction.delta(Part.Cockpit, Film.T_S2 + 600f).rot, 1f)
        assertEquals(-32f, Reaction.BARS_BENT, 0f)
        // the stem's foot (37.6, -96) stays on the fork whatever the bend: a rotation about it moves it by nothing
        for (t in listOf(Film.T_S1 + 60f, Film.T_S1 + 1_000f, Film.T_S2 + 100f)) {
            val d = Reaction.delta(Part.Cockpit, t)
            val px = 37.6f - Part.Cockpit.px
            val py = -96f - Part.Cockpit.py
            val r = Math.toRadians(d.rot.toDouble())
            val x = px * kotlin.math.cos(r).toFloat() - py * kotlin.math.sin(r).toFloat() + d.dx
            val y = px * kotlin.math.sin(r).toFloat() + py * kotlin.math.cos(r).toFloat() + d.dy
            val drop = Reaction.delta(Part.Fork, t).dy - 2.5f * Reaction.spring(t, Film.T_S2, Film.HIT_STOP_2)
            assertEquals("stem foot x at $t", px, x, 1e-3f)
            assertEquals("stem foot y at $t", py + drop, y, 1e-3f)
        }
        // the first stomp crooks the saddle (10 degrees, 2.5 u) where the boot stands; the second leaves it at 30 degrees and 8 u down,
        // and the capsule sagging 6 u, nose down 4 degrees
        assertEquals(10f, Reaction.delta(Part.Saddle, Film.T_S1 + 1_000f).rot, 0.5f)
        assertEquals(2.5f, Reaction.delta(Part.Saddle, Film.T_S1 + 1_000f).dy, 0.3f)
        assertEquals(30f, Reaction.delta(Part.Saddle, Film.T_S2 + 600f).rot, 1f)
        assertEquals(8f, Reaction.delta(Part.Saddle, Film.T_S2 + 600f).dy, 0.5f)
        assertEquals(6f, Reaction.delta(Part.Tube, Film.T_S2 + 600f).dy, 0.5f)
        assertEquals(4f, Reaction.delta(Part.Tube, Film.T_S2 + 600f).rot, 0.2f)
        assertEquals(Reaction.TUBE_SAG, 6f, 0f); assertEquals(Reaction.TUBE_NOSE, 4f, 0f)
        assertEquals(Reaction.SADDLE_CROOKED, 30f, 0f); assertEquals(Reaction.SADDLE_SUNK, 8f, 0f)
        // the capsule gives 12 u under the sole at the second stomp (5.5 u under the first)
        assertEquals(5.5f, Reaction.delta(Part.Tube, Film.T_S1 + 100f).dy, 0.05f)
        assertEquals(6f + 6f, Reaction.delta(Part.Tube, Film.T_S2 + 100f).dy, 0.1f)
        // the wheels squash 15 % and 10 % and stay on the road; the lower frame gives 2.5 u at the second stomp, 5.5 u at the first
        assertEquals(0.85f, Reaction.delta(Part.RearWheel, Film.T_S1 + 100f).sy, 1e-3f)
        assertEquals(0.85f, Reaction.delta(Part.FrontWheel, Film.T_S1 + 100f).sy, 1e-3f)
        assertEquals(1.05f, Reaction.delta(Part.RearWheel, Film.T_S1 + 100f).sx, 1e-3f)
        assertEquals(0.90f, Reaction.delta(Part.FrontWheel, Film.T_S2 + 100f).sy, 1e-3f)
        assertEquals(2.5f, Reaction.delta(Part.Fork, Film.T_S2 + 100f).dy, 1e-3f)
        assertEquals(5.5f, Reaction.delta(Part.Fork, Film.T_S1 + 100f).dy, 1e-3f)
        // the second stomp leaves the front wheel out of true for good: 6 % wider and turned 12 degrees; the rear wheel stays round
        val front = Reaction.delta(Part.FrontWheel, Film.T_S2 + 1_000f)
        assertEquals(1.06f, front.sx, 1e-3f)
        assertEquals(12f, front.rot, 1e-3f)
        assertEquals(1f, front.sy, 1e-3f)
        assertEquals(1f, Reaction.delta(Part.RearWheel, Film.T_S2 + 1_000f).sx, 1e-3f)
        assertEquals(1f, Reaction.delta(Part.FrontWheel, Film.T_S2 - 100f).sx, 0.1f)
        // every reaction is bounded: no part is ever moved more than 20 u or turned more than 45 degrees
        var t = 3_000f
        while (t <= Film.T_POP) {
            for (p in Part.entries) {
                val d = Reaction.delta(p, t)
                assertTrue("${p.name} dy at $t", abs(d.dy) < 20f && abs(d.dx) < 20f)
                assertTrue("${p.name} rot at $t", abs(d.rot) < 45f)
                assertTrue(d.sx.isFinite() && d.sy.isFinite() && d.sy > 0.5f)
            }
            t += 10f
        }
    }

    @Test
    fun theFirstStompHopsTheWholeBikeOffTheRoad() {
        val world = FilmWorld.build()
        // on the rebound both wheels are off the road for at least 100 ms, by up to 3 u; before and after they are on it
        for (p in listOf(Part.RearWheel, Part.FrontWheel)) {
            var airborne = 0
            var peak = 0f
            var t = Film.T_S1.toFloat()
            while (t < Film.T_S2) {
                val w = world.pose(p, t)
                val bottom = w.y + Geo.WHEEL_R * w.sy
                if (bottom < -0.5f) airborne += 5
                peak = maxOf(peak, -bottom)
                assertTrue("${p.name} below the road at $t: $bottom", bottom <= 0.05f)
                t += 5f
            }
            assertTrue("${p.name} is airborne $airborne ms", airborne >= 100)
            assertTrue("${p.name} peaks at $peak u", peak in 2.5f..6f)
        }
        // the frame goes with the wheels: at the hit it drops 5.5 u, on the rebound it is thrown up 4.9 u
        assertEquals(5.5f, Reaction.delta(Part.Fork, Film.T_S1 + 100f).dy, 1e-3f)
        val up = (0..1_000 step 5).minOf { Reaction.delta(Part.Fork, Film.T_S1 + it.toFloat()).dy }
        assertEquals(-4.9f, up, 0.2f)
        // the stomp registers: the squash and the drop are far larger than the 3 u / 10 % that went unseen before
        assertTrue(Reaction.BODY_DROP_1 >= 3f && Reaction.BODY_DROP_1 <= 6f)
        assertTrue(Reaction.WHEEL_SQUASH_1 >= 0.10f && Reaction.WHEEL_SQUASH_1 <= 0.18f)
    }

    @Test
    fun theFirstStompLandsOnTheSaddleAWideFlatTarget() {
        val hit = Boots.pose(Film.T_S1.toFloat())!!
        // the sole is as long as the boot (74 u times its size at contact), and the saddle (x -40 to -15) is under it
        val heel = hit.toeX - 74f * hit.scale
        assertTrue("heel $heel", heel < -40f)
        assertTrue("toe ${hit.toeX}", hit.toeX > -15f)
        // at the hit the sole is on the top of the standing saddle (-107.6), not above a grip tip
        assertEquals(-107.6f, hit.soleY, 0.3f)
        assertEquals(-107.6f, Geo.hull(Part.Saddle).minOf { it.y - it.r }, 4.2f)
        // and it presses it down with the bike's own give: the saddle's highest point is at the sole at the end of the press
        val world = FilmWorld.build()
        val pressed = Boots.pose(Film.T_S1 + 60f)!!.soleY
        assertEquals(pressed, topOf(world, Part.Saddle, Film.T_S1 + 60f), 3f)
        // the capsule is below the sole all through the first stomp: nothing else is hit
        for (dt in 0..160 step 10) assertTrue(topOf(world, Part.Tube, Film.T_S1 + dt.toFloat()) > Boots.pose(Film.T_S1 + dt.toFloat())!!.soleY - 0.5f)
    }

    @Test
    fun theKickstandIsKnockedFoldedByTheSecondStompAndStandsAgainOnTheReassembly() {
        assertEquals(0f, Reaction.standFold(Film.T_S2.toFloat()), 0f)
        assertEquals(0f, Reaction.standFold(Film.T_S1 + 200f), 0f)
        assertEquals(Reaction.STAND_FOLD, Reaction.standFold(Film.T_S2 + 80f), 1e-3f)
        assertEquals(Reaction.STAND_FOLD, Reaction.standFold(Film.T_POP - 1f), 1e-3f)
        // it stays folded through the wreck and is put right while the rear triangle comes home
        assertEquals(Reaction.STAND_FOLD, Reaction.standFold(Film.T_HOMEFLIGHT + 300f), 1e-3f)
        assertEquals(0f, Reaction.standFold(Film.T_HOMEFLIGHT + 360f + FilmWorld.RETURN_MS), 1e-3f)
        assertEquals(0f, Reaction.standFold(Film.T_TABLEAU.toFloat()), 0f)
        var last = Reaction.STAND_FOLD
        var t = Film.T_HOMEFLIGHT + 360f
        while (t <= Film.T_HOMEFLIGHT + 360f + FilmWorld.RETURN_MS) {
            val f = Reaction.standFold(t)
            assertTrue("fold rises at $t", f <= last + 1e-4f)
            last = f
            t += 20f
        }
    }

    @Test
    fun theKneeComesIntoThePictureWhileTheBootFalls() {
        // the shin leans 24 degrees at the top of each wind-up; at contact 2 the knee is inside the cover's top edge
        for (apex in listOf(3_640f, 5_000f, 7_280f)) assertEquals(24f, Boots.lean(apex), 1e-3f)
        for (t in listOf(2_600f, 3_400f, 4_500f, 5_900f, 7_040f)) assertTrue("lean at $t", Boots.lean(t) in 8f..14f)
        var t = 2_600f
        while (t <= 8_000f) { assertTrue("lean at $t", Boots.lean(t) in 8f..24f); t += 20f }
        val knee = Boots.pose(Film.T_S2.toFloat())!!
        assertTrue("knee ${knee.kneeY} below the top edge ${-cover.skyU(Camera.Z_STOMP)}", knee.kneeY > -cover.skyU(Camera.Z_STOMP))
    }

    // ---------------------------------------------------------------------------------------------------------
    // 10. Nothing falls from the sky any more
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun nothingFallsFromTheSkyInTheCodeOrAnyString() {
        // the words are built from pieces so that this file is not a hit of the grep it describes
        val words = listOf("clo" + "ud", "wol" + "ke", "nua" + "ge", "nuv" + "ola", "obl" + "ak", "обл" + "ак", "wo" + "lk", "4" + "04", "bal" + "ken", "pet" + "ty", "WheelR" + "oll", "Lur" + "ch")
        val regex = Regex(words.joinToString("|"), RegexOption.IGNORE_CASE)
        val root = java.io.File(System.getProperty("user.dir") ?: ".")
        val sources = java.io.File(root, "src/main/java/dev/steven/s3control/ui/intro")
        val res = java.io.File(root, "src/main/res")
        val files = (sources.listFiles()?.toList() ?: emptyList()) +
            (res.listFiles { f -> f.name.startsWith("values") }?.mapNotNull { java.io.File(it, "strings_intro.xml").takeIf { f -> f.exists() } } ?: emptyList())
        assertTrue("no sources found from $root", files.size >= 14)
        for (f in files) {
            val hit = f.readLines().withIndex().firstOrNull { regex.containsMatchIn(it.value) }
            assertNull("${f.name}:${hit?.index?.plus(1)} ${hit?.value}", hit)
        }
    }
}
