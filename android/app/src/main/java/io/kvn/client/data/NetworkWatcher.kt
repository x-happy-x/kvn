package io.kvn.client.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import io.kvn.client.vpn.WifiMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сеть, через которую телефон выходит в интернет (без учёта самого VPN).
 * [kind] — класс сети: по нему отдельно копится статистика серверов;
 * [key] — конкретная сеть (имя Wi-Fi или оператор): по нему пинги считаются
 * свежими или устаревшими.
 */
data class NetworkId(val kind: String, val key: String, val label: String) {
    val known: Boolean get() = kind != KIND_NONE

    companion object {
        const val KIND_WIFI = "wifi"
        const val KIND_CELL = "cell"
        const val KIND_OTHER = "other"
        const val KIND_NONE = "none"

        val NONE = NetworkId(KIND_NONE, "none", "нет сети")

        fun kindTitle(kind: String): String = when (kind) {
            KIND_WIFI -> "Wi-Fi"
            KIND_CELL -> "мобильная сеть"
            KIND_OTHER -> "другая сеть"
            else -> "нет сети"
        }
    }
}

/**
 * Следит за сетью под VPN: запрос с NET_CAPABILITY_NOT_VPN видит Wi-Fi и
 * мобильную сеть, даже когда туннель поднят. Нужен, чтобы понять, что сеть
 * сменилась: пинги устарели, соединение пора проверить.
 */
class NetworkWatcher(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val networks = linkedMapOf<Network, NetworkCapabilities>()

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<NetworkId> = _state.asStateFlow()

    val current: NetworkId get() = _state.value

    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network, capabilities)
            override fun onLost(network: Network) = remove(network)
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network, capabilities)
            override fun onLost(network: Network) = remove(network)
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { connectivity?.registerNetworkCallback(request, callback) }
    }

    private fun update(network: Network, capabilities: NetworkCapabilities) {
        synchronized(networks) { networks[network] = capabilities }
        publish()
    }

    private fun remove(network: Network) {
        synchronized(networks) { networks.remove(network) }
        publish()
    }

    private fun publish() {
        val next = snapshot()
        if (next != _state.value) _state.value = next
    }

    /**
     * Через что сейчас идёт трафик: Wi-Fi важнее мобильной сети (так делает и
     * Android), проверенная сеть важнее непроверенной.
     */
    private fun snapshot(): NetworkId {
        val known = synchronized(networks) { networks.toMap() }
        val candidates = known.ifEmpty { fromSystem() }
        val best = candidates.values.sortedWith(
            compareByDescending<NetworkCapabilities> { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                .thenByDescending { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
        ).firstOrNull() ?: return NetworkId.NONE
        return when {
            best.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = ssidOf(best)
                NetworkId(NetworkId.KIND_WIFI, "wifi:${ssid ?: "?"}", ssid?.let { "Wi-Fi «$it»" } ?: "Wi-Fi")
            }
            best.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val operator = runCatching {
                    appContext.getSystemService(TelephonyManager::class.java)?.networkOperatorName
                }.getOrNull()?.trim()?.ifEmpty { null }
                NetworkId(NetworkId.KIND_CELL, "cell:${operator ?: "?"}", operator?.let { "мобильная сеть $it" } ?: "мобильная сеть")
            }
            else -> NetworkId(NetworkId.KIND_OTHER, "other", "другая сеть")
        }
    }

    /** Пока колбэк не пришёл — берём сети у системы напрямую. */
    private fun fromSystem(): Map<Network, NetworkCapabilities> {
        val manager = connectivity ?: return emptyMap()
        @Suppress("DEPRECATION")
        return manager.allNetworks.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            network to caps
        }.toMap()
    }

    private fun ssidOf(capabilities: NetworkCapabilities): String? {
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (capabilities.transportInfo as? WifiInfo)?.ssid else null
        @Suppress("DEPRECATION")
        val raw = fromCaps?.takeIf { WifiMonitor.cleanSsid(it) != null }
            ?: runCatching { appContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
        return WifiMonitor.cleanSsid(raw)
    }
}
