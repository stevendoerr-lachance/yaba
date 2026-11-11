package dev.steven.s3control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.components.BikeTube
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.intro.SceneOpts
import dev.steven.s3control.ui.intro.Stage
import dev.steven.s3control.ui.intro.drawFilm
import dev.steven.s3control.ui.intro.rememberFilmKit
import dev.steven.s3control.ui.theme.NightRide
import dev.steven.s3control.ui.theme.S3Theme

/**
 * Debug-only: the handoff check. mode=tube draws a dark BikeTube (what the first screen shows behind the film),
 * mode=film draws the film's last frame (17,060 ms) into that tube's measured rect. The two screenshots must match
 * in the tube's rectangle. adb shell am start -n dev.steven.yaba/dev.steven.s3control.HandoffCheckActivity --es mode tube|film [--ez day true]
 */
class HandoffCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "tube"
        val day = intent.getBooleanExtra("day", false)
        setContent {
            S3Theme(darkTheme = !day) {
                val (world, frames) = rememberFilmKit()
                var slot by remember { mutableStateOf<Rect?>(null) }
                Box(Modifier.fillMaxSize().background(NightRide.asphalt)) {
                    BikeTube(
                        frame = MatrixFrame.Empty,
                        lightsOn = false,
                        columns = 36,
                        modifier = Modifier
                            .padding(top = 130.dp, start = 20.dp, end = 20.dp)
                            .onGloballyPositioned { slot = it.boundsInWindow() },
                    )
                    val s = slot
                    if (mode == "film" && s != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawFilm(17_060f, Stage(0f, 0f, size.width, size.height), NightRide, world, frames, SceneOpts(slot = s))
                        }
                    }
                }
            }
        }
    }
}
