package dev.steven.s3control.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FilmWorldTest {
    private val world = FilmWorld.build()

    @Test
    fun buildIsDeterministicAndSane() {
        val a = FilmWorld.build()
        val b = FilmWorld.build()
        assertEquals(a.fingerprint(), b.fingerprint())
        assertTrue(a.isSane())
        assertEquals(252, a.dotCount)
        assertEquals(11, a.partCount)
    }

    @Test
    fun theErrorHas46LitDots() {
        assertEquals(46, (0 until world.dotCount).count { world.dotLit[it] })
        // the E42 is 17 columns wide, centred in 36: the first lit column is 9, the last 25
        val litCols = (0 until world.dotCount).filter { world.dotLit[it] }.map { world.dotCol[it] }
        assertEquals(9, litCols.min())
        assertEquals(25, litCols.max())
        assertEquals(46, world.flyers.size)
        // the life sign is one of them: the crossbar of the 4 (17, 4); the old cell (17, 3) is dark
        val cells = world.flyers.map { world.dotCol[it] to world.dotRow[it] }.toSet()
        assertTrue((17 to 4) in cells)
        assertTrue((17 to 3) !in cells)
    }

    @Test
    fun theGeometryIsTheSpecs() {
        assertEquals(36.5f, Geo.WHEEL_R, 0f)
        assertEquals(84f, Geo.TUBE_W, 0f)
        assertEquals(21.8f, Geo.TUBE_H, 0f)
        assertEquals(1.8333f, Geo.PITCH, 1e-3f)
        // the capsule: 2 pad_y + 7 pitch is its height, its middle is (4, -85.1) and is the tube's pivot
        assertEquals(Geo.TUBE_H, 2f * Geo.PAD_Y + 7f * Geo.PITCH, 0.02f)
        assertEquals(Geo.TUBE_CX, Geo.TUBE_L + Geo.TUBE_W / 2f, 1e-4f)
        assertEquals(Geo.TUBE_CY, Geo.TUBE_T + Geo.TUBE_H / 2f, 0.01f)
        assertEquals(Geo.TUBE_CX, Part.Tube.px, 0f)
        assertEquals(Geo.TUBE_CY, Part.Tube.py, 0f)
        // the wheels are 28 inch on the photo's scale: axles at +-56, tyres touch the road, the bike is 185 u long
        assertEquals(-Geo.WHEEL_R, Part.RearWheel.py, 0f)
        assertEquals(-56f, Part.RearWheel.px, 0f)
        assertEquals(56f, Part.FrontWheel.px, 0f)
        assertEquals(92.5f, 56f + Geo.WHEEL_R, 0f)
        assertEquals(84f, Part.FrontWheel.restX, 0f)
        // the matrix cell centres are inside the capsule, 36 x 7
        assertTrue(Geo.cellX(0) > Geo.TUBE_L + Geo.PAD_X && Geo.cellX(35) < Geo.TUBE_L + Geo.TUBE_W - Geo.PAD_X)
        assertTrue(Geo.cellY(0) > Geo.TUBE_T && Geo.cellY(6) < Geo.TUBE_T + Geo.TUBE_H)
        // the hulls sit on the road after the rest rotation: every rest pivot is above the road and not deeper than the part is big
        for (p in Part.entries) {
            val rest = world.restPose(p)
            assertTrue("${p.name} ${rest.y}", rest.y < 0f && rest.y > -60f)
        }
        assertEquals(-Geo.WHEEL_R, world.restPose(Part.FrontWheel).y, 1e-3f)
        // the coin-flat rear wheel: nearly all of its height is gone
        assertEquals(Geo.COIN_MIN, world.restPose(Part.RearWheel).sy, 1e-4f)
    }

    @Test
    fun everythingLiesAndHangsWithinTheFrame() {
        // the wide camera sees 283 u around cx (0.76 of a tall stage's 215 u): the wreck (-141 to 121) has 10 u to spare on each side
        val half = 107.5f / Camera.Z_WIDE
        val lo = Camera.CX_WIDE - half
        val hi = Camera.CX_WIDE + half
        assertEquals(-151.4f, lo, 0.2f)
        assertEquals(131.4f, hi, 0.2f)
        assertTrue(-141f - lo > 9f && hi - 121f > 9f)
        for (p in Part.entries) {
            val x = world.restPose(p).x
            assertTrue("${p.name} rests at $x", x in lo..hi)
            val h = world.hangX(p)
            assertTrue("${p.name} hangs at $h", h in lo..hi)
        }
        // the lit dots lie on the road in the silence
        for (i in 0 until world.dotCount) {
            val d = world.dot(i, Film.T_SILENCE - 1f)
            assertTrue("dot $i at ${d.x}", d.x in lo..hi)
        }
    }

    @Test
    fun noNaNAnywhereInTime() {
        var t = 0f
        while (t <= Film.T_END) {
            for (p in Part.entries) {
                val pose = world.pose(p, t)
                assertTrue("${p.name} @${t}", listOf(pose.x, pose.y, pose.rot, pose.sx, pose.sy).all { it.isFinite() })
            }
            val m = world.bike(t)
            assertTrue(listOf(m.x, m.y, m.rot, m.squash).all { it.isFinite() })
            if (t >= Film.T_POP) for (i in 0 until world.dotCount step 7) {
                val d = world.dot(i, t)
                assertTrue(d.x.isFinite() && d.y.isFinite())
            }
            assertTrue(world.spin(t).isFinite())
            t += 20f
        }
    }

    @Test
    fun theBikeRollsInStandsAndIsSquashedByTheThirdStompOnly() {
        assertEquals(FilmWorld.ROLL_FROM, world.bike(0f).x, 0f)
        assertEquals(0f, world.bike(Film.T_ROLL_END + 50f).x, 0f)
        // it never leaves the road: no lift, no turn, whatever the time
        var t = 0f
        while (t < Film.T_POP) {
            val m = world.bike(t)
            assertEquals(0f, m.y, 0f)
            assertEquals(0f, m.rot, 0f)
            t += 25f
        }
        // the squash is quick, not a cut: 40 ms from the third stomp's hit, the boot slams down through the bike in the same time
        assertEquals(1f, world.bike(Film.T_S3 - 1f).squash, 0f)
        assertEquals(1f, world.bike(Film.T_S3.toFloat()).squash, 0f)
        val mid = world.bike(Film.T_S3 + 20f).squash
        assertTrue("squash at +20 ms is $mid", mid < 0.6f && mid > FilmWorld.SQUASH)
        for (ms in 0 until Film.SQUASH_MS) assertTrue(world.bike(Film.T_S3 + ms.toFloat()).squash >= world.bike(Film.T_S3 + ms + 1f).squash)
        assertEquals(FilmWorld.SQUASH, world.bike(Film.T_S3 + Film.SQUASH_MS.toFloat()).squash, 1e-6f)
        assertEquals(FilmWorld.SQUASH, world.bike(Film.T_POP - 1f).squash, 1e-6f)
        assertEquals(0.38f, FilmWorld.SQUASH, 0f)
        // squashed about the road: every part is at 0.38 of its height, the wheels still touch it
        for (p in Part.entries) {
            val sq = world.pose(p, Film.T_S3 + 50f)
            assertEquals("${p.name} sy", 0.38f, sq.sy, 1e-3f)
        }
        val wheel = world.pose(Part.RearWheel, Film.T_S3 + 50f)
        assertEquals(0f, wheel.y + Geo.WHEEL_R * wheel.sy, 1e-3f)
        // the wheels roll in: the spin follows the distance
        assertEquals(0f, world.spin(Film.T_ROLL_START.toFloat()), 1e-3f)
        assertEquals(-FilmWorld.ROLL_FROM / Geo.WHEEL_R * 180f / Math.PI.toFloat(), world.spin(Film.T_ROLL_END.toFloat()), 0.1f)
        assertEquals(world.spin(Film.T_ROLL_END.toFloat()), world.spin(Film.T_HANG.toFloat()), 0f)
    }

    @Test
    fun theDamageStaysOnTheBikeUntilThePop() {
        // after the second stomp has settled: the bars bent back 32 degrees, the saddle crooked 30 degrees and sunk 8 u, the capsule 6 u low
        val t = Film.T_S2 + 600f
        val cockpit = world.pose(Part.Cockpit, t)
        assertEquals(-32f, cockpit.rot, 1f)
        val saddle = world.pose(Part.Saddle, t)
        assertEquals(30f, saddle.rot, 1f)
        assertEquals(8f, saddle.y - Part.Saddle.py, 0.5f)
        val tube = world.pose(Part.Tube, t)
        assertEquals(6f, tube.y - Part.Tube.py, 0.5f)
        assertEquals(4f, tube.rot, 0.3f)
        // and it is still there just before the pop, squashed with the bike
        val before = world.pose(Part.Cockpit, Film.T_POP - 1f)
        assertEquals(-32f, before.rot, 1f)
        assertEquals((Part.Cockpit.py + Reaction.delta(Part.Cockpit, Film.T_POP - 1f).dy) * 0.38f, before.y, 1e-3f)
        // the stomp's spring is visible at the hit: the capsule is pushed 6 u (+6 u) down at the second stomp
        assertTrue(world.pose(Part.Tube, Film.T_S2 + 100f).y - Part.Tube.py > 11f)
        // the front wheel is left out of true (6 % wider, turned) and the bike is still standing on the road
        assertEquals(1.06f, world.pose(Part.FrontWheel, t).sx, 3e-3f)
        // the wheels stay on the road through every stomp (the first one throws the bike up to 5 u on its rebound)
        var tt = 3_000f
        while (tt < Film.T_S3) {
            for (p in listOf(Part.RearWheel, Part.FrontWheel)) {
                val w = world.pose(p, tt)
                val th = Math.toRadians(w.rot.toDouble())
                val h = Geo.WHEEL_R * kotlin.math.sqrt((w.sx * kotlin.math.sin(th)).let { it * it } + (w.sy * kotlin.math.cos(th)).let { it * it }).toFloat()
                val bottom = w.y + h
                assertTrue("${p.name} bottom $bottom at $tt", bottom <= 0.01f && bottom > -6f)
            }
            tt += 10f
        }
    }

    @Test
    fun theTubeLandsWhereTheThudSays() {
        val landing = world.firstContact(Part.Tube)
        assertTrue("tube lands at $landing, the cue says ${Film.THUD_TUBE_LANDS}", abs(landing - Film.THUD_TUBE_LANDS) <= 40f)
        // the wheels land earlier, the rear one pinned to the coin settle
        assertTrue(world.firstContact(Part.FrontWheel) < landing)
        assertEquals(Film.T_DROP + 440f, world.firstContact(Part.RearWheel), 0f)
        assertEquals(9_280f, FilmWorld.REAR_WHEEL_TOUCH, 0f)
    }

    @Test
    fun theBlastIsHardThenHangs() {
        // 100 ms after the pop most of the way is done: the parts are far from where the bike was, at the hang pose after 800 ms
        for (p in Part.entries) {
            val start = world.pose(p, Film.T_POP - 1f)
            val hang = world.pose(p, Film.T_HANG.toFloat())
            val soon = world.pose(p, Film.T_POP + 100f)
            val travelled = kotlin.math.hypot(soon.x - start.x, soon.y - start.y)
            val total = kotlin.math.hypot(hang.x - start.x, hang.y - start.y)
            if (total > 20f) assertTrue("${p.name} travelled ${travelled / total} of the way in 100 ms", travelled / total > 0.85f)
            // held through the hang: still at the hang and just before the drop (the rear wheel and a stagger of 0 start falling at the drop)
            val held = world.pose(p, Film.T_DROP - 20f)
            assertEquals("${p.name} x", hang.x, held.x, 0.01f)
            assertEquals("${p.name} y", hang.y, held.y, 0.01f)
        }
        // the lit dots go out radially at speed: past their place by up to a sixth of the way, then settle (the dark sockets stay in
        // the capsule, so only the 46 lit ones count)
        var over = 0
        var far = 0
        for (i in world.flyers) {
            val early = world.dot(i, Film.T_POP + 200f)
            val hung = world.dot(i, Film.T_HANG.toFloat())
            val squashed = world.dot(i, Film.T_POP + 1f)
            val full = hung.x - squashed.x
            if (kotlin.math.abs(full) > 6f) {
                far++
                if ((early.x - squashed.x) / full > 1.02f) over++
            }
        }
        assertTrue("$over of $far dots overshoot", far >= 20 && over > far * 2 / 3)
    }

    @Test
    fun theDotsStartInTheDentedSquashedCapsule() {
        // the flyers leave the capsule as it was at the pop: squashed to 0.38, sagging, with its nose down
        val tube = world.pose(Part.Tube, Film.T_POP - 1f)
        assertEquals(0.38f, tube.sy, 1e-3f)
        for (i in world.flyers) {
            val d = world.dot(i, Film.T_POP.toFloat())
            val cell = world.cellWorld(world.dotCol[i], world.dotRow[i], tube)
            assertEquals(cell.first, d.x, 0.01f)
            assertEquals(cell.second, d.y, 0.01f)
            assertTrue(d.lit)
        }
    }

    @Test
    fun onlyTheLitDotsFlyAndTheDarkOnesRideTheCapsule() {
        for (i in 0 until world.dotCount) assertEquals(world.dotLit[i], world.flies(i))
        // a dark socket is always in its cell of the capsule, wherever the capsule is: through the blast, the hang and the fall
        for (t in listOf(Film.T_POP + 50f, Film.T_POP + 300f, Film.T_HANG + 200f, Film.T_DROP + 100f, Film.T_DROP + 700f, Film.T_SILENCE.toFloat())) {
            val tube = world.pose(Part.Tube, t)
            for (i in 0 until world.dotCount step 3) {
                if (world.flies(i)) continue
                val d = world.dot(i, t)
                val cell = world.cellWorld(world.dotCol[i], world.dotRow[i], tube)
                assertEquals("socket $i at $t", cell.first, d.x, 1e-3f)
                assertEquals("socket $i at $t", cell.second, d.y, 1e-3f)
                assertTrue(!d.lit)
            }
        }
        // the lit ones hang out of the capsule: all of them above the tube's own place, well clear of the road, in the exploded view
        val hungTube = world.pose(Part.Tube, Film.T_HANG.toFloat())
        for (i in world.flyers) {
            val d = world.dot(i, Film.T_HANG.toFloat())
            assertTrue(d.y < hungTube.y + 40f && d.y > -400f)
        }
    }

    @Test
    fun theExplodedViewIsTallerOnATallStageAndFallsInTheSameTime() {
        val tall = FilmWorld.build(1.35f)
        val flat = FilmWorld.build(1f)
        for (p in Part.entries) {
            assertEquals("${p.name} x", flat.hangX(p), tall.hangX(p), 1e-3f)
            assertEquals("${p.name} y", flat.hangY(p) * 1.35f, tall.hangY(p), 1e-2f)
        }
        // gravity grows with the stretch, so the tube still lands where the haptic thud says
        assertTrue(abs(tall.firstContact(Part.Tube) - Film.THUD_TUBE_LANDS) <= 40f)
        assertEquals(flat.firstContact(Part.Tube), tall.firstContact(Part.Tube), 15f)
        assertTrue(tall.isSane())
        // the rest and the hang of a part that is not stretched are the same on both
        assertEquals(flat.restPose(Part.Tube).x, tall.restPose(Part.Tube).x, 0f)
        // a world is cached per step of 0.05
        assertTrue(FilmWorld.forStretch(1.35f) === FilmWorld.forStretch(1.351f))
    }

    @Test
    fun theCoinSettlesWithFourTouches() {
        val d = Film.T_DROP
        for (touch in intArrayOf(d + 440, d + 620, d + 740, d + 820)) assertEquals(0f, world.coinAngle(touch.toFloat()), 1e-3f)
        assertEquals(90f, world.coinAngle(d + 280f), 0f)
        assertEquals(90f, world.coinAngle(d.toFloat()), 0f)
        assertEquals(28f, world.coinAngle(d + 530f), 1e-3f)
        assertEquals(14f, world.coinAngle(d + 680f), 1e-3f)
        assertEquals(6f, world.coinAngle(d + 780f), 1e-3f)
        assertEquals(0f, world.coinAngle(d + 2_000f), 0f)
    }

    @Test
    fun everythingIsLyingStillInTheSilence() {
        // the last small bounce (the cockpit falls from highest and last) ends about 200 ms into the silence; from there to the first
        // light nothing on the road moves
        for (p in Part.entries) {
            val a = world.pose(p, Film.T_SILENCE + 250f)
            val b = world.pose(p, Film.T_LIGHT_1 - 20f)
            assertEquals("${p.name} x", a.x, b.x, 0.05f)
            assertEquals("${p.name} y", a.y, b.y, 0.05f)
        }
    }

    @Test
    fun theFrontWheelReturnsLikeTheOtherParts() {
        // it lies where it fell (restX 84) until its return begins, and is home 700 ms later
        val before = world.pose(Part.FrontWheel, Film.T_HOMEFLIGHT + 400f)
        assertEquals(Part.FrontWheel.restX, before.x, 0.5f)
        val home = world.pose(Part.FrontWheel, Film.T_HOMEFLIGHT + 440f + FilmWorld.RETURN_MS)
        assertEquals(Part.FrontWheel.px, home.x, 0.01f)
        assertEquals(world.rollDeg(10f), 10f / Geo.WHEEL_R * 180f / Math.PI.toFloat(), 1e-3f)
    }

    @Test
    fun partsReturnOnTheSpecsClockAndTheLastIsHomeAtTheTableau() {
        val h = Film.T_HOMEFLIGHT
        // each part starts moving at T_HOMEFLIGHT + n, and is whole 700 ms later
        val starts = mapOf(
            Part.RearWheel to 240, Part.Lock to 300, Part.RearTri to 360, Part.DownTube to 420, Part.RearFender to 440,
            Part.FrontFender to 440, Part.FrontWheel to 440, Part.Tube to 500, Part.Fork to 540, Part.Saddle to 580, Part.Cockpit to 600,
        )
        for ((p, n) in starts) {
            val at = world.pose(p, h + n - 10f)
            val rest = world.restPose(p)
            assertEquals("${p.name} still at rest before $n", rest.x, at.x, 0.05f)
            val done = world.pose(p, h + n + 700f)
            assertEquals("${p.name} home x", p.px, done.x, 0.01f)
            assertEquals("${p.name} home y", p.py - Lift.at(h + n + 700f, false), done.y, 0.01f)
        }
        assertEquals(Film.T_TABLEAU, h + 600 + FilmWorld.RETURN_MS)
    }

    @Test
    fun theBikeIsWholeAgainAtTheTableauWithNoLeftoverDroop() {
        for (p in Part.entries) {
            val pose = world.pose(p, Film.T_TABLEAU.toFloat() + 1f)
            assertEquals("${p.name} x", p.px, pose.x, 0.1f)
            assertEquals("${p.name} rot", 0f, pose.rot, 0.1f)
            assertEquals("${p.name} sx", 1f, pose.sx, 1e-3f)
            assertEquals("${p.name} sy", 1f, pose.sy, 1e-3f)
            assertEquals("${p.name} y", p.py - Lift.UP, pose.y, 0.1f)
        }
    }

    @Test
    fun theDotsFlyHomeIntoTheTube() {
        val t = Film.T_DOTS_HOME.toFloat() + 5f
        val tube = world.pose(Part.Tube, t)
        for (i in 0 until world.dotCount step 5) {
            val d = world.dot(i, t)
            val cell = world.cellWorld(world.dotCol[i], world.dotRow[i], tube)
            assertEquals(cell.first, d.x, 0.05f)
            assertEquals(cell.second, d.y, 0.05f)
            assertTrue(!d.lit)
        }
        // and they leave column by column, left to right
        val early = world.dot(0, Film.T_HOMEFLIGHT + 10f)
        val late = world.dot(world.dotCount - 1, Film.T_HOMEFLIGHT + 10f)
        assertTrue(early.y != late.y)
        assertNotEquals(early.x, late.x)
        // they are home by 12,160 and the tube is whole at 12,200
        assertEquals(12_160, Film.T_DOTS_HOME)
    }

    @Test
    fun theErrorGlowsRedOnTheRoadAndTheLifeSignIsTheOnlyLightInTheSilence() {
        // before the silence the 46 dots of the error still glow on the road
        assertEquals(46, (0 until world.dotCount).count { world.dot(it, Film.T_SILENCE - 100f).lit })
        // the red ones are not the life sign; the life sign is flagged and led
        val lifeDots = world.flyers.filter { world.dot(it, Film.T_SILENCE - 100f).life }
        assertEquals(1, lifeDots.size)
        assertEquals(LifeSign.COL, world.dotCol[lifeDots.single()])
        assertEquals(LifeSign.ROW, world.dotRow[lifeDots.single()])
        // in the silence it is the only dot alight, in the cell of the crossbar of the 4
        for (t in listOf(Film.T_SILENCE + 300f, Film.T_SILENCE + 500f)) {
            val lit = (0 until world.dotCount).filter { world.dot(it, t).lit }
            assertEquals("lit dots at $t", 1, lit.size)
            assertEquals(LifeSign.COL, world.dotCol[lit.single()])
            assertEquals(LifeSign.ROW, world.dotRow[lit.single()])
            assertTrue(world.dot(lit.single(), t).life)
        }
        // after the light is back they are out again, a cut, and no dot is flagged as life any more than the one
        assertEquals(0, (0 until world.dotCount).count { world.dot(it, Film.T_LIGHT_3 + 5f).lit })
        // the life sign has hopped home into the fallen tube by T_LIFE_HOME
        val tube = world.restPose(Part.Tube)
        val home = world.cellWorld(LifeSign.COL, LifeSign.ROW, tube)
        val d = world.dot(lifeDots.single(), Film.T_LIFE_HOME.toFloat())
        assertEquals(home.first, d.x, 0.05f)
        assertEquals(home.second, d.y, 0.05f)
    }

    @Test
    fun theFlyersOfTheHomeflightAreLitAndDimIntoTheirSockets() {
        // mid-flight: lit again (home > 0), not the signal state of the road
        val i = world.flyers.first { world.dotCol[it] == 12 }
        val dep = Film.T_HOMEFLIGHT + 12 * Film.DOT_STAGGER
        val mid = world.dot(i, dep + 200f)
        assertTrue(!mid.lit)
        assertTrue(mid.home > 0.2f && mid.home < 0.8f)
        assertEquals(0f, world.dot(i, dep - 1f).home, 0f)
        assertEquals(1f, world.dot(i, dep + Film.DOT_FLIGHT.toFloat()).home, 0f)
    }

    @Test
    fun theHandoffFramePoseIsTheWelcomesTube() {
        // at the cut the lift has settled: the tube sits at its pivot, which is the capsule's middle
        val p = world.pose(Part.Tube, Film.T_CUT.toFloat(), handoff = true)
        assertEquals(Geo.TUBE_CX, p.x, 1e-3f)
        assertEquals(Geo.TUBE_CY, p.y, 1e-3f)
        assertEquals(0f, p.rot, 0f)
    }

    @Test
    fun theErrorHangsAsACleanGlyphAndOnlyScattersOnTheDrop() {
        // at the hang the 46 lit dots are the code E42 scaled 2.0 times, the same in x and y (a clean glyph: no push, no jitter),
        // on every stage
        for (stretch in listOf(1f, 1.35f)) {
            val w = FilmWorld.build(stretch)
            val tube = w.pose(Part.Tube, Film.T_HANG.toFloat())
            for (i in w.flyers) {
                val d = w.dot(i, Film.T_HANG.toFloat())
                val ex = tube.x + (Geo.cellX(w.dotCol[i]) - Part.Tube.px) * 2f
                val ey = w.hangY(Part.Tube) + (Geo.cellY(w.dotRow[i]) - Part.Tube.py) * 2f
                assertEquals("x of dot $i", ex, d.x, 0.2f)
                assertEquals("y of dot $i", ey, d.y, 0.2f)
            }
            // it is still there just before the drop, every dot where it was
            for (i in w.flyers) {
                val a = w.dot(i, Film.T_HANG.toFloat())
                val b = w.dot(i, Film.T_DROP - 1f)
                assertEquals(a.x, b.x, 1e-3f)
                assertEquals(a.y, b.y, 1e-3f)
            }
        }
        // the neighbours of a column are one pitch (2 x 1.83 u) apart in x, and the rows one pitch in y: the glyph is a grid
        val cols = world.flyers.groupBy { world.dotCol[it] }
        val c9 = cols.getValue(9).map { world.dot(it, Film.T_HANG.toFloat()).x }.toSet()
        assertEquals(1, c9.map { (it * 100).toInt() }.toSet().size)
        // after the drop begins they swerve (sideways, by their own stagger: some past the straight line from the hang to the road),
        // and on the road the scatter is over: x no longer changes
        var swerving = 0
        for (i in world.flyers) {
            val hangX = world.dot(i, Film.T_HANG.toFloat()).x
            val restX = world.dot(i, Film.T_SILENCE - 50f).x
            assertEquals(restX, world.dot(i, Film.T_SILENCE - 10f).x, 1e-3f)
            val lo = minOf(hangX, restX)
            val hi = maxOf(hangX, restX)
            val out = (0..60).any { k ->
                val x = world.dot(i, Film.T_DROP + 10f * k).x
                x < lo - 1f || x > hi + 1f
            }
            if (out) swerving++
        }
        assertTrue("$swerving of 46 dots swerve", swerving >= 10)
    }

    @Test
    fun theFrontFenderWrapsOverTheTyreLikeThePhotos() {
        // 166 to 315 degrees about the front axle: it ends about 2:30 (the photo's wraps to there), the rear one is as before
        assertEquals(166f, Geo.FRONT_FENDER_FROM, 0f)
        assertEquals(315f, Geo.FRONT_FENDER_TO, 0f)
        assertEquals(195f, Geo.REAR_FENDER_FROM, 0f)
        assertEquals(335f, Geo.REAR_FENDER_TO, 0f)
        // the pivot is the centroid of the new arc (a part's pivot is its centroid)
        val mid = Math.toRadians(((Geo.FRONT_FENDER_FROM + Geo.FRONT_FENDER_TO) / 2f).toDouble())
        val half = Math.toRadians(((Geo.FRONT_FENDER_TO - Geo.FRONT_FENDER_FROM) / 2f).toDouble())
        val rc = Geo.FENDER_R * kotlin.math.sin(half) / half
        assertEquals(Geo.AXLE_X + rc * kotlin.math.cos(mid), Part.FrontFender.px.toDouble(), 0.3)
        assertEquals(-Geo.WHEEL_R + rc * kotlin.math.sin(mid), Part.FrontFender.py.toDouble(), 0.3)
        // the rear one's tail is low (195 degrees: 15 above the axle's level) and its pivot is the centroid of its arc, too
        val rmid = Math.toRadians(((Geo.REAR_FENDER_FROM + Geo.REAR_FENDER_TO) / 2f).toDouble())
        val rhalf = Math.toRadians(((Geo.REAR_FENDER_TO - Geo.REAR_FENDER_FROM) / 2f).toDouble())
        val rrc = Geo.FENDER_R * kotlin.math.sin(rhalf) / rhalf
        assertEquals(-Geo.AXLE_X + rrc * kotlin.math.cos(rmid), Part.RearFender.px.toDouble(), 0.3)
        assertEquals(-Geo.WHEEL_R + rrc * kotlin.math.sin(rmid), Part.RearFender.py.toDouble(), 0.3)
        // the fenders hug the tyres: 1.9 u off the tyre's outer edge (it floated 2.7 u off before), and clear of it
        assertEquals(38.4f, Geo.FENDER_R, 0f)
        assertTrue(Geo.FENDER_R - Geo.WHEEL_R in 1.5f..2.2f)
        // the tyre is a solid band: thick as a balloon tyre, its inner edge on the rim ring
        assertTrue(Geo.TYRE_T >= 5f && Geo.WHEEL_R - Geo.TYRE_T > Geo.RIM_R)
    }

    @Test
    fun theRoadCracksGrowWithTheStompsAndFadeWithTheLight() {
        // nothing before a hit, the whole crack 60 ms after it, one per stomp, each with more branches than the one before
        for (n in 0 until 3) {
            val hit = Cracks.hitAt(n)
            assertEquals(0f, Cracks.amount(hit - 1f, n), 0f)
            assertEquals(0f, Cracks.amount(hit.toFloat(), n), 0f)
            assertTrue(Cracks.amount(hit + 30f, n) in 0.5f..1f)
            assertEquals(1f, Cracks.amount(hit + Cracks.GROW_MS.toFloat(), n), 1e-6f)
            assertEquals(1f, Cracks.amount(Film.T_SILENCE.toFloat(), n), 0f)
        }
        val branches = (0 until 3).map { n -> Cracks.strokes.count { it.crack == n } }
        assertTrue("branches $branches", branches[0] < branches[1] && branches[1] < branches[2])
        // they are in the road (y > 0, below the kerb), inside the wide shot's frame, never deeper than the inner screen's road is
        for (st in Cracks.strokes) {
            for (i in st.pts.indices step 2) {
                val x = Cracks.originX[st.crack] + st.pts[i]
                assertTrue("x $x", x in -141f..121f)
                assertTrue("y ${st.pts[i + 1]}", st.pts[i + 1] in 0.5f..48f)
            }
            assertTrue(st.from in 0f..0.7f)
        }
        // the burst is about 70 u wide (it was 150), so that it does not run under the whole wreck, and none is wider
        for (n in 0 until 3) {
            val xs = Cracks.strokes.filter { it.crack == n }.flatMap { st -> (st.pts.indices step 2).map { Cracks.originX[n] + st.pts[it] } }
            assertTrue("crack $n is ${xs.max() - xs.min()} u wide", xs.max() - xs.min() <= 70.5f)
        }
        val burst = Cracks.strokes.filter { it.crack == 2 }.flatMap { st -> (st.pts.indices step 2).map { st.pts[it] } }
        assertTrue(burst.max() - burst.min() >= 66f)
        // every branch except the first of each crack starts on a point of an earlier branch of the same crack (a crack, not scribbles)
        for (n in 0 until 3) {
            val ss = Cracks.strokes.filter { it.crack == n }
            for (st in ss.drop(1)) {
                val sx = st.pts[0]; val sy = st.pts[1]
                assertTrue("branch of crack $n at ($sx, $sy) hangs in the air", ss.any { o -> o !== st && (0 until o.pts.size / 2).any { j -> o.pts[2 * j] == sx && o.pts[2 * j + 1] == sy } })
            }
        }
        // they are not retracted: grown stays 1 through the homeflight; they fade in the three cuts of the light instead, and are gone with
        // the third, before the bike is home: the road is whole at the tableau
        for (n in 0 until 3) {
            assertEquals(1f, Cracks.amount(Film.T_HOMEFLIGHT + 500f, n), 0f)
            assertEquals(1f, Cracks.amount(Film.T_TABLEAU.toFloat(), n), 0f)
        }
        assertEquals(listOf(1f, 1f, 0.6f, 0.3f, 0f, 0f), listOf(Film.T_DROP + 100, Film.T_LIGHT_1 - 1, Film.T_LIGHT_1, Film.T_LIGHT_2, Film.T_LIGHT_3, Film.T_TABLEAU).map { Cracks.fade(it.toFloat()) })
        // the silence shot (the 2.3x lens) shows the hairline of each crack only; the wide shots show them whole
        assertTrue(!Cracks.drawsBranch(Film.T_SILENCE.toFloat()) && !Cracks.drawsBranch(Film.T_LIGHT_1 - 1f))
        assertTrue(Cracks.drawsBranch(Film.T_SILENCE - 1f) && Cracks.drawsBranch(Film.T_LIGHT_1.toFloat()) && Cracks.drawsBranch(Film.T_S3 + 100f))
    }
}
