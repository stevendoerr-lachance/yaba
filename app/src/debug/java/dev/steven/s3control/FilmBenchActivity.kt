package dev.steven.s3control

import android.graphics.Picture
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.steven.s3control.ui.intro.Film
import dev.steven.s3control.ui.intro.FilmFrames
import dev.steven.s3control.ui.intro.FilmWorld
import dev.steven.s3control.ui.intro.SceneOpts
import dev.steven.s3control.ui.intro.Stage
import dev.steven.s3control.ui.intro.drawFilm
import dev.steven.s3control.ui.theme.NightRide

/**
 * Debug-only: times the recording of the film's draw commands (no rasterising) over the whole film, per second of the film.
 * adb shell am start -n dev.steven.yaba/dev.steven.s3control.FilmBenchActivity ; adb logcat -s FilmBench
 */
class FilmBenchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val world = FilmWorld.build()
            val frames = FilmFrames("AUW", "E42")
            val density = Density(dm.density)
            val scope = CanvasDrawScope()
            val picture = Picture()
            fun record(t: Float) {
                val c = picture.beginRecording(w, h)
                scope.draw(density, LayoutDirection.Ltr, Canvas(c), Size(w.toFloat(), h.toFloat())) {
                    drawFilm(t, Stage(0f, 0f, w.toFloat(), h.toFloat()), NightRide, world, frames, SceneOpts())
                }
                picture.endRecording()
            }
            val end = Film.T_END.toFloat()
            repeat(40) { record(it * end / 40f) } // warm up
            val sb = StringBuilder()
            // one row per second of the film, the last one cut at its end
            for (sec in 0..(Film.T_END - 1) / 1000) {
                val times = ArrayList<Long>()
                var t = sec * 1000f
                while (t < minOf(sec * 1000f + 1000f, end)) {
                    val t0 = System.nanoTime()
                    record(t)
                    times += System.nanoTime() - t0
                    t += 16.7f
                }
                times.sort()
                sb.append("s%02d p50=%.2fms max=%.2fms\n".format(sec, times[times.size / 2] / 1e6, times.last() / 1e6))
            }
            Log.i("FilmBench", "\n$sb")
        }.start()
    }
}
