package dev.sparkynox.sparkytube.download

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sparkynox.sparkytube.ui.SparkyTheme
import java.io.File
import java.util.Locale

// Offline playlist: every video file SparkyTube downloaded to the public Downloads folder.
// Plays them through OfflinePlayerActivity, no network needed, and deletes the ones you don't want.
class OfflineLibraryActivity : AppCompatActivity() {

    private var files by mutableStateOf<List<File>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setContent { SparkyTheme { LibraryScreen() } }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // plain directory listing, Downloads is a small flat folder and this needs no media permission
    private fun refreshList() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val ext = setOf("mp4", "webm", "mkv")
        files = dir.listFiles { f -> f.isFile && f.extension.lowercase(Locale.US) in ext }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun play(file: File) {
        startActivity(
            Intent(this, OfflinePlayerActivity::class.java)
                .putExtra(OfflinePlayerActivity.EXTRA_FILE_PATH, file.absolutePath)
                .putExtra(OfflinePlayerActivity.EXTRA_TITLE, file.nameWithoutExtension)
        )
    }

    @Composable
    private fun LibraryScreen() {
        var deleting by remember { mutableStateOf<File?>(null) }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(
                        "Offline library", Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (files.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No downloaded videos yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(files, key = { it.absolutePath }) { f ->
                            Surface(
                                Modifier.fillMaxWidth().clickable { play(f) },
                                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                        Text(
                                            f.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${formatBytes(f.length())}  \u2022  ${f.extension.uppercase(Locale.US)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { deleting = f }) { Icon(Icons.Filled.Delete, "Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }

        deleting?.let { f ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete this download?") },
                text = { Text(f.nameWithoutExtension) },
                confirmButton = {
                    TextButton(onClick = {
                        val ok = f.delete()
                        Toast.makeText(this@OfflineLibraryActivity, if (ok) "Deleted" else "Couldn't delete file", Toast.LENGTH_SHORT).show()
                        deleting = null
                        refreshList()
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
        }
    }
}
