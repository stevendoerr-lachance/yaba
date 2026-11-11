package dev.steven.s3control.ui.screens

import dev.steven.s3control.audio.BellSlotRecord
import dev.steven.s3control.audio.BundledBell
import dev.steven.s3control.ble.BellTone
import dev.steven.s3control.ble.SOUNDS
import dev.steven.s3control.ui.components.padLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The owner asked for an emoji on every bell tone tile, sound pad and bundled bell row (after his first ride test).
 * BRAND bans emoji and names these three as its one exception (Don't 8). A brand pass that strips them fails here:
 * the data (every tone, sound and bell has one) and the UI path (each control takes a non-null emoji and draws it
 * with [dev.steven.s3control.ui.components.SoundEmoji], and the screens hand it the sound's own). The regression the
 * owner saw was in the UI, the data never went away.
 */
class SoundEmojiTest {

    @Test fun everyBellToneHasAnEmoji() {
        BellTone.entries.forEach { assertTrue("$it has no emoji", it.emoji.isNotBlank()) }
    }

    @Test fun everySoundOnTheBoardHasAnEmoji() {
        SOUNDS.forEach { assertTrue("sound 0x${it.id.toString(16)} has no emoji", it.emoji.isNotBlank()) }
    }

    /** A tone tile and its pad sit on one screen: the same sound shows the same picture, and no two sounds share one. */
    @Test fun oneEmojiPerSound() {
        BellTone.entries.forEach { tone ->
            assertEquals("$tone", SOUNDS.first { it.id == tone.code }.emoji, tone.emoji)
        }
        val shared = SOUNDS.groupBy { it.emoji }.filterValues { it.size > 1 }.keys
        assertTrue("emoji used by more than one sound: $shared", shared.isEmpty())
    }

    @Test fun everyBundledBellHasAnEmoji() {
        BundledBell.entries.forEach { assertTrue("$it has no emoji", it.emoji.isNotBlank()) }
    }

    private fun slot(bundledId: String?) = BellSlotRecord(
        name = "x",
        bundledId = bundledId,
        seconds = 1f,
        wave = null,
        sentAt = 0L,
    )

    @Test fun fixedTonesKeepTheirOwnEmoji() {
        val withBell = slot(BundledBell.UFO.name)
        BellTone.entries.filter { it != BellTone.FOGHORN }.forEach { tone ->
            assertEquals(tone.emoji, toneEmoji(tone, null))
            assertEquals(tone.emoji, toneEmoji(tone, withBell))
        }
        assertEquals(BellTone.FOGHORN.emoji, toneEmoji(BellTone.FOGHORN, null))
    }

    @Test fun slotWithABundledBellShowsThatBellsEmoji() {
        assertEquals(BundledBell.TRAM.emoji, toneEmoji(BellTone.FOGHORN, slot(BundledBell.TRAM.name)))
        assertEquals(BundledBell.UFO.emoji, toneEmoji(BellTone.FOGHORN, slot(BundledBell.UFO.name)))
    }

    @Test fun slotWithOwnFileOrUnknownBellKeepsTheFoghornEmoji() {
        assertEquals(BellTone.FOGHORN.emoji, toneEmoji(BellTone.FOGHORN, slot(null)))
        assertEquals(BellTone.FOGHORN.emoji, toneEmoji(BellTone.FOGHORN, slot("GONE_IN_AN_UPDATE")))
    }

    // --- The UI path ------------------------------------------------------------------------------

    private val sourceRoot: File = listOf("src/main/java/dev/steven/s3control", "app/src/main/java/dev/steven/s3control")
        .map(::File).first { it.isDirectory }

    private fun source(path: String) = File(sourceRoot, path).readText()

    /** A function's parameter list and body in [text]: balanced from "fun [name](" to its closing brace. */
    private fun function(text: String, name: String): Pair<String, String> {
        val start = text.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val open = start + "fun $name".length
        val close = matching(text, open, '(', ')')
        val brace = text.indexOf('{', close)
        return text.substring(open, close + 1) to text.substring(brace, matching(text, brace, '{', '}') + 1)
    }

    private fun matching(text: String, from: Int, open: Char, close: Char): Int {
        var depth = 0
        for (i in from until text.length) {
            when (text[i]) {
                open -> depth++
                close -> if (--depth == 0) return i
            }
        }
        error("unbalanced $open$close from $from")
    }

    @Test fun everyControlTakesAndDrawsItsEmoji() {
        listOf(
            "ui/components/SoundBits.kt" to "BellToneTile",
            "ui/components/Controls.kt" to "SoundPad",
            "ui/components/BundledBellBits.kt" to "BundledBellRow",
        ).forEach { (file, name) ->
            val (params, body) = function(source(file), name)
            assertTrue("$name must take a non-null emoji", Regex("""\bemoji:\s*String\s*,""").containsMatchIn(params))
            assertTrue("$name must draw its emoji with SoundEmoji", Regex("""\bSoundEmoji\(\s*emoji\b""").containsMatchIn(body))
        }
    }

    @Test fun soundEmojiDrawsTheEmoji() {
        val (_, body) = function(source("ui/components/SoundBits.kt"), "SoundEmoji")
        assertTrue("SoundEmoji must draw its text", Regex("""\bText\(\s*emoji\b""").containsMatchIn(body))
    }

    @Test fun theScreensPassEachSoundsOwnEmoji() {
        val screen = source("ui/screens/SoundScreen.kt")
        assertTrue("bell tiles", Regex("""emoji\s*=\s*toneEmoji\(""").containsMatchIn(screen))
        assertTrue("sound pads", Regex("""emoji\s*=\s*sound\.emoji\b""").containsMatchIn(screen))
        val chooser = source("ui/components/SoundSourceBits.kt")
        assertTrue("bundled bell rows", Regex("""emoji\s*=\s*bell\.emoji\b""").containsMatchIn(chooser))
    }

    // --- Pad labels break between words -----------------------------------------------------------------

    @Test fun padLabelMakesTheHyphenABreak() {
        assertEquals("Entsperr-\u200BCountdown", padLabel("Entsperr-Countdown"))
        assertEquals("Klingel", padLabel("Klingel"))
    }

    @Test fun aLineMayEndOnlyBetweenWords() {
        val label = padLabel("Entsperr-Countdown")
        assertTrue(endsCleanly(label, "Entsperr-\u200B".length))
        assertFalse(endsCleanly(label, "Entsperr-\u200BCountdo".length))
        assertTrue(endsCleanly("Dringender Pfiff", "Dringender ".length))
        assertFalse(endsCleanly("Dringender Pfiff", "Dringen".length))
    }
}
