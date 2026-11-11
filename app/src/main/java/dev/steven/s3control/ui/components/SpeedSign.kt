package dev.steven.s3control.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.steven.s3control.ui.theme.S3

/**
 * A speed-limit road sign. Selected = the real sign (white disc, red ring, black numerals);
 * unselected = an outline waiting to be put up.
 */
@Composable
fun SpeedSign(
    kmh: Int,
    region: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = S3.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ring by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "ring",
    )
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    val disc by animateColorAsState(if (selected) SignWhite else Color.Transparent, label = "disc")
    val numeral by animateColorAsState(
        when {
            !enabled -> c.rib
            selected -> SignBlack
            else -> c.fog
        },
        label = "numeral",
    )
    val ringColor = if (enabled) lerpColor(c.rib, c.signal, ring) else c.rib

    Column(
        modifier
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = interaction,
                indication = null,
                onClick = { haptics.confirm(); onClick() },
            )
            .semantics(mergeDescendants = true) { contentDescription = "$kmh km/h, $region" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(S3.s),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).scale(press), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val outer = size.minDimension / 2f
                val stroke = outer * (0.07f + 0.13f * ring)
                drawCircle(disc, outer - stroke / 2f)
                drawCircle(ringColor, outer - stroke / 2f, style = Stroke(stroke))
            }
            Text(
                kmh.toString(),
                style = S3.type.dataLarge.copy(fontSize = 30.sp, lineHeight = 30.sp),
                color = numeral,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            region,
            style = S3.type.caption,
            color = if (selected) c.paint else c.fog,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(0.dp))
    }
}

private val SignWhite = Color(0xFFF6F7F8)
private val SignBlack = Color(0xFF15171A)

fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
