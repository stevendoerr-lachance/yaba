package dev.steven.s3control.ui.components

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.s3TypographyFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * The app language, wherever it is chosen: [LanguageList] (the options), [LanguageSheet] (the list over the screen) and
 * [LanguageButton] (a small top tube with the current language's two letters in its dots; it opens the sheet).
 *
 * The mechanism is the per-app language of Android 13+ ([I18n.setLanguage], the system's LocaleManager). The system stores the choice and recreates the activity; what the screen keeps in its saved
 * state comes back in the new language (the onboarding stays on its step, the opening film does not play again). Below
 * Android 13 the app follows the system language and the sheet says so.
 *
 * Nothing here knows the shell or a tab: the button can stand in any corner. It stands in the onboarding's corner, at the
 * end of the first line of every tab on the cover screen and at the end of the inner screen's tab row (ui/AppRoot.kt).
 */

/** One choice: [tag] "" follows the system ([endonym] null), otherwise a language and its name in itself. */
@Immutable
data class LanguageOption(val tag: String, val endonym: String?)

object AppLanguages {
    /** Follow the system first, then the seven languages, each in its own name (the list of [I18n.languages]). */
    val options: List<LanguageOption> = I18n.languages.map { (tag, name) -> LanguageOption(tag, name) }

    /** The name of [tag] in itself ("Deutsch", "Русский"), null for "" or a language the app does not offer. */
    fun endonym(tag: String): String? = options.firstOrNull { it.tag == tag }?.endonym

    /** The two letters the button shows ("DE"); the dot matrix has every one of them. */
    fun code(tag: String): String = tag.uppercase(Locale.ROOT)
}

/** The chosen app language: [selected] is "" while the app follows the system. */
@Stable
class AppLanguageState internal constructor(private val context: Context, initial: String) {
    var selected: String by mutableStateOf(initial)
        private set

    /** Hands [tag] to the system, which recreates the activity in that language. False below Android 13. */
    fun choose(tag: String): Boolean {
        selected = tag
        return I18n.setLanguage(context, tag)
    }
}

@Composable
fun rememberAppLanguage(): AppLanguageState {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppLanguageState(context, I18n.currentLanguage(context)) }
}

/**
 * The options as a vertical radio list: "System language" in the UI's language, then each language in its own name and its
 * own type (Русский in Roboto Flex, the Latin names in Archivo), the chosen one with a lit LED. Rows at least 48 dp with a
 * hairline between them, a haptic tick on a change; TalkBack reads a radio group and each name with its language's voice.
 */
@Composable
fun LanguageList(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val systemLabel = stringResource(R.string.language_system)
    Column(modifier.fillMaxWidth().selectableGroup()) {
        AppLanguages.options.forEachIndexed { i, option ->
            if (i > 0) Hairline()
            LanguageRow(
                label = option.endonym ?: systemLabel,
                tag = option.tag.takeIf { option.endonym != null },
                selected = option.tag == selected,
                onSelect = {
                    if (option.tag != selected) haptics.tick()
                    onSelect(option.tag)
                },
            )
        }
    }
}

/** One language: LED dot lit for the active one. [tag] marks the name's language (type and TalkBack voice). */
@Composable
private fun LanguageRow(label: String, tag: String?, selected: Boolean, onSelect: () -> Unit) {
    val c = S3.colors
    val type = if (tag == null) S3.type else s3TypographyFor(tag)
    val text = remember(label, tag) {
        if (tag == null) AnnotatedString(label) else AnnotatedString(label, SpanStyle(localeList = LocaleList(tag)))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedDot(if (selected) c.led else c.rib)
        Spacer(Modifier.width(10.dp))
        Text(text, style = if (selected) type.bodyStrong else type.body, color = c.paint)
    }
}

/** How long the lit LED stands on the new choice before the sheet goes and the system switches the language. */
private const val CHOICE_BEAT_MS = 180L

/**
 * The list over the screen: a panel in the bike's frame colour on a scrim of asphalt, at the bottom of the cover screen and in
 * the middle of the inner screen. It appears and goes as a cut (nothing in this app slides up). Back, a tap beside it or
 * Close leave it unchanged. A choice lights its LED, and a moment later the sheet closes and the system switches the
 * language; the current one closes it as it is.
 */
@Composable
fun LanguageSheet(onDismiss: () -> Unit) {
    val language = rememberAppLanguage()
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var picked by remember { mutableStateOf(language.selected) }
    var applying by remember { mutableStateOf(false) }
    val title = stringResource(R.string.language_title)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val c = S3.colors
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let { w ->
                w.setDimAmount(0f)
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    isAppearanceLightStatusBars = !c.isDark
                    isAppearanceLightNavigationBars = !c.isDark
                }
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(c.asphalt.copy(alpha = 0.86f))
                // a tap beside the panel closes it; no node for TalkBack (Back and Close do that there)
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            val wide = maxWidth >= 600.dp
            val shape = if (wide) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .align(if (wide) Alignment.Center else Alignment.BottomCenter)
                    .then(if (wide) Modifier.padding(S3.l) else Modifier)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .background(c.frame)
                    .border(1.dp, c.rib, shape)
                    // taps on the panel stay on the panel
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { paneTitle = title }
                    .then(if (wide) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                    .padding(start = S3.gutter, end = S3.gutter, top = S3.m, bottom = S3.m),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = S3.type.section, color = c.paint, modifier = Modifier.weight(1f).semantics { heading() })
                    Spacer(Modifier.width(S3.s))
                    QuietButton(stringResource(R.string.language_close), onDismiss)
                }
                Spacer(Modifier.height(S3.s))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (!I18n.canChooseLanguage) {
                        Hint(stringResource(R.string.language_follows_system), Modifier.padding(vertical = S3.s))
                    } else {
                        LanguageList(
                            selected = picked,
                            onSelect = { tag ->
                                when {
                                    applying -> Unit
                                    tag == picked -> onDismiss()
                                    else -> {
                                        applying = true
                                        picked = tag
                                        scope.launch {
                                            delay(CHOICE_BEAT_MS)
                                            dismiss()
                                            language.choose(tag)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The language in a corner: a top tube of its own, 58 × 32 dp in a 48 dp touch row, with the two letters of the language
 * the screen is in ("DE") in the matrix's dots (capitals are the matrix's, not a label's). With [showName] the language's
 * name in itself stands before it ("Deutsch"), for the first screen, where someone who does not read this language must
 * see at once that it can be changed. A tap opens [LanguageSheet]. TalkBack: "Sprache: Deutsch", double tap to choose.
 *
 * It shows the language the resources really are in ([R.string.language_tag]), also while the app follows a system
 * language it does not have (then English).
 */
@Composable
fun LanguageButton(modifier: Modifier = Modifier, showName: Boolean = false) {
    val c = S3.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val tag = stringResource(R.string.language_tag)
    val name = AppLanguages.endonym(tag) ?: AppLanguages.code(tag)
    val description = stringResource(R.string.language_current, name)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "language-button")
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.language_choose),
            ) { open = true }
            .clearAndSetSemantics { contentDescription = description }
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (showName) {
            Text(name, style = s3TypographyFor(tag).label, color = c.paint, maxLines = 1)
            Spacer(Modifier.width(S3.s))
        }
        LanguageTube(tag)
    }
    if (open) LanguageSheet(onDismiss = { open = false })
}

/** Dot pitch of the little tube: the inner screen's tab glyphs (17.5 dp high). */
private val TUBE_PITCH = 2.5.dp

/** Two letters of the matrix font and the column between them. "IT" (9 columns) stands centred in the same field. */
private const val TUBE_COLUMNS = 11

/**
 * The little tube's size, 57.5 × 32 dp: the button's visible part and, without [LanguageButton]'s name, its width. The shell
 * plans the inner screen's tab row and the cover screen's first lines with it.
 */
val LanguageTubeSize: DpSize = DpSize(TUBE_PITCH * TUBE_COLUMNS + 30.dp, TUBE_PITCH * MatrixFont.ROWS + 14.5.dp)

/** The two letters of [tag] on the bike's tube: its capsule and light bars, the letters lit, the rest of the field unlit dots. */
@Composable
private fun LanguageTube(tag: String, modifier: Modifier = Modifier) {
    val c = S3.colors
    val frame = remember(tag) { MatrixFrame.text(AppLanguages.code(tag)) }
    Canvas(modifier.size(LanguageTubeSize)) {
        drawTubeBody(c, Rect(0f, 0f, size.width, size.height), lamp = 0f, barWidthPx = 2.dp.toPx(), strokePx = 1.dp.toPx())
        val p = TUBE_PITCH.toPx()
        drawMatrixField(
            shown = frame,
            previous = frame,
            sweepCol = TUBE_COLUMNS + 2f,
            sweeping = false,
            columns = TUBE_COLUMNS,
            origin = Offset((size.width - p * TUBE_COLUMNS) / 2f, (size.height - p * MatrixFont.ROWS) / 2f),
            pitch = p,
            lit = c.led,
            unlit = c.ledOff,
            glow = c.ledGlow,
        )
    }
}
