package dev.steven.s3control.ride

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything that happens between phone and bike, newest last. The ride logger and the
 * diagnosis page read from here; a dropped link right after "Shipping Mode" is the pattern we hunt.
 */
class EventLog(private val capacity: Int = 3000) {
    /** [MARK]: a moment the rider marked in the cockpit (or a situation tag after a takeover). */
    enum class Kind { LINK, STATE, COMMAND, ERROR, ALERT, INFO, MARK }

    /** Stable identifiers for the incidents the diagnosis page counts. */
    enum class Code {
        LINK_LOST_RIDING, SHIPPING, KICKLOCK_RIDING, SUPPLY_LOW, FAULT,
        SHUTDOWN_RIDING, POWER_BUTTON_RIDING, BATTERY_JUMP, MARK,
        /** The Wache (the guard design): value lines, sequences, link. */
        GUARD,
        /** The Wache: a trigger (movement at the bike). */
        GUARD_ALARM,
    }

    data class Event(
        val atMillis: Long,
        val kind: Kind,
        val message: String,
        val detail: String? = null,
        val code: Code? = null,
        /** The number behind [code]: the fault code, the mark number, the battery level after a jump. */
        val value: Int? = null,
        /** Monotonic per process; survives [clear], so "newer than" never depends on list positions. */
        val seq: Long = 0,
    ) {
        val time: String get() = TIME.format(Instant.ofEpochMilli(atMillis))
    }

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    @Volatile var sink: ((Event) -> Unit)? = null

    private var nextSeq = 0L

    /** [Event.seq] of the newest event added so far (0 before the first one). */
    val lastSeq: Long get() = synchronized(this) { nextSeq }

    fun add(kind: Kind, message: String, detail: String? = null, code: Code? = null, value: Int? = null) {
        val e: Event
        synchronized(this) {
            e = Event(System.currentTimeMillis(), kind, message, detail, code, value, ++nextSeq)
            val next = _events.value + e
            _events.value = if (next.size > capacity) next.takeLast(capacity) else next
        }
        sink?.invoke(e)
    }

    fun clear() = synchronized(this) { _events.value = emptyList() }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
