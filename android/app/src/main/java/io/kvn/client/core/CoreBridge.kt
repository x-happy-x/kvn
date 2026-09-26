package io.kvn.client.core

import libcore.Libcore

/** Ядро, на котором поднимается туннель. */
enum class Engine(val id: String, val title: String) {
    XRAY("xray", "Xray"),
    MIHOMO("mihomo", "Mihomo");

    companion object {
        fun of(id: String?): Engine = entries.firstOrNull { it.id == id } ?: XRAY
    }
}

/**
 * Тонкая обёртка над gomobile-библиотекой libcore: все вызовы Go идут через неё,
 * чтобы остальной код не зависел от сгенерированных классов.
 */
object CoreBridge {
    fun init(dir: String) = Libcore.init(dir)

    fun parseSubscription(body: String): String = Libcore.parseSubscription(body)

    fun start(engine: Engine, nodeJson: String, fd: Int, optionsJson: String) =
        Libcore.start(engine.id, nodeJson, fd, optionsJson)

    fun stop() = Libcore.stop()

    fun isRunning(): Boolean = Libcore.isRunning()

    fun supports(engine: Engine, nodeJson: String): Boolean = Libcore.supports(engine.id, nodeJson)

    fun buildConfig(engine: Engine, nodeJson: String, optionsJson: String): String =
        Libcore.buildConfig(engine.id, nodeJson, optionsJson)

    fun tcpPing(host: String, port: Int, timeoutMs: Int = 3000): Int = Libcore.tcpPing(host, port, timeoutMs)

    fun logs(): String = Libcore.logs()

    /** Готовые наборы целей для проверки доступности (JSON). */
    fun scanPresets(): String = Libcore.scanPresets()

    /** Проверка одной цели напрямую и через VPN; блокирует поток на 5–30 с. */
    fun scan(target: String): String = Libcore.scan(target)

    /**
     * Настоящая проверка сервера: отдельный экземпляр ядра без TUN открывает
     * [url] через прокси. Работающий VPN не трогает.
     */
    fun testNode(engine: Engine, nodeJson: String, optionsJson: String, url: String = "", timeoutMs: Int = 8000): String =
        Libcore.testNode(engine.id, nodeJson, optionsJson, url, timeoutMs)

    fun isHappCrypt5(value: String): Boolean = Libcore.isHappCrypt5(value)

    /** Проверка, что работающий VPN пропускает трафик; время ответа в мс. */
    fun checkConnection(url: String = "", timeoutMs: Int = 8000, method: String = "GET"): Int =
        Libcore.checkConnection(method, url, timeoutMs)

    /** Пинг сервера способом [method] (tcp, icmp, proxy-get, proxy-head); бросает исключение при неудаче. */
    fun ping(method: String, engine: Engine, nodeJson: String, optionsJson: String, url: String, timeoutMs: Int): Int =
        Libcore.ping(method, engine.id, nodeJson, optionsJson, url, timeoutMs)

    /** Проверка DNS-сервера: JSON {ok, ms, ips, error}. [viaVpn] — через работающий туннель. */
    fun checkDns(server: String, domain: String, viaVpn: Boolean, timeoutMs: Int = 4000): String =
        Libcore.checkDNS(server, domain, viaVpn, timeoutMs)

    /** HTTP-запрос с запасным DNS (если системный не находит имя). Ответ — JSON. */
    fun httpFetch(method: String, url: String, headersJson: String, body: String, timeoutMs: Int): String =
        Libcore.httpFetch(method, url, headersJson, body, timeoutMs)

    /** happ://crypt5/… → обычная ссылка. */
    fun decryptHapp(link: String): String = Libcore.decryptHapp(link)

    /** Ссылка сервера (vless://, vmess://…) для QR-кода и «поделиться». */
    fun shareLink(nodeJson: String): String = Libcore.shareLink(nodeJson)

    fun xrayVersion(): String = Libcore.xrayVersion()

    fun mihomoVersion(): String = Libcore.mihomoVersion()
}
