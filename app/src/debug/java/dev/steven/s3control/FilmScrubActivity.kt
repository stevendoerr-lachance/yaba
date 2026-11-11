package dev.steven.s3control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.components.TubeDefaults
import dev.steven.s3control.ui.intro.FilmStill
import dev.steven.s3control.ui.intro.SceneOpts
import dev.steven.s3control.ui.intro.Stage
import dev.steven.s3control.ui.theme.Daylight
import dev.steven.s3control.ui.theme.NightRide
import dev.steven.s3control.ui.theme.S3Theme

/**
 * Debug-only: draws one frame of the opening film.
 * adb shell am start -n dev.steven.yaba/dev.steven.s3control.FilmScrubActivity --ef t 4380 [--ez day true]
 *   [--ez half true] [--ez slot true] [--ez lockup true] [--ez strip true] [--ez noouch true]
 * half = the stage is the left half of the window (a half-opened fold); slot = a Welcome-like tube slot to land on.
 */
class FilmScrubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = intent.getFloatExtra("t", 0f)
        val day = intent.getBooleanExtra("day", false)
        val half = intent.getBooleanExtra("half", false)
        val withSlot = intent.getBooleanExtra("slot", false)
        val lockup = intent.getBooleanExtra("lockup", false)
        setContent {
            S3Theme(darkTheme = !day) {
                val d = LocalDensity.current.density
                FilmStill(
                    t = t,
                    modifier = Modifier.fillMaxSize(),
                    c = if (day) Daylight else NightRide,
                    opts = SceneOpts(
                        ouch = !intent.getBooleanExtra("noouch", false),
                        lockup = lockup,
                        slot = if (withSlot) {
                            // where a Welcome tube sits on the cover screen: 20 dp gutter, 130 dp from the top
                            val w = resources.displayMetrics.widthPixels.toFloat()
                            val pitch = (w - 2 * 20 * d - 2 * TubeDefaults.PadX.value * d) / 36f
                            val h = 2 * TubeDefaults.PadY.value * d + 7 * pitch
                            Rect(20 * d, 130 * d, w - 20 * d, 130 * d + h)
                        } else null,
                    ),
                    stageOf = { size ->
                        if (half) Stage(0f, 0f, size.width / 2f, size.height) else Stage(0f, 0f, size.width, size.height)
                    },
                )
            }
        }
    }
}
