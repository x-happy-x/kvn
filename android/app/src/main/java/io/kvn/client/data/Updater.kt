package io.kvn.client.data

import android.content.Context
import android.os.Build
import io.kvn.client.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Канал обновлений. */
enum class UpdateChannel(val id: String, val title: String, val description: String) {
    STABLE("stable", "Стабильный", "Проверенные версии — релизы с тегом vX.Y.Z"),
    DEV("dev", "Dev", "Каждая свежая сборка из main — новое раньше всех, но может ломаться");

    companion object {
        fun of(id: String?): UpdateChannel? = entries.firstOrNull { it.id == id }

        /** По умолчанию — канал, из которого установлена текущая сборка. */
        val default: UpdateChannel get() = if (BuildConfig.VERSION_NAME.contains("-dev")) DEV else STABLE
    }
}

/** Найденное обновление. */
data class UpdateInfo(
    val channel: UpdateChannel,
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    val pageUrl: String,
)

/**
 * Обновления из релизов GitHub. Stable — последний релиз (не пререлиз),
 * dev — пререлиз `nightly`. В каждом релизе лежит `version.json` с
 * versionCode сборки: он сравнивается с установленным. APK берётся под
 * архитектуру телефона, иначе универсальный.
 */
object Updater {
    private const val REPO = "x-happy-x/kvn"

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val currentCode: Int get() = BuildConfig.VERSION_CODE

    /** Есть ли версия новее установленной; null — нет. */
    fun check(channel: UpdateChannel): UpdateInfo? {
        val api = when (channel) {
            UpdateChannel.STABLE -> "https://api.github.com/repos/$REPO/releases/latest"
            UpdateChannel.DEV -> "https://api.github.com/repos/$REPO/releases/tags/nightly"
        }
        val headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "KVN/${BuildConfig.VERSION_NAME}")
        val response = Http.request("GET", api, headers, timeoutMs = 20_000)
        if (response.status == 404) return null
        check(response.status in 200..299) { "GitHub ответил ${response.status}" }
        val release = JSONObject(response.body)
        val assets = release.optJSONArray("assets") ?: return null
        val files = List(assets.length()) { assets.getJSONObject(it) }

        val versionAsset = files.firstOrNull { it.optString("name") == "version.json" } ?: return null
        val versionResponse = Http.request("GET", versionAsset.optString("browser_download_url"), mapOf("User-Agent" to "KVN"), timeoutMs = 20_000)
        check(versionResponse.status in 200..299) { "version.json: ${versionResponse.status}" }
        val version = JSONObject(versionResponse.body)
        val code = version.optInt("versionCode")
        if (code <= currentCode) return null

        val apks = files.filter { it.optString("name").endsWith(".apk") }
        val abi = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.optString("name").endsWith("-$abi.apk") } }
        val apk = abi ?: apks.firstOrNull { it.optString("name").endsWith("-universal.apk") } ?: apks.firstOrNull() ?: return null
        return UpdateInfo(
            channel = channel,
            versionName = version.optString("versionName").ifEmpty { release.optString("tag_name") },
            versionCode = code,
            apkUrl = apk.optString("browser_download_url"),
            apkName = apk.optString("name"),
            size = apk.optLong("size"),
            pageUrl = release.optString("html_url"),
        )
    }

    /**
     * Скачивает APK в кэш приложения. Идёт напрямую (приложение исключено из
     * VPN), с продолжением прогресса через [onProgress] (0..1).
     */
    fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, info.apkName)
        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "KVN/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(connection.responseCode in 200..299) { "Загрузка: сервер ответил ${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.size
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return target
    }
}
