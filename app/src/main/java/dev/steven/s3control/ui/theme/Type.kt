package dev.steven.s3control.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.steven.s3control.R

/*
 * One family, Archivo, pushed along its width axis:
 *  - expanded (wdth 125) black for headings and the numbers that matter
 *  - normal (wdth 100) for reading
 *  - condensed (wdth 75) for dense values that sit next to each other
 * Scale: 13 / 16 / 20 / 26 / 34 / 44 / 64.
 */

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
    ),
)

val ArchivoExpanded = FontFamily(
    archivo(700, 125f),
    archivo(800, 125f),
    archivo(900, 125f),
)

val Archivo = FontFamily(
    archivo(400, 100f),
    archivo(500, 100f),
    archivo(600, 100f),
    archivo(700, 100f),
)

val ArchivoCondensed = FontFamily(
    archivo(500, 75f),
    archivo(600, 75f),
    archivo(700, 75f),
    archivo(800, 75f),
)

/*
 * Russian: Archivo has no Cyrillic, and the system would fall back to Roboto in its normal width and in whatever weight it
 * has, mixed with Archivo's Latin in the same line. In `ru` the whole UI runs in Roboto Flex (subset: Cyrillic, Latin
 * basics, punctuation; axes wght 400-900, wdth 60-151, XTRA 380-603) at the same three widths: expanded is the Klappe,
 * normal the Hand, condensed data. Calibrated against Archivo by the advance of the capital Н / H and of the digits (em):
 *  - expanded (Archivo 900/125, H 1.009): wdth 151 and XTRA 603 give Н 0.968 (wdth 125 alone: 0.79, a Klappe that has lost its mouth)
 *  - normal (Archivo 400/100, H 0.736): the font's own default, Н 0.712
 *  - condensed (Archivo 600/75, digit 0.464): wdth 60 and XTRA 380 give the digit 0.464 exactly, Н 0.605 (Archivo 0.575)
 * The matrix and the logo are dots and are not affected.
 */
@OptIn(ExperimentalTextApi::class)
private fun robotoFlex(weight: Int, width: Float, xtra: Float) = Font(
    R.font.roboto_flex_cyrillic,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("XTRA", xtra),
    ),
)

val RobotoFlexExpanded = FontFamily(
    robotoFlex(700, 151f, 603f),
    robotoFlex(800, 151f, 603f),
    robotoFlex(900, 151f, 603f),
)

val RobotoFlexNormal = FontFamily(
    robotoFlex(400, 100f, 468f),
    robotoFlex(500, 100f, 468f),
    robotoFlex(600, 100f, 468f),
    robotoFlex(700, 100f, 468f),
)

val RobotoFlexCondensed = FontFamily(
    robotoFlex(500, 60f, 380f),
    robotoFlex(600, 60f, 380f),
    robotoFlex(700, 60f, 380f),
    robotoFlex(800, 60f, 380f),
)

@Immutable
data class S3Type(
    val hero: TextStyle,
    val display: TextStyle,
    val title: TextStyle,
    val section: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val data: TextStyle,
    val dataLarge: TextStyle,
)

private fun typography(expanded: FontFamily, normal: FontFamily, condensed: FontFamily) = S3Type(
    hero = TextStyle(fontFamily = expanded, fontWeight = FontWeight(900), fontSize = 64.sp, lineHeight = 60.sp, letterSpacing = (-0.02).em),
    display = TextStyle(fontFamily = expanded, fontWeight = FontWeight(900), fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-0.015).em),
    title = TextStyle(fontFamily = expanded, fontWeight = FontWeight(800), fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
    section = TextStyle(fontFamily = expanded, fontWeight = FontWeight(700), fontSize = 20.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = normal, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyStrong = TextStyle(fontFamily = normal, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 24.sp),
    label = TextStyle(fontFamily = normal, fontWeight = FontWeight(500), fontSize = 14.sp, lineHeight = 18.sp),
    caption = TextStyle(fontFamily = normal, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 18.sp),
    data = TextStyle(fontFamily = condensed, fontWeight = FontWeight(600), fontSize = 20.sp, lineHeight = 22.sp),
    dataLarge = TextStyle(fontFamily = condensed, fontWeight = FontWeight(800), fontSize = 34.sp, lineHeight = 34.sp),
)

/** The type scale in Archivo: every language but Russian. */
val S3Typography = typography(ArchivoExpanded, Archivo, ArchivoCondensed)

/** The same scale in Roboto Flex, for Russian. */
val S3TypographyCyrillic = typography(RobotoFlexExpanded, RobotoFlexNormal, RobotoFlexCondensed)

/** The scale for [language] (an ISO 639 code): Roboto Flex for `ru`, Archivo otherwise. */
fun s3TypographyFor(language: String?): S3Type = if (language == "ru") S3TypographyCyrillic else S3Typography

val LocalS3Type = staticCompositionLocalOf { S3Typography }
