package dev.steven.s3control.ble

/** Where the Wache stands. Every phase but OFF means the Wache owns the bike ([GuardState.active]). */
enum class GuardPhase {
    OFF,
    /** Arming: alarm on, lock, wait for 5523 = 2, else write it. Up to [GuardRules.ARM_MAX_SECONDS]. */
    ARMING,
    /** Armed and connected; nothing moves, or an alarm is over and the bike watches again. */
    WATCHING,
    /** Something moved: notified, siren written or not ([GuardAlarm.siren]). Until the bike watches again. */
    ALARM,
    /** Back to watching after an alarm, "Sirene aus" or "Erneut versuchen": 5523 = 0, lock, 5523 = 2. */
    REARMING,
    /** Armed, the link to the armed bike is down; YABA reconnects. */
    LOST,
    /** "Wache beenden" runs: 5523 = 0, unlock, alarm mode back. Waits up to 30 s for a link that is down. */
    DISARMING,
}

/** What counted as movement (the guard design 3.3). */
enum class GuardTrigger {
    WHEEL, BIKE_ALARM, STATUS_ALARM, UNLOCK_REQUEST, BUTTON, BATTERY_REMOVED, LOCK_OPENED, PIN_ENTRY, OFFLINE_ALARM,
}

/**
 * What happened with the siren on a trigger (the guard design 3.4). CAP_REACHED: [GuardRules.MAX_SIREN_CYCLES] rounds in
 * this episode; BUDGET: the write budget of the last ten minutes is used up.
 */
enum class SirenNote { YABA, BIKE, RUNNING, OFF_BY_SETTING, SNOOZED, CAP_REACHED, BUDGET, NONE }

/**
 * Something the UI explains once (cleared by [BikeGuard.clearIssue] or the next arm). ARM_FAILED_PARTLY: arming failed
 * after a first write (the bike may be locked or its alarm on). DISARM_UNCONFIRMED: ended, 5523 still 2/3/4.
 * DISARM_LOCKED: ended, the alarm is off but 5521 did not read 0. ENDED_OFFLINE: ended without reaching the bike.
 * ENDED_PARTLY: ended after disarm writes without an answer. NOT_LOCKED: active, and the bike is not locked (a lock
 * found open after a reconnect, or YABA's own re-lock did not take); "Erneut versuchen" locks it again.
 */
enum class GuardIssue {
    NOT_S3, NOT_CONNECTED, NOT_PARKED, BIKE_STATE, BIKE_BUSY,
    SERVICE, ARM_FAILED, ARM_FAILED_PARTLY, ARM_ALARM_DURING, ARM_ALARM_MODE, ARM_LOCK,
    DISARM_UNCONFIRMED, DISARM_LOCKED, ENDED_OFFLINE, ENDED_PARTLY, BIKE_GONE, NOT_LOCKED,
}

data class GuardAlarm(
    val atMillis: Long,
    val trigger: GuardTrigger,
    val siren: SirenNote,
    /** YABA's siren round this trigger started or fell into (1-based); 0 when YABA wrote none in this episode. */
    val cycle: Int,
    /** Trigger sentence plus siren sentence ([GuardTexts.alarm]), in the app language of that moment. */
    val text: String,
)

/** One Wache line of the event log, for the "Zuletzt" list. [alert]: a trigger. */
data class GuardEvent(val atMillis: Long, val text: String, val alert: Boolean)

data class GuardState(
    val phase: GuardPhase = GuardPhase.OFF,
    /** The bike confirmed its own motion watch: 5523 read 2 at the last check (arming, re-arm, reconnect). */
    val bikeWatch: Boolean = false,
    /** 5523 as the Wache last read it; null before the first read. */
    val defence: Int? = null,
    /** "Sirene sofort"; persisted, also while OFF. */
    val sirenImmediate: Boolean = true,
    /** Normalized MAC of the armed bike; null while OFF. */
    val armedMac: String? = null,
    val armedAtMillis: Long? = null,
    /** The newest trigger of this Wache; kept until it ends. */
    val lastAlarm: GuardAlarm? = null,
    /** YABA's siren rounds in the current episode (cap [GuardRules.MAX_SIREN_CYCLES]). */
    val sirenCycles: Int = 0,
    val issue: GuardIssue? = null,
    /** The byte an issue names (5524, 5521 or 5523), if any. */
    val issueValue: Int? = null,
    /** The error text behind [GuardIssue.ARM_FAILED] and [GuardIssue.ARM_FAILED_PARTLY]. */
    val issueDetail: String? = null,
    /** The Wache's lines in the event log, newest first, at most 40. */
    val events: List<GuardEvent> = emptyList(),
    /** LOST: the link has been down longer than [GuardRules.LOST_NOTIFY_MS] and the notification went out. */
    val lostNotified: Boolean = false,
) {
    /** The Wache owns the bike: every phase but OFF. Lever, alarm bar, disconnect and switching follow this. */
    val active: Boolean get() = phase != GuardPhase.OFF
    val armed: Boolean
        get() = phase == GuardPhase.WATCHING || phase == GuardPhase.ALARM || phase == GuardPhase.REARMING || phase == GuardPhase.LOST
    val alarming: Boolean get() = phase == GuardPhase.ALARM
    /** A sequence runs (arm, re-arm, disarm). */
    val working: Boolean get() = phase == GuardPhase.ARMING || phase == GuardPhase.REARMING || phase == GuardPhase.DISARMING
}

object GuardRules {
    const val POLL_MS = 1_500L
    const val ARM_NATIVE_WAIT_MS = 8_000L
    const val ARM_DIRECT_WAIT_MS = 6_000L
    const val ARM_MAX_SECONDS = 20
    const val WHEEL_HOLD_MS = 1_000L
    const val WHEEL_EDGE_WINDOW_MS = 5_000L
    const val WHEEL_FAST_KMH = 3
    const val OWN_WRITE_QUIET_MS = 3_000L
    const val SIREN_REPEAT_MS = 35_000L
    const val MAX_SIREN_CYCLES = 4
    const val EPISODE_CALM_MS = 600_000L
    const val REARM_CALM_MS = 5_000L
    const val ALARM_CALM_MS = 30_000L
    const val SILENCE_SNOOZE_MS = 120_000L
    const val NOTIFY_REPEAT_MS = 35_000L
    const val LOST_NOTIFY_MS = 20_000L
    const val DISARM_LINK_WAIT_MS = 30_000L
    const val MIN_WRITE_GAP_MS = 2_000L
    const val WRITE_BUDGET = 24
    const val WRITE_BUDGET_WINDOW_MS = 600_000L

    /** Why "Wache scharf schalten" is not offered for [s], or null (the guard design 1.1). Pure. */
    fun armBlocker(s: BikeState): GuardIssue? = when {
        Feature.S3_FIRMWARE_EXPERIMENTS !in s.features || s.family == BikeFamily.S2 -> GuardIssue.NOT_S3
        !s.isReady -> GuardIssue.NOT_CONNECTED
        s.status == BikeStatus.UPDATING || s.status == BikeStatus.SHIPPING || s.shippingImminent -> GuardIssue.BIKE_STATE
        !LabProtocol.isParked(s) -> GuardIssue.NOT_PARKED
        s.upload != null || s.logProgress != null -> GuardIssue.BIKE_BUSY
        else -> null
    }
}
