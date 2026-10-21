package dev.sparkynox.sparkytube.update

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.sparkynox.sparkytube.ui.SparkyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// In-app updater screen. Mandatory updates (release notes with [force]) have no back and no Later.
class UpdateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        // opened by the app on start because a newer version exists (shows "Later" unless mandatory)
        const val EXTRA_PROMPT = "prompt"
    }

    private fun forced(): Boolean {
        val l = UpdateManager.latest
        return l != null && l.mandatory && UpdateManager.hasUpdate(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = intent?.getBooleanExtra(EXTRA_PROMPT, false) == true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!forced()) finish()
            }
        })

        setContentView(ComposeView(this).apply {
            setContent {
                SparkyTheme {
                    UpdateScreen(
                        prompt = prompt,
                        onCheck = { lifecycleScope.launch { UpdateManager.checkNow(this@UpdateActivity) } },
                        onClose = { if (!forced()) finish() },
                    )
                }
            }
        })
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
private fun UpdateScreen(prompt: Boolean, onCheck: () -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // the manager's fields are plain volatiles, a counter makes compose re-read them
    var tick by mutableIntStateOf(0)
    LaunchedEffect(Unit) {
        if (UpdateManager.latest == null) onCheck()
        while (true) { delay(400); tick++ }
    }
    @Suppress("UNUSED_EXPRESSION") tick

    val m = UpdateManager
    val l = m.latest
    val hasUpdate = m.hasUpdate(ctx)
    val mandatory = l != null && l.mandatory && hasUpdate
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (hasUpdate) "Update available" else "Updates",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                )
                if (!mandatory) TextButton(onClick = onClose) { Text(if (prompt && hasUpdate) "Later" else "Close") }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Installed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${m.installedName(ctx)} (${m.installedCode(ctx)})", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Latest", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            m.phase == UpdateManager.Phase.CHECKING && l == null -> "Checking…"
                            l == null -> if (m.lastCheckOk) "Nothing found" else "Couldn't reach GitHub"
                            else -> l.versionName + if (l.size > 0) "  •  ${fmtBytes(l.size)}" else ""
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (l != null && !hasUpdate) {
                        Text("You're on the newest version.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (mandatory) {
                        Text(
                            "This update is required to keep using SparkyTube.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (l != null && l.changelog.isNotBlank()) {
                Text("What's new", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                ) {
                    Text(
                        l.changelog,
                        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (m.phase == UpdateManager.Phase.DOWNLOADING) {
                val total = m.total
                if (total > 0) LinearProgressIndicator(progress = { (m.downloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "${fmtBytes(m.downloaded)}" + (if (total > 0) " / ${fmtBytes(total)}" else "") +
                        (if (m.speed > 0) "  •  ${fmtBytes(m.speed)}/s" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (m.error.isNotBlank()) {
                Text(m.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            when {
                m.phase == UpdateManager.Phase.DOWNLOADING -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Downloading…") }
                hasUpdate && m.phase == UpdateManager.Phase.READY ->
                    Button(onClick = { m.install(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Install") }
                hasUpdate ->
                    Button(onClick = { m.startDownload(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (m.phase == UpdateManager.Phase.ERROR) "Try again" else "Update now")
                    }
                else -> OutlinedButton(
                    onClick = { scope.launch { m.checkNow(ctx) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = m.phase != UpdateManager.Phase.CHECKING,
                ) { Text("Check for updates") }
            }
        }
    }
}
