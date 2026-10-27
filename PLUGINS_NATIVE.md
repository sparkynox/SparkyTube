# Writing a native (Kotlin / dex) plugin

JS plugins live inside the YouTube page. A native plugin is real Kotlin code that runs inside the app itself. It can reach the screen, the player and the audio engine, which JS can't.

> **A native plugin has the same access as the app: files, network, your login, everything.** There is no sandbox and the `permissions` list is ignored. SparkyTube asks before installing one. Only install plugins you trust, and only write ones you'd trust yourself.

## What a plugin is

One of these, installed from **Settings > Plugins > File or Link**:

| Format | Notes |
|---|---|
| `my-plugin.dex` | The id comes from the file name. Main class must be `SparkyPluginMain` |
| `my-plugin.zip` / `.jar` / `.apk` | Must contain `classes.dex`. Add `plugin.json` for the name, id and main class |

Android can't load plain JVM `.class` files, so a normal `.jar` from Gradle must go through **d8** first (the build below does that).

`plugin.json` (optional, same as JS plugins):

```json
{
  "id": "native-hello",
  "name": "Native Hello",
  "version": "1.0",
  "author": "SparkyNox",
  "description": "Toasts when a video ends",
  "main": "SparkyPluginMain"
}
```

`main` is the class name (with the package if it has one). Default: `SparkyPluginMain`.

## The API

Your class extends `SparkyPlugin`:

```kotlin
class SparkyPluginMain : SparkyPlugin() {
    override fun onLoad(host: PluginHost) { }
    override fun onEvent(name: String, dataJson: String) { }
    override fun onUnload() { }
}
```

- `onLoad` runs when the plugin starts (app start, install, or coming back from the plugin screen).
- `onEvent` gets `play`, `pause`, `videoend` and the other player events, with a JSON string.
- `onUnload` runs before a reload or when the plugin is turned off. Clean up threads and listeners here.
- Everything is called on the **main thread**. Don't block it, use your own thread for slow work.
- An exception in your plugin is caught and logged, it doesn't crash the app.

`PluginHost` is what you get in `onLoad`:

| Call | What it does |
|---|---|
| `context()` | Application context |
| `activity()` | The real `MainActivity` |
| `webView()` | The main WebView |
| `player()` | The Media3 `MediaController` (can be null) |
| `runOnUi(Runnable)` | Run something on the main thread |
| `evaluateJs(js)` | Run JavaScript in the WebView |
| `toast(msg)` | Show a toast |
| `videoInfoJson()` | JSON about the current video |
| `downloadCurrent()` | Open the download picker for the current video |
| `openUrl(url)` | Open a link |
| `log(msg)` | Write to the app log (Settings > Logs) |
| `storageGet(key)` / `storageSet(key, value)` | Small private storage for your plugin |

Beyond that, every class in the app is kept un-renamed in release builds, so reflection on `MainActivity` and the rest works.

### Control the audio engine

```kotlin
import dev.sparkynox.sparkytube.fx.LunoFx

val p = LunoFx.params.copy(on = true, bass = 8f, rvMix = 0.3f)
LunoFx.update(host.context(), p)
```

`LunoFx.params` has `eq` (10 bands, dB), `bass`, `width`, `rvMix`, `rvRoom`, `rvDamp`, `p8On`, `p8Period`, `p8Depth`, `normOn` and `normTarget`. `LunoFx.update` saves it and applies it live.

### Example: show a toast when a video ends

```kotlin
import dev.sparkynox.sparkytube.plugins.api.PluginHost
import dev.sparkynox.sparkytube.plugins.api.SparkyPlugin

class SparkyPluginMain : SparkyPlugin() {
    private var host: PluginHost? = null

    override fun onLoad(host: PluginHost) {
        this.host = host
        host.log("loaded")
    }

    override fun onEvent(name: String, dataJson: String) {
        if (name == "videoend") host?.toast("Video ended")
    }
}
```

## Building it (no PC needed)

The example in `plugins-examples/native-hello/` has everything, including a GitHub Actions workflow that does the whole build:

1. Create a new GitHub repo and push the contents of `plugins-examples/native-hello/` to it.
2. The workflow (`.github/workflows/build.yml`) compiles your Kotlin, runs d8 and uploads `native-hello.zip` as a build artifact.
3. Download the zip from the Actions run and install it from Settings > Plugins > File.

What the workflow does, in case you want to do it by hand:

1. `kotlinc` compiles `api/SparkyPlugin.kt` on its own (so the API classes are **not** shipped in your plugin).
2. `kotlinc` compiles your plugin with the API classes and `android.jar` on the classpath.
3. `d8` turns your classes **plus the Kotlin stdlib** into `classes.dex`.
4. `classes.dex` + `plugin.json` are zipped together.

`api/SparkyPlugin.kt` is a copy of `app/src/main/java/dev/sparkynox/sparkytube/plugins/api/SparkyPlugin.kt` with the `player()` line removed (so you don't need the Media3 jars to compile). If you want `player()`, add Media3 to the compile classpath and keep that line.

## Rules of thumb

- **Bundle the Kotlin stdlib** in your dex (the workflow does). Your plugin uses its own copy of `kotlin.*`.
- **Don't ship the API classes** (`dev.sparkynox.sparkytube.plugins.api.*`). The app provides them. A second copy breaks loading.
- Keep `SparkyPlugin` signatures as they are. Plugins are compiled against them.
- Plugins reload when you come back from the plugin screen, so toggling and updating works without restarting the app.
- If install says `Main class "..." not found`, check the `main` field and that the class is in `classes.dex`. If it says `must extend SparkyPlugin`, you shipped your own copy of the API.
- Something not working? Check Settings > Logs, plugin messages are tagged `NativePlugin`.
