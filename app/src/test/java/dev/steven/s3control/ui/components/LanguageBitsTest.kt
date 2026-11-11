package dev.steven.s3control.ui.components

import dev.steven.s3control.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The language choice: the options, their names in themselves, and the value the system stores and gives back. */
class LanguageBitsTest {
    private val names = linkedMapOf(
        "de" to "Deutsch",
        "en" to "English",
        "nl" to "Nederlands",
        "fr" to "Français",
        "it" to "Italiano",
        "hr" to "Hrvatski",
        "ru" to "Русский",
    )

    @Test
    fun followTheSystemFirstThenTheSevenLanguages() {
        val options = AppLanguages.options
        assertEquals(8, options.size)
        assertEquals(LanguageOption("", null), options.first())
        assertEquals(names.keys.toList(), options.drop(1).map { it.tag })
        assertEquals(options.size, options.map { it.tag }.toSet().size)
    }

    @Test
    fun everyLanguageIsNamedInItself() {
        names.forEach { (tag, name) ->
            assertEquals(tag, name, AppLanguages.endonym(tag))
            assertEquals(name, AppLanguages.options.single { it.tag == tag }.endonym)
        }
        assertNull(AppLanguages.endonym(""))
        assertNull(AppLanguages.endonym("es"))
    }

    @Test
    fun everyChoiceRoundTripsThroughTheSystemStore() {
        AppLanguages.options.forEach { option ->
            val stored = I18n.storedTags(option.tag)
            assertEquals(option.tag, stored)
            assertEquals(option.tag, I18n.tagFromStored(stored))
        }
    }

    @Test
    fun whatTheSystemGivesBackIsReadAsAnOfferedChoice() {
        // a region or a second language in the stored list does not matter; what the app does not offer follows the system
        assertEquals("de", I18n.tagFromStored("de-AT"))
        assertEquals("de", I18n.tagFromStored("de-DE,en-US"))
        assertEquals("hr", I18n.tagFromStored("hr_HR"))
        assertEquals("ru", I18n.tagFromStored("RU"))
        assertEquals("", I18n.tagFromStored(""))
        assertEquals("", I18n.tagFromStored("es-ES"))
        assertEquals("", I18n.storedTags("es"))
    }

    @Test
    fun theButtonsTwoLettersAreAllInTheMatrixFont() {
        val unknown = MatrixFont.render("?")
        names.keys.forEach { tag ->
            val code = AppLanguages.code(tag)
            assertEquals(2, code.length)
            assertEquals(tag.uppercase(), code)
            code.forEach { ch -> assertFalse("$ch is not in the matrix font", MatrixFont.render(ch.toString()).contentEquals(unknown)) }
            // two letters and the column between them fit the tube's 11 columns
            assertTrue(code, MatrixFrame.text(code).width <= 11)
        }
    }

    @Test
    fun everyLanguageFolderSaysWhichLanguageItIs() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        fun tagIn(folder: String): String? {
            val file = File(res, "$folder/strings_language.xml")
            if (!file.isFile) return null
            return Regex("""<string name="language_tag">([^<]*)</string>""").find(file.readText())?.groupValues?.get(1)
        }
        assertEquals("en", tagIn("values"))
        names.keys.filter { it != "en" }.forEach { tag -> assertEquals(tag, tagIn("values-$tag")) }
        // the system's own language page offers the same seven
        val config = File(res, "xml/locales_config.xml").readText()
        val offered = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals(names.keys.toSet(), offered.toSet())
    }

    @Test
    fun theLanguageStringsStandTogetherInEveryLanguage() {
        // The language left the Bike tab: its strings are the shell's, in strings_language.xml, and none is left behind.
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val keys = listOf(
            "language_tag", "language_choose", "language_close",
            "language_title", "language_system", "language_follows_system", "language_current",
        )
        val folders = listOf("values") + names.keys.filter { it != "en" }.map { "values-$it" }
        folders.forEach { folder ->
            val text = File(res, "$folder/strings_language.xml").readText()
            keys.forEach { key -> assertTrue("$folder lacks $key", text.contains("""<string name="$key">""")) }
            assertTrue("$folder: language_current names the language", text.contains("%1\$s"))
            val bike = File(res, "$folder/strings_bike.xml").readText()
            assertFalse("$folder still has bike_language_*", bike.contains("""name="bike_language_"""))
        }
    }
}
