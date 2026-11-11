package dev.steven.s3control.ui.screens

import dev.steven.s3control.ui.components.MatrixFont
import dev.steven.s3control.ui.components.MatrixFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The tube's Bluetooth frames ([bleWordFrame]) in every language: the glyph, two columns of air and the word, within the
 * 36 columns of the onboarding tube (and so the hero's 40), so no frame runs as a marquee and the glyph always stays. The
 * words are read from the seven resource folders: onb_matrix_scan (searching, on the hero and in onboarding alike) and
 * shell_matrix_ble_off.
 */
class TubeFrameTest {

    private val locales = listOf("values", "values-de", "values-fr", "values-hr", "values-it", "values-nl", "values-ru")

    private fun word(locale: String, file: String, key: String): String {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).firstOrNull { it.isDirectory }
        assumeTrue("run from the app module", res != null)
        val text = File(res, "$locale/$file").readText()
        return Regex("""<string name="$key">([^<]*)</string>""").find(text)?.groupValues?.get(1)
            ?: error("$locale/$file has no $key")
    }

    private val searching by lazy { locales.associateWith { word(it, "strings_onboarding.xml", "onb_matrix_scan") } }
    private val bleOff by lazy { locales.associateWith { word(it, "strings_shell.xml", "shell_matrix_ble_off") } }

    private val glyph = MatrixFrame.text(MatrixFont.BLE.toString()) + MatrixFrame.gap(2)

    @Test
    fun everyWordStandsBesideTheGlyphWithinTheTube() {
        for ((name, words) in listOf("searching" to searching, "ble off" to bleOff)) {
            for ((locale, word) in words) {
                val frame = bleWordFrame(word)
                assertTrue("$locale $name $word is ${frame.width} columns", frame.width <= 36)
                assertTrue("$locale $name $word fits the hero", frame.width <= HeroTubeColumns)
                assertArrayEquals("$locale $word", glyph.columns, frame.columns.copyOfRange(0, glyph.width))
                assertArrayEquals("$locale $word", MatrixFrame.text(word).columns, frame.columns.copyOfRange(glyph.width, frame.width))
            }
        }
    }

    @Test
    fun theHeroSearchesWithTheOnboardingWord() {
        // one word per state across screens: SUCHE, ZOEK, and SCAN in the other five
        assertEquals(
            mapOf(
                "values" to "SCAN", "values-de" to "SUCHE", "values-fr" to "SCAN", "values-hr" to "SCAN",
                "values-it" to "SCAN", "values-nl" to "ZOEK", "values-ru" to "SCAN",
            ),
            searching,
        )
    }

    @Test
    fun germanSearchIsTheOnboardingFrame() {
        // FindStep draws the same 36 columns while it searches.
        assertEquals(36, bleWordFrame("SUCHE").width)
        assertEquals(5 + 2 + 5, bleWordFrame("?").width)
    }

    private val guardArmed by lazy { locales.associateWith { word(it, "strings_guard.xml", "guard_matrix_armed") } }
    private val guardAlarm by lazy { locales.associateWith { word(it, "strings_guard.xml", "guard_matrix_alarm") } }

    /**
     * The Wache's words (the guard design 7.2): the armed frame is the 7-column lock, two columns of air and the word, the alarm
     * frame the word alone; both fit the hero's 40 columns without a marquee in every language, in the closed ASCII list of
     * BRAND "Languages" (no diacritics: MatrixFont draws none of them but È, which belongs to the call).
     */
    @Test
    fun theGuardWordsFitTheHeroInEveryLanguage() {
        val lock = MatrixFrame.text(MatrixFont.LOCKED.toString()) + MatrixFrame.gap(2)
        assertEquals(locales.toSet(), guardArmed.keys)
        assertEquals(locales.toSet(), guardAlarm.keys)
        for ((locale, word) in guardArmed) {
            assertTrue("$locale armed $word", word.matches(Regex("[A-Z]{1,5}")))
            val frame = guardArmedFrame(word)
            assertTrue("$locale armed $word is ${frame.width} columns", frame.width <= HeroTubeColumns)
            assertArrayEquals("$locale $word", lock.columns, frame.columns.copyOfRange(0, lock.width))
            assertArrayEquals("$locale $word", MatrixFrame.text(word).columns, frame.columns.copyOfRange(lock.width, frame.width))
        }
        for ((locale, word) in guardAlarm) {
            assertTrue("$locale alarm $word", word.matches(Regex("[A-Z]{1,6}")))
            val width = MatrixFrame.text(word).width
            assertTrue("$locale alarm $word is $width columns", width <= HeroTubeColumns)
        }
        assertEquals(
            mapOf(
                "values" to "GUARD", "values-de" to "WACHE", "values-fr" to "GARDE", "values-hr" to "GUARD",
                "values-it" to "GUARD", "values-nl" to "WAAK", "values-ru" to "GUARD",
            ),
            guardArmed,
        )
        // The widest: 7 + 2 + 29 for five letters.
        assertEquals(38, guardArmedFrame("WACHE").width)
        assertEquals(35, MatrixFrame.text("ALARME").width)
    }

    @Test
    fun widths() {
        val expected = mapOf(
            "SCAN" to 30, "SUCHE" to 36, "ZOEK" to 30,
            "OFF" to 24, "AUS" to 24, "UIT" to 22, "ISKLJ" to 34, "VYKL" to 30,
        )
        for ((word, width) in expected) assertEquals(word, width, bleWordFrame(word).width)
    }
}
