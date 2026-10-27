package dev.sparkynox.sparkytube.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// simple message popup: artwork, title, text and one or two buttons
@Composable
fun MessagePopup(
    emote: Emote,
    title: String,
    message: String,
    confirmText: String = "OK",
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: () -> Unit = {},
    cancelable: Boolean = true,
) {
    Dialog(
        onDismissRequest = { if (cancelable) onConfirm() },
        properties = DialogProperties(dismissOnBackPress = cancelable, dismissOnClickOutside = cancelable),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painterResource(emote.res), contentDescription = null,
                    modifier = Modifier.height(120.dp), contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (dismissText != null) {
                        TextButton(onClick = onDismiss) { Text(dismissText) }
                    }
                    TextButton(onClick = onConfirm) { Text(confirmText) }
                }
            }
        }
    }
}

// shows the popup from a normal (View based) activity. onDismiss runs for the secondary button
fun showMessagePopup(
    activity: Activity,
    emote: Emote,
    title: String,
    message: String,
    confirmText: String = "OK",
    onConfirm: () -> Unit = {},
    dismissText: String? = null,
    onDismiss: () -> Unit = {},
    cancelable: Boolean = true,
) {
    val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
    val host = ComposeView(activity)
    fun close() {
        host.post {
            host.disposeComposition()
            root.removeView(host)
        }
    }
    host.setContent {
        SparkyTheme {
            var open by remember { mutableStateOf(true) }
            if (open) {
                MessagePopup(
                    emote, title, message, confirmText,
                    onConfirm = { open = false; onConfirm(); close() },
                    dismissText = dismissText,
                    onDismiss = { open = false; onDismiss(); close() },
                    cancelable = cancelable,
                )
            }
        }
    }
    root.addView(host, ViewGroup.LayoutParams(0, 0))
}
