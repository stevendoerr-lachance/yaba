package dev.steven.s3control.ui.intro

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.steven.s3control.ui.components.BikeTube
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.theme.NightRide
import dev.steven.s3control.ui.theme.S3Theme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Golden frames of the opening film (Night) at the instants the spec names, checked by what they must show rather than
 * against stored pictures (a picture would have to be re-recorded for every phone), plus the handoff identity: the film's
 * last frame is the first screen's own tube, pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
class FilmFramesTest {
    @get:Rule
    val rule = createComposeRule()

    // the beats of the film: the roll-in, the stand, the boot's omen, stomp 1 and its AUW, stomp 2 (an impact frame) and the
    // blink (on, off, and the insert from the third blink on), the apex, stomp 3 (an impact frame, after its 40 ms squash) and its
    // hold, the blast, the hang (the E42 as a clean glyph), the drop, the
    // silence, the light, the homeflight, the tableau
    private val golden = listOf(
        1_000f, 2_700f, 3_500f, 3_780f, 4_100f, 5_100f, 5_300f, 5_700f, 6_400f, 6_700f, 7_400f, 7_580f, 7_700f, 8_300f, 8_700f, 9_600f,
        10_200f, 11_000f, 11_700f, 12_100f, 13_000f,
    )

    private fun Bitmap.brightFraction(): Float {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        return px.count { p -> ((p shr 16) and 0xFF) > 200 && ((p shr 8) and 0xFF) > 200 && (p and 0xFF) > 200 }.toFloat() / px.size
    }

    @Test
    fun everyGoldenFrameRendersWhatTheStoryNeeds() {
        assertTrue("the golden list has the two impact frames", golden.count { StageLight.impact(it) } == 2)
        var t by mutableStateOf(0f)
        rule.setContent {
            S3Theme(darkTheme = true) {
                Box(Modifier.requiredSize(414.dp, 900.dp)) { FilmStill(t, Modifier.fillMaxSize()) }
            }
        }
        for (at in golden) {
            t = at
            rule.waitForIdle()
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            assertEquals(true, bmp.width > 0 && bmp.height > 0)
            val bright = bmp.brightFraction()
            // the bottom of the window is the road band (frame, with a rib kerb), darkened by the stage light and nothing else
            // (an impact frame is lit flat: no dim)
            val dim = if (StageLight.impact(at)) 0f else StageLight.dim(at)
            val corner = bmp.getPixel(2, bmp.height - 2)
            // (the insert is shot so close that the window's bottom edge is inside the road's kerb: rib, not frame)
            val road = if (Camera.inInsert(at)) NightRide.rib else NightRide.frame
            val expected = (road.red * 255f * (1f - dim)).toInt()
            assertTrue("t=$at corner ${Integer.toHexString(corner)}", abs(((corner shr 16) and 0xFF) - expected) <= 3)
            // the two impact frames (the second and the third stomp): bike and boot as solid paint, a lot of bright pixels
            if (StageLight.impact(at)) assertTrue("impact $bright at $at", bright > 0.08f)
            // everything else: the bike is a thin line drawing, never a filled field. There is no empty frame any more.
            else assertTrue("t=$at bright $bright", bright < 0.25f)
        }
    }

    @Test
    fun theHandoffFrameIsTheScreensOwnTube() {
        var film by mutableStateOf(false)
        var slot by mutableStateOf<Rect?>(null)
        var origin by mutableStateOf(Offset.Zero)
        rule.setContent {
            S3Theme(darkTheme = true) {
                val (world, frames) = rememberFilmKit()
                Box(Modifier.requiredSize(414.dp, 900.dp).background(NightRide.asphalt)) {
                    BikeTube(
                        frame = MatrixFrame.Empty,
                        lightsOn = false,
                        columns = 36,
                        modifier = Modifier
                            .padding(top = 130.dp, start = 20.dp, end = 20.dp)
                            .onGloballyPositioned { slot = it.boundsInWindow() },
                    )
                    val s = slot
                    if (film && s != null) {
                        Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
                            // the slot is in window px, the canvas draws from its own corner (what IntroFilm does)
                            drawFilm(Film.T_CUT.toFloat(), Stage(0f, 0f, size.width, size.height), NightRide, world, frames, SceneOpts(slot = s.translate(-origin)))
                        }
                    }
                }
            }
        }
        // let the tube's own boot sweep finish before looking at it
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val tube = rule.onRoot().captureToImage().asAndroidBitmap()
        film = true
        rule.waitForIdle()
        val filmFrame = rule.onRoot().captureToImage().asAndroidBitmap()
        val r = checkNotNull(slot).translate(-origin)
        var differing = 0
        var worst = 0
        val total = (r.width * r.height).toInt()
        for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt()) {
            val a = tube.getPixel(x, y)
            val b = filmFrame.getPixel(x, y)
            if (a != b) {
                differing++
                for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)))
            }
        }
        assertTrue("$differing of $total pixels differ, worst $worst", differing <= total / 1000 && worst <= 8)
        // and outside the slot the film is plain asphalt
        assertEquals(NightRide.asphalt.toArgb(), filmFrame.getPixel(5, 5))
    }
}
