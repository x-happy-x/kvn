package io.kvn.client.data

import android.util.Base64
import java.net.URLDecoder

/** Ответ сервера подписки вместе с метаданными из заголовков. */
data class FetchedSubscription(
    val body: String,
    val title: String?,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
    val announce: String?,
)

object SubscriptionFetcher {
    fun fetch(url: String, headers: Map<String, String>): FetchedSubscription {
        val response = Http.request("GET", url, headers)
        if (response.status !in 200..299) {
            throw IllegalStateException("Сервер подписки ответил ${response.status}")
        }
        val info = parseUserInfo(response.header("subscription-userinfo"))
        return FetchedSubscription(
            body = response.body,
            title = decodeHeader(response.header("profile-title")) ?: fileName(response.header("content-disposition")),
            upload = info["upload"] ?: 0,
            download = info["download"] ?: 0,
            total = info["total"] ?: 0,
            expire = info["expire"] ?: 0,
            announce = decodeHeader(response.header("announce")),
        )
    }

    /** `upload=1; download=2; total=3; expire=4` → карта чисел. */
    fun parseUserInfo(header: String?): Map<String, Long> {
        if (header.isNullOrBlank()) return emptyMap()
        return header.split(';').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size != 2) return@mapNotNull null
            val value = pieces[1].trim().toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            pieces[0].trim().lowercase() to value
        }.toMap()
    }

    /** Заголовки Happ-панелей бывают в виде `base64:...`. */
    fun decodeHeader(header: String?): String? {
        if (header.isNullOrBlank()) return null
        if (header.startsWith("base64:")) {
            return runCatching {
                String(Base64.decode(header.removePrefix("base64:"), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.trim()?.ifEmpty { null }
        }
        return header.trim()
    }

    private fun fileName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (encoded != null) {
            return runCatching { URLDecoder.decode(encoded.groupValues[1].trim('"'), "UTF-8") }.getOrNull()
        }
        return Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
    }
}
