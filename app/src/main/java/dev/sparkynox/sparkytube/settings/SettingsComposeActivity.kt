package dev.sparkynox.sparkytube.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.sparkynox.sparkytube.MainActivity
import dev.sparkynox.sparkytube.download.DownloadQueue
import dev.sparkynox.sparkytube.extractor.StreamExtractor
import dev.sparkynox.sparkytube.ui.SparkyTheme
import kotlinx.coroutines.launch

// Settings, in Compose. Same rows and the same SettingsPrefs calls as the old HTML screen,
// drawn natively: category home first, then one page per category.
class SettingsComposeActivity : AppCompatActivity() {

    private class Item(
        val id: String,
        val type: String,                    // toggle | choice | link | info | css
        val title: String,
        val icon: String,
        val sub: () -> String = { "" },
        val value: () -> Any? = { null },
        val options: List<Pair<String, String>> = emptyList(),
        val enabled: () -> Boolean = { true },
        val set: (String) -> Unit = {},
        val run: () -> Unit = {}
    )

    private class Section(val key: String, val title: String, val icon: String, val sub: String, val items: List<Item>)

    companion object {
        const val EXTRA_OPEN_SECTION = "open_section"
    }

    // bumped whenever a value changed, so rows re-read their summary/value lambdas
    private var tick by mutableIntStateOf(0)
    private var openKey by mutableStateOf<String?>(null)
    private var choiceFor by mutableStateOf<Item?>(null)
    private var cssOpen by mutableStateOf(false)

    private val sections: List<Section> by lazy { buildSections() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openKey = intent?.getStringExtra(EXTRA_OPEN_SECTION)?.ifBlank { null }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openKey != null) openKey = null else finish()
            }
        })

        setContentView(ComposeView(this).apply {
            setContent { SparkyTheme { SettingsRoot() } }
        })

        // so "System update" can say up to date / available right away
        lifecycleScope.launch {
            dev.sparkynox.sparkytube.update.UpdateManager.checkNow(this@SettingsComposeActivity)
            tick++
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    private fun toggle(id: String, title: String, icon: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        Item(id, "toggle", title, icon, sub = { sub }, value = get, set = { set(it == "true") })

    private fun choice(
        id: String, title: String, icon: String, options: List<Pair<String, String>>,
        get: () -> String, set: (String) -> Unit,
        sub: (String) -> String = { "" }, enabled: () -> Boolean = { true }
    ) = Item(id, "choice", title, icon, sub = { sub(get()) }, value = get, options = options, enabled = enabled, set = set)

    private fun link(id: String, title: String, icon: String, sub: () -> String, run: () -> Unit) =
        Item(id, "link", title, icon, sub = sub, run = run)

    private fun info(id: String, title: String, icon: String, sub: () -> String) =
        Item(id, "info", title, icon, sub = sub)

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun buildSections(): List<Section> {
        val p = SettingsPrefs
        return listOf(
            Section("features", "Features", "shield", "Ad blocking, SponsorBlock, anime streaming", listOf(
                toggle("adblock", "Ad blocking", "shield", "Blocks ad/tracker domains and hides ad slots",
                        { p.isAdBlockEnabled(this) }, { p.setAdBlockEnabled(this, it) }),
                toggle("sponsorblock", "SponsorBlock", "skip", "Auto-skip sponsor segments, self-promo, interaction reminders",
                        { p.isSponsorBlockEnabled(this) }, { p.setSponsorBlockEnabled(this, it) }),
                toggle("anime", "Anime streaming", "film", "Crunchyroll wrapper, reachable from the overflow menu",
                        { p.isAnimeStreamingEnabled(this) }, { p.setAnimeStreamingEnabled(this, it) })
            )),
            Section("youtube", "YouTube", "ext", "YouTube settings and related videos", listOf(
                link("ytsettings", "YT Settings", "ext", { "Open YouTube's own settings page" }) {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_OPEN_YT_SETTINGS, true)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                        finish()
                    },
                choice("related", "Related videos fetcher", "list",
                        listOf("AUTO" to "Auto (recommended)", "JAVASCRIPT" to "JavaScript (fastest)", "NEWPIPE" to "NewPipe (most reliable)"),
                        { p.getRelatedVideosFetcher(this).name }, { p.setRelatedVideosFetcher(this, SettingsPrefs.RelatedVideosFetcher.valueOf(it)) },
                        sub = {
                            when (it) {
                                "JAVASCRIPT" -> "JavaScript — fastest, only works while the video's page is loaded"
                                "NEWPIPE" -> "NewPipe — always works, one extra network request"
                                else -> "Auto — tries JavaScript first, falls back to NewPipe"
                            }
                        })
            )),
            Section("player", "Player and quality", "play", "Default quality and data saver", listOf(
                choice("quality", "Default video quality", "sliders",
                        listOf("144", "240", "360", "480", "720", "1080").map { it to "${it}p" },
                        { p.getDefaultQualityValue(this).toString() }, { p.setDefaultQualityValue(this, it.toInt()) },
                        sub = { "${it}p — applies to new videos as they load" }),
                toggle("datasaver", "Data Saver Mode", "pulse",
                        "Blocks YouTube telemetry, disables images, starts videos at the lowest quality",
                        { p.isDataSaverEnabled(this) }, { p.setDataSaverEnabled(this, it); StreamExtractor.clearCache() })
            )),
            Section("extraction", "Extraction", "code", "Extractors, local servers and fallbacks", listOf(
                choice("extractor", "Extractor Method", "code",
                        listOf("AUTO" to "Auto", "LOCAL_SERVER" to "Local Servers", "NEWPIPE" to "NewPipe", "YT_DLP" to "yt-dlp (default)"),
                        { p.getExtractorMethod(this).name }, { p.setExtractorMethod(this, SettingsPrefs.ExtractorMethod.valueOf(it)) },
                        sub = {
                            when (it) {
                                "LOCAL_SERVER" -> "Local Servers only — set up under Local Servers above"
                                "NEWPIPE" -> "NewPipe only — skips the local server and JS fast path"
                                "YT_DLP" -> "yt-dlp first, NewPipe takes over if yt-dlp fails"
                                else -> "Auto — local server (if enabled) → JavaScript → NewPipe"
                            }
                        }),
                link("localservers", "Local Servers", "server", {
                        if (p.isLocalServerEnabled(this)) "Enabled — ${p.getLocalServerUrl(this)}" else "Off — using NewPipe/JavaScript extraction"
                    }) { open(LocalServerActivity::class.java) },
                toggle("piped", "Piped fallback", "globe",
                        "Fixes age-restricted videos by routing through a public Piped instance when NewPipe fails",
                        { p.isPipedFallbackEnabled(this) }, { p.setPipedFallbackEnabled(this, it) }),
                toggle("ytdlpfallback", "yt-dlp fallback (heavy)", "zap",
                        "Last-resort extractor for age-gated videos. Adds ~30-40MB to the app.",
                        { p.isYtDlpFallbackEnabled(this) }, { on ->
                            if (on && !p.isYtDlpFallbackEnabled(this)) {
                                AlertDialog.Builder(this)
                                    .setTitle("Enable yt-dlp fallback?")
                                    .setMessage(
                                        "This runs yt-dlp on-device as a last-resort fallback when NewPipe and Piped both fail " +
                                            "to play a video (usually age-restricted ones). It's slower than the other methods and " +
                                            "uses more storage/battery, but is the most reliable."
                                    )
                                    .setPositiveButton("Enable") { _, _ ->
                                        p.setYtDlpFallbackEnabled(this, true)
                                        tick++
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            } else {
                                p.setYtDlpFallbackEnabled(this, on)
                            }
                        })
            )),
            Section("downloads", "Downloads", "download", "Queue, progress and offline library", listOf(
                toggle("download", "Download button", "download", "Shows the download button on videos",
                        { p.isDownloadEnabled(this) }, { p.setDownloadEnabled(this, it) }),
                link("downloads", "Download manager", "layers", { "See progress, speed, and manage active downloads" }) {
                        open(dev.sparkynox.sparkytube.download.DownloadsActivity::class.java)
                    },
                choice("maxdl", "Simultaneous downloads", "layers",
                        listOf("1", "2", "3", "4").map { it to it },
                        { p.getMaxConcurrentDownloads(this).toString() }, {
                            p.setMaxConcurrentDownloads(this, it.toInt())
                            DownloadQueue.pump()
                        },
                        sub = { "$it at a time, the rest wait in the queue" }),
                link("offline", "Offline Library", "folder", { "Play or delete videos you've already downloaded" }) {
                        open(dev.sparkynox.sparkytube.download.OfflineLibraryActivity::class.java)
                    }
            )),
            Section("appearance", "Appearance", "moon", "Theme, colors and custom CSS", listOf(
                choice("theme", "Theme", "moon",
                        listOf("DARK" to "Dark", "LIGHT" to "Light", "SYSTEM" to "Follow system"),
                        { p.getThemeMode(this).name }, {
                            p.setThemeMode(this, SettingsPrefs.ThemeMode.valueOf(it))
                            // flips night mode, AppCompat recreates this screen in the new colors
                            p.applyThemeMode(this)
                        },
                        sub = { "Dark, Light, or follow your phone" }),
                toggle("dynamiccolor", "Dynamic color (Material You)", "drop",
                        "Matches app colors to your wallpaper on Android 12+. Restart app to apply.",
                        { p.isDynamicColorEnabled(this) }, { p.setDynamicColorEnabled(this, it) }),
                toggle("customcss", "Custom CSS", "code", "Your own CSS, applied on top of SparkyTube's",
                        { p.isCustomCssEnabled(this) }, { p.setCustomCssEnabled(this, it) }),
                Item("cssedit", "css", "Edit CSS", "code", sub = { "Write or paste your CSS" }, value = { p.getCustomCss(this) })
            )),
            Section("experimental", "Experimental", "spark", "Lumi AI", listOf(
                toggle("lumiai", "Experimental features (Lumi AI)", "spark",
                        "Staff only for now — not available to regular users yet",
                        { false }, { on ->
                            if (on) {
                                AlertDialog.Builder(this)
                                    .setTitle("Coming soon")
                                    .setMessage("Lumi AI is still being worked on and is staff only for now (Owner/Mod/Admin). It isn't available to regular users yet.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        })
            )),
            Section("update", "Update", "refresh", "Updates and notifications", listOf(
                link("sysupdate", "System update", "refresh", {
                        val m = dev.sparkynox.sparkytube.update.UpdateManager
                        val l = m.latest
                        when {
                            l != null && m.hasUpdate(this) -> "Version ${l.versionName} is available, tap to update"
                            l != null -> "Up to date"
                            else -> "Tap to check"
                        }
                    }) { open(dev.sparkynox.sparkytube.update.UpdateActivity::class.java) },
                info("version", "Version", "file", {
                        "${dev.sparkynox.sparkytube.update.UpdateManager.installedName(this)} " +
                            "(${dev.sparkynox.sparkytube.update.UpdateManager.installedCode(this)})"
                    }),
                toggle("updater", "Automatic update check", "refresh", "Checks for a new version in the background and when the app opens",
                        { p.isUpdaterEnabled(this) }, { p.setUpdaterEnabled(this, it) }),
                toggle("updatenotif", "Update notifications", "bell", "Show a notification when a new version or a message arrives",
                        { p.isUpdateNotificationsEnabled(this) }, { p.setUpdateNotificationsEnabled(this, it) })
            )),
            Section("app", "App", "file", "Plugins, popups and logs", listOf(
                toggle("popups", "Block all popups", "eyeoff", "Hides the contact-reminder, update, and welcome dialogs",
                        { p.arePopupsBlocked(this) }, { p.setPopupsBlocked(this, it) }),
                link("plugins", "Plugins", "layers", { "Beta: install and manage JS plugins" }) {
                        open(dev.sparkynox.sparkytube.plugins.PluginsActivity::class.java)
                    },
                link("logs", "Logs", "file", {
                        if (dev.sparkynox.sparkytube.logs.LogRecorder.getLastCrash(this) != null) "⚠ Last session crashed — tap to view"
                        else "Session log + crash reports"
                    }) { open(dev.sparkynox.sparkytube.logs.LogsActivity::class.java) }
            ))
        )
    }


    // ---------- UI

    private fun iconFor(name: String): ImageVector = when (name) {
        "shield" -> Icons.Filled.Lock
        "skip", "play" -> Icons.Filled.PlayArrow
        "film", "spark" -> Icons.Filled.Star
        "ext" -> Icons.Filled.Share
        "list", "layers", "folder" -> Icons.Filled.Menu
        "sliders" -> Icons.Filled.Settings
        "pulse" -> Icons.Filled.Favorite
        "code", "server" -> Icons.Filled.Build
        "globe", "file" -> Icons.Filled.Info
        "zap" -> Icons.Filled.Warning
        "download" -> Icons.Filled.KeyboardArrowDown
        "moon" -> Icons.Filled.Face
        "drop" -> Icons.Filled.Create
        "layout" -> Icons.Filled.Home
        "refresh" -> Icons.Filled.Refresh
        "bell" -> Icons.Filled.Notifications
        "eyeoff" -> Icons.Filled.Clear
        else -> Icons.Filled.Settings
    }

    @Composable
    private fun SettingsRoot() {
        @Suppress("UNUSED_VARIABLE") val t = tick
        val key = openKey
        val section = sections.firstOrNull { it.key == key }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (openKey != null) openKey = null else finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        section?.title ?: "Settings",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (section == null) {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(sections, key = { it.key }) { s -> CategoryCard(s) }
                    }
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(section.items, key = { it.id }) { item -> SettingRow(item) }
                    }
                }
            }
        }

        choiceFor?.let { ChoiceDialog(it) }
        if (cssOpen) CssDialog()
    }

    @Composable
    private fun CategoryCard(s: Section) {
        @Suppress("UNUSED_VARIABLE") val t = tick
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { openKey = s.key },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(s.icon)
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        s.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun RoundIcon(name: String) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(name), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }

    @Composable
    private fun SettingRow(item: Item) {
        @Suppress("UNUSED_VARIABLE") val t = tick
        val enabled = item.enabled()
        val onClick: (() -> Unit)? = when (item.type) {
            "toggle" -> {
                { item.set(((item.value() as? Boolean) != true).toString()); tick++ }
            }
            "choice" -> {
                { choiceFor = item }
            }
            "css" -> {
                { cssOpen = true }
            }
            "link" -> {
                { item.run() }
            }
            else -> null
        }
        Surface(
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)
                .clip(RoundedCornerShape(20.dp))
                .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(item.icon)
                Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    val sub = item.sub()
                    if (sub.isNotBlank()) Text(
                        sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.type == "toggle") {
                    Switch(
                        checked = (item.value() as? Boolean) == true,
                        enabled = enabled,
                        onCheckedChange = { item.set(it.toString()); tick++ },
                    )
                }
            }
        }
    }

    @Composable
    private fun ChoiceDialog(item: Item) {
        val current = item.value()?.toString()
        M3AlertDialog(
            onDismissRequest = { choiceFor = null },
            title = { Text(item.title) },
            text = {
                Column {
                    item.options.forEach { (v, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                item.set(v)
                                choiceFor = null
                                tick++
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = v == current, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choiceFor = null }) { Text("Cancel") } },
        )
    }

    @Composable
    private fun CssDialog() {
        var text by remember { mutableStateOf(SettingsPrefs.getCustomCss(this)) }
        M3AlertDialog(
            onDismissRequest = { cssOpen = false },
            title = { Text("Custom CSS") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 12,
                )
            },
            confirmButton = {
                TextButton(onClick = { SettingsPrefs.setCustomCss(this, text); cssOpen = false; tick++ }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { cssOpen = false }) { Text("Cancel") } },
        )
    }
}
