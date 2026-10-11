package dev.sparkynox.sparkytube.plugins

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

// JS plugins that run inside the YouTube page. a plugin is either one .js file with a
// header comment (see PLUGINS.md) or a small zip: plugin.json + main.js (+ style.css)
object PluginStore {

    // storage is always there, these need to be asked for in the plugin's header
    val PERMISSIONS = setOf("toast", "video", "download", "open_url")

    private const val PREFS = "sparky_plugins"
    private const val MAX_CODE = 512 * 1024
    private const val MAX_CSS = 256 * 1024

    class Plugin(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val permissions: List<String>,
        val enabled: Boolean
    )

    class Parsed(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val permissions: List<String>,
        val code: String,
        val css: String
    )

    // set when a plugin was installed/toggled, MainActivity reloads the page once
    @Volatile var dirty = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun root(c: Context) = File(c.filesDir, "plugins").apply { mkdirs() }
    private fun dirOf(c: Context, id: String) = File(root(c), id)

    private val idRegex = Regex("^[a-z0-9][a-z0-9._-]{2,39}$")

    // ---- reading ----

    fun list(c: Context): List<Plugin> {
        val out = ArrayList<Plugin>()
        root(c).listFiles()?.filter { it.isDirectory }?.forEach { d ->
            try {
                val m = JSONObject(File(d, "meta.json").readText())
                val perms = ArrayList<String>()
                val arr = m.optJSONArray("permissions") ?: JSONArray()
                for (i in 0 until arr.length()) perms.add(arr.getString(i))
                out.add(
                    Plugin(
                        id = d.name,
                        name = m.optString("name", d.name),
                        version = m.optString("version", "1.0"),
                        author = m.optString("author", ""),
                        description = m.optString("description", ""),
                        permissions = perms,
                        enabled = prefs(c).getBoolean("on_" + d.name, true)
                    )
                )
            } catch (e: Exception) {
                // broken folder, ignore it
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun setEnabled(c: Context, id: String, on: Boolean) {
        prefs(c).edit().putBoolean("on_$id", on).apply()
        dirty = true
    }

    fun remove(c: Context, id: String) {
        if (!idRegex.matches(id)) return
        dirOf(c, id).deleteRecursively()
        val e = prefs(c).edit()
        e.remove("on_$id").remove("token_$id")
        // its saved data goes too
        prefs(c).all.keys.filter { it.startsWith("data_$id::") }.forEach { e.remove(it) }
        e.apply()
        dirty = true
    }

    // ---- permissions / tokens (the bridge checks these on every call) ----

    private fun tokenFor(c: Context, id: String): String {
        val p = prefs(c)
        var t = p.getString("token_$id", null)
        if (t == null) {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            t = b.joinToString("") { "%02x".format(it) }
            p.edit().putString("token_$id", t).apply()
        }
        return t
    }

    fun verify(c: Context, id: String, token: String): Boolean {
        if (!idRegex.matches(id)) return false
        val t = prefs(c).getString("token_$id", null) ?: return false
        return t == token && prefs(c).getBoolean("on_$id", true) && dirOf(c, id).exists()
    }

    fun hasPermission(c: Context, id: String, perm: String): Boolean {
        return try {
            val m = JSONObject(File(dirOf(c, id), "meta.json").readText())
            val arr = m.optJSONArray("permissions") ?: return false
            (0 until arr.length()).any { arr.getString(it) == perm }
        } catch (e: Exception) { false }
    }

    // per-plugin saved values
    fun dataGet(c: Context, id: String, key: String): String? = prefs(c).getString("data_$id::$key", null)

    fun dataSet(c: Context, id: String, key: String, value: String): Boolean {
        if (key.length > 80 || value.length > 64 * 1024) return false
        prefs(c).edit().putString("data_$id::$key", value).apply()
        return true
    }

    fun dataRemove(c: Context, id: String, key: String) {
        prefs(c).edit().remove("data_$id::$key").apply()
    }

    // ---- installing ----

    // single .js with a header:
    //   // ==SparkyPlugin==
    //   // @id hide-shorts
    //   // @name Hide Shorts
    //   // @permissions toast, video
    //   // ==/SparkyPlugin==
    fun parseUserscript(code: String): Parsed {
        val start = code.indexOf("==SparkyPlugin==")
        val end = code.indexOf("==/SparkyPlugin==")
        if (start < 0 || end < start) throw IllegalArgumentException("Header missing, the file must start with // ==SparkyPlugin==")
        val fields = HashMap<String, String>()
        code.substring(start + "==SparkyPlugin==".length, end).lines().forEach { raw ->
            val line = raw.trim().removePrefix("//").trim()
            if (line.startsWith("@")) {
                val sp = line.indexOfFirst { it == ' ' || it == '\t' }
                if (sp > 0) fields[line.substring(1, sp).lowercase()] = line.substring(sp).trim()
            }
        }
        val perms = (fields["permissions"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return Parsed(
            id = fields["id"] ?: throw IllegalArgumentException("@id is missing in the header"),
            name = fields["name"] ?: throw IllegalArgumentException("@name is missing in the header"),
            version = fields["version"] ?: "1.0",
            author = fields["author"] ?: "",
            description = fields["description"] ?: "",
            permissions = perms,
            code = code,
            css = ""
        )
    }

    fun parseBundle(bytes: ByteArray): Parsed {
        var manifest: String? = null
        var js: String? = null
        var css = ""
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                if (++count > 20) throw IllegalArgumentException("Too many files in the zip")
                val name = e.name.substringAfterLast('/')   // flat, folders in the zip are ignored
                val data = zin.readBytes()
                if (data.size > MAX_CODE) throw IllegalArgumentException("$name is too big")
                when (name) {
                    "plugin.json" -> manifest = String(data, Charsets.UTF_8)
                    "main.js" -> js = String(data, Charsets.UTF_8)
                    "style.css" -> css = String(data, Charsets.UTF_8)
                }
            }
        }
        val m = JSONObject(manifest ?: throw IllegalArgumentException("plugin.json is missing in the zip"))
        val perms = ArrayList<String>()
        val arr = m.optJSONArray("permissions") ?: JSONArray()
        for (i in 0 until arr.length()) perms.add(arr.getString(i))
        return Parsed(
            id = m.optString("id").ifBlank { throw IllegalArgumentException("id is missing in plugin.json") },
            name = m.optString("name").ifBlank { throw IllegalArgumentException("name is missing in plugin.json") },
            version = m.optString("version", "1.0"),
            author = m.optString("author", ""),
            description = m.optString("description", ""),
            permissions = perms,
            code = js ?: throw IllegalArgumentException("main.js is missing in the zip"),
            css = css
        )
    }

    fun install(c: Context, p: Parsed): Plugin {
        if (!idRegex.matches(p.id)) throw IllegalArgumentException("Bad id \"${p.id}\", use 3 to 40 chars of a-z 0-9 . _ -")
        if (p.code.length > MAX_CODE) throw IllegalArgumentException("Plugin code is too big")
        if (p.css.length > MAX_CSS) throw IllegalArgumentException("Plugin css is too big")
        val unknown = p.permissions.filter { it !in PERMISSIONS }
        if (unknown.isNotEmpty()) throw IllegalArgumentException("Unknown permission: ${unknown.joinToString()}")

        val d = dirOf(c, p.id)
        d.mkdirs()
        val meta = JSONObject()
            .put("id", p.id).put("name", p.name).put("version", p.version)
            .put("author", p.author).put("description", p.description)
            .put("permissions", JSONArray(p.permissions))
        File(d, "meta.json").writeText(meta.toString())
        File(d, "main.js").writeText(p.code)
        val cssFile = File(d, "style.css")
        if (p.css.isNotBlank()) cssFile.writeText(p.css) else cssFile.delete()
        tokenFor(c, p.id)
        dirty = true
        return Plugin(p.id, p.name, p.version, p.author, p.description, p.permissions, prefs(c).getBoolean("on_${p.id}", true))
    }

    fun installAuto(c: Context, bytes: ByteArray): Plugin {
        val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val parsed = if (isZip) parseBundle(bytes) else parseUserscript(String(bytes, Charsets.UTF_8))
        return install(c, parsed)
    }

    fun installFromUrl(c: Context, url: String): Plugin {
        if (!url.startsWith("https://")) throw IllegalArgumentException("The link must start with https://")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Server said ${r.code}")
            val body = r.body ?: throw IllegalStateException("Empty response")
            if (body.contentLength() > 2 * 1024 * 1024) throw IllegalArgumentException("File is too big")
            return installAuto(c, body.bytes())
        }
    }

    // ---- what gets injected into the page ----

    private var runtimeCache: String? = null

    private fun runtime(c: Context): String {
        runtimeCache?.let { return it }
        val t = c.assets.open("plugin_runtime.js").bufferedReader().use { it.readText() }
        runtimeCache = t
        return t
    }

    // first script is the runtime, then one script per enabled plugin so a broken
    // plugin can't take the others down with it. empty list = nothing to inject
    fun injectionScripts(c: Context): List<String> {
        val enabled = list(c).filter { it.enabled }
        if (enabled.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        out.add(runtime(c))
        enabled.forEach { p ->
            try {
                val d = dirOf(c, p.id)
                val code = File(d, "main.js").readText()
                val cssFile = File(d, "style.css")
                val css = if (cssFile.exists()) cssFile.readText() else ""
                val meta = JSONObject()
                    .put("name", p.name).put("version", p.version)
                    .put("permissions", JSONArray(p.permissions))
                out.add(
                    "(function(){var sparky=window.__sparkyPlugins&&window.__sparkyPlugins.api(" +
                        JSONObject.quote(p.id) + "," + JSONObject.quote(tokenFor(c, p.id)) + "," + meta.toString() + "," +
                        JSONObject.quote(css) + ");if(!sparky)return;try{\n" + code + "\n}catch(e){sparky.__error(e)}})();"
                )
            } catch (e: Exception) {
                // unreadable plugin, skip
            }
        }
        return out
    }
}
