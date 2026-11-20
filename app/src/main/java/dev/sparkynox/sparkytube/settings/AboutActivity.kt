package dev.sparkynox.sparkytube.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import dev.sparkynox.sparkytube.ui.SparkyTheme
import dev.sparkynox.sparkytube.update.UpdateActivity

/** About screen: who made it, where the code lives, what it is built on, and a device block to paste into bug reports. */
class AboutActivity : AppCompatActivity() {

    private val repo = "https://github.com/sparkynox/SparkyTube"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SparkyTheme { About() } }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    @Suppress("DEPRECATION")
    private fun versionCode(): Long = try {
        val i = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) i.longVersionCode else i.versionCode.toLong()
    } catch (e: Exception) { 0L }

    private fun deviceInfo(): String = buildString {
        appendLine("SparkyTube ${versionName()} (${versionCode()})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        append("ABI ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    @Composable
    private fun About() {
        val ctx = this
        val icon = remember {
            runCatching { packageManager.getApplicationIcon(packageName).toBitmap(256, 256).asImageBitmap() }.getOrNull()
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = 32.dp)) {
            Box(
                Modifier.padding(8.dp).size(44.dp).clip(CircleShape).clickable { finish() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }

            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) Image(icon, null, Modifier.size(96.dp).clip(RoundedCornerShape(26.dp)))
                Text("SparkyTube", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Version ${versionName()}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "YouTube without the clutter. Ad free, light on data, fast on a weak phone.",
                    Modifier.padding(top = 10.dp, start = 36.dp, end = 36.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Group("Developer") {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        "https://github.com/sparkynox.png", null, Modifier.size(52.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("SparkyNox", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Builds it, on a phone, in Termux", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Line("GitHub", "github.com/sparkynox") { open("https://github.com/sparkynox") }
                Line("Telegram", "@SparkyNox") { open("https://t.me/SparkyNox") }
                Line("Instagram", "@sparkynox07") { open("https://instagram.com/sparkynox07") }
                Line("Modrinth", "modrinth.com/user/sparkynox") { open("https://www.modrinth.com/user/sparkynox") }
            }

            Group("Project") {
                Line("Source code", "Open on GitHub") { open(repo) }
                Line("What's new", "Release notes and changelog") { open("$repo/releases") }
                Line("Report a problem", "Open an issue (copy the device info below first)") { open("$repo/issues") }
                Line("Check for updates", "Opens the update screen") { startActivity(Intent(ctx, UpdateActivity::class.java)) }
                Line("Licenses", "SparkyTube and the libraries it uses") { open("$repo/blob/main/THIRD_PARTY_NOTICES.md") }
            }

            Group("Built with") {
                Credit("LazyLuno", "Our own C++ extractor, the fast default")
                Credit("NewPipe Extractor", "Full quality list and fallback")
                Credit("Media3 / ExoPlayer", "Playback")
                Credit("SponsorBlock", "Skip sponsor segments")
                Credit("Return YouTube Dislike", "Dislike counts")
                Credit("LRCLIB", "Synced lyrics")
                Credit("Coil", "Images")
            }

            Group("Device info") {
                Text(
                    deviceInfo(), Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Line("Copy", "Put the info above on the clipboard") {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SparkyTube", deviceInfo()))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }
            }

            Text(
                "Made with \u2665 on a phone", Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun Group(title: String, content: @Composable () -> Unit) {
        Text(
            title, Modifier.padding(start = 24.dp, bottom = 6.dp, top = 6.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        )
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) { Column { content() } }
    }

    @Composable
    private fun Line(title: String, sub: String, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Credit(name: String, what: String) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
