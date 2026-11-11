package dev.steven.s3control.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every colour has one job. Nothing here is decorative:
 * - asphalt/frame/rib build the matte surfaces (road, bike frame, tube ribs)
 * - led is the top-tube dot matrix: lit dots, primary emphasis, "unlocked"
 * - signal is traffic-sign red: speed-limit rings, locked, alarm
 * - lamp is bike-light amber: charging, warnings, lights
 */
@Immutable
data class S3Palette(
    val asphalt: Color,
    val frame: Color,
    val rib: Color,
    val led: Color,
    /** An unlit matrix dot: present, but never competing with the lit ones. */
    val ledOff: Color,
    val ledGlow: Color,
    val signal: Color,
    val lamp: Color,
    val paint: Color,
    val fog: Color,
    val onSignal: Color,
    val onLed: Color,
    val isDark: Boolean,
)

val NightRide = S3Palette(
    asphalt = Color(0xFF191C21),
    frame = Color(0xFF252A31),
    rib = Color(0xFF353B45),
    led = Color(0xFFD6ECFF),
    ledOff = Color(0xFF2E343D),
    ledGlow = Color(0x55A9D4FF),
    signal = Color(0xFFFF5A61),
    lamp = Color(0xFFFFB43D),
    paint = Color(0xFFF1F2F4),
    fog = Color(0xFF8E96A1),
    // Dark text on the lighter night red: 5.6:1 (white would be 3.3:1).
    onSignal = Color(0xFF191C21),
    onLed = Color(0xFF191C21),
    isDark = true,
)

val Daylight = S3Palette(
    asphalt = Color(0xFFDFE2E5),
    frame = Color(0xFFF2F4F5),
    rib = Color(0xFFC3C8CF),
    led = Color(0xFF1A2230),
    ledOff = Color(0xFFD2D6DB),
    ledGlow = Color(0x00000000),
    signal = Color(0xFFB80F18),
    lamp = Color(0xFF8A5100),
    paint = Color(0xFF191C21),
    fog = Color(0xFF58606B),
    onSignal = Color(0xFFFFFFFF),
    onLed = Color(0xFFF2F4F5),
    isDark = false,
)

val LocalS3Palette = staticCompositionLocalOf { NightRide }
