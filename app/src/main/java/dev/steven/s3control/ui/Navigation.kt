package dev.steven.s3control.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import dev.steven.s3control.R

enum class Destination(@StringRes val labelRes: Int) {
    RIDE(R.string.shell_dest_ride),
    SOUND(R.string.shell_dest_sound),
    DIAGNOSIS(R.string.shell_dest_diagnosis),
    BIKE(R.string.shell_dest_bike),
}

/** Navigation actions any screen may trigger; provided by AppRoot. */
class Navigator(
    val go: (Destination) -> Unit,
    val openGarage: () -> Unit,
    val closeOverlay: () -> Unit,
)

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator provided") }
