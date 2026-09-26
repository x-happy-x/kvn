package io.kvn.client.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import io.kvn.client.core.Engine
import java.util.Locale

/**
 * Под кого приложение представляется серверу подписки.
 *
 * Панели (Remnawave, Marzban, sub-lab) выбирают формат по User-Agent: Happ
 * получает список ссылок или Xray JSON, FlClashX — Clash YAML. Поэтому
 * подписка для каждого ядра запрашивается отдельно, со своими заголовками.
 * Заголовки устройства (`x-hwid` и др.) — те же, что шлют сами Happ и FlClashX:
 * по ним панели считают устройства и лимиты.
 */
object Mimicry {
    const val HAPP_USER_AGENT = "Happ/3.10.0"
    const val FLCLASHX_USER_AGENT = "FlClash X/0.3.2 Platform/android"

    fun clientName(engine: Engine): String = when (engine) {
        Engine.XRAY -> "Happ"
        Engine.MIHOMO -> "FlClashX"
    }

    fun defaultUserAgent(engine: Engine): String = when (engine) {
        Engine.XRAY -> HAPP_USER_AGENT
        Engine.MIHOMO -> FLCLASHX_USER_AGENT
    }

    fun userAgent(engine: Engine, settings: AppSettings): String =
        settings.customUserAgent(engine).trim().ifEmpty { defaultUserAgent(engine) }

    fun headers(context: Context, engine: Engine, settings: AppSettings): Map<String, String> {
        val locale = Locale.getDefault()
        val language = locale.toLanguageTag().ifEmpty { "en-US" }
        return linkedMapOf(
            "User-Agent" to userAgent(engine, settings),
            "Accept" to "*/*",
            "Accept-Language" to "$language,en;q=0.9",
            "x-hwid" to hwid(context, settings),
            "x-device-os" to "Android",
            "x-ver-os" to Build.VERSION.RELEASE.orEmpty(),
            "x-device-model" to deviceModel(),
            "x-device-locale" to language,
        )
    }

    /** x-hwid: свой из настроек или ANDROID_ID устройства. */
    fun hwid(context: Context, settings: AppSettings): String = settings.customHwid.trim().ifEmpty { hwid(context) }

    /** Стабильный идентификатор устройства: ANDROID_ID, как у Happ на Android. */
    @SuppressLint("HardwareIds")
    fun hwid(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty().ifEmpty { "0000000000000000" }

    fun deviceModel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase(Locale.ROOT) }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
    }
}
