import dev.sparkynox.sparkytube.plugins.api.PluginHost
import dev.sparkynox.sparkytube.plugins.api.SparkyPlugin

// replaces "Hi, <your name>" on the Home screen with "Welcome Back Admin!"
class SparkyPluginMain : SparkyPlugin() {
    private var host: PluginHost? = null

    override fun onLoad(host: PluginHost) {
        this.host = host
        host.setUiText("home_greeting", "Welcome Back Admin!")
    }

    override fun onUnload() {
        host?.setUiText("home_greeting", null)
    }
}
