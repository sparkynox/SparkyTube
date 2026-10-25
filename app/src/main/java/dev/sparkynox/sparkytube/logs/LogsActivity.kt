package dev.sparkynox.sparkytube.logs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.sparkynox.sparkytube.ui.SparkyTheme
import kotlinx.coroutines.delay
import java.io.File

// session log + last crash. Opened from the top bar menu or from the crash screen.
class LogsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setContent { SparkyTheme { LogsScreen() } }
        })
    }

    @Composable
    private fun LogsScreen() {
        // the files can be megabytes, one Text with all of it freezes and then kills the screen. show the tail only
        var crash by remember { mutableStateOf(LogRecorder.getLastCrash(this@LogsActivity)?.take(12_000)) }
        var session by remember { mutableStateOf(LogRecorder.getSessionLog(this@LogsActivity).takeLast(60_000)) }
        var confirmClear by remember { mutableStateOf(false) }

        // keeps up with new lines while the screen is open
        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                session = LogRecorder.getSessionLog(this@LogsActivity).takeLast(60_000)
            }
        }

        val scroll = rememberScrollStateKeyed()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(
                        "Logs", Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = { shareLogs() }) { Icon(Icons.Filled.Share, "Share") }
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, "Clear") }
                }

                crash?.let { c ->
                    val summary = c.lineSequence().drop(1).firstOrNull { it.isNotBlank() } ?: ""
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("The app crashed last time", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                    }
                }

                val text = buildString {
                    crash?.let {
                        append("=== LAST CRASH ===\n"); append(it); append("\n\n=== CURRENT SESSION LOG ===\n")
                    }
                    append(session.ifBlank { "(no log lines recorded yet this session)" })
                }
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        text,
                        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear logs?") },
                text = { Text("This deletes the current session log and last crash report.") },
                confirmButton = {
                    TextButton(onClick = {
                        LogRecorder.clearSessionLog(this@LogsActivity)
                        LogRecorder.clearLastCrash(this@LogsActivity)
                        crash = null
                        session = ""
                        confirmClear = false
                        Toast.makeText(this@LogsActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }) { Text("Clear") }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun rememberScrollStateKeyed() = rememberScrollState()

    // session log + crash file as attachments (content:// via the FileProvider)
    private fun shareLogs() {
        try {
            val sessionFile = File(filesDir, "sparky_session_log.txt")
            val crashFile = File(filesDir, "sparky_last_crash.txt")
            val uris = ArrayList<Uri>()
            val authority = "$packageName.fileprovider"
            if (sessionFile.exists()) uris.add(FileProvider.getUriForFile(this, authority, sessionFile))
            if (crashFile.exists()) uris.add(FileProvider.getUriForFile(this, authority, crashFile))
            if (uris.isEmpty()) {
                Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uris[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "text/plain"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Share SparkyTube logs"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't share logs", Toast.LENGTH_SHORT).show()
        }
    }
}
