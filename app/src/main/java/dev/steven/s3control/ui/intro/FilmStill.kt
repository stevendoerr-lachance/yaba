package dev.steven.s3control.ui.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.steven.s3control.R
import dev.steven.s3control.ui.theme.NightRide
import dev.steven.s3control.ui.theme.S3Palette
import dev.steven.s3control.ui.theme.S3Theme

/** The world and the tube's frames, built once: the matrix words are the call (AUW) and the bike's fault code (E42). */
@Composable
internal fun rememberFilmKit(): Pair<FilmWorld, FilmFrames> {
    val ouch = stringResource(R.string.intro_matrix_ouch)
    val error = stringResource(R.string.intro_matrix_error)
    val world = remember { FilmWorld.forStretch(1f) }
    val frames = remember(ouch, error) { FilmFrames(ouch, error) }
    return world to frames
}

/** One frame of the film at [t], for the preview scrubber, the debug activity and the golden frames. */
@Composable
internal fun FilmStill(
    t: Float,
    modifier: Modifier = Modifier,
    c: S3Palette = NightRide,
    opts: SceneOpts = SceneOpts(),
    stageOf: (Size) -> Stage = { Stage(0f, 0f, it.width, it.height) },
) {
    val (_, frames) = rememberFilmKit()
    Canvas(modifier.background(c.asphalt)) {
        val stage = stageOf(size)
        drawFilm(t, stage, c, FilmWorld.forStage(stage), frames, opts)
    }
}

/** A slider over the whole film: calibrate beats by eye. */
@Composable
internal fun FilmScrubber(modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(Film.T_S2.toFloat()) }
    Column(modifier.fillMaxSize().background(NightRide.asphalt)) {
        FilmStill(t, Modifier.weight(1f).fillMaxWidth())
        Text("${t.toInt()} ms", color = NightRide.paint, modifier = Modifier.padding(horizontal = 16.dp))
        Slider(t, { t = it }, valueRange = 0f..Film.T_END.toFloat(), modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Preview(widthDp = 412, heightDp = 900, showBackground = true)
@Composable
private fun FilmScrubberPreview() {
    S3Theme(darkTheme = true) { FilmScrubber() }
}
