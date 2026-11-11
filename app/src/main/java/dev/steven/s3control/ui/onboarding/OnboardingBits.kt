package dev.steven.s3control.ui.onboarding

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.steven.s3control.ui.theme.S3

/**
 * The status slot: one or two lines of plain text, in signal red for an error. The node is there even when it is empty, so the
 * first message is announced as a live region and an error never moves a button (the frame reserves two lines for it).
 */
@Composable
internal fun StatusLine(text: String?, modifier: Modifier = Modifier, color: Color = S3.colors.signal) {
    Box(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        if (text != null) Text(text, style = S3.type.label, color = color)
    }
}

/**
 * A way out that must be there but must not compete with the step's one or two buttons ("Change key", "Revoke Bluetooth access"):
 * one centred line of text in the Hand's plain cut, in fog and underlined, 48 dp high to touch. It is a button for TalkBack, and says what it
 * does in its words, not in its looks.
 */
@Composable
internal fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = S3.type.label, color = S3.colors.fog, textDecoration = TextDecoration.Underline)
    }
}

internal tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** This app's own settings page: "Permissions" lives there. */
internal fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
