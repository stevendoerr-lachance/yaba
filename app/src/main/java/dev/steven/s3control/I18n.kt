package dev.steven.s3control

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Strings for code that has no Compose context (protocol labels, event log, errors).
 * Backed by the application context, so it follows the per-app language.
 */
object I18n {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun get(@StringRes id: Int): String = app.getString(id)

    fun get(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = app.resources.getQuantityString(id, count, *args)

    /** Languages offered in the app, by BCP-47 tag; "" means "follow the system". */
    val languages = listOf("" to null, "de" to "Deutsch", "en" to "English", "nl" to "Nederlands", "fr" to "Français", "it" to "Italiano", "hr" to "Hrvatski", "ru" to "Русский")

    /** Per-app language (Android 13+). Returns false where the system has no per-app languages. */
    fun setLanguage(context: Context, tag: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val stored = storedTags(tag)
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (stored.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(stored)
        return true
    }

    /** The offered tag of the per-app language, "" = system. */
    fun currentLanguage(context: Context): String {
        if (Build.VERSION.SDK_INT < 33) return ""
        return tagFromStored(context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
    }

    /** What the system's per-app language store is given for [tag]: the tag itself, or "" (no list: follow the system) for one the app does not offer. */
    fun storedTags(tag: String): String = if (languages.any { it.first == tag }) tag else ""

    /**
     * The offered tag a stored list reads back as: the language of its first entry ("de-AT,en" is "de"), or "" (follow the
     * system) for an empty list or a language the app does not offer (set from outside, e.g. with adb).
     */
    fun tagFromStored(stored: String): String {
        val language = stored.substringBefore(',').trim().substringBefore('-').substringBefore('_').lowercase(java.util.Locale.ROOT)
        return if (language.isNotEmpty() && languages.any { it.first == language }) language else ""
    }

    val canChooseLanguage: Boolean get() = Build.VERSION.SDK_INT >= 33
}
