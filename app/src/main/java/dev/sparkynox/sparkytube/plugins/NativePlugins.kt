package dev.sparkynox.sparkytube.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import dalvik.system.DexClassLoader
import dev.sparkynox.sparkytube.logs.LogRecorder
import dev.sparkynox.sparkytube.plugins.api.PluginHost
import dev.sparkynox.sparkytube.plugins.api.SparkyPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// native plugins: a .dex, or a .jar/.zip/.apk with classes.dex inside (+ optional plugin.json).
// they run inside the app process with the app's full access, no permission checks
object NativePlugins {

    const val DEFAULT_MAIN = "SparkyPluginMain"
    private const val TAG = "NativePlugin"

    class Parsed(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val permissions: List<String>,
        val main: String,
        val jar: ByteArray
    )

    // kotlin.* comes from the plugin's own dex first. the app's copy is shrunk by R8,
    // so it can't be trusted to have what the plugin needs
    private class PluginClassLoader(path: String, parent: ClassLoader) :
        DexClassLoader(path, null, null, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("kotlin.") || name.startsWith("kotlinx.")) {
                synchronized(getClassLoadingLock(name)) {
                    findLoadedClass(name)?.let { return it }
                    try {
                        return findClass(name)
                    } catch (_: ClassNotFoundException) {
                    }
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    private class Loaded(val id: String, val plugin: SparkyPlugin)

    private val lock = Any()
    private val loaded = ArrayList<Loaded>()
    private val main = Handler(Looper.getMainLooper())

    private fun isDex(b: ByteArray) =
        b.size > 8 && b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() && b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()

    private fun isZip(b: ByteArray) =
        b.size > 3 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    fun looksNative(bytes: ByteArray): Boolean {
        if (isDex(bytes)) return true
        if (!isZip(bytes)) return false
        var n = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (n++ < 5000) {
                val e = z.nextEntry ?: break
                if (e.name == "classes.dex") return true
            }
        }
        return false
    }

    private fun idFromName(fileName: String?): String {
        val base = (fileName ?: "").substringAfterLast('/').substringBeforeLast('.').lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-").trim('-', '.', '_')
        if (base.length < 3) throw IllegalArgumentException("Can't tell the plugin id. Add a plugin.json with an id, or name the file like my-plugin.dex")
        return base.take(40)
    }

    fun parse(bytes: ByteArray, fileName: String?): Parsed {
        if (isDex(bytes)) {
            val id = idFromName(fileName)
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("classes.dex"))
                z.write(bytes)
                z.closeEntry()
            }
            return Parsed(id, id, "1.0", "", "", emptyList(), DEFAULT_MAIN, bos.toByteArray())
        }

        var manifest: String? = null
        var hasDex = false
        var n = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (n++ < 5000) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name == "classes.dex") hasDex = true
                if (e.name == "plugin.json") {
                    val d = z.readBytes()
                    if (d.size > 64 * 1024) throw IllegalArgumentException("plugin.json is too big")
                    manifest = String(d, Charsets.UTF_8)
                }
            }
        }
        if (!hasDex) throw IllegalArgumentException("classes.dex is missing in the file")

        val m = manifest?.let { JSONObject(it) }
        val perms = ArrayList<String>()
        val arr = m?.optJSONArray("permissions") ?: JSONArray()
        for (i in 0 until arr.length()) perms.add(arr.getString(i))
        val id = m?.optString("id").orEmpty().ifBlank { idFromName(fileName) }
        return Parsed(
            id = id,
            name = m?.optString("name").orEmpty().ifBlank { id },
            version = m?.optString("version", "1.0") ?: "1.0",
            author = m?.optString("author", "") ?: "",
            description = m?.optString("description", "") ?: "",
            permissions = perms,
            main = m?.optString("main").orEmpty().ifBlank { DEFAULT_MAIN },
            jar = bytes
        )
    }

    // makes sure the main class exists and really extends SparkyPlugin before we keep anything
    fun validate(c: Context, jar: File, mainClass: String) {
        val cl = PluginClassLoader(jar.absolutePath, c.classLoader)
        val cls = try {
            cl.loadClass(mainClass)
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException("Main class \"$mainClass\" not found in the dex")
        } catch (e: Throwable) {
            throw IllegalArgumentException("Couldn't load \"$mainClass\": ${e.javaClass.simpleName} ${e.message}")
        }
        if (!SparkyPlugin::class.java.isAssignableFrom(cls)) {
            throw IllegalArgumentException("\"$mainClass\" must extend SparkyPlugin")
        }
    }

    // no permission checks here, native plugins already run with the app's full access
    private class Host(
        private val c: Context,
        private val id: String,
        private val base: PluginBridge.Host
    ) : PluginHost {
        override fun context(): Context = c.applicationContext
        override fun toast(message: String) { main.post { base.toast(message.take(200)) } }
        override fun videoInfoJson(): String = base.videoInfoJson()
        override fun downloadCurrent() { main.post { base.downloadCurrent() } }
        override fun openUrl(url: String) { main.post { base.openUrl(url) } }
        override fun runOnUi(r: Runnable) { main.post(r) }
        override fun evaluateJs(js: String) { main.post { base.webView().evaluateJavascript(js, null) } }
        override fun activity() = base.activity()
        override fun webView() = base.webView()
        override fun player() = base.controller()
        override fun log(message: String) { LogRecorder.i(TAG, "$id: ${message.take(300)}") }
        override fun storageGet(key: String): String? = PluginStore.dataGet(c, id, key)
        override fun storageSet(key: String, value: String): Boolean = PluginStore.dataSet(c, id, key, value)
    }

    // (re)loads every enabled native plugin. call on the main thread
    fun start(c: Context, base: PluginBridge.Host) {
        synchronized(lock) {
            unloadAll()
            PluginStore.list(c).filter { it.enabled && it.kind == "native" }.forEach { p ->
                try {
                    val d = PluginStore.dirOf(c, p.id)
                    val mainClass = JSONObject(File(d, "meta.json").readText()).optString("main", DEFAULT_MAIN)
                    val cl = PluginClassLoader(File(d, "plugin.jar").absolutePath, c.classLoader)
                    val plugin = cl.loadClass(mainClass).getDeclaredConstructor().newInstance() as SparkyPlugin
                    plugin.onLoad(Host(c, p.id, base))
                    loaded.add(Loaded(p.id, plugin))
                    LogRecorder.i(TAG, "loaded ${p.id}")
                } catch (t: Throwable) {
                    LogRecorder.w(TAG, "${p.id} failed to load", t)
                }
            }
        }
    }

    private fun unloadAll() {
        loaded.forEach {
            try { it.plugin.onUnload() } catch (t: Throwable) { LogRecorder.w(TAG, "${it.id} onUnload failed", t) }
        }
        loaded.clear()
    }

    fun emit(name: String, dataJson: String) {
        val list = synchronized(lock) { ArrayList(loaded) }
        for (l in list) {
            try {
                l.plugin.onEvent(name, dataJson)
            } catch (t: Throwable) {
                LogRecorder.w(TAG, "${l.id} crashed on $name", t)
            }
        }
    }
}
