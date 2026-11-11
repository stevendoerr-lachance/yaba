package dev.steven.s3control.ui.intro

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.steven.s3control.ui.theme.NightRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the real drawing code against a canvas whose Android end is the stubbed android.jar (returnDefaultValues): nothing is
 * rasterised, but every branch of [drawFilm] is walked, so an exception, an index out of range or a division by something
 * that is not a number in the drawing shows here, at any instant, on both of the Find N6's screens, and in the strip.
 */
class FilmDrawTest {
    private val frames = FilmFrames("AUW", "E42")
    private val density = Density(2.75f)

    private fun draw(w: Float, h: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
        val scope = CanvasDrawScope()
        scope.draw(density, LayoutDirection.Ltr, Canvas(android.graphics.Canvas()), Size(w, h), block)
    }

    @Test
    fun theWholeFilmDrawsOnBothScreens() {
        for ((w, h) in listOf(1140f to 2616f, 2248f to 2480f)) {
            val stage = Stage(0f, 0f, w, h)
            val world = FilmWorld.forStage(stage)
            var t = 0f
            while (t <= Film.T_END) {
                draw(w, h) { drawFilm(t, stage, NightRide, world, frames, SceneOpts()) }
                draw(w, h) { drawFilm(t, stage, NightRide, world, frames, SceneOpts(ouch = false, lockup = true)) }
                t += 25f
            }
            // every instant the spec names, exactly: the stomps, the blink edges, the pop, the cut
            val edges = listOf(
                Film.T_S1, Film.T_S2, Film.T_S3, Film.T_S3 + Film.SQUASH_MS, Film.T_POP, Film.T_E42, Film.T_STUCK, Film.T_INSERT, Film.T_TOTALE,
                Film.T_TAP_1, Film.T_TAP_2, Film.T_TAP_3, Film.T_SILENCE, Film.T_LIGHT_1, Film.T_CUT, Film.T_END, Film.T_END + 5_000,
            )
            for (e in edges) for (dt in -1..1) draw(w, h) { drawFilm(e + dt.toFloat(), stage, NightRide, world, frames, SceneOpts()) }
        }
    }

    @Test
    fun theHandoffFrameDrawsForEveryLaterTime() {
        val stage = Stage(0f, 0f, 1140f, 2616f)
        val world = FilmWorld.forStage(stage)
        val slot = Rect(55f, 330f, 1085f, 330f + 2f * 49f + 7f * ((1030f - 2f * 110f) / 36f))
        // drawFilm(t >= T_CUT, slot = ...) renders the final frame for any larger t, 17,060 (the old cut) included
        for (t in listOf(Film.T_HANDOFF.toFloat(), Film.T_CUT.toFloat(), Film.T_END.toFloat(), 17_060f, 30_000f)) {
            draw(1140f, 2616f) { drawFilm(t, stage, NightRide, world, frames, SceneOpts(slot = slot)) }
        }
        val pose = world.pose(Part.Tube, 17_060f, handoff = true)
        assertEquals(Geo.TUBE_CX, pose.x, 1e-3f)
        assertEquals(Geo.TUBE_CY, pose.y, 1e-3f)
    }

    @Test
    fun aHalfOpenedFoldDrawsToo() {
        val stage = Stage(0f, 0f, 1124f, 2480f)
        val world = FilmWorld.forStage(stage)
        var t = 0f
        while (t <= Film.T_END) {
            draw(2248f, 2480f) { drawFilm(t, stage, NightRide, world, frames, SceneOpts()) }
            t += 100f
        }
    }

    @Test
    fun theStripDrawsItsThreePanelsWithoutAHeapOrAnythingSpectacular() {
        // the three still instants and their shots: the boot standing on the dented bike, the wreck, the lifted bike with YABA
        val wreck = Film.T_SILENCE - 40f
        val lifted = Film.T_TABLEAU + 1_500f
        val times = listOf(5_160f, wreck, lifted)
        val boot = Boots.pose(5_160f)
        assertNotNull("the boot stands on the dented bike in panel 1", boot)
        // the end of the second stomp's hit-stop (the boot is on the capsule), before the film's own blink has lit E42
        assertEquals(Boots.PRESS_2, boot!!.soleY, 0.5f)
        assertTrue(5_160f >= Film.T_S2 + 60f && 5_160f <= Film.T_S2 + Film.HIT_STOP_2 && 5_160f < Film.T_E42)
        assertEquals(FrameId.Empty, TubeFace.at(5_160f, ouch = true, lockup = false).shown)
        assertTrue("no boot in the wreck panel", Boots.pose(wreck) == null)
        assertTrue("no boot in the last panel", Boots.pose(lifted) == null)
        // panel 1 shows E42 steady in signal (set by hand: the film's face is blank there), panel 3 the wordmark, swept in
        assertEquals(FrameId.E42, SceneOpts(face = Face(FrameId.E42, tone = Tone.Signal)).face!!.shown)
        assertEquals(FrameId.Yaba, TubeFace.at(lifted, ouch = true, lockup = true).shown)
        assertTrue(lifted >= TubeFace.LOCKUP_SWEEP + 520f)
        // the damage of panel 1: bars bent back (the second stomp has tipped them 5 degrees forward again), the saddle crooked, steady
        assertEquals(-32f + 5f, world().pose(Part.Cockpit, 5_160f).rot, 1f)
        assertTrue(world().pose(Part.Saddle, 5_160f).rot >= 30f)
        // the kickstand is folded and the front wheel is out of true in it, too
        assertTrue(Reaction.standFold(5_160f) > 0.9f * Reaction.STAND_FOLD)
        assertTrue(world().pose(Part.FrontWheel, 5_160f).sx > 1.06f)
        // the capsule is pressed under the sole, the whole bike is in the picture
        assertTrue(world().pose(Part.Tube, 5_160f).y - Part.Tube.py > 11f)
        // the cracks of the first two stomps are in the road of panel 1, the burst of all three in panel 2, none in the lifted bike's
        assertEquals(1f, Cracks.amount(5_160f, 0), 0f)
        assertEquals(1f, Cracks.amount(5_160f, 1), 0f)
        assertEquals(0f, Cracks.amount(5_160f, 2), 0f)
        assertEquals(1f, Cracks.amount(wreck, 2), 0f)
        assertEquals(1f, Cracks.fade(wreck), 0f)
        assertEquals(0f, Cracks.fade(lifted), 0f)
        // the wreck (panel 2) lies on the road: nothing above 60 u
        for (p in Part.entries) assertTrue("${p.name} lies at ${world().pose(p, wreck).y}", world().pose(p, wreck).y > -60f)
        // panel 3: the bike is lifted
        assertEquals(Part.Tube.py - Lift.at(lifted, false), world().pose(Part.Tube, lifted).y, 0.01f)
        for ((i, t) in times.withIndex()) {
            val w = 380f
            val h = 240f
            val stage = Stage(0f, 0f, w, h)
            val cam = stripCam(i, stage)
            assertTrue(cam.k.isFinite() && cam.k > 0f)
            // the strip's camera fits what the panel needs: the bike's 186 u across (the wreck's 262 u), whatever the panel's shape
            val needed = if (i == 1) 262f else 190f
            assertTrue("panel $i sees ${w / cam.k} u across", w / cam.k >= needed - 1f)
            val face = if (i == 0) Face(FrameId.E42, tone = Tone.Signal) else null
            draw(w, h) { drawFilm(t, stage, NightRide, world(), frames, SceneOpts(strip = true, lockup = i == 2, camera = cam, face = face)) }
            // and a tall one, as on a phone in a column of three
            val tall = Stage(0f, 0f, 300f, 500f)
            draw(300f, 500f) { drawFilm(t, tall, NightRide, world(), frames, SceneOpts(strip = true, lockup = i == 2, camera = stripCam(i, tall), face = face)) }
        }
    }

    private fun world() = FilmWorld.forStretch(1f)
}
