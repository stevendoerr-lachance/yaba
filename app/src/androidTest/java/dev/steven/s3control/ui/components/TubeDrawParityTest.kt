package dev.steven.s3control.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.steven.s3control.ui.theme.S3Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F1 acceptance: [BikeTube] and [MatrixDisplay] draw through the shared draw functions now ([drawTubeBody],
 * [drawMatrixField]); drawn next to the code they replaced (LegacyTube.kt), in Night and Daylight, at 360 and 414 dp,
 * at rest, mid-sweep, with the lights on and with a look (strike dot, glow, recoil), every pixel is the same.
 */
@RunWith(AndroidJUnit4::class)
class TubeDrawParityTest {
    @get:Rule
    val rule = createComposeRule()

    private var dark by mutableStateOf(true)
    private var widthDp by mutableStateOf(414)
    private var lights by mutableStateOf(true)
    private var look by mutableStateOf(DotLook.Calm)
    private var frame by mutableStateOf(MatrixFrame.Empty)
    private var legacy by mutableStateOf(false)

    // One slot for each, drawn once with the new code and once with the old: at the very same place, because the
    // rasteriser's last bit of rounding depends on where a shape sits.
    private fun compose() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            S3Theme(darkTheme = dark) {
                Column(Modifier.requiredWidth(widthDp.dp)) {
                    Box(Modifier.testTag("tube")) {
                        if (legacy) LegacyBikeTube(frame, lights, Modifier.fillMaxWidth(), columns = 36, look = look)
                        else BikeTube(frame, lights, Modifier.fillMaxWidth(), columns = 36, look = look)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.testTag("field")) {
                        if (legacy) LegacyMatrixDisplay(frame, Modifier.fillMaxWidth(), columns = 36, look = look)
                        else MatrixDisplay(frame, Modifier.fillMaxWidth(), columns = 36, look = look)
                    }
                }
            }
        }
    }

    private var round = 0

    private fun grab(tag: String): Pair<IntArray, Pair<Int, Int>> {
        val b = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px to (b.width to b.height)
    }

    private fun same(isDark: Boolean, w: Int, lightsOn: Boolean, lk: DotLook, atMs: Long) {
        dark = isDark; widthDp = w; lights = lightsOn; look = lk
        round++
        val word = MatrixFrame.text("AUW") + MatrixFrame.gap(1) + MatrixFrame.text((40 + round).toString())
        val shots = HashMap<Boolean, Map<String, Pair<IntArray, Pair<Int, Int>>>>()
        for (old in listOf(false, true)) {
            legacy = old
            frame = MatrixFrame.Empty
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            frame = word
            rule.mainClock.advanceTimeBy(atMs)
            rule.waitForIdle()
            shots[old] = mapOf("tube" to grab("tube"), "field" to grab("field"))
        }
        for (tag in listOf("tube", "field")) {
            val (px, size) = shots.getValue(false).getValue(tag)
            val (py, size2) = shots.getValue(true).getValue(tag)
            assertEquals("$tag size", size, size2)
            var differing = 0
            var first = ""
            for (i in px.indices) if (px[i] != py[i]) {
                differing++
                if (first.isEmpty()) first = "first at (${i % size.first},${i / size.first}): ${Integer.toHexString(px[i])} vs ${Integer.toHexString(py[i])}"
            }
            assertEquals("$tag new vs old (dark=$isDark, ${w}dp, lights=$lightsOn, t=$atMs, ${size.first}x${size.second}): differing pixels, $first", 0, differing)
        }
    }

    @Test
    fun atRestAndMidSweepAndWithALook() {
        compose()
        val strike = DotLook(dot = { 1.206f }, glow = { 1.8f }, lit = { 0.8f }, shift = { -1 })
        for (isDark in listOf(true, false)) for (w in listOf(360, 414)) {
            same(isDark, w, lightsOn = true, lk = DotLook.Calm, atMs = 1_500)
            same(isDark, w, lightsOn = false, lk = DotLook.Calm, atMs = 250)
            same(isDark, w, lightsOn = true, lk = strike, atMs = 300)
        }
    }
}
