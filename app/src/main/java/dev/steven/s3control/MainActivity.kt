package dev.steven.s3control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.steven.s3control.data.ThemeChoice
import dev.steven.s3control.ui.AppRoot
import dev.steven.s3control.ui.components.OutburstBlockWhile
import dev.steven.s3control.ui.intro.IntroFilm
import dev.steven.s3control.ui.intro.IntroHandoff
import dev.steven.s3control.ui.intro.LocalIntroHandoff
import dev.steven.s3control.ui.theme.S3Theme
import androidx.compose.runtime.withFrameNanos

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.DARK -> true
                ThemeChoice.LIGHT -> false
            }

            // The opening plays by itself only until it has been seen once (introSeen is set when its reveal or a
            // skip starts), and on demand (Bike, About). The first screen composes behind it and waits for the
            // handoff: it is released one frame after the film left, never earlier.
            val handoff = remember { IntroHandoff(holding = !settings.introSeen) }
            var playing by rememberSaveable { mutableStateOf(!settings.introSeen) }
            var resumeMs by rememberSaveable { mutableLongStateOf(0L) }
            val replay by vm.introReplay.collectAsStateWithLifecycle()
            // a recreated activity must not replay what was asked for before it
            var handled by rememberSaveable { mutableIntStateOf(replay) }
            LaunchedEffect(replay) {
                if (replay != handled) {
                    handled = replay
                    resumeMs = 0L
                    playing = true
                }
            }
            // Order matters: the film (and with it OutburstBlockWhile) is gone, one frame passes, only then the
            // first screen's call may start. Released earlier, the gate would deny it and burn it for good.
            LaunchedEffect(playing) {
                if (!playing) {
                    withFrameNanos { }
                    handoff.release()
                }
            }
            // The film is always a night scene: light status bar icons while it plays, the theme's own once the reveal
            // has crossed the middle of the screen (the day screen under it would otherwise get white icons for 0.3 s).
            var revealed by remember { mutableStateOf(false) }
            LaunchedEffect(playing) { if (!playing) revealed = false }
            DisposableEffect(dark, playing, revealed) {
                val style = if (dark || (playing && !revealed)) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            S3Theme(darkTheme = dark) {
                OutburstBlockWhile(playing)
                Box {
                    // nothing underneath is read out, focused or called out while the film plays
                    Box(if (playing) Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false } else Modifier) {
                        CompositionLocalProvider(LocalIntroHandoff provides handoff) { AppRoot(vm) }
                    }
                    if (playing) {
                        // read once, without a subscription: the film reports its clock back every 250 ms, and
                        // that must not recompose the app around it
                        val startAt = remember { Snapshot.withoutReadObservation { resumeMs } }
                        IntroFilm(
                            resumeMs = startAt,
                            handoff = handoff,
                            onClock = { resumeMs = it },
                            onSeen = { vm.updateSettings { it.copy(introSeen = true) } },
                            onReveal = { revealed = true },
                            onFinished = { playing = false },
                        )
                    }
                }
            }
        }
    }
}
