package dev.steven.s3control.ui.components

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.steven.s3control.R
import dev.steven.s3control.audio.BellConverter
import dev.steven.s3control.audio.BundledBell
import dev.steven.s3control.ui.theme.S3
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- Preview on the phone -------------------------------------------------------------------------

/**
 * Plays bundled bells on the phone, one at a time: starting one stops the one before. Takes
 * transient focus (music ducks and comes back) and stops when another app takes the audio.
 */
@Stable
class BellPreview internal constructor(context: Context) {
    private val app = context.applicationContext
    private val audio: AudioManager = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(
            { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
            },
            Handler(Looper.getMainLooper()),
        )
        .build()
    private var player: MediaPlayer? = null

    /** The bell sounding on the phone right now. */
    var playing by mutableStateOf<BundledBell?>(null)
        private set

    /** The last preview started with media volume at zero, so there was nothing to hear. */
    var muted by mutableStateOf(false)
        private set

    /** The last preview could not be played at all. */
    var failed by mutableStateOf(false)
        private set

    fun toggle(bell: BundledBell) {
        if (playing == bell) stop() else play(bell)
    }

    fun play(bell: BundledBell) {
        stop()
        val mp = runCatching { MediaPlayer.create(app, bell.raw, attributes, audio.generateAudioSessionId()) }.getOrNull()
        failed = mp == null
        if (mp == null) return
        // Refused during a phone call: stay quiet rather than talk over it.
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mp.release()
            return
        }
        mp.setOnCompletionListener { if (player === it) stop() }
        mp.setOnErrorListener { p, _, _ ->
            if (player === p) {
                failed = true
                stop()
            }
            true
        }
        muted = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        // Muted: show the system's media slider where the finger is (the hint above may be scrolled
        // out of view; TalkBack reads the slider too). Shows only, changes nothing.
        if (muted) {
            runCatching {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            }
        }
        player = mp
        playing = bell
        mp.start()
    }

    /** Playback position in ms, 0 when nothing plays. Cheap enough to read once per frame. */
    fun positionMs(): Int = player?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: 0

    fun stop() {
        val p = player ?: return
        player = null
        playing = null
        p.setOnCompletionListener(null)
        p.setOnErrorListener(null)
        runCatching { p.stop() }
        p.release()
        audio.abandonAudioFocusRequest(focus)
    }
}

/** A [BellPreview] that falls silent when the app goes to the background and is released with the screen. */
@Composable
fun rememberBellPreview(): BellPreview {
    val context = LocalContext.current
    val preview = remember(context) { BellPreview(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { preview.stop() }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    return preview
}

// --- What the bike gets ---------------------------------------------------------------------------

/** Columns of the waveform in a bell row. */
const val BELL_WAVE_COLUMNS = 24

/** A bundled bell as the bike gets it: length after BellConverter's trim, and its waveform. */
@Immutable
class BundledBellFacts(val seconds: Float, val wave: MatrixFrame)

/** Per process: the files never change while the app runs. */
private val bellFacts = ConcurrentHashMap<BundledBell, BundledBellFacts>()

/**
 * Facts for every bundled bell, filled in one by one in the background (about a tenth of a second
 * each, once per process). Bells that could not be read are simply missing.
 */
@Composable
fun rememberBundledBellFacts(): Map<BundledBell, BundledBellFacts> {
    val app = LocalContext.current.applicationContext
    val facts by produceState<Map<BundledBell, BundledBellFacts>>(bellFacts.toMap(), app) {
        for (bell in BundledBell.entries) {
            if (bellFacts.containsKey(bell)) continue
            bellFacts[bell] = analyse(app, bell) ?: continue
            value = bellFacts.toMap()
        }
    }
    return facts
}

private suspend fun analyse(context: Context, bell: BundledBell): BundledBellFacts? = try {
    // The very conversion "Choose" runs later, so length and waveform match the card it opens.
    val result = BellConverter.convert(context, bell.uri(context))
    withContext(Dispatchers.Default) {
        BundledBellFacts(result.seconds, waveformFrame(result.file, BELL_WAVE_COLUMNS))
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** "1,9": one decimal, written exactly the way the custom bell card writes the length. */
fun bellSecondsNumber(seconds: Float, locale: Locale): String = String.format(locale, "%.1f", seconds)

/** "1.9 seconds" for TalkBack, in the plural form each language uses for a decimal number. */
private fun bellSecondsSpoken(seconds: Float, locale: Locale): String {
    val value = BigDecimal(String.format(Locale.ROOT, "%.1f", seconds))
    val number = NumberFormat.getInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE, number)
        .formatMeasures(Measure(value, MeasureUnit.SECOND))
}

// --- The row --------------------------------------------------------------------------------------

private val WaveWidth = 96.dp
private val WaveGap = 8.dp
private val GlyphSize = 24.dp
private val GlyphGap = 12.dp
private val EmojiGap = 10.dp

/**
 * One bundled bell as a plain row between hairlines: no frame, no fill. A small play glyph at the left edge, the
 * bell's emoji, the name, the waveform strip (fog; the columns already heard light in led while it plays) and the
 * caption that says where it sounds. Tapping the row plays it on the phone, a second tap stops it.
 * [onBike]: this bell is what the slot holds now (told in led, nothing to choose). [onChoose] null hides "Wählen",
 * which always keeps its place at the row's end: the name column takes what is left and wraps.
 */
@Composable
fun BundledBellRow(
    name: String,
    /** The bell's emoji ([SoundEmoji]). The owner asked for these on the bell list: keep them in a brand pass. */
    emoji: String,
    facts: BundledBellFacts?,
    playing: Boolean,
    positionMs: () -> Int,
    onBike: Boolean,
    chooseEnabled: Boolean,
    onPreview: () -> Unit,
    onChoose: (() -> Unit)?,
    chooseDescription: String,
    modifier: Modifier = Modifier,
    locale: Locale = currentLocale(),
) {
    val c = S3.colors
    val haptics = rememberHaptics()

    // Read in the draw phase only: the waveform redraws every frame, nothing recomposes.
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, facts) {
        progress = 0f
        val spanMs = (facts?.seconds ?: 0f) * 1000f
        if (!playing || spanMs <= 0f) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            progress = (positionMs() / spanMs).coerceIn(0f, 1f)
        }
    }

    val shown = facts?.let { stringResource(R.string.bells_seconds, bellSecondsNumber(it.seconds, locale)) }
    val description = facts?.let { stringResource(R.string.bells_row_cd, name, bellSecondsSpoken(it.seconds, locale)) } ?: name
    val caption = stringResource(
        when {
            playing -> R.string.bells_state_playing
            onBike -> R.string.bells_state_on_bike
            else -> R.string.bells_caption_phone
        },
    )
    val stateText = listOfNotNull(
        stringResource(R.string.bells_state_playing).takeIf { playing },
        stringResource(R.string.bells_state_on_bike).takeIf { onBike },
    ).joinToString(", ").ifEmpty { null }
    val actionLabel = stringResource(if (playing) R.string.bells_stop_action else R.string.bells_play_action)

    Row(modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = actionLabel) { haptics.tick(); onPreview() }
                .semantics {
                    contentDescription = description
                    if (stateText != null) stateDescription = stateText
                }
                .padding(top = 10.dp, bottom = 10.dp, end = S3.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayGlyph(playing, Modifier.width(GlyphSize))
            Spacer(Modifier.width(GlyphGap))
            SoundEmoji(emoji)
            Spacer(Modifier.width(EmojiGap))
            // The row reads name and spoken length as one; the parts stay silent.
            Column(Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(
                    name,
                    style = S3.type.bodyStrong,
                    color = if (onBike) c.led else c.paint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BellWave(facts?.wave, playing, { progress }, Modifier.weight(1f, fill = false).width(WaveWidth))
                    Spacer(Modifier.width(WaveGap))
                    Text(shown.orEmpty(), style = S3.type.label, color = c.fog, maxLines = 1, softWrap = false)
                }
                Text(
                    caption,
                    style = S3.type.caption,
                    color = if (playing || onBike) c.led else c.fog,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onChoose != null) {
            UnderlinedAction(
                stringResource(R.string.bells_choose),
                onClick = onChoose,
                enabled = chooseEnabled,
                description = chooseDescription,
            )
        }
    }
}

/**
 * Play or stop as a 5×5 patch of the matrix: a triangle while quiet, a square while it plays. Lit dots in fog,
 * in led while the bell sounds; the rest are ghost dots like the top tube's.
 */
@Composable
private fun PlayGlyph(playing: Boolean, modifier: Modifier = Modifier) {
    val c = S3.colors
    val lit = if (playing) c.led else c.fog
    val ghost = c.ledOff
    val columns = if (playing) StopColumns else PlayColumns
    Canvas(modifier.aspectRatio(1f).clearAndSetSemantics { }) {
        val pitch = size.width / 5
        val r = pitch * 0.34f
        for (x in 0 until 5) {
            for (y in 0 until 5) {
                val center = Offset(pitch * (x + 0.5f), pitch * (y + 0.5f))
                if (columns[x] and (1 shl y) != 0) drawCircle(lit, r, center) else drawCircle(ghost, r * 0.78f, center)
            }
        }
    }
}

private val PlayColumns = intArrayOf(0, 0b11111, 0b01110, 0b00100, 0)
private val StopColumns = intArrayOf(0, 0b01110, 0b01110, 0b01110, 0)

/**
 * The bell's waveform on a strip of the top-tube matrix, same dots as [MatrixDisplay], in fog. While it
 * plays, the part already heard is lit LED-blue. Blank until the bell has been analysed, then it
 * sweeps in from the left like the bike's display.
 */
@Composable
private fun BellWave(
    frame: MatrixFrame?,
    playing: Boolean,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val c = S3.colors
    val lit = c.led
    val idle = c.fog
    val unlit = c.ledOff
    val glow = c.ledGlow
    val reduceMotion = rememberReducedMotion()
    val reveal = remember { Animatable(if (frame != null) 1f else 0f) }
    LaunchedEffect(frame != null) {
        if (frame == null || reveal.value >= 1f) return@LaunchedEffect
        if (reduceMotion) reveal.snapTo(1f) else reveal.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.aspectRatio(BELL_WAVE_COLUMNS / MatrixFont.ROWS.toFloat())) {
        val pitch = size.width / BELL_WAVE_COLUMNS
        val r = pitch * 0.34f
        val head = if (playing) progress() * BELL_WAVE_COLUMNS else 0f
        val shownColumns = reveal.value * (BELL_WAVE_COLUMNS + 2)
        for (x in 0 until BELL_WAVE_COLUMNS) {
            val mask = if (frame != null && x < shownColumns) frame.columns.getOrElse(x) { 0 } else 0
            val heard = x < head
            for (y in 0 until MatrixFont.ROWS) {
                val center = Offset(pitch * (x + 0.5f), pitch * (y + 0.5f))
                if (mask and (1 shl y) != 0) {
                    if (heard && glow.alpha > 0f) drawCircle(glow, r * 1.55f, center)
                    drawCircle(if (heard) lit else idle, r, center)
                } else {
                    drawCircle(unlit, r * 0.78f, center)
                }
            }
        }
    }
}

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
