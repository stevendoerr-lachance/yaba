package dev.steven.s3control.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeChoice { SYSTEM, DARK, LIGHT }

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** Show 37 km/h and power level 5 even on firmware that officially dropped them. Private land only. */
    val privateGround: Boolean = false,
    val autoConnect: Boolean = true,
    val acceptedSpeedNotice: Boolean = false,
    /** Unlock automatically when the bike asks (bell or boost pressed while connected). */
    val touchUnlock: Boolean = false,
    /** The opening film plays by itself until it was seen once (set when its reveal or skip starts). */
    val introSeen: Boolean = false,
    /** null = a legacy install that never decided; AppRoot resolves it from the vault once. */
    val onboardingDone: Boolean? = null,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        prefs.edit()
            .putString("theme", next.theme.name)
            .putBoolean("privateGround", next.privateGround)
            .putBoolean("autoConnect", next.autoConnect)
            .putBoolean("acceptedSpeedNotice", next.acceptedSpeedNotice)
            .putBoolean("touchUnlock", next.touchUnlock)
            .putBoolean("introSeen", next.introSeen)
            .also { e -> next.onboardingDone?.let { e.putBoolean("onboardingDone", it) } ?: e.remove("onboardingDone") }
            .apply()
        _state.value = next
    }

    private fun read() = AppSettings(
        theme = runCatching { ThemeChoice.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeChoice.SYSTEM),
        privateGround = prefs.getBoolean("privateGround", false),
        autoConnect = prefs.getBoolean("autoConnect", true),
        acceptedSpeedNotice = prefs.getBoolean("acceptedSpeedNotice", false),
        touchUnlock = prefs.getBoolean("touchUnlock", false),
        introSeen = prefs.getBoolean("introSeen", false),
        onboardingDone = if (prefs.contains("onboardingDone")) prefs.getBoolean("onboardingDone", false) else null,
    )
}
