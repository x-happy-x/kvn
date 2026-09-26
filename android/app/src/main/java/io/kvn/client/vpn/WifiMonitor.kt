package io.kvn.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import io.kvn.client.data.AppSettings
import io.kvn.client.data.WifiMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Текущее подключение к Wi-Fi. [ssid] — null, если сеть не Wi-Fi или имя скрыто системой. */
data class WifiState(val connected: Boolean = false, val ssid: String? = null)

/**
 * Следит за Wi-Fi через NetworkCallback. Имя сети Android отдаёт только при
 * доступе к геопозиции (а в фоне — «всегда»); без него известно лишь, что
 * Wi-Fi подключён, и режим «в выбранных сетях» не срабатывает.
 */
class WifiMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val networks = mutableMapOf<Network, String?>()

    private val _state = MutableStateFlow(WifiState())
    val state: StateFlow<WifiState> = _state.asStateFlow()

    private fun onCapabilities(network: Network, capabilities: NetworkCapabilities) {
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        synchronized(networks) { networks[network] = ssidOf(capabilities) }
        publish()
    }

    private fun onNetworkLost(network: Network) {
        synchronized(networks) { networks.remove(network) }
        publish()
    }

    // Конструктор с флагами появился в Android 12; на старых версиях его вызов упадёт.
    private val callback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    onCapabilities(network, capabilities)

                override fun onLost(network: Network) = onNetworkLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    onCapabilities(network, capabilities)

                override fun onLost(network: Network) = onNetworkLost(network)
            }
        }

    private var registered = false

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val manager = connectivity ?: return
        runCatching { manager.registerNetworkCallback(request, callback) }.onSuccess { registered = true }
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
        synchronized(networks) { networks.clear() }
        _state.value = WifiState()
    }

    private fun publish() {
        val snapshot = synchronized(networks) { networks.toMap() }
        _state.value = WifiState(
            connected = snapshot.isNotEmpty(),
            ssid = snapshot.values.firstOrNull { it != null },
        )
    }

    private fun ssidOf(capabilities: NetworkCapabilities): String? {
        val fromCapabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (capabilities.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val raw = fromCapabilities?.takeIf { cleanSsid(it) != null }
            ?: runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        return cleanSsid(raw)
    }

    companion object {
        /** `"Home"` → `Home`; `<unknown ssid>` и пустое → null. */
        fun cleanSsid(raw: String?): String? {
            val value = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
            if (value.isEmpty() || value == WifiManager.UNKNOWN_SSID.removeSurrounding("\"") || value == "<unknown ssid>") return null
            return value
        }

        /** Причина паузы или null, если VPN должен работать. */
        fun pauseReason(state: WifiState, settings: AppSettings): String? = when (settings.wifiMode) {
            WifiMode.OFF -> null
            WifiMode.ANY -> if (state.connected) "Wi-Fi${state.ssid?.let { " «$it»" }.orEmpty()}" else null
            WifiMode.LIST -> state.ssid?.takeIf { state.connected && it in settings.wifiNetworks }?.let { "Wi-Fi «$it»" }
        }
    }
}
