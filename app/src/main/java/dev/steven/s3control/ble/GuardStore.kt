package dev.steven.s3control.ble

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * What survives app kill and phone reboot of an armed Wache (the guard design 1.5): SharedPreferences "guard".
 * Written with commit() whenever [armed], the siren rounds, the time of the last siren, the write budget or the owed
 * lock change, so a process killed right after (a restart loop) still finds them; everything else with apply().
 * "Sirene sofort" is kept while the Wache is off.
 */
class GuardStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val armed: Boolean get() = prefs.getBoolean(K_ARMED, false)

    val sirenImmediate: Boolean get() = prefs.getBoolean(K_SIREN, true)

    fun load(): StoredGuard = StoredGuard(
        armed = prefs.getBoolean(K_ARMED, false),
        mac = prefs.getString(K_MAC, null),
        armedAt = prefs.long(K_ARMED_AT),
        sirenImmediate = prefs.getBoolean(K_SIREN, true),
        armPath = prefs.getString(K_PATH, null)?.let { p -> ArmPath.entries.firstOrNull { it.name == p } },
        bikeWatch = prefs.getBoolean(K_WATCH, false),
        restoreAlarmMode = prefs.getBoolean(K_RESTORE_MODE, false),
        lastAlarmAt = prefs.long(K_ALARM_AT),
        lastAlarmTrigger = prefs.getString(K_ALARM_TRIGGER, null)?.let { t -> GuardTrigger.entries.firstOrNull { it.name == t } },
        sirenCycles = prefs.getInt(K_CYCLES, 0),
        episodeStart = prefs.long(K_EPISODE),
        lastSirenAt = prefs.long(K_SIREN_AT),
        defence = if (prefs.contains(K_DEFENCE)) prefs.getInt(K_DEFENCE, 0) else null,
        autoWrites = prefs.getString(K_WRITES, null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
        lockOwed = prefs.getBoolean(K_LOCK_OWED, false),
        holdWrites = prefs.getBoolean(K_HOLD, false),
    )

    /** Saves [s]; what a restart loop must not lose goes to disk synchronously. */
    @SuppressLint("ApplySharedPref")
    fun save(s: StoredGuard) {
        val writes = s.autoWrites.joinToString(",")
        val critical = s.armed != armed ||
            s.sirenCycles != prefs.getInt(K_CYCLES, 0) ||
            s.lastSirenAt != prefs.long(K_SIREN_AT) ||
            writes != prefs.getString(K_WRITES, "") ||
            s.lockOwed != prefs.getBoolean(K_LOCK_OWED, false)
        val e = prefs.edit()
            .putBoolean(K_ARMED, s.armed)
            .putString(K_MAC, s.mac)
            .putBoolean(K_SIREN, s.sirenImmediate)
            .putString(K_PATH, s.armPath?.name)
            .putBoolean(K_WATCH, s.bikeWatch)
            .putBoolean(K_RESTORE_MODE, s.restoreAlarmMode)
            .putString(K_ALARM_TRIGGER, s.lastAlarmTrigger?.name)
            .putInt(K_CYCLES, s.sirenCycles)
            .putString(K_WRITES, writes)
            .putBoolean(K_LOCK_OWED, s.lockOwed)
            .putBoolean(K_HOLD, s.holdWrites)
        if (s.defence == null) e.remove(K_DEFENCE) else e.putInt(K_DEFENCE, s.defence)
        e.putOrRemove(K_ARMED_AT, s.armedAt)
        e.putOrRemove(K_ALARM_AT, s.lastAlarmAt)
        e.putOrRemove(K_EPISODE, s.episodeStart)
        e.putOrRemove(K_SIREN_AT, s.lastSirenAt)
        if (critical) e.commit() else e.apply()
    }

    fun setSirenImmediate(on: Boolean) = prefs.edit().putBoolean(K_SIREN, on).apply()

    /** The Wache is off: everything but "Sirene sofort" goes. */
    @SuppressLint("ApplySharedPref")
    fun clear() {
        val siren = sirenImmediate
        prefs.edit().clear().putBoolean(K_SIREN, siren).commit()
    }

    private fun SharedPreferences.long(key: String): Long? = if (contains(key)) getLong(key, 0L) else null

    private fun SharedPreferences.Editor.putOrRemove(key: String, v: Long?) {
        if (v == null) remove(key) else putLong(key, v)
    }

    companion object {
        const val NAME = "guard"
        private const val K_ARMED = "armed"
        private const val K_MAC = "mac"
        private const val K_ARMED_AT = "armedAt"
        private const val K_SIREN = "sirenImmediate"
        private const val K_PATH = "armPath"
        private const val K_WATCH = "bikeWatch"
        private const val K_RESTORE_MODE = "restoreAlarmMode"
        private const val K_ALARM_AT = "lastAlarmAt"
        private const val K_ALARM_TRIGGER = "lastAlarmTrigger"
        private const val K_CYCLES = "sirenCycles"
        private const val K_EPISODE = "episodeStart"
        private const val K_SIREN_AT = "lastSirenAt"
        private const val K_DEFENCE = "defence"
        private const val K_WRITES = "autoWrites"
        private const val K_LOCK_OWED = "lockOwed"
        private const val K_HOLD = "holdWrites"
    }
}
