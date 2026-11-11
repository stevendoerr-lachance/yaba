package dev.steven.s3control.ble

import dev.steven.s3control.R

/**
 * The Wache's sentences for a trigger and for what happened with the siren (the guard design 3.4, 7.8): the alarm
 * notification, [GuardAlarm.text] and the GUARD_ALARM log line all use them. In the app language of the moment.
 */
object GuardTexts {
    /** [Texts.App] in the app; a JVM test may swap it (I18n needs a Context). */
    @Volatile internal var texts: Texts = Texts.App

    fun trigger(t: GuardTrigger): String = texts.get(
        when (t) {
            GuardTrigger.WHEEL -> R.string.guard_core_trigger_wheel
            GuardTrigger.BIKE_ALARM -> R.string.guard_core_trigger_bike_alarm
            GuardTrigger.STATUS_ALARM -> R.string.guard_core_trigger_status
            GuardTrigger.UNLOCK_REQUEST -> R.string.guard_core_trigger_unlock_request
            GuardTrigger.BUTTON -> R.string.guard_core_trigger_button
            GuardTrigger.BATTERY_REMOVED -> R.string.guard_core_trigger_battery
            GuardTrigger.LOCK_OPENED -> R.string.guard_core_trigger_lock_opened
            GuardTrigger.PIN_ENTRY -> R.string.guard_core_trigger_pin
            GuardTrigger.OFFLINE_ALARM -> R.string.guard_core_trigger_offline
        },
    )

    /** "" for [SirenNote.NONE]. [cycle] for YABA, the count for CAP_REACHED. */
    fun siren(n: SirenNote, cycle: Int): String = when (n) {
        SirenNote.YABA -> texts.get(R.string.guard_core_siren_yaba, cycle, GuardRules.MAX_SIREN_CYCLES)
        SirenNote.BIKE -> texts.get(R.string.guard_core_siren_bike)
        SirenNote.RUNNING -> texts.get(R.string.guard_core_siren_running)
        SirenNote.OFF_BY_SETTING -> texts.get(R.string.guard_core_siren_off_setting)
        SirenNote.SNOOZED -> texts.get(R.string.guard_core_siren_snoozed)
        SirenNote.CAP_REACHED -> texts.get(R.string.guard_core_siren_cap, cycle)
        SirenNote.BUDGET -> texts.get(R.string.guard_core_siren_budget)
        SirenNote.NONE -> ""
    }

    fun alarm(t: GuardTrigger, n: SirenNote, cycle: Int): String {
        val siren = siren(n, cycle)
        return if (siren.isEmpty()) trigger(t) else trigger(t) + " " + siren
    }
}
