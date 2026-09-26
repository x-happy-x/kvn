package io.kvn.client.data

import io.kvn.client.core.CoreBridge
import org.json.JSONObject
import java.net.IDN
import java.net.URI

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * HTTP-запросы приложения (подписки, sub-lab) идут через libcore: там
 * запасной DNS — если системный резолвер не находит имя, адрес ищется через
 * 77.88.8.8, 1.1.1.1, 8.8.8.8 и Cloudflare DoH. Кириллические домены
 * переводятся в punycode.
 */
object Http {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 20_000,
    ): HttpResponse {
        val json = JSONObject(CoreBridge.httpFetch(method, normalizeUrl(url), JSONObject(headers).toString(), body.orEmpty(), timeoutMs))
        val responseHeaders = mutableMapOf<String, String>()
        json.optJSONObject("headers")?.let { object_ ->
            object_.keys().forEach { key -> responseHeaders[key.lowercase()] = object_.optString(key) }
        }
        return HttpResponse(json.optInt("status"), responseHeaders, json.optString("body"))
    }

    /** Убирает случайные пробелы и переводит домен в punycode. */
    fun normalizeUrl(input: String): String {
        val cleaned = input.trim().filterNot { it.isWhitespace() || it == '​' || it == '﻿' }
        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return cleaned
        val host = uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.substringBefore(':') ?: return cleaned
        val ascii = runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrDefault(host)
        if (ascii == host) return cleaned
        return cleaned.replaceFirst(host, ascii)
    }
}
