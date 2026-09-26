package io.kvn.client.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    /** `sub.example.com` → `https://sub.example.com`. */
    fun normalizeServer(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

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
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                val message = json.optString("error").ifEmpty {
                    when (code) {
                        401 -> "Сессия sub-lab истекла — войдите заново"
                        404 -> "Этот сервер sub-lab не поддерживает вход из приложения — обновите sub-lab"
                        else -> "sub-lab ответил $code"
                    }
                }
                throw SubLabException(message, code)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
