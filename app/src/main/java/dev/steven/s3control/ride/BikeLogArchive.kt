package dev.steven.s3control.ride

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every download of the bike's own log, kept on the phone. [dev.steven.s3control.AppViewModel.readBikeLog]
 * writes each download to `filesDir/logs/bikelog-<yyyyMMdd-HHmmss>.txt`; this class reads them back and
 * joins them into one history, so a ride survives after the bike's 128 KB ring has overwritten it.
 *
 * Joining: downloads overlap (each one repeats what is still in the ring). A new download is appended
 * after the newest line it shares with the archive; lines with a leading epoch are unique, lines without
 * one (the boot-time "Wake Reason: …") are only ever appended in their place, never deduplicated, so
 * repeated identical boot lines stay countable.
 */
class BikeLogArchive(val dir: File) {

    data class Snapshot(
        /** All lines, oldest first, joined with '\n'. */
        val text: String,
        val downloads: Int,
        val lines: Int,
    ) {
        companion object {
            val EMPTY = Snapshot("", 0, 0)
        }
    }

    fun files(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Joins every saved download, oldest first, plus [latest] (the download still in memory, whose file
     * may not be written yet). Appending a download that is already on disk adds nothing.
     */
    fun load(latest: String? = null): Snapshot {
        val files = files()
        val texts = files.mapNotNull { runCatching { it.readText() }.getOrNull() }.toMutableList()
        val latestIsNew = !latest.isNullOrEmpty() && texts.lastOrNull() != latest
        if (latestIsNew) texts.add(latest!!)
        val lines = merge(texts)
        return Snapshot(lines.joinToString("\n"), files.size + if (latestIsNew) 1 else 0, lines.size)
    }

    /** True when the file written for the download at [atMillis] exists and holds exactly [text]. */
    fun holds(text: String, atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val f = File(dir, fileName(atMillis, zone))
        return f.isFile && runCatching { f.readText() == text }.getOrDefault(false)
    }

    companion object {
        const val PREFIX = "bikelog-"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val STAMPED = Regex("""^\d{9,10} """)
        private val LEADING_DIGITS = Regex("""^\d+""")

        /** The name AppViewModel gives the download finished at [atMillis] (same pattern, same zone). */
        fun fileName(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            PREFIX + STAMP.withZone(zone).format(Instant.ofEpochMilli(atMillis)) + ".txt"

        /** A line that starts with a plausible Unix-seconds stamp ("1723229090 …"). */
        fun isStamped(line: String): Boolean = STAMPED.containsMatchIn(line)

        /**
         * The ring is read from its tail, which can sit in the middle of a line: "29090 ;21.2;…" would
         * parse as a telemetry row from 1970. Such a cut first line is dropped; a cut message without
         * digits is harmless (the parser keeps it as unparsed).
         */
        internal fun isCutHead(line: String): Boolean =
            LEADING_DIGITS.find(line)?.value?.length?.let { it !in 9..10 } ?: false

        /** Joins downloads (oldest first) into one line list. See the class comment for the rule. */
        fun merge(downloads: List<String>): List<String> {
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            for (text in downloads) {
                val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
                if (lines.isEmpty()) continue
                // The newest line of this download the archive already holds.
                var anchor = -1
                if (seen.isNotEmpty()) {
                    for (i in lines.indices.reversed()) {
                        val l = lines[i]
                        if (isStamped(l) && l in seen) { anchor = i; break }
                    }
                }
                val start = when {
                    anchor >= 0 -> anchor + 1
                    isCutHead(lines.first()) -> 1
                    else -> 0
                }
                for (i in start until lines.size) {
                    val l = lines[i]
                    if (isStamped(l)) {
                        if (!seen.add(l)) continue
                    }
                    out.add(l)
                }
            }
            return out
        }
    }
}
