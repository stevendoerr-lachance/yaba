package dev.steven.s3control.ui.onboarding

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.steven.s3control.AppViewModel
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Link
import dev.steven.s3control.ble.LockState
import dev.steven.s3control.data.BikeCredentials
import dev.steven.s3control.ui.BleReadiness
import dev.steven.s3control.ui.components.LocalShellBlePrompt
import dev.steven.s3control.ui.components.Moment
import dev.steven.s3control.ui.components.ServerFailure
import dev.steven.s3control.ui.components.ServerRecord
import dev.steven.s3control.ui.components.ShellBlePrompt
import dev.steven.s3control.ui.components.rememberLoginFieldsState
import dev.steven.s3control.ui.components.rememberManualBikeState
import dev.steven.s3control.ui.theme.S3
import dev.steven.s3control.ui.theme.S3Theme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The core rule of the onboarding: no screen scrolls, ever. Every step in every state is composed in a fixed window (the
 * Find N6 cover screen, the floor phone, the inner screen and one half pane of it) at three font scales, with the keyboard
 * closed and open (a made-up IME that shrinks the window and tells the frame), in Night and Daylight, in all seven languages.
 * Each time:
 *  - nothing scrolls (a multi-line text field scrolls inside itself and is not counted),
 *  - everything the person can press or type into lies inside the window,
 *  - there is something to press or type into, except where the keyboard is open and the ladder's last rung has taken the
 *    primary button away (the keyboard's Done action submits).
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest --tests '*OnboardingNoScrollTest*'
 */
@RunWith(AndroidJUnit4::class)
class OnboardingNoScrollTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A window in dp: width, the height that is usable with the keyboard closed, and with it open. */
    private class Window(val name: String, val w: Int, val closed: Int, val open: Int)

    private val windows = listOf(
        Window("n6-cover", 414, 911, 587),
        Window("floor", 360, 592, 316),
        Window("inner", 900, 793, 463),
        Window("half-pane", 450, 793, 463),
    )
    private val fonts = listOf(1.0f, 1.3f, 2.0f)
    // -e onb_langs en,de narrows a quick run; the default is all seven languages
    private val languages = (InstrumentationRegistry.getArguments().getString("onb_langs") ?: "en,de,nl,fr,it,hr,ru").split(',')

    private class Case(val name: String, val content: @Composable (OnboardingEnv) -> Unit)

    private val bike = BikeCredentials(
        id = 1, name = "My S3", ownerName = "Alex", mac = "74:D2:85:AB:CD:EF",
        encryptionKey = "00000000000000000000000000000000", userKeyId = 1,
    )

    private fun cases(vm: AppViewModel): List<Case> {
        val none = FindActions({}, {}, {}, {}, {}, {})
        val searching = BikeState(bike = bike, link = Link.SEARCHING)
        val offline = BikeState(bike = bike, link = Link.OFFLINE)
        val note = UnlockNote(seconds = 0.8f, battery = 87, first = true, relief = false, token = 1)
        return listOf(
            Case("welcome") { WelcomeStep(it, {}, {}) },
            Case("signin") { SignInStep(vm, rememberLoginFieldsState(), it) {} },
            Case("import-choose") { ImportChooseStep(vm, it, {}, {}) },
            Case("import-paste") {
                ImportPasteStep(vm, "[{\"mac\":\"74:D2:85:AB:CD:EF\",\"encryptionKey\":\"00000000000000000000000000000000\"}]", {}, it)
            },
            Case("manual") { ManualStep(vm, rememberManualBikeState(), it) },
            Case("permission") { PermissionStep(it, BleReadiness(false, true, {}, {})) },
            Case("permission-denied") {
                CompositionLocalProvider(LocalShellBlePrompt provides ShellBlePrompt(asked = true)) {
                    PermissionStep(it, BleReadiness(false, true, {}, {}, permissionResults = 1))
                }
            },
            Case("permission-bluetooth-off") { PermissionStep(it, BleReadiness(true, false, {}, {})) },
            Case("find") { FindView(it, bike.name, offline, false, false, null, none) },
            Case("find-searching") { FindView(it, bike.name, searching, false, false, null, none) },
            Case("find-not-found") { FindView(it, bike.name, searching, true, false, null, none) },
            Case("find-not-found-vanmoof-app") { FindView(it, bike.name, searching, true, true, null, none) },
            Case("unlock") {
                UnlockView(it, BikeState(bike = bike, link = Link.READY, battery = 87, lock = LockState.LOCKED), null, null, null, {}, {})
            },
            Case("unlock-error") {
                UnlockView(it, BikeState(bike = bike, link = Link.READY, battery = 87, lock = LockState.LOCKED), null, null, vm.account.value.error ?: "Not connected", {}, {})
            },
            Case("unlocked") {
                UnlockView(it, BikeState(bike = bike, link = Link.READY, battery = 87, lock = LockState.UNLOCKED), note, null, null, {}, {})
            },
            Case("server-http") {
                ServerNoticeView(
                    ServerRecord(ServerFailure.Http(503), 0L, null, 0), it, retry = {},
                    importAction = FrameAction("import") { androidx.compose.material3.Text("import", Modifier.size(48.dp)) },
                )
            },
            Case("server-timeout") {
                ServerNoticeView(
                    ServerRecord(ServerFailure.Timeout, 0L, null, 0), it, retry = {},
                    importAction = FrameAction("import") { androidx.compose.material3.Text("import", Modifier.size(48.dp)) },
                )
            },
            Case("server-dead") {
                ServerNoticeView(
                    ServerRecord(ServerFailure.Timeout, 0L, Moment.ServerDead, 7), it, retry = {},
                    importAction = FrameAction("import") { androidx.compose.material3.Text("import", Modifier.size(48.dp)) },
                )
            },
        )
    }

    private class Setup(val window: Window, val font: Float, val ime: Boolean, val dark: Boolean, val language: String, val case: Case)

    @Test
    fun noOnboardingScreenEverScrollsAndEverythingPressableIsInTheWindow() {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        // An error on the account channel, so the status line has something to say in the steps that show it.
        rule.runOnUiThread { vm.importKeys("this is not a key") }
        val all = cases(vm)
        // The link line pulses and the search button spins for ever: the clock is moved by hand, never waited for.
        rule.mainClock.autoAdvance = false
        var setup by mutableStateOf<Setup?>(null)

        rule.setContent {
            val s = setup ?: return@setContent
            val base = LocalContext.current
            val config = remember(s.language, s.font) {
                Configuration(base.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(s.language))
                    fontScale = s.font
                }
            }
            // A wrapper around the activity (not a configuration context of its own), so the activity is still found behind it.
            val localised = remember(config) { ContextThemeWrapper(base, 0).apply { applyOverrideConfiguration(config) } }
            key(s.window.name, s.font, s.ime, s.dark, s.language, s.case.name) {
                CompositionLocalProvider(
                    LocalContext provides localised,
                    LocalConfiguration provides config,
                    LocalDensity provides Density(2.75f, s.font),
                    LocalImeOverrideDp provides (if (s.ime) 330.dp else null),
                ) {
                    S3Theme(s.dark) {
                        val h = if (s.ime) s.window.open else s.window.closed
                        Box(Modifier.requiredSize(s.window.w.dp, h.dp).background(S3.colors.asphalt).testTag("window")) {
                            s.case.content(OnboardingEnv(wide = s.window.w >= 600, fold = null, originXPx = 0f))
                        }
                    }
                }
            }
        }

        val failures = mutableListOf<String>()
        for (window in windows) for (font in fonts) for (ime in listOf(false, true)) {
            for (language in languages) {
                // Daylight is looked at in one language: the layout does not depend on the colours.
                val themes = if (language == "en") listOf(true, false) else listOf(true)
                for (dark in themes) for (case in all) {
                    val label = "${case.name} @ ${window.name} font $font ime=$ime dark=$dark $language"
                    rule.runOnUiThread { setup = Setup(window, font, ime, dark, language, case) }
                    rule.mainClock.advanceTimeBy(64)
                    rule.waitForIdle()
                    try {
                        check(window, font, ime, vm.account.value.error)
                    } catch (e: AssertionError) {
                        failures += "$label: ${e.message}"
                    }
                }
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    private fun check(window: Window, font: Float, ime: Boolean, statusError: String?) {
        // 1. nothing scrolls, except a text field inside itself
        val scrolling = rule.onAllNodes(hasScrollAction() and !hasSetTextAction()).fetchSemanticsNodes()
        assertTrue("scrollable node: ${scrolling.map { it.config }}", scrolling.isEmpty())

        // 2. everything pressable or typeable lies inside the window (compared with the window's own bounds: the window may be
        //    larger than the screen the test runs on)
        val win = rule.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val pressable: SemanticsMatcher = hasClickAction() or hasSetTextAction()
        val nodes = rule.onAllNodes(pressable).fetchSemanticsNodes()
        for (node in nodes) {
            val b = node.boundsInRoot
            assertTrue(
                "outside the window $win: $b of ${node.config}",
                b.left >= win.left - 1f && b.top >= win.top - 1f && b.right <= win.right + 1f && b.bottom <= win.bottom + 1f,
            )
        }

        // 3. so does every text that is on the screen (nothing is cut off at the bottom of the window)
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
        for (node in texts) {
            val b = node.boundsInRoot
            if (b.width <= 2f && b.height <= 2f) continue // the title that is only there for TalkBack
            // Unsupported by design (spec 3.2): the smallest phone, the largest font, the keyboard open and an error to show. The
            // fields stay on screen at the top, the red line goes under the keyboard; with the keyboard closed it is there.
            val text = node.config[SemanticsProperties.Text].joinToString { it.text }
            if (ime && window.name == "floor" && font >= 2f && text == statusError) continue
            assertTrue(
                "text outside the window $win: $b of $text",
                b.left >= win.left - 1f && b.top >= win.top - 1f && b.right <= win.right + 1f && b.bottom <= win.bottom + 1f,
            )
        }

        // 4. there is something to work with (with the keyboard open the last rung may have only the fields left)
        assertTrue("nothing to press or type into", nodes.isNotEmpty())
    }
}
