package dev.windsaloft.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck

/**
 * "Online" means a network that has actually reached the internet. A plane's Wi-Fi before you've paid
 * (a captive portal) or a dead cell signal counts as offline, so nothing waits on requests that will
 * time out: forecasts come from the cache and the map uses saved tiles only.
 */
class Connectivity(context: Context) : INetworkAvailablityCheck {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean {
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Calls [onChange] whenever the connection comes or goes. */
    fun watch(onChange: (Boolean) -> Unit) {
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(isOnline())
            override fun onLost(network: Network) = onChange(isOnline())
        })
    }

    // osmdroid's tile downloader asks these before every download.
    override fun getNetworkAvailable() = isOnline()
    override fun getWiFiNetworkAvailable() = isOnline()
    override fun getCellularDataNetworkAvailable() = isOnline()
    @Deprecated("osmdroid no longer calls this") override fun getRouteToPathExists(hostAddress: Int) = isOnline()
}
