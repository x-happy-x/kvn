package io.kvn.client.data

import io.kvn.client.core.Engine
import java.net.URLDecoder
import java.util.Base64

/**
 * Что открыть из ссылки чужого VPN-клиента: адрес подписки или сами серверы.
 * [engine] — ядро, под которое ссылка сделана (Clash-клиенты → Mihomo,
 * v2ray-клиенты → Xray), null — любое.
 */
data class ImportRequest(
    val input: String,
    val name: String?,
    val engine: Engine?,
    val client: String,
)

class DeepLinkException(message: String) : Exception(message)

/**
 * Ссылки импорта других клиентов: happ://, v2rayng://, clash://, sing-box://,
 * hiddify://, sub:// (Shadowrocket) и прочие, а также сами vless://, vmess://…
 * Разбор без android.net.Uri: у клиентов вложенные адреса бывают и
 * закодированы, и нет, и с собственными `?` и `&`.
 */
object DeepLinks {
    private data class Client(val title: String, val engine: Engine?)

    private val clients = mapOf(
        "happ" to Client("Happ", Engine.XRAY),
        "v2rayng" to Client("v2rayNG", Engine.XRAY),
        "v2raytun" to Client("v2RayTun", Engine.XRAY),
        "v2box" to Client("V2Box", Engine.XRAY),
        "streisand" to Client("Streisand", Engine.XRAY),
        "incy" to Client("Incy", Engine.XRAY),
        "npvtunnel" to Client("NapsternetV", Engine.XRAY),
        "clash" to Client("Clash", Engine.MIHOMO),
        "clashmeta" to Client("Clash Meta", Engine.MIHOMO),
        "clash-verge" to Client("Clash Verge", Engine.MIHOMO),
        "mihomo" to Client("Mihomo", Engine.MIHOMO),
        "flclash" to Client("FlClash", Engine.MIHOMO),
        "flclashx" to Client("FlClashX", Engine.MIHOMO),
        "koala-clash" to Client("Koala Clash", Engine.MIHOMO),
        "clashmi" to Client("ClashMi", Engine.MIHOMO),
        "stash" to Client("Stash", Engine.MIHOMO),
        "prizrak-box" to Client("Prizrak Box", Engine.MIHOMO),
        "sing-box" to Client("sing-box", null),
        "sing-box-sfa" to Client("sing-box", null),
        "hiddify" to Client("Hiddify", null),
        "karing" to Client("Karing", null),
        "sn" to Client("NekoBox", null),
        "nekobox" to Client("NekoBox", null),
        "throne" to Client("Throne", null),
        "exclave" to Client("Exclave", null),
        "sub" to Client("Shadowrocket", null),
        "shadowrocket" to Client("Shadowrocket", null),
        "kvn" to Client("KVN", null),
    )

    /** Схемы самих серверов: такие ссылки импортируются как список серверов. */
    val proxySchemes = setOf(
        "vless", "vmess", "trojan", "ss", "ssr", "hysteria", "hysteria2", "hy2", "tuic", "wireguard", "wg", "anytls",
    )

    /** Все схемы клиентов — для intent-filter и проверки буфера обмена. */
    val clientSchemes: Set<String> get() = clients.keys

    fun isSupported(text: String): Boolean {
        val scheme = text.trim().substringBefore("://", "").lowercase()
        return scheme in clients || scheme in proxySchemes
    }

    /**
     * Разбирает ссылку. [decryptHapp] расшифровывает happ://crypt5/… (делает libcore).
     * Возвращает null, если схема не наша.
     */
    fun parse(raw: String, decryptHapp: (String) -> String): ImportRequest? {
        val text = raw.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return null
        if (scheme in proxySchemes) return ImportRequest(text, null, null, "ссылка на сервер")
        val client = clients[scheme] ?: return null
        val rest = text.substringAfter("://")

        if (scheme == "happ") {
            val lower = rest.lowercase()
            if (lower.startsWith("crypt5/")) {
                val decrypted = runCatching { decryptHapp(text) }.getOrElse {
                    throw DeepLinkException("Не удалось расшифровать ссылку Happ: ${it.message}")
                }
                return parse(decrypted, decryptHapp)?.copy(client = client.title, engine = client.engine)
                    ?: ImportRequest(decrypted, null, client.engine, client.title)
            }
            if (Regex("^crypt\\d*/").containsMatchIn(lower)) {
                throw DeepLinkException("Этот вид зашифрованных ссылок Happ пока не поддерживается")
            }
        }

        val name = queryParam(rest, "name") ?: fragment(rest)
        val payload = extractPayload(rest)
            ?: throw DeepLinkException("В ссылке ${client.title} не нашлось адреса подписки")
        // Внутри может оказаться ещё одна ссылка клиента или сами серверы.
        if (!payload.startsWith("http://") && !payload.startsWith("https://")) {
            val nested = parse(payload, decryptHapp)
            if (nested != null) return nested.copy(name = nested.name ?: name, engine = nested.engine ?: client.engine)
        }
        return ImportRequest(payload, name, client.engine, client.title)
    }

    private fun extractPayload(rest: String): String? {
        val lower = rest.lowercase()
        val httpIndex = listOf("https://", "http://", "https%3a", "http%3a")
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (httpIndex != null) {
            val before = lower.substring(0, httpIndex)
            var value = rest.substring(httpIndex)
            val encoded = value.lowercase().startsWith("http%3a") || value.lowercase().startsWith("https%3a")
            val queryStyle = Regex("(url|sub|link|config|profile)=$").containsMatchIn(before)
            // `#` снаружи — имя профиля; внутри закодированного адреса он был бы %23.
            value = value.substringBefore('#')
            if (encoded || queryStyle) value = value.substringBefore('&')
            if (encoded) value = decode(value)
            return value.trim().ifEmpty { null }
        }
        // Параметр url= с закодированной ссылкой сервера (vless%3A…) или base64 (Shadowrocket).
        queryParam(rest, "url")?.let { return it }
        val body = rest.substringBefore('#').substringAfter('/', rest.substringBefore('#')).ifEmpty { rest.substringBefore('#') }
        for (candidate in listOf(rest.substringBefore('#'), body)) {
            val decoded = decodeBase64(candidate) ?: continue
            if (decoded.startsWith("http://") || decoded.startsWith("https://") || isSupported(decoded)) return decoded.trim()
        }
        val plain = decode(rest.substringBefore('#'))
        return if (isSupported(plain)) plain else null
    }

    private fun queryParam(rest: String, key: String): String? {
        val query = rest.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        return query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first.equals(key, ignoreCase = true) }
            ?.second?.let { decode(it).trim() }?.ifEmpty { null }
    }

    private fun fragment(rest: String): String? {
        if (!rest.contains('#')) return null
        return decode(rest.substringAfterLast('#')).trim().ifEmpty { null }
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64(value: String): String? {
        val cleaned = value.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        if (cleaned.length < 8 || !cleaned.matches(Regex("[A-Za-z0-9+/]+"))) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
    }
}
