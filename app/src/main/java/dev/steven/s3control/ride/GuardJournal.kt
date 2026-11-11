package dev.steven.s3control.ride

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Wache's own record (the guard design 2.3): every Wache line also goes to `filesDir/logs/guard-yyyyMMdd.txt` as
 * `HH:mm:ss.SSS KIND text` plus, when there is one, a tab and `(detail)`. KIND is the event log's kind, or ALARM for a
 * trigger. It survives process death, can be shared after the first real test, and refills the "Zuletzt" list after a
 * restart ([recent]). The `guard-` prefix and the `.txt` extension keep it out of the ride logs (`.csv`) and the bike
 * logs (`bikelog-`).
 */
class GuardJournal(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "logs").apply { mkdirs() }

    @Synchronized
    fun append(atMillis: Long, kind: String, text: String, detail: String? = null) {
        val at = Instant.ofEpochMilli(atMillis)
        val file = File(dir, "guard-${DAY.format(at)}.txt")
        val line = buildString {
            append(TIME.format(at)).append(' ').append(kind).append(' ').append(text.replace('\n', ' ').replace('\t', ' '))
            if (!detail.isNullOrEmpty()) append('\t').append('(').append(detail.replace('\n', ' ')).append(')')
            append('\n')
        }
        runCatching { FileWriter(file, true).use { it.write(line) } }
    }

    /** One line read back: when, its text without the detail, and whether it was a trigger. */
    data class Line(val atMillis: Long, val text: String, val alarm: Boolean)

    /**
     * The newest [max] lines written at or after [sinceMillis] (today's and yesterday's file), newest first. Lines it
     * cannot read are skipped.
     */
    @Synchronized
    fun recent(sinceMillis: Long, max: Int): List<Line> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val out = ArrayList<Line>()
        for (day in listOf(today.minusDays(1), today)) {
            val file = File(dir, "guard-${DAY_NAME.format(day)}.txt")
            if (!file.isFile) continue
            runCatching {
                file.forEachLine { raw ->
                    parse(day, raw, zone)?.takeIf { it.atMillis >= sinceMillis }?.let(out::add)
                }
            }
        }
        return out.sortedByDescending { it.atMillis }.take(max)
    }

    private fun parse(day: LocalDate, raw: String, zone: ZoneId): Line? {
        val firstSpace = raw.indexOf(' ')
        val secondSpace = raw.indexOf(' ', firstSpace + 1)
        if (firstSpace <= 0 || secondSpace <= firstSpace) return null
        val time = runCatching { LocalTime.parse(raw.substring(0, firstSpace), TIME_PARSE) }.getOrNull() ?: return null
        val kind = raw.substring(firstSpace + 1, secondSpace)
        val text = raw.substring(secondSpace + 1).substringBefore('\t')
        if (text.isBlank()) return null
        return Line(day.atTime(time).atZone(zone).toInstant().toEpochMilli(), text, kind == KIND_ALARM)
    }

    /** The newest journal file, or null when there is none. */
    fun newest(): File? = dir.listFiles { f -> f.name.startsWith("guard-") && f.extension == "txt" }?.maxByOrNull { it.name }

    /** ACTION_SEND for the newest journal file, or null when there is none. */
    fun shareIntent(subject: String): Intent? {
        val file = newest() ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "$subject ${file.nameWithoutExtension}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        /** The journal's KIND for a trigger line (the event log calls it ALERT, like a lost link). */
        const val KIND_ALARM = "ALARM"
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault())
        private val DAY_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        private val TIME_PARSE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
