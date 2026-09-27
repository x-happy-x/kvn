package io.kvn.client.data

import io.kvn.client.core.Engine
import org.json.JSONArray
import org.json.JSONObject

/** Сервер из подписки. [json] — узел в формате libcore, его и получает ядро. */
data class ServerNode(
    val id: String,
    val subscriptionId: String,
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val xray: Boolean,
    val mihomo: Boolean,
    val json: String,
) {
    fun supports(engine: Engine) = when (engine) {
        Engine.XRAY -> xray
        Engine.MIHOMO -> mihomo
    }

    /** Флаг из начала названия («🇩🇪 Германия») или null. */
    val flag: String? get() = leadingFlag(name)

    /** Название без флага в начале. */
    val title: String get() = flag?.let { name.trimStart().removePrefix(it).trim() }?.ifEmpty { name } ?: name

    companion object {
        fun fromLibcore(subscriptionId: String, item: JSONObject): ServerNode {
            val name = item.optString("name")
            return ServerNode(
                id = "$subscriptionId/$name",
                subscriptionId = subscriptionId,
                name = name,
                type = item.optString("type"),
                server = item.optString("server"),
                port = item.optInt("port"),
                xray = item.has("xray"),
                mihomo = item.has("clash"),
                json = item.toString(),
            )
        }

        fun listFromLibcore(subscriptionId: String, json: String): List<ServerNode> =
            listFromJson(subscriptionId, JSONArray(json))

        fun listFromJson(subscriptionId: String, array: JSONArray?): List<ServerNode> {
            if (array == null) return emptyList()
            return List(array.length()) { fromLibcore(subscriptionId, array.getJSONObject(it)) }
        }
    }
}

/** Откуда взялась подписка. */
enum class SubscriptionSource(val id: String) {
    /** Ссылка, добавленная вручную. */
    URL("url"),

    /** Серверы, вставленные текстом: одни и те же для обоих ядер. */
    MANUAL("manual"),

    /** Подписка из аккаунта sub-lab; синхронизируется вместе с ним. */
    SUBLAB("sublab");

    companion object {
        fun of(id: String?): SubscriptionSource = entries.firstOrNull { it.id == id } ?: URL
    }
}

data class Subscription(
    val id: String,
    val name: String,
    /** Ссылка подписки или пусто, если серверы вставлены вручную. */
    val url: String,
    val source: SubscriptionSource = if (url.isEmpty()) SubscriptionSource.MANUAL else SubscriptionSource.URL,
    /** Короткая ссылка sub-lab, по которой подписка сопоставляется при синхронизации. */
    val shortId: String = "",
    /** Теги из sub-lab: по ним работает фильтр подписок аккаунта. */
    val tags: List<String> = emptyList(),
    val updatedAt: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    /** Окончание подписки, секунды Unix; 0 — бессрочно. */
    val expire: Long = 0,
    /** Объявление панели (заголовок `announce`). */
    val announce: String = "",
    /**
     * Ядро, закреплённое за подпиской: при подключении к её серверу приложение
     * переключается на него. null — подписка работает на текущем ядре.
     */
    val engine: Engine? = null,
    /**
     * Серверы для каждого ядра. Xray получает подписку как Happ, Mihomo — как
     * FlClashX, поэтому панель может отдать им разные списки.
     */
    val nodesXray: List<ServerNode> = emptyList(),
    val nodesMihomo: List<ServerNode> = emptyList(),
    val error: String? = null,
    /** Выключенная подписка остаётся в списке, но не пингуется, не проверяется и не выбирается. */
    val enabled: Boolean = true,
    /** Свёрнута на экране серверов. */
    val collapsed: Boolean = false,
) {
    val used: Long get() = upload + download

    val remote: Boolean get() = url.isNotEmpty()

    fun nodesFor(engine: Engine): List<ServerNode> = when (engine) {
        Engine.XRAY -> nodesXray
        Engine.MIHOMO -> nodesMihomo
    }

    /** Ядро, на котором работают серверы подписки при текущем ядре приложения. */
    fun effectiveEngine(current: Engine): Engine = engine ?: current

    /**
     * Серверы, которые показываются при текущем ядре приложения. Пока список
     * для ядра не скачан (например, нет интернета), показываются серверы,
     * сохранённые для другого ядра, — те из них, что это ядро умеет запускать.
     */
    fun visibleNodes(current: Engine): List<ServerNode> {
        val engine = effectiveEngine(current)
        val own = nodesFor(engine)
        if (own.isNotEmpty()) return own
        val other = if (engine == Engine.XRAY) nodesMihomo else nodesXray
        return other.filter { it.supports(engine) }
    }

    /** Список для ядра ещё не скачивался — показываются серверы другого ядра. */
    fun borrowsNodes(current: Engine): Boolean = nodesFor(effectiveEngine(current)).isEmpty() && visibleNodes(current).isNotEmpty()

    fun withNodes(engine: Engine, nodes: List<ServerNode>): Subscription = when (engine) {
        Engine.XRAY -> copy(nodesXray = nodes)
        Engine.MIHOMO -> copy(nodesMihomo = nodes)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("source", source.id)
        put("shortId", shortId)
        put("tags", JSONArray().apply { tags.forEach { put(it) } })
        put("updatedAt", updatedAt)
        put("upload", upload)
        put("download", download)
        put("total", total)
        put("expire", expire)
        put("announce", announce)
        engine?.let { put("engine", it.id) }
        error?.let { put("error", it) }
        put("enabled", enabled)
        put("collapsed", collapsed)
        put("nodesXray", JSONArray().apply { nodesXray.forEach { put(JSONObject(it.json)) } })
        put("nodesMihomo", JSONArray().apply { nodesMihomo.forEach { put(JSONObject(it.json)) } })
    }

    companion object {
        fun fromJson(json: JSONObject): Subscription {
            val id = json.getString("id")
            // До разделения по ядрам серверы лежали одним списком `nodes`.
            val legacy = ServerNode.listFromJson(id, json.optJSONArray("nodes"))
            val url = json.optString("url")
            val source = when {
                json.has("source") -> SubscriptionSource.of(json.optString("source"))
                url.isEmpty() -> SubscriptionSource.MANUAL
                else -> SubscriptionSource.URL
            }
            return Subscription(
                id = id,
                name = json.optString("name"),
                url = url,
                source = source,
                shortId = json.optString("shortId"),
                tags = json.optJSONArray("tags")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty(),
                updatedAt = json.optLong("updatedAt"),
                upload = json.optLong("upload"),
                download = json.optLong("download"),
                total = json.optLong("total"),
                expire = json.optLong("expire"),
                announce = json.optString("announce"),
                engine = json.optString("engine").ifEmpty { null }?.let { Engine.of(it) },
                nodesXray = json.optJSONArray("nodesXray")?.let { ServerNode.listFromJson(id, it) } ?: legacy,
                nodesMihomo = json.optJSONArray("nodesMihomo")?.let { ServerNode.listFromJson(id, it) } ?: legacy,
                error = json.optString("error").ifEmpty { null },
                enabled = json.optBoolean("enabled", true),
                collapsed = json.optBoolean("collapsed", false),
            )
        }
    }
}

/** Какие приложения идут через VPN. */
enum class AppMode(val id: String, val title: String) {
    ALL("all", "Все приложения"),
    ONLY("only", "Только выбранные"),
    EXCEPT("except", "Все, кроме выбранных");

    companion object {
        fun of(id: String?): AppMode = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/** Когда VPN сам встаёт на паузу. */
enum class WifiMode(val id: String, val title: String) {
    OFF("off", "Не отключать"),
    ANY("any", "В любой сети Wi-Fi"),
    LIST("list", "В выбранных сетях Wi-Fi");

    companion object {
        fun of(id: String?): WifiMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** Группировка серверов на экране «Серверы». */
enum class ServerGrouping(val id: String, val title: String) {
    /** По подпискам, с заголовками подписок. */
    SUBSCRIPTIONS("subscriptions", "По подпискам"),

    /** По пингу: до 100 мс, 100–250, 250–500, дольше, нет ответа. */
    PING("ping", "По пингу"),

    /** По надёжности из истории проверок. */
    RELIABILITY("reliability", "По доступности");

    companion object {
        fun of(id: String?): ServerGrouping = entries.firstOrNull { it.id == id } ?: SUBSCRIPTIONS
    }
}

/** Порядок серверов внутри группы. */
enum class ServerSort(val id: String, val title: String) {
    /** Свой порядок: перетаскиванием, новые — как в подписке. */
    DEFAULT("default", "Свой порядок"),

    /** Сначала быстрые по последнему пингу, недоступные — в конце. */
    PING("ping", "По пингу"),

    /** Сначала те, что чаще работают (история проверок). */
    RELIABILITY("reliability", "По доступности");

    companion object {
        fun of(id: String?): ServerSort = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Вид списка серверов. */
enum class ServerView(val id: String, val title: String) {
    LIST("list", "Список"),
    COMPACT("compact", "Компактно"),
    CARDS("cards", "Карточки");

    companion object {
        fun of(id: String?): ServerView = entries.firstOrNull { it.id == id } ?: COMPACT
    }
}

/** Сессия аккаунта sub-lab: хранится только токен, пароль не сохраняется. */
data class SubLabAccount(
    val server: String = "",
    val token: String = "",
    val username: String = "",
    val name: String = "",
    val syncedAt: Long = 0,
) {
    val loggedIn: Boolean get() = server.isNotEmpty() && token.isNotEmpty()
}

data class AppSettings(
    val engine: Engine = Engine.XRAY,
    val selectedNodeId: String? = null,
    val dns: String = "1.1.1.1",
    val bypassLan: Boolean = true,
    /** Весь .ru/.рф/.su мимо VPN (грубо; обычно хватает белого списка). */
    val directRu: Boolean = false,
    /** Сайты из белого списка — мимо VPN. */
    val whitelistEnabled: Boolean = true,
    val whitelistDomains: List<String> = Whitelist.DEFAULT_DOMAINS,
    val ipv6: Boolean = false,
    /** Свой User-Agent для запросов подписки; пусто — мимикрия под Happ / FlClashX. */
    val userAgentXray: String = "",
    val userAgentMihomo: String = "",
    /** Свой x-hwid для подписок; пусто — ANDROID_ID устройства. */
    val customHwid: String = "",
    /** Сколько ждать ответа сервера подписки. */
    val subscriptionTimeoutSec: Int = 20,
    /**
     * Фильтр подписок sub-lab по тегам: берутся только подписки хотя бы с одним
     * из них. Пусто — все.
     */
    val subLabTags: Set<String> = emptySet(),
    /** Теги, встреченные при последней синхронизации, — подсказки для фильтра. */
    val subLabKnownTags: Set<String> = emptySet(),
    val logLevel: String = "warning",
    val appMode: AppMode = AppMode.ALL,
    val apps: Set<String> = emptySet(),
    val wifiMode: WifiMode = WifiMode.OFF,
    val wifiNetworks: Set<String> = emptySet(),
    val account: SubLabAccount = SubLabAccount(),
    val bypass: BypassOptions = BypassOptions(),
    /** Предложение добавить плитку в шторку уже показывали. */
    val tilePrompted: Boolean = false,
    /** Открывать окно добавления, если в буфере обмена ссылка клиента или сервера. */
    val clipboardImport: Boolean = true,
    val auto: AutoOptions = AutoOptions(),
    /** Вводная инструкция пройдена (или пропущена). */
    val onboarded: Boolean = false,
    val serverGrouping: ServerGrouping = ServerGrouping.SUBSCRIPTIONS,
    val serverSort: ServerSort = ServerSort.DEFAULT,
    val serverView: ServerView = ServerView.COMPACT,
    /** Свой порядок серверов (id), заданный перетаскиванием. */
    val serverOrder: List<String> = emptyList(),
    /** Показывать настройки для опытных: ядро, DNS, обход блокировок, диагностику. */
    val advanced: Boolean = false,
    val checks: CheckOptions = CheckOptions(),
    val updateChannel: UpdateChannel = UpdateChannel.default,
    /** Проверять обновления при запуске (не чаще раза в 12 часов). */
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheck: Long = 0,
) {
    /** Настройки в формате libcore Options. */
    fun coreOptions(mtu: Int): String = JSONObject().apply {
        put("dns", dns)
        put("mtu", mtu)
        put("bypassLan", bypassLan)
        put("directRu", directRu)
        put("directDomains", JSONArray().apply { if (whitelistEnabled) whitelistDomains.forEach { put(it) } })
        put("ipv6", ipv6)
        put("logLevel", logLevel)
        put("fragment", JSONObject().apply {
            put("enabled", bypass.fragment)
            put("packets", bypass.fragmentPackets)
            put("length", bypass.fragmentLength)
            put("interval", bypass.fragmentInterval)
        })
        put("noise", JSONObject().apply {
            put("enabled", bypass.noise)
            put("type", bypass.noiseType)
            put("packet", bypass.noisePacket)
            put("delay", bypass.noiseDelay)
        })
        put("mux", JSONObject().apply {
            put("enabled", bypass.mux)
            put("concurrency", bypass.muxConcurrency)
        })
    }.toString()

    fun customUserAgent(engine: Engine): String = when (engine) {
        Engine.XRAY -> userAgentXray
        Engine.MIHOMO -> userAgentMihomo
    }
}

/** Способы пинга серверов, как в Happ. */
enum class PingMethod(val id: String, val title: String, val description: String) {
    TCP("tcp", "TCP", "Соединение с сервером. Быстро, но не проверяет, пропускает ли сервер трафик"),
    ICMP("icmp", "ICMP", "Обычный ping до адреса сервера. Многие серверы на него не отвечают"),
    PROXY_GET("proxy-get", "Через прокси (GET)", "Открывает адрес проверки через сам сервер — честно, но дольше"),
    PROXY_HEAD("proxy-head", "Через прокси (HEAD)", "То же, но запросом HEAD: меньше трафика");

    val throughProxy: Boolean get() = this == PROXY_GET || this == PROXY_HEAD

    companion object {
        fun of(id: String?): PingMethod = entries.firstOrNull { it.id == id } ?: TCP
    }
}

/** Настройки пингов и проверок соединения. */
data class CheckOptions(
    val pingMethod: PingMethod = PingMethod.TCP,
    val pingTimeoutMs: Int = 3000,
    /** Адреса проверки: соединение считается рабочим, если открылся хотя бы один. */
    val testUrls: List<String> = DEFAULT_TEST_URLS,
    /** GET или HEAD. */
    val testMethod: String = "GET",
    val testTimeoutMs: Int = 10_000,
    /** Проверить соединение вскоре после подключения. */
    val afterConnect: Boolean = true,
    val afterConnectDelaySec: Int = 5,
    /** Через сколько секунд повторять проверку после неудачи. */
    val retrySeconds: Int = 20,
    /** Дополнительно проверять, что через VPN отвечает выбранный DNS. */
    val dnsCheck: Boolean = false,
    val dnsDomain: String = "google.com",
) {
    val testUrl: String get() = testUrls.firstOrNull().orEmpty()

    companion object {
        val DEFAULT_TEST_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
    }
}

/** DNS-сервер из готового списка. */
data class DnsPreset(val title: String, val address: String)

val DNS_PRESETS = listOf(
    DnsPreset("Cloudflare", "1.1.1.1"),
    DnsPreset("Google", "8.8.8.8"),
    DnsPreset("Quad9", "9.9.9.9"),
    DnsPreset("Яндекс", "77.88.8.8"),
    DnsPreset("AdGuard (без рекламы)", "94.140.14.14"),
    DnsPreset("Cloudflare DoH", "https://1.1.1.1/dns-query"),
    DnsPreset("Google DoH", "https://dns.google/dns-query"),
    DnsPreset("AdGuard DoH", "https://dns.adguard-dns.com/dns-query"),
    DnsPreset("Cloudflare TCP", "tcp://1.1.1.1:53"),
    DnsPreset("Cloudflare DoT", "tls://1.1.1.1"),
)

/** Авто-режим: выбор лучшего сервера и контроль соединения. */
data class AutoOptions(
    /** При подключении выбирать лучший сервер по статистике и проверять его. */
    val selectBest: Boolean = false,
    /** Где искать: во всех подписках или только в подписке выбранного сервера. */
    val allSubscriptions: Boolean = true,
    /** Периодически проверять, что через VPN открываются сайты. */
    val healthCheck: Boolean = true,
    val intervalMinutes: Int = 5,
    /** Сколько проверок подряд должно не пройти, чтобы сервер сочли нерабочим. */
    val failures: Int = 3,
    /** Нерабочий сервер менять на лучший из работающих. */
    val failover: Boolean = true,
)

/**
 * Обход блокировок, как в Happ. Работает только в Xray: у mihomo аналогов нет.
 */
data class BypassOptions(
    /** Фрагментация TLS ClientHello: ТСПУ не собирает SNI из кусков. */
    val fragment: Boolean = false,
    val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "100-200",
    val fragmentInterval: String = "10-20",
    /** «Шум» перед UDP (QUIC): мешает распознать протокол по первым пакетам. */
    val noise: Boolean = false,
    val noiseType: String = "rand",
    val noisePacket: String = "10-20",
    val noiseDelay: String = "10-16",
    /** Мультиплексирование соединений (не для XTLS Vision). */
    val mux: Boolean = false,
    val muxConcurrency: Int = 8,
)

/** Региональные индикаторы (флаги) — пары символов из диапазона U+1F1E6..U+1F1FF. */
fun leadingFlag(text: String): String? {
    val trimmed = text.trimStart()
    if (trimmed.length < 4) return null
    val first = trimmed.codePointAt(0)
    val second = trimmed.codePointAt(Character.charCount(first))
    val range = 0x1F1E6..0x1F1FF
    if (first !in range || second !in range) return null
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
