package dev.sparkynox.sparkytube.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object VpnCheck {
    // the warning is shown once per app run
    @Volatile var warned = false

    fun isActive(c: Context): Boolean {
        return try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (_: Throwable) {
            false
        }
    }
}
