package dev.steven.s3control.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

object S3 {
    val colors: S3Palette
        @Composable @ReadOnlyComposable get() = LocalS3Palette.current
    val type: S3Type
        @Composable @ReadOnlyComposable get() = LocalS3Type.current

    /** Spacing steps. Sections breathe at [xl]; related things sit at [s]. */
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 40.dp
    val xxl = 64.dp

    /** Side gutter on the cover screen; panes on the inner screen use [l]. */
    val gutter = 20.dp
}

@Composable
fun S3Theme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) NightRide else Daylight
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.led, onPrimary = palette.onLed,
            secondary = palette.lamp, onSecondary = palette.asphalt,
            error = palette.signal, onError = palette.onSignal,
            background = palette.asphalt, onBackground = palette.paint,
            surface = palette.frame, onSurface = palette.paint,
            surfaceVariant = palette.rib, onSurfaceVariant = palette.fog,
            outline = palette.rib, outlineVariant = palette.rib,
            surfaceContainer = palette.frame, surfaceContainerHigh = palette.frame,
            surfaceContainerHighest = palette.rib, surfaceContainerLow = palette.frame,
        )
    } else {
        lightColorScheme(
            primary = palette.led, onPrimary = palette.onLed,
            secondary = palette.lamp, onSecondary = palette.frame,
            error = palette.signal, onError = palette.onSignal,
            background = palette.asphalt, onBackground = palette.paint,
            surface = palette.frame, onSurface = palette.paint,
            surfaceVariant = palette.rib, onSurfaceVariant = palette.fog,
            outline = palette.rib, outlineVariant = palette.rib,
            surfaceContainer = palette.frame, surfaceContainerHigh = palette.frame,
            surfaceContainerHighest = palette.rib, surfaceContainerLow = palette.frame,
        )
    }
    // Archivo has no Cyrillic: Russian runs in Roboto Flex (see Type.kt)
    val type = s3TypographyFor(LocalConfiguration.current.locales[0]?.language)
    val m3Type = Typography(
        displayLarge = type.hero, displayMedium = type.display, displaySmall = type.title,
        headlineLarge = type.display, headlineMedium = type.title, headlineSmall = type.section,
        titleLarge = type.section, titleMedium = type.bodyStrong, titleSmall = type.label,
        bodyLarge = type.body, bodyMedium = type.body, bodySmall = type.caption,
        labelLarge = type.label, labelMedium = type.label, labelSmall = type.caption,
    )
    CompositionLocalProvider(LocalS3Palette provides palette, LocalS3Type provides type) {
        MaterialTheme(colorScheme = scheme, typography = m3Type, content = content)
    }
}
