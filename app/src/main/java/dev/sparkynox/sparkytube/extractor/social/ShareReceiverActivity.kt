package dev.sparkynox.sparkytube.extractor.social

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.download.SocialDownloader
import dev.sparkynox.sparkytube.lunokit.glass.LunoSheet
import dev.sparkynox.sparkytube.settings.SettingsPrefs
import dev.sparkynox.sparkytube.ui.LunoLoader
import dev.sparkynox.sparkytube.ui.SparkyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SparkyTube in the share menu: share a link from Instagram, X, TikTok or Facebook to SparkyTube and it
 * resolves it and downloads it. (Settings > Social downloader > "Download from a link" does the same with the copied link.)
 */
class ShareReceiverActivity : AppCompatActivity() {

    private var state by mutableStateOf<State>(State.Loading)
    private val downloaded = mutableStateMapOf<Int, String>() // item index -> "Downloading" / "Failed"

    sealed class State {
        object Loading : State()
        class Done(val r: SocialResult) : State()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when {
            intent?.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            intent?.hasExtra(EXTRA_PASTE) == true -> clipboardText()
            else -> intent?.dataString
        }.orEmpty()

        setContent { SparkyTheme { Sheet() } }

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { SocialResolver.resolve(this@ShareReceiverActivity, text) }
            state = State.Done(r)
            if (r.ok && SettingsPrefs.isSocialAutoDownload(this@ShareReceiverActivity)) {
                r.best().forEach { m -> start(r, r.items.indexOf(m)) }
            }
        }
    }

    private fun clipboardText(): String? =
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    private fun start(r: SocialResult, index: Int) {
        val m = r.items.getOrNull(index) ?: return
        val id = SocialDownloader.enqueue(this, r, m)
        downloaded[index] = if (id != null) "Downloading" else "Failed"
        if (id != null) Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun Sheet() {
        LunoSheet(onDismissRequest = { finish() }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                when (val s = state) {
                    State.Loading -> Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        LunoLoader(size = 28.dp)
                        Text("Looking at the link...", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                    is State.Done -> Result(s.r)
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.Result(r: SocialResult) {
        if (!r.ok) {
            Text(
                if (r.site.isBlank()) "Can't download that" else "${r.site}: can't download that",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(r.error ?: "Nothing found", Modifier.padding(top = 6.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r.needsLogin) Button(onClick = {
                    startActivity(Intent(this@ShareReceiverActivity, SocialLoginActivity::class.java).putExtra(SocialLoginActivity.EXTRA_SITE, r.site))
                    finish()
                }) { Text("Sign in to ${r.site}") }
                OutlinedButton(onClick = { finish() }) { Text("Close") }
            }
            return
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.thumbnail != null) AsyncImage(
                r.thumbnail, null, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f).padding(start = if (r.thumbnail != null) 14.dp else 0.dp)) {
                Text(
                    r.title.ifBlank { r.site }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(r.site, r.author.takeIf { it.isNotBlank() }?.let { "@$it" }).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.height(10.dp))
        r.items.forEachIndexed { i, m ->
            val status = downloaded[i]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = status == null) { start(r, i) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${m.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${m.ext}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    status ?: "Download", style = MaterialTheme.typography.labelLarge,
                    color = if (status == "Failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        val pics = r.items.withIndex().filter { it.value.kind == MediaKind.IMAGE && downloaded[it.index] == null }
        if (pics.size > 1) TextButton(onClick = { pics.forEach { start(r, it.index) } }) { Text("Download all ${pics.size} pictures") }
        TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
    }

    companion object {
        const val EXTRA_PASTE = "paste_from_clipboard"
    }
}
