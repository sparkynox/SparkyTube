package dev.sparkynox.sparkytube.plugins

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.sparkynox.sparkytube.ui.SparkyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// plugin manager: install from a file, a link or pasted code, switch on/off, remove
class PluginsActivity : AppCompatActivity() {

    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var messageOk by mutableStateOf(true)
    private var tick by mutableIntStateOf(0)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runInstall { PluginStore.installAuto(this, bytes(uri)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setContent { SparkyTheme { PluginsScreen() } }
        })
    }

    private fun bytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Couldn't open the file")
        input.use {
            val data = it.readBytes()
            if (data.size > 2 * 1024 * 1024) throw IllegalArgumentException("File is too big")
            return data
        }
    }

    private fun runInstall(work: () -> PluginStore.Plugin) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { work() }
                message = "Installed ${p.name} ${p.version}"
                messageOk = true
            } catch (e: Exception) {
                message = e.message ?: "Install failed"
                messageOk = false
            }
            busy = false
            tick++
        }
    }

    @Composable
    private fun PluginsScreen() {
        @Suppress("UNUSED_VARIABLE") val t = tick
        val plugins = PluginStore.list(this)
        var urlDialog by remember { mutableStateOf(false) }
        var codeDialog by remember { mutableStateOf(false) }
        var removing by remember { mutableStateOf<PluginStore.Plugin?>(null) }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(
                        "Plugins", Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    )
                }

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "install") {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Install a plugin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Plugins are userscripts or bundles that run inside the YouTube page. Only install ones you trust.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("File") }
                                    FilledTonalButton(onClick = { urlDialog = true }, enabled = !busy) { Text("Link") }
                                    FilledTonalButton(onClick = { codeDialog = true }, enabled = !busy) { Text("Paste code") }
                                }
                                if (busy) Text("Installing…", style = MaterialTheme.typography.bodySmall)
                                if (message.isNotBlank()) Text(
                                    message, style = MaterialTheme.typography.bodySmall,
                                    color = if (messageOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    if (plugins.isEmpty()) item(key = "empty") {
                        Text(
                            "Nothing installed yet.", Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(plugins, key = { it.id }) { p ->
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            listOf(p.version, p.author).filter { it.isNotBlank() }.joinToString(" • "),
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(checked = p.enabled, onCheckedChange = {
                                        PluginStore.setEnabled(this@PluginsActivity, p.id, it)
                                        tick++
                                    })
                                }
                                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium)
                                if (p.permissions.isNotEmpty()) Text(
                                    "Can use: " + p.permissions.joinToString(", "),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { removing = p }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }

        if (urlDialog) {
            var url by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { urlDialog = false },
                title = { Text("Install from link") },
                text = {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(onClick = {
                        urlDialog = false
                        val u = url.trim()
                        if (u.isNotEmpty()) runInstall { PluginStore.installFromUrl(this@PluginsActivity, u) }
                    }) { Text("Install") }
                },
                dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("Cancel") } },
            )
        }
        if (codeDialog) {
            var code by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { codeDialog = false },
                title = { Text("Paste userscript") },
                text = {
                    OutlinedTextField(code, { code = it }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(onClick = {
                        codeDialog = false
                        if (code.isNotBlank()) runInstall { PluginStore.install(this@PluginsActivity, PluginStore.parseUserscript(code)) }
                    }) { Text("Install") }
                },
                dismissButton = { TextButton(onClick = { codeDialog = false }) { Text("Cancel") } },
            )
        }
        removing?.let { p ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text("Remove ${p.name}?") },
                text = { Text("Its data is deleted too.") },
                confirmButton = {
                    TextButton(onClick = {
                        PluginStore.remove(this@PluginsActivity, p.id)
                        removing = null
                        tick++
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
            )
        }
    }
}
