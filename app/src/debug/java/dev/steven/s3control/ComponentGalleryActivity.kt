package dev.steven.s3control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ble.LightMode
import dev.steven.s3control.ble.SOUNDS
import dev.steven.s3control.ble.SpeedLimit
import dev.steven.s3control.ui.components.BigButton
import dev.steven.s3control.ui.components.BikeTube
import dev.steven.s3control.ui.components.ChoiceBar
import dev.steven.s3control.ui.components.ErrorOrServerNotice
import dev.steven.s3control.ui.components.Fact
import dev.steven.s3control.ui.components.Heat
import dev.steven.s3control.ui.components.LinkIndicator
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import dev.steven.s3control.ui.components.OutburstTube
import dev.steven.s3control.ui.components.PowerLadder
import dev.steven.s3control.ui.components.QuietButton
import dev.steven.s3control.ui.components.SectionTitle
import dev.steven.s3control.ui.components.SoundPad
import dev.steven.s3control.ui.components.SpeedSign
import dev.steven.s3control.ui.components.UnlockSlider
import dev.steven.s3control.ui.components.YabaWordmark
import dev.steven.s3control.ui.components.coverUntilSentence
import dev.steven.s3control.ui.components.rememberLexicon
import dev.steven.s3control.ui.components.rememberOutburstStage
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Theme

/** Debug-only gallery of the design-system components. adb shell am start -n dev.steven.s3control/.ComponentGalleryActivity --ez light false */
class ComponentGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val light = intent.getBooleanExtra("light", false)
        setContent {
            S3Theme(darkTheme = !light) {
                var limit by remember { mutableIntStateOf(1) }
                var level by remember { mutableIntStateOf(4) }
                var lightMode by remember { mutableIntStateOf(0) }
                var locked by remember { mutableStateOf(true) }
                Column(
                    Modifier.fillMaxSize().background(S3.colors.asphalt).windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState()).padding(horizontal = S3.gutter),
                    verticalArrangement = Arrangement.spacedBy(S3.l),
                ) {
                    Spacer(Modifier.height(S3.m))
                    Text("My S3", style = S3.type.display, color = S3.colors.paint)
                    LinkIndicator(stringResource(R.string.core_gallery_connected), S3.colors.led, pulsing = false)
                    BikeTube(
                        frame = MatrixFrame.text("${MatrixFont.LOCKED} 87%") + MatrixFrame.gap(2) + MatrixFrame.bar(0.87f, 10),
                        lightsOn = lightMode == 1,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Fact(stringResource(R.string.core_gallery_battery), stringResource(R.string.core_gallery_percent, 87))
                        Fact(stringResource(R.string.core_gallery_distance), stringResource(R.string.core_gallery_distance_value, 4710.5))
                        Fact(stringResource(R.string.core_gallery_level), "4")
                        Fact(stringResource(R.string.core_gallery_limit), stringResource(R.string.core_detail_speed, 32))
                    }
                    UnlockSlider(locked = locked, busy = false, onUnlock = { locked = false }, onLock = { locked = true })
                    SectionTitle(stringResource(R.string.core_gallery_speed_limit))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.m)) {
                        listOf(SpeedLimit.JP, SpeedLimit.EU, SpeedLimit.US).forEachIndexed { i, sign ->
                            SpeedSign(sign.kmh, sign.region, selected = limit == i, onClick = { limit = i }, modifier = Modifier.weight(1f).widthIn(max = 110.dp))
                        }
                    }
                    SectionTitle(stringResource(R.string.core_gallery_assist))
                    PowerLadder(level, 4, onSelect = { level = it })
                    SectionTitle(stringResource(R.string.core_gallery_light))
                    ChoiceBar(LightMode.entries.map { it.code to it.label }, lightMode, onSelect = { lightMode = it })
                    SectionTitle(stringResource(R.string.core_gallery_soundboard))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S3.s)) {
                        SOUNDS.filter { it.id == 0x16 || it.id == 0x18 }.forEach { SoundPad(it.label, it.emoji, {}, Modifier.weight(1f)) }
                    }
                    BigButton(stringResource(R.string.core_gallery_send_bell), {})
                    QuietButton(stringResource(R.string.core_gallery_search_again), {}, Modifier.fillMaxWidth())
                    SectionTitle("Grummeln")
                    GrummelPreview()
                    // A real failure of VanMoof's server, as the app words it: counted like one (caps, spacing, session).
                    ErrorOrServerNotice(stringResource(R.string.core_api_server_error, 503), event = remember { Any() }, onRetry = {})
                    Spacer(Modifier.height(S3.xl))
                }
            }
        }
    }
}

/**
 * The call on the top tube, one button per word of the lexicon. Level 1 sighs, level 2 curses (two or three beats),
 * level 3 thunder (the rehearsal only). The paragraph under the tube is uncovered from the left after the silence.
 * Caps and spacing do not apply here; reduced motion, TalkBack and the drive lock still do (then the word just stands).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrummelPreview() {
    val stage = rememberOutburstStage(calm = YabaWordmark)
    val scope = rememberCoroutineScope()
    val words = rememberLexicon()
    Column(verticalArrangement = Arrangement.spacedBy(S3.m)) {
        OutburstTube(stage, lightsOn = false, description = null, columns = 36)
        Text(stringResource(R.string.bike_outbursts_caption), style = S3.type.body, color = S3.colors.paint, modifier = Modifier.coverUntilSentence(stage))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(S3.s), verticalArrangement = Arrangement.spacedBy(S3.s)) {
            words.forEach { word ->
                QuietButton(word.label, { stage.rehearseIn(scope, word.outburst, sentence = word.outburst.heat != Heat.Thunder) })
            }
        }
    }
}
