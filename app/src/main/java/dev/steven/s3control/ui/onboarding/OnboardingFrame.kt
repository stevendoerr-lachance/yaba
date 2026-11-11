package dev.steven.s3control.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import dev.steven.s3control.ui.paneSplit
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Type
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The frame of every onboarding step. No screen here scrolls, ever: a step does not scroll, it takes things away.
 *
 * Each step hands over what it has (rail, hero tube, title, body, caption, fields, status, primary, secondaries) and
 * a LADDER: the order in which things go when the height runs short. The frame measures what the step always shows
 * (the fields, the status line, the buttons: composed once, measured once), measures the texts with a TextMeasurer
 * and computes what every rung would need. LadderMath picks the richest rung that fits the SETTLED height (the one the
 * keyboard is heading for, not the one it passes through). Only the chosen rung is composed: the slots of the other
 * rungs do not exist. The fields slot is composed on every pass, so typed text and an autofill session survive a rung
 * change, the keyboard, folding and unfolding.
 *
 * Cover: one column. The top group (rail, hero, title, body, caption) and the bottom group (fields, status, primary, secondaries)
 * are ONE block: what is left over goes above it (46 %) and between the groups (22 %, at most 40 dp), so a step with few controls
 * has no hole in the middle; above the keyboard nothing is left over. Inner screen: two panes split at the fold, each with its own
 * ladder. The left pane holds the whole story (rail, hero across the pane, title, body and the small line right under the body)
 * and is centred at 45 % of the height, its gaps growing with a tall screen; the right pane holds what is done (fields, status,
 * buttons) and is centred on the title block, so the eye goes straight across, and the keyboard only moves the right pane.
 *
 * The corner ([LocalOnboardingCorner], the language) stands at the top right of every step, over the cover's column or the right
 * pane; those keep its band free, and the ladder counts it like any other height.
 */

/** What a rung can take away. A step lists them in the order they go. */
internal sealed interface Drop {
    data object Caption : Drop
    data object Rail : Drop
    data object Hero60 : Drop
    data object HeroNone : Drop

    /** The body is no longer drawn; TalkBack still reads it with the title. */
    data object BodyToSemantics : Drop

    /** The title goes from `title` to `section` (the lockup of the Welcome to 20 sp). */
    data object TitleCompact : Drop

    /** The reserved two-line status slot takes only its natural height, and only when there is something to say. */
    data object StatusWhenNonEmpty : Drop

    /** Only while the keyboard is open: the primary button leaves, the keyboard's Done action submits. */
    data object PrimaryUnderIme : Drop
    data object AllSecondaries : Drop

    /** The title is no longer drawn; TalkBack still reads it (with the body). */
    data object TitleToSemantics : Drop
    data class Secondary(val id: String) : Drop
}

internal class FrameAction(val id: String, val content: @Composable () -> Unit)

/**
 * One title or a lockup of two lines (the Welcome: line 1 in the heavy cut, line 2 in the plain cut). [description] is what
 * TalkBack reads as the heading.
 */
internal class TitleSpec(val lines: List<String>, val description: String = lines.joinToString(" "), val lockup: Boolean = false) {
    constructor(text: String) : this(listOf(text), text, false)
}

/** [strongExtra] is a second paragraph in `bodyStrong` after an [androidx.compose.ui.unit.Dp] 16 gap (the first ride). */
internal class BodySpec(
    val text: String,
    val strongExtra: String? = null,
    val modifier: Modifier = Modifier,
    /** For the strong line only (the first ride writes itself in after the sentence). */
    val strongModifier: Modifier = Modifier,
)

internal class HeroSpec(val content: @Composable (Modifier) -> Unit)

internal class FrameSpec(
    /** Announced when the step appears (the sweep has ended). */
    val paneTitle: String,
    val title: TitleSpec,
    val ladder: List<Drop>,
    /** The step number 1..3 of four on the rail; null = no rail. */
    val rail: Int? = null,
    val hero: HeroSpec? = null,
    val body: BodySpec? = null,
    val caption: String? = null,
    val fields: (@Composable () -> Unit)? = null,
    val status: (@Composable () -> Unit)? = null,
    val statusEmpty: Boolean = true,
    val primary: (@Composable () -> Unit)? = null,
    val secondaries: List<FrameAction> = emptyList(),
)

/** [fold] and [originXPx] split the inner screen into its two panes (see [paneSplit]). */
@Immutable
internal class OnboardingEnv(val wide: Boolean, val fold: FoldingFeature?, val originXPx: Float)

/**
 * Tests (and previews) set this to pretend the keyboard is open: the host has already shrunk the frame by this much,
 * so the frame only has to know that the rung "primary under the keyboard" is allowed.
 */
internal val LocalImeOverrideDp = compositionLocalOf<Dp?> { null }

/**
 * What stands in the top corner of every step: the language ([dev.steven.s3control.ui.components.LanguageButton]), handed in
 * by the host. It sits at the top right of the cover's column and of the inner screen's right pane, aligned with their right
 * edge, and those panes keep a band of its height free at the top, so no rung ever draws under it (the left pane, the story,
 * is not under it and keeps its own top). First for TalkBack, as the top of a screen is.
 */
internal val LocalOnboardingCorner = compositionLocalOf<(@Composable () -> Unit)?> { null }

private enum class PaneKind { Single, Left, Right }

private class Part(val id: String, val h: Int, val gapAfter: Int)

private class Flags(
    val caption: Boolean,
    val rail: Boolean,
    val heroScale: Float,
    val body: Boolean,
    val titleCompact: Boolean,
    val titleVisible: Boolean,
    val statusCollapse: Boolean,
    val primary: Boolean,
    val allSecondaries: Boolean,
    val hidden: Set<String>,
)

private class RungMemory {
    var wide: Boolean? = null
    var single = 0
    var left = 0
    var right = 0

    fun get(kind: PaneKind) = when (kind) { PaneKind.Single -> single; PaneKind.Left -> left; PaneKind.Right -> right }
    fun set(kind: PaneKind, v: Int) = when (kind) { PaneKind.Single -> single = v; PaneKind.Left -> left = v; PaneKind.Right -> right = v }
}

private class TitleLayout(val styles: List<TextStyle>, val heightPx: Int, val maxLines: Int)

private fun Drop.appliesTo(kind: PaneKind): Boolean = when (this) {
    Drop.Caption, Drop.Rail, Drop.Hero60, Drop.HeroNone, Drop.BodyToSemantics, Drop.TitleCompact, Drop.TitleToSemantics -> kind != PaneKind.Right
    Drop.StatusWhenNonEmpty, Drop.PrimaryUnderIme, Drop.AllSecondaries, is Drop.Secondary -> kind != PaneKind.Left
}

private fun flagsFor(ladder: List<Drop>, rung: Int, imeOpen: Boolean): Flags {
    var caption = true
    var rail = true
    var hero = 1f
    var body = true
    var compact = false
    var titleVisible = true
    var statusCollapse = false
    var primary = true
    var allSecondaries = false
    val hidden = HashSet<String>()
    for (i in 0 until rung.coerceAtMost(ladder.size)) {
        when (val d = ladder[i]) {
            Drop.Caption -> caption = false
            Drop.Rail -> rail = false
            Drop.Hero60 -> hero = 0.6f
            Drop.HeroNone -> hero = 0f
            Drop.BodyToSemantics -> body = false
            Drop.TitleCompact -> compact = true
            Drop.StatusWhenNonEmpty -> statusCollapse = true
            Drop.PrimaryUnderIme -> if (imeOpen) primary = false
            Drop.AllSecondaries -> allSecondaries = true
            Drop.TitleToSemantics -> titleVisible = false
            is Drop.Secondary -> hidden += d.id
        }
    }
    return Flags(caption, rail, hero, body, compact, titleVisible, statusCollapse, primary, allSecondaries, hidden)
}

private fun total(parts: List<Part>): Int =
    if (parts.isEmpty()) 0 else parts.sumOf { it.h } + parts.dropLast(1).sumOf { it.gapAfter }

/**
 * The title's styles and height at [widthPx]. The size shrinks in 2 sp steps (never below [minSp]) until no word is broken
 * in the middle and the lines fit; a lockup keeps every line on one line. The same styles are drawn, so what is measured
 * is what is shown.
 */
private fun resolveTitle(
    measurer: TextMeasurer,
    title: TitleSpec,
    compact: Boolean,
    wide: Boolean,
    widthPx: Int,
    type: S3Type,
    density: Density,
): TitleLayout {
    val first = when {
        compact -> type.section
        wide -> type.display
        else -> type.title
    }
    val base = first.fontSize.value
    val ratio = first.lineHeight.value / base
    val minSp = when {
        compact -> 16f
        wide -> 28f
        else -> 22f
    }
    val maxLines = if (title.lockup) 1 else 3
    fun stylesAt(size: Float): List<TextStyle> {
        val s1 = first.copy(fontSize = size.sp, lineHeight = (size * ratio).sp)
        return title.lines.mapIndexed { i, _ ->
            if (i == 0) s1 else type.body.copy(fontSize = s1.fontSize, lineHeight = s1.lineHeight)
        }
    }
    var size = base
    while (true) {
        val styles = stylesAt(size)
        val results = title.lines.mapIndexed { i, line ->
            measurer.measure(line, styles[i], maxLines = maxLines, constraints = Constraints(maxWidth = widthPx), density = density)
        }
        val wordsFit = title.lines.withIndex().all { (i, line) ->
            line.split(' ', '\n').filter { it.isNotEmpty() }.all { word ->
                measurer.measure(word, styles[i], softWrap = false, maxLines = 1, density = density).size.width <= widthPx
            }
        }
        val fits = results.none { it.hasVisualOverflow } && wordsFit
        if (fits || size - 2f < minSp) return TitleLayout(styles, results.sumOf { it.size.height }, maxLines)
        size -= 2f
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnboardingFrame(spec: FrameSpec, env: OnboardingEnv, modifier: Modifier = Modifier) {
    val type = S3.type
    val measurer = rememberTextMeasurer(cacheSize = 48)
    val memory = remember { RungMemory() }
    val imeOverride = LocalImeOverrideDp.current
    val corner = LocalOnboardingCorner.current
    val safe = WindowInsets.safeDrawing
    val imeTarget = WindowInsets.imeAnimationTarget
    val barsAndCutout = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    SubcomposeLayout(
        modifier.semantics {
            isTraversalGroup = true
            paneTitle = spec.paneTitle
        },
    ) { constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val dens: Density = this
        val wide = env.wide
        if (memory.wide != wide) {
            memory.wide = wide
            memory.single = 0; memory.left = 0; memory.right = 0
        }

        // --- the height that is left once the keyboard has settled -------------------------------------------
        val settledH: Int
        val imeOpen: Boolean
        if (imeOverride != null) {
            settledH = h
            imeOpen = imeOverride > 0.dp
        } else {
            val liveBottom = safe.getBottom(this)
            val baseBottom = barsAndCutout.getBottom(this)
            val targetBottom = imeTarget.getBottom(this)
            settledH = (h + liveBottom - max(baseBottom, targetBottom)).coerceAtLeast(0)
            imeOpen = targetBottom > baseBottom
        }

        // --- panes -------------------------------------------------------------------------------------------
        val gutter = 20.dp.roundToPx()
        val padSide = 24.dp.roundToPx()
        val padTop = 24.dp.roundToPx()
        val padBottom = 16.dp.roundToPx()
        val minGap = 16.dp.roundToPx()
        val gap8 = 8.dp.roundToPx()
        // The story of a tall inner screen breathes: its gaps grow with the settled height (up to 1.5x at 960 dp), so the pane is
        // not a small block in the middle of a big empty one. The cover and a short window keep the plain rhythm.
        val gapScale = if (wide) (settledH / dens.density / 640f).coerceIn(1f, 1.5f) else 1f
        val gap12 = (12.dp.toPx() * gapScale).roundToInt()
        val gap16 = (16.dp.toPx() * gapScale).roundToInt()
        val gap24 = (24.dp.toPx() * gapScale).roundToInt()

        val topKind: PaneKind
        val bottomKind: PaneKind
        val topX: Int
        val topW: Int
        val bottomX: Int
        val bottomW: Int
        if (!wide) {
            topKind = PaneKind.Single; bottomKind = PaneKind.Single
            topX = gutter; topW = (w - 2 * gutter).coerceAtLeast(0)
            bottomX = topX; bottomW = topW
        } else {
            val split = paneSplit(env.fold, env.originXPx, w.toDp(), this)
            val leftEnd = split.left.roundToPx()
            val rightStart = leftEnd + split.hinge.roundToPx()
            topKind = PaneKind.Left; bottomKind = PaneKind.Right
            topX = padSide; topW = (leftEnd - 2 * padSide).coerceAtLeast(0)
            bottomX = rightStart + padSide; bottomW = (w - rightStart - 2 * padSide).coerceAtLeast(0)
        }

        // --- what a step always shows: composed once, measured once ------------------------------------------
        fun fixedSlot(id: String, index: Int, content: @Composable () -> Unit): Placeable =
            subcompose(id) { Slot(index, content) }.first().measure(Constraints(maxWidth = bottomW))

        val fields = spec.fields?.let { fixedSlot("fields", 5, it) }
        val status = spec.status?.let { fixedSlot("status", 6, it) }
        val primary = spec.primary?.let { fixedSlot("primary", 7, it) }
        val secondaries = spec.secondaries.mapIndexed { i, a -> a.id to fixedSlot("sec:${a.id}", 8 + i, a.content) }.toMap()
        val cornerSlot = corner?.let { fixedSlot("corner", -1, it) }
        // The corner's band: its height and 8 dp under it, at least the plain top padding.
        val cornerTop = cornerSlot?.let { max(padTop, it.height + gap8) } ?: padTop
        fun padTopFor(kind: PaneKind): Int = if (kind == PaneKind.Left) padTop else cornerTop
        val statusReserved = (type.caption.lineHeight.toPx() * 2).roundToInt()

        // --- what the texts need ----------------------------------------------------------------------------
        val titleLayouts = HashMap<Boolean, TitleLayout>()
        fun titleLayout(compact: Boolean) = titleLayouts.getOrPut(compact) {
            resolveTitle(measurer, spec.title, compact, wide, topW, type, dens)
        }
        val bodyH = spec.body?.let { b ->
            var hgt = measurer.measure(b.text, type.body, constraints = Constraints(maxWidth = topW), density = dens).size.height
            if (b.strongExtra != null) {
                hgt += gap16 + measurer.measure(b.strongExtra, type.bodyStrong, constraints = Constraints(maxWidth = topW), density = dens).size.height
            }
            hgt
        } ?: 0
        val captionW = topW
        val captionH = spec.caption?.let {
            measurer.measure(it, type.caption, constraints = Constraints(maxWidth = captionW), density = dens).size.height
        } ?: 0
        val railH = 20.dp.roundToPx()

        fun topParts(kind: PaneKind, f: Flags): List<Part> = buildList {
            if (kind == PaneKind.Right) return@buildList
            if (spec.rail != null && f.rail) add(Part("rail", railH, gap16))
            if (spec.hero != null && f.heroScale > 0f) add(Part("hero", tubeHeightPx((topW * f.heroScale).roundToInt(), dens), gap24))
            if (f.titleVisible) add(Part("title", titleLayout(f.titleCompact).heightPx, gap16))
            if (spec.body != null && f.body) add(Part("body", bodyH, gap12))
            if (spec.caption != null && f.caption) add(Part("caption", captionH, 0))
        }

        fun bottomParts(kind: PaneKind, f: Flags): List<Part> = buildList {
            if (kind == PaneKind.Left) return@buildList
            if (fields != null) add(Part("fields", fields.height, gap8))
            if (status != null) {
                val sh = when {
                    f.statusCollapse -> if (spec.statusEmpty) 0 else status.height
                    else -> max(status.height, statusReserved)
                }
                if (sh > 0) add(Part("status", sh, gap8))
            }
            if (primary != null && f.primary) add(Part("primary", primary.height, gap8))
            // With the keyboard open the secondary buttons are not offered (the keyboard's Done action submits).
            if (!f.allSecondaries && !imeOpen) {
                spec.secondaries.forEach { a -> if (a.id !in f.hidden) add(Part("sec:${a.id}", secondaries.getValue(a.id).height, gap8)) }
            }
        }

        fun needPx(kind: PaneKind, f: Flags): Int {
            val top = total(topParts(kind, f))
            val bottom = total(bottomParts(kind, f))
            return padTopFor(kind) + top + (if (top > 0 && bottom > 0) minGap else 0) + bottom + padBottom
        }

        fun choose(kind: PaneKind): Flags {
            val ladder = spec.ladder.filter { it.appliesTo(kind) }
            val needs = FloatArray(ladder.size + 1) { k -> needPx(kind, flagsFor(ladder, k, imeOpen)) / dens.density }
            val k = LadderMath.choose(needs, settledH / dens.density, memory.get(kind))
            memory.set(kind, k)
            return flagsFor(ladder, k, imeOpen)
        }

        val topFlags = choose(topKind)
        val bottomFlags = if (bottomKind == topKind) topFlags else choose(bottomKind)

        // --- the rung that fits: compose only what it shows ---------------------------------------------------
        val topList = topParts(topKind, topFlags)
        val bottomList = bottomParts(bottomKind, bottomFlags)
        val variable = HashMap<String, Placeable>()
        for (part in topList + bottomList) {
            val id = part.id
            when (id) {
                "rail" -> variable[id] = subcompose("rail") { Slot(0) { Rail(spec.rail ?: 1) } }
                    .first().measure(Constraints(maxWidth = topW))
                "hero" -> {
                    val heroW = (topW * topFlags.heroScale).roundToInt().toDp()
                    variable[id] = subcompose("hero") { Slot(1) { spec.hero?.content?.invoke(Modifier.width(heroW)) } }
                        .first().measure(Constraints(maxWidth = topW))
                }
                "title" -> {
                    val layout = titleLayout(topFlags.titleCompact)
                    val spoken = spokenWithTitle(spec, topFlags)
                    val description = when {
                        spoken != null -> spec.title.description + spoken
                        spec.title.lockup -> spec.title.description
                        else -> null
                    }
                    variable[id] = subcompose("title") { Slot(2) { TitleBlock(spec.title, layout, description) } }
                        .first().measure(Constraints(maxWidth = topW))
                }
                "body" -> variable[id] = subcompose("body") { Slot(3) { spec.body?.let { BodyBlock(it) } } }
                    .first().measure(Constraints(maxWidth = topW))
                "caption" -> variable[id] = subcompose("caption") { Slot(4) { spec.caption?.let { CaptionBlock(it) } } }
                    .first().measure(Constraints(maxWidth = captionW))
            }
        }
        // The title that is not drawn is still a heading for TalkBack: a node of 1 dp.
        val titleAside = if (!topFlags.titleVisible && topKind != PaneKind.Right) {
            val description = spec.title.description + (spokenWithTitle(spec, topFlags, titleGone = true) ?: "")
            subcompose("title") {
                Slot(2) { Box(Modifier.size(1.dp).semantics { heading(); contentDescription = description }) }
            }.first().measure(Constraints())
        } else null

        fun placeable(id: String): Placeable = variable[id]
            ?: when (id) {
                "fields" -> fields!!
                "status" -> status!!
                "primary" -> primary!!
                else -> secondaries.getValue(id.removePrefix("sec:"))
            }

        // --- place ------------------------------------------------------------------------------------------
        val topTotal = total(topList)
        val bottomTotal = total(bottomList)
        val both = topTotal > 0 && bottomTotal > 0
        val topPad = padTopFor(topKind)
        val bottomPad = padTopFor(bottomKind)
        val free = h - topPad - padBottom - topTotal - bottomTotal - (if (both) minGap else 0)

        layout(w, h) {
            // Where the top group starts. Inner screen: the story is centred at 45 % of the height (a little above the middle, where
            // the eye rests), and goes up only as far as it must. Cover: the two groups are ONE block. What is left over goes
            // above it (46 %) and between the groups (the smaller share, at most 40 dp): a step with few controls does not
            // pin its text to the top and its buttons to the bottom with a hole between them. With the keyboard open there is
            // no leftover and the groups stand as tight as they always did.
            val betweenExtra: Int
            var y: Int
            if (wide) {
                betweenExtra = 0
                y = (h * 0.45f - topTotal / 2f).roundToInt().coerceAtLeast(padTop).coerceAtMost(max(padTop, h - padBottom - topTotal))
            } else if (both) {
                val spare = free.coerceAtLeast(0)
                betweenExtra = min(spare * 0.22f, 40.dp.toPx()).roundToInt()
                y = topPad + ((spare - betweenExtra) * 0.46f).roundToInt()
            } else {
                betweenExtra = 0
                y = topPad + (free.coerceAtLeast(0) * 0.46f).roundToInt()
            }
            titleAside?.placeRelative(topX, y)
            var textTop = -1
            var textBottom = y
            for (part in topList) {
                val p = placeable(part.id)
                if (textTop < 0 && (part.id == "title" || part.id == "body")) textTop = y
                p.placeRelative(topX, y)
                y += p.height + part.gapAfter
                if (part.id != "rail" && part.id != "hero") textBottom = y - part.gapAfter
            }
            val topEnd = if (topList.isEmpty()) topPad else y - topList.last().gapAfter
            if (bottomList.isNotEmpty()) {
                var by: Int
                if (wide) {
                    // the right pane is centred on the title block (the sentence the form belongs to); with no text on the left, on the pane
                    val centre = if (textTop >= 0) (textTop + textBottom) / 2 else if (topList.isEmpty()) (h * 0.45f).roundToInt() else (padTop + topEnd) / 2
                    by = (centre - bottomTotal / 2).coerceIn(bottomPad, max(bottomPad, h - padBottom - bottomTotal))
                } else if (topList.isNotEmpty()) {
                    // On the cover the two groups must not touch, even while the keyboard is still moving.
                    by = max(topEnd + minGap + betweenExtra, bottomPad)
                } else {
                    by = max(h - padBottom - bottomTotal, bottomPad)
                }
                for (part in bottomList) {
                    val p = placeable(part.id)
                    p.placeRelative(bottomX, by)
                    by += part.h + part.gapAfter
                }
            }
            // the corner: flush with the right edge of the cover's column or the right pane, at the top
            cornerSlot?.placeRelative(bottomX + bottomW - cornerSlot.width, 0)
        }
    }
}

/**
 * What TalkBack reads after the title when the rung no longer draws something: the body (with its strong line) and the small
 * line. Null when nothing was dropped. With [titleGone] the title is not drawn either, and the sentence follows the heading
 * as one text.
 */
private fun spokenWithTitle(spec: FrameSpec, f: Flags, titleGone: Boolean = false): String? {
    val sb = StringBuilder()
    val body = spec.body
    if (body != null && (!f.body || titleGone)) {
        sb.append(". ").append(body.text)
        body.strongExtra?.let { sb.append(' ').append(it) }
    }
    val caption = spec.caption
    if (caption != null && !f.caption) sb.append(". ").append(caption)
    return if (sb.isEmpty()) null else sb.toString()
}

/** Wraps a slot so TalkBack reads the frame in the order [index] says, whatever order the slots were composed in. */
@Composable
private fun Slot(index: Int, content: @Composable () -> Unit) {
    Box(
        Modifier.semantics {
            isTraversalGroup = true
            traversalIndex = index.toFloat()
        },
    ) { content() }
}

@Composable
private fun TitleBlock(title: TitleSpec, layout: TitleLayout, description: String?) {
    Column(
        Modifier.semantics(mergeDescendants = true) {
            heading()
            if (description != null) contentDescription = description
        },
    ) {
        title.lines.forEachIndexed { i, line ->
            Text(
                line,
                style = layout.styles[i],
                color = S3.colors.paint,
                maxLines = layout.maxLines,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun BodyBlock(body: BodySpec) {
    Column(body.modifier) {
        Text(body.text, style = S3.type.body, color = S3.colors.paint)
        if (body.strongExtra != null) {
            Spacer(Modifier.height(S3.m))
            Text(body.strongExtra, style = S3.type.bodyStrong, color = S3.colors.paint, modifier = body.strongModifier)
        }
    }
}

@Composable
private fun CaptionBlock(text: String) {
    Text(text, style = S3.type.caption, color = S3.colors.fog)
}
