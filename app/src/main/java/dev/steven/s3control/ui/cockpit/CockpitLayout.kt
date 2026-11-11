package dev.steven.s3control.ui.cockpit

import androidx.compose.runtime.Immutable
import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import kotlin.math.floor
import kotlin.math.min

/*
 * Where everything sits, as plain numbers in dp. No Compose layout here: the composables only place
 * what the plan says, so every window class can be checked on the JVM against its height.
 */

/** The four ways the cockpit splits a window. Book and Table split at the fold (plus its hinge). */
sealed interface CockpitWindow {
    data object CoverPortrait : CockpitWindow
    data object CoverLandscape : CockpitWindow
    data class Book(val splitX: Float, val hinge: Float) : CockpitWindow
    data class Table(val splitY: Float, val hinge: Float) : CockpitWindow
}

/** A fold relative to the cockpit root, in dp along its axis: [start]..[end] across x (vertical) or y. */
data class CockpitFold(val vertical: Boolean, val start: Float, val end: Float)

fun cockpitWindow(w: Float, h: Float, fold: CockpitFold?): CockpitWindow {
    if (fold != null && fold.end >= fold.start) {
        val axis = if (fold.vertical) w else h
        if (fold.start >= axis * 0.25f && fold.end <= axis * 0.75f) {
            val hinge = fold.end - fold.start
            return if (fold.vertical) CockpitWindow.Book(fold.start, hinge) else CockpitWindow.Table(fold.start, hinge)
        }
    }
    if (min(w, h) >= 600f) {
        return if (w <= 1.15f * h) CockpitWindow.Book(w / 2, 0f) else CockpitWindow.Table(h / 2, 0f)
    }
    return if (w > h) CockpitWindow.CoverLandscape else CockpitWindow.CoverPortrait
}

/** What a plan left out to make the mark capsule big enough, in the order it gave things up. */
enum class CockpitDrop { MEASURE, LAMP_VALUES, BATTERY_ROW, PITCH, ALERT_ONE_LINE, FACTS, LAMPS, UNIT_ROW, EXIT_ROW }

/**
 * Block sizes for one window. Text blocks are already multiplied by [text] (the clamped font scale).
 * [columnW] is the instrument column (cover) or the left pane/column; [sideW] the right one.
 */
@Immutable
data class CockpitPlan(
    val window: CockpitWindow,
    val pitch: Float,
    val text: Float,
    val gutter: Float,
    val top: Float,
    val bottom: Float,
    val gap: Float,
    val columnW: Float,
    val sideW: Float,
    val statusH: Float,
    val unitH: Float,
    val alertH: Float,
    val alertLines: Int,
    val batteryRow: Boolean,
    val batteryH: Float,
    val lampValues: Boolean,
    val lampH: Float,
    val measure: Boolean,
    val measureH: Float,
    val lamps: Boolean,
    val facts: Boolean,
    val factsH: Float,
    val recentRows: Int,
    val recentHeadH: Float,
    val recentRowH: Float,
    val capsuleW: Float,
    val capsuleH: Float,
    /** Cover landscape too short for the unit row: mode word and unit sit in the status row. */
    val unitInStatus: Boolean = false,
    /** Cover landscape too short for the exit row: the exit slot ends the alert row. */
    val exitInAlert: Boolean = false,
    /** Height the tallest column needs, padding included. Never more than the window height. */
    val usedH: Float,
    val dropped: List<CockpitDrop>,
) {
    val matrixW: Float get() = pitch * CockpitFrames.COLUMNS
    val matrixH: Float get() = pitch * MatrixFont.ROWS
    val recentH: Float get() = if (recentRows > 0) recentHeadH + recentRows * recentRowH else 0f
}

object CockpitSizes {
    const val P_MIN = 20f
    const val P_MAX = 40f
    const val CAPSULE_MIN_COVER = 120f
    const val CAPSULE_MIN_BOOK = 140f
    const val CAPSULE_MAX_BOOK = 400f
    /** A capsule is never taller than this share of its width: wider than tall, never a circle. */
    const val CAPSULE_RATIO = 0.6f
    const val EXIT_ROW = 48f
    const val UNIT_GAP = 4f
    const val LAMP_FULL = 68f
    const val LAMP_SHORT = 46f
    const val MEASURE = 24f
    const val BATTERY = 72f
    const val FACTS = 44f
    const val RECENT_HEAD = 26f
    /** Two lines of 16/22 plus air: a long log message wraps instead of being cut mid-word. */
    const val RECENT_ROW = 50f
}

private fun clampP(p: Float) = p.coerceIn(CockpitSizes.P_MIN, CockpitSizes.P_MAX)

/**
 * The plan for [window] at [w] × [h] dp (inside the safe drawing insets) and the user's [fontScale].
 * Text blocks grow with the font scale up to 1.3, the plan then drops blocks in the order of the spec.
 */
fun cockpitPlan(window: CockpitWindow, w: Float, h: Float, fontScale: Float): CockpitPlan {
    val f = fontScale.coerceIn(1f, 1.3f)
    return when (window) {
        CockpitWindow.CoverPortrait -> coverPortrait(w, h, f)
        CockpitWindow.CoverLandscape -> coverLandscape(w, h, f)
        is CockpitWindow.Book -> book(window, w, h, f)
        is CockpitWindow.Table -> table(window, w, h, f)
    }
}

private fun coverPortrait(w: Float, h: Float, f: Float): CockpitPlan {
    val gutter = 20f
    val top = 8f
    val bottom = 16f
    val gap = 16f
    val colW = w - 2 * gutter
    var p = clampP(colW / 11f)
    var measure = true
    var values = true
    var battery = true
    val dropped = ArrayList<CockpitDrop>()
    val status = 48f * f
    val unit = 28f * f
    val alert = 64f * f
    fun used(): Float {
        var sum = top + status + gap + 7 * p + CockpitSizes.UNIT_GAP + unit + gap + alert + gap
        if (battery) sum += CockpitSizes.BATTERY * f + gap
        sum += (if (values) CockpitSizes.LAMP_FULL else CockpitSizes.LAMP_SHORT) * f
        if (measure) sum += CockpitSizes.MEASURE * f
        return sum + gap + bottom
    }
    fun capsule() = h - used()
    val min = CockpitSizes.CAPSULE_MIN_COVER
    if (capsule() < min) { measure = false; dropped += CockpitDrop.MEASURE }
    if (capsule() < min) { values = false; dropped += CockpitDrop.LAMP_VALUES }
    if (capsule() < min) { battery = false; dropped += CockpitDrop.BATTERY_ROW }
    if (capsule() < min && p > CockpitSizes.P_MIN) {
        p = (p - (min - capsule()) / 7f).coerceAtLeast(CockpitSizes.P_MIN)
        dropped += CockpitDrop.PITCH
    }
    val cap = capsule().coerceAtLeast(0f)
    return CockpitPlan(
        window = CockpitWindow.CoverPortrait, pitch = p, text = f, gutter = gutter, top = top, bottom = bottom, gap = gap,
        columnW = colW, sideW = colW, statusH = status, unitH = unit, alertH = alert, alertLines = 2,
        batteryRow = battery, batteryH = CockpitSizes.BATTERY * f,
        lampValues = values, lampH = (if (values) CockpitSizes.LAMP_FULL else CockpitSizes.LAMP_SHORT) * f,
        measure = measure, measureH = CockpitSizes.MEASURE * f, lamps = true,
        facts = false, factsH = 0f, recentRows = 0, recentHeadH = 0f, recentRowH = 0f,
        capsuleW = colW, capsuleH = cap, usedH = used() + cap, dropped = dropped,
    )
}

private fun coverLandscape(w: Float, h: Float, f: Float): CockpitPlan {
    val gutter = 20f
    val colGap = 24f
    val top = 8f
    val bottom = 8f
    var status = 40f * f
    val unit = 28f * f
    val dropped = ArrayList<CockpitDrop>()
    var folded = false
    fun leftFixed() = top + status + 4f + (if (folded) 0f else unit + 4f) + bottom
    // Too short for 20 dp dots plus the unit row: the mode word and the unit move into the status
    // row, so the left column never runs past the window.
    if ((h - leftFixed()) / 7f < CockpitSizes.P_MIN) {
        folded = true
        dropped += CockpitDrop.UNIT_ROW
    }
    // Still too short with a large font: the status row stops growing with it (its text fits itself).
    if (folded && (h - leftFixed()) / 7f < CockpitSizes.P_MIN) status = 40f
    var p = clampP(min((w - 2 * gutter - colGap) / 2f / 11f, (h - leftFixed()) / 7f))
    var alert = 64f * f
    var alertLines = 2
    var exitInAlert = false
    var lamps = true
    val lampH = CockpitSizes.LAMP_SHORT * f
    fun rightUsed(): Float {
        var sum = top + (if (exitInAlert) maxOf(CockpitSizes.EXIT_ROW, alert) else CockpitSizes.EXIT_ROW + 8f + alert) + 8f
        if (lamps) sum += lampH + 12f
        return sum + bottom
    }
    fun capsule() = h - rightUsed()
    val min = CockpitSizes.CAPSULE_MIN_COVER
    if (capsule() < min) {
        alert = 52f * f
        alertLines = 1
        dropped += CockpitDrop.ALERT_ONE_LINE
    }
    if (capsule() < min) {
        exitInAlert = true
        dropped += CockpitDrop.EXIT_ROW
    }
    // Lamp names fit down to 12 sp (done by the lamp row). A narrow right column gets room back
    // from the matrix.
    var side = w - 2 * gutter - colGap - 11 * p
    if (side < 300f && p > CockpitSizes.P_MIN) {
        p = ((w - 2 * gutter - colGap - 300f) / 11f).coerceIn(CockpitSizes.P_MIN, p)
        side = w - 2 * gutter - colGap - 11 * p
        dropped += CockpitDrop.PITCH
    }
    if (capsule() < min) {
        lamps = false
        dropped += CockpitDrop.LAMPS
    }
    val cap = capsule().coerceAtLeast(0f)
    val leftUsed = leftFixed() + 7 * p
    return CockpitPlan(
        window = CockpitWindow.CoverLandscape, pitch = p, text = f, gutter = gutter, top = top, bottom = bottom, gap = colGap,
        columnW = 11 * p, sideW = side, statusH = status, unitH = if (folded) 0f else unit, alertH = alert, alertLines = alertLines,
        batteryRow = false, batteryH = 0f, lampValues = false, lampH = lampH, measure = false, measureH = 0f, lamps = lamps,
        facts = false, factsH = 0f, recentRows = 0, recentHeadH = 0f, recentRowH = 0f,
        capsuleW = side, capsuleH = cap, unitInStatus = folded, exitInAlert = exitInAlert,
        usedH = maxOf(leftUsed, rightUsed() + cap), dropped = dropped,
    )
}

private fun book(window: CockpitWindow.Book, w: Float, h: Float, f: Float): CockpitPlan {
    val pad = 24f
    val gap = 24f
    val leftW = window.splitX - 2 * pad
    val rightW = w - window.splitX - window.hinge - 2 * pad
    val paneH = h - 2 * pad
    var p = clampP(leftW / 11f)
    val status = 48f * f
    val unit = 28f * f
    val alert = 88f * f
    val battery = CockpitSizes.BATTERY * f
    var facts = true
    var measure = true
    var values = true
    val dropped = ArrayList<CockpitDrop>()
    fun leftUsed(): Float {
        var sum = status + 16f + 7 * p + CockpitSizes.UNIT_GAP + unit + gap + alert + gap + battery + gap
        sum += (if (values) CockpitSizes.LAMP_FULL else CockpitSizes.LAMP_SHORT) * f
        if (measure) sum += CockpitSizes.MEASURE * f
        if (facts) sum += gap + CockpitSizes.FACTS * f
        return sum
    }
    if (leftUsed() > paneH) { facts = false; dropped += CockpitDrop.FACTS }
    if (leftUsed() > paneH) { measure = false; dropped += CockpitDrop.MEASURE }
    if (leftUsed() > paneH) { values = false; dropped += CockpitDrop.LAMP_VALUES }
    if (leftUsed() > paneH && p > CockpitSizes.P_MIN) {
        p = (p - (leftUsed() - paneH) / 7f).coerceAtLeast(CockpitSizes.P_MIN)
        dropped += CockpitDrop.PITCH
    }
    val head = CockpitSizes.RECENT_HEAD * f
    val row = CockpitSizes.RECENT_ROW * f
    val fixedRight = CockpitSizes.EXIT_ROW + gap
    // The capsule first (capped at 0.6 of its width), the recent list takes what is left.
    val capMax = min(CockpitSizes.CAPSULE_MAX_BOOK, rightW * CockpitSizes.CAPSULE_RATIO)
    val cap = (paneH - fixedRight).coerceIn(0f, capMax)
    val rows = floor((paneH - fixedRight - cap - gap - head) / row).toInt().coerceIn(0, 6)
    val listH = if (rows > 0) head + rows * row + gap else 0f
    return CockpitPlan(
        window = window, pitch = p, text = f, gutter = pad, top = pad, bottom = pad, gap = gap,
        columnW = leftW, sideW = rightW, statusH = status, unitH = unit, alertH = alert, alertLines = 2,
        batteryRow = true, batteryH = battery,
        lampValues = values, lampH = (if (values) CockpitSizes.LAMP_FULL else CockpitSizes.LAMP_SHORT) * f,
        measure = measure, measureH = CockpitSizes.MEASURE * f, lamps = true,
        facts = facts, factsH = CockpitSizes.FACTS * f, recentRows = rows, recentHeadH = head, recentRowH = row,
        capsuleW = rightW, capsuleH = cap,
        usedH = 2 * pad + maxOf(leftUsed(), fixedRight + listH + cap), dropped = dropped,
    )
}

private fun table(window: CockpitWindow.Table, w: Float, h: Float, f: Float): CockpitPlan {
    val pad = 24f
    val gap = 24f
    val topH = window.splitY - 2 * pad
    val bottomH = h - window.splitY - window.hinge - 2 * pad
    val status = 40f * f
    val unit = 28f * f
    var p = clampP(min((w - 2 * pad - gap) / 2f / 11f, (topH - status - 4f - 4f - unit) / 7f))
    var alert = 88f * f
    var alertLines = 2
    var battery = true
    val dropped = ArrayList<CockpitDrop>()
    fun topRight() = CockpitSizes.EXIT_ROW + 16f + alert + (if (battery) 16f + CockpitSizes.BATTERY * f else 0f)
    if (topRight() > topH) { alert = 64f * f; dropped += CockpitDrop.ALERT_ONE_LINE }
    if (topRight() > topH) { battery = false; dropped += CockpitDrop.BATTERY_ROW }
    var measure = true
    var values = true
    fun lampsH() = (if (values) CockpitSizes.LAMP_FULL else CockpitSizes.LAMP_SHORT) * f + (if (measure) CockpitSizes.MEASURE * f else 0f)
    if (lampsH() > bottomH) { measure = false; dropped += CockpitDrop.MEASURE }
    if (lampsH() > bottomH) { values = false; dropped += CockpitDrop.LAMP_VALUES }
    val head = CockpitSizes.RECENT_HEAD * f
    val row = CockpitSizes.RECENT_ROW * f
    val rows = floor((bottomH - lampsH() - gap - head) / row).toInt().coerceIn(0, 4)
    fun leftTop() = status + 4f + 7 * p + 4f + unit
    if (leftTop() > topH && p > CockpitSizes.P_MIN) {
        p = (p - (leftTop() - topH) / 7f).coerceAtLeast(CockpitSizes.P_MIN)
        dropped += CockpitDrop.PITCH
    }
    val bottomUsed = lampsH() + (if (rows > 0) gap + head + rows * row else 0f)
    val topUsed = maxOf(window.splitY, 2 * pad + maxOf(topRight(), leftTop()))
    val leftW = 11 * p
    val rightW = w - 2 * pad - gap - leftW
    val cap = bottomH.coerceIn(0f, min(CockpitSizes.CAPSULE_MAX_BOOK, rightW * CockpitSizes.CAPSULE_RATIO))
    // The room the capped capsule leaves above it holds the connection facts.
    val facts = bottomH - cap - gap >= CockpitSizes.FACTS * f
    return CockpitPlan(
        window = window, pitch = p, text = f, gutter = pad, top = pad, bottom = pad, gap = gap,
        columnW = leftW, sideW = rightW, statusH = status, unitH = unit, alertH = alert, alertLines = alertLines,
        batteryRow = battery, batteryH = CockpitSizes.BATTERY * f,
        lampValues = values, lampH = (if (values) CockpitSizes.LAMP_FULL else CockpitSizes.LAMP_SHORT) * f,
        measure = measure, measureH = CockpitSizes.MEASURE * f, lamps = true,
        facts = facts, factsH = CockpitSizes.FACTS * f, recentRows = rows, recentHeadH = head, recentRowH = row,
        capsuleW = rightW, capsuleH = cap,
        usedH = topUsed + window.hinge + 2 * pad + maxOf(bottomUsed, cap + if (facts) gap + CockpitSizes.FACTS * f else 0f),
        dropped = dropped,
    )
}

/**
 * The cockpit matrix: always exactly 11 columns, cell A (5) | gap (1) | cell B (5). Narrow glyphs are
 * centred in their cell, so a speed going 9 → 10 or 19 → 20 never shifts a column.
 */
object CockpitFrames {
    const val COLUMNS = 11
    private const val CELL = 5

    /** C1 placeholder ship, bit 0 = top. Redraw from the tube's own shipping splash. */
    val SHIP = intArrayOf(0x48, 0x18, 0x78, 0x3C, 0x7E, 0x3F, 0x7E, 0x3C, 0x78, 0x18, 0x48)

    private fun glyph(ch: Char): IntArray = MatrixFont.render(ch.toString())

    private fun cell(ch: Char?): IntArray {
        val out = IntArray(CELL)
        if (ch == null) return out
        val g = glyph(ch)
        val offset = ((CELL - g.size) / 2).coerceAtLeast(0)
        for (i in g.indices) if (offset + i < CELL) out[offset + i] = g[i]
        return out
    }

    private fun pair(a: Char?, b: Char?): IntArray = cell(a) + IntArray(1) + cell(b)

    /** One glyph of any width placed at [offset] in an empty field. */
    private fun at(ch: Char, offset: Int): IntArray {
        val out = IntArray(COLUMNS)
        val g = glyph(ch)
        for (i in g.indices) if (offset + i in 0 until COLUMNS) out[offset + i] = g[i]
        return out
    }

    /** Right-aligned digits; below 10 cell A stays dark (ghost dots only). */
    fun speed(kmh: Int): IntArray {
        val v = kmh.coerceIn(0, 99)
        return if (v < 10) pair(null, '0' + v) else pair('0' + v / 10, '0' + v % 10)
    }

    fun dashes(): IntArray = pair('-', '-')

    fun bleQuery(): IntArray = pair(MatrixFont.BLE, '?')

    fun bleCentred(): IntArray = at(MatrixFont.BLE, 3)

    fun bar(fraction: Float): IntArray = MatrixFrame.bar(fraction, COLUMNS).columns

    fun ship(): IntArray = SHIP.copyOf()

    fun locked(): IntArray = at(MatrixFont.LOCKED, 2)

    /** A fault code for the takeover (never in the speed field): two digits in A|B, one digit centred. */
    fun code(code: Int?): IntArray = when {
        code == null || code < 0 -> at('?', 3)
        code < 10 -> at('0' + code, 3)
        else -> pair('0' + (code / 10) % 10, '0' + code % 10)
    }
}
