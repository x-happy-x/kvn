package io.kvn.client.data

import org.json.JSONObject
import java.net.IDN
import java.net.URI

/** Подписка из списка пользователя sub-lab (`GET /api/favorites`). */
data class SubLabSubscription(
    val title: String,
    val url: String,
    val shortId: String,
)

/** Ответ sub-lab на вход по паролю. */
data class SubLabLogin(
    val token: String,
    val username: String,
    val name: String,
)

class SubLabException(message: String, val status: Int = 0) : Exception(message) {
    val unauthorized: Boolean get() = status == 401
}

/**
 * Клиент sub-lab: вход по логину и паролю (`POST /api/auth/password`) и список
 * подписок пользователя. Дальше подписки скачиваются по их коротким ссылкам,
 * как любые другие — с заголовками Happ или FlClashX.
 */
object SubLabClient {
    /**
     * Адрес sub-lab в любом виде — `sub.example.com`, полная ссылка на подписку
     * `https://sub.example.com/l/abc`, с пробелами или кириллицей — в origin
     * вида `https://sub.example.com`.
     */
    fun normalizeServer(input: String): String {
        var text = input.trim().filterNot { it.isWhitespace() }.trimEnd('/')
        if (text.isEmpty()) return ""
        if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) text = "https://$text"
        val uri = runCatching { URI(text) }.getOrNull() ?: return Http.normalizeUrl(text)
        val host = uri.host ?: return Http.normalizeUrl(text)
        val ascii = runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host).lowercase()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme.lowercase()}://$ascii$port"
    }

    /** Похожа ли ссылка на короткую ссылку sub-lab (`/l/<id>`) — по ней можно угадать адрес сервера. */
    fun serverFromSubscriptionUrl(url: String): String? =
        if (Regex("^https?://[^/]+/l/[A-Za-z0-9_-]+").containsMatchIn(url.trim())) normalizeServer(url) else null

    fun login(server: String, login: String, password: String): SubLabLogin {
        val body = JSONObject().put("login", login).put("password", password)
        val json = request("POST", "$server/api/auth/password", token = null, body = body)
        val user = json.optJSONObject("user") ?: JSONObject()
        return SubLabLogin(
            token = json.optString("token").ifEmpty { throw SubLabException("sub-lab не выдал токен") },
            username = user.optString("username"),
            name = user.optString("name"),
        )
    }

    /** Свои и выданные пользователю подписки; чужие (видны админу) и скрытые пропускаются. */
    fun subscriptions(server: String, token: String): List<SubLabSubscription> {
        val json = request("GET", "$server/api/favorites", token = token, body = null)
        val array = json.optJSONArray("favorites") ?: return emptyList()
        val out = mutableListOf<SubLabSubscription>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optBoolean("foreign") || item.optBoolean("hidden")) continue
            val url = item.optString("url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val shortId = item.optString("shortId")
            out += SubLabSubscription(
                title = item.optString("title").ifEmpty { shortId.ifEmpty { url } },
                url = url,
                shortId = shortId,
            )
        }
        return out
    }

    fun logout(server: String, token: String) {
        runCatching { request("POST", "$server/api/auth/logout", token = token, body = JSONObject()) }
    }

    private fun request(method: String, url: String, token: String?, body: JSONObject?): JSONObject {
        val headers = buildMap {
            put("Accept", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
            if (body != null) put("Content-Type", "application/json; charset=utf-8")
        }
        val response = try {
            Http.request(method, url, headers, body?.toString())
        } catch (error: Exception) {
            throw SubLabException(error.message ?: "sub-lab недоступен")
        }
        val json = runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
        if (response.status !in 200..299) {
            val message = json.optString("error").ifEmpty {
                when (response.status) {
                    401 -> "Сессия sub-lab истекла — войдите заново"
                    404 -> "По этому адресу нет sub-lab с входом из приложения: проверьте адрес или обновите sub-lab"
                    else -> "sub-lab ответил ${response.status}"
                }
            }
            throw SubLabException(message, response.status)
        }
        return json
    }
}
