package io.kvn.client.data

import io.kvn.client.core.CoreBridge
import org.json.JSONObject

/** Результат проверки DNS-сервера. */
data class DnsCheck(val ok: Boolean, val ms: Int, val ips: List<String>, val error: String?) {
    companion object {
        fun run(server: String, domain: String, viaVpn: Boolean): DnsCheck = runCatching {
            val json = JSONObject(CoreBridge.checkDns(server, domain, viaVpn))
            val ips = json.optJSONArray("ips")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
            DnsCheck(json.optBoolean("ok"), json.optInt("ms"), ips, json.optString("error").ifEmpty { null })
        }.getOrElse { DnsCheck(false, 0, emptyList(), it.message ?: "ошибка проверки") }
    }
}
