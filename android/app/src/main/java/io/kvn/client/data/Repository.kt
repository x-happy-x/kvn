package io.kvn.client.data

import android.content.Context
import io.kvn.client.core.CoreBridge
import io.kvn.client.core.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Итог синхронизации с sub-lab. */
/** Итог синхронизации; [filtered] — подписки, скрытые фильтром по тегам. */
data class SyncReport(val added: Int, val removed: Int, val total: Int, val errors: List<String>, val filtered: Int = 0)

/**
 * Подписки и настройки. Подписки лежат JSON-файлом, настройки — в SharedPreferences;
 * наружу всё отдаётся через StateFlow, чтобы экран и VPN-сервис видели одно и то же.
 */
class Repository(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "subscriptions.json")

    private val _subscriptions = MutableStateFlow(loadSubscriptions())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Серверы, видимые при текущем ядре (у подписок с закреплённым ядром — его серверы). */
    /** Серверы включённых подписок: только они пингуются, проверяются и выбираются. */
    val allNodes: List<ServerNode> get() = _subscriptions.value.filter { it.enabled }.flatMap { it.visibleNodes(_settings.value.engine) }

    /** Ядро, на котором работает сервер: закреплённое за его подпиской или текущее. */
    fun engineFor(node: ServerNode): Engine =
        _subscriptions.value.firstOrNull { it.id == node.subscriptionId }?.effectiveEngine(_settings.value.engine)
            ?: _settings.value.engine

    fun selectedNode(): ServerNode? {
        val nodes = allNodes
        val selectedId = _settings.value.selectedNodeId
        return nodes.firstOrNull { it.id == selectedId } ?: nodes.firstOrNull()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        saveSettings(_settings.value)
    }

    fun selectNode(node: ServerNode) = updateSettings { it.copy(selectedNodeId = node.id) }

    fun setEngine(engine: Engine) = updateSettings { it.copy(engine = engine) }

    /** Добавляет подписку по ссылке или вставленные вручную ссылки серверов. */
    suspend fun addSubscription(input: String, name: String?, engine: Engine? = null): Subscription = withContext(Dispatchers.IO) {
        val text = input.trim()
        val id = UUID.randomUUID().toString()
        val subscription = if (text.startsWith("http://") || text.startsWith("https://")) {
            val base = Subscription(id = id, name = name.orEmpty(), url = text, engine = engine)
            download(base, base.effectiveEngine(_settings.value.engine))
        } else {
            val nodes = ServerNode.listFromLibcore(id, CoreBridge.parseSubscription(text))
            Subscription(
                id = id,
                name = name?.ifBlank { null } ?: "Свои серверы",
                url = "",
                updatedAt = System.currentTimeMillis(),
                engine = engine,
                nodesXray = nodes,
                nodesMihomo = nodes,
            )
        }
        _subscriptions.update { it + subscription }
        persist()
        if (_settings.value.selectedNodeId == null) {
            subscription.visibleNodes(_settings.value.engine).firstOrNull()?.let { selectNode(it) }
        }
        subscription
    }

    /**
     * Обновляет подписку для ядра (по умолчанию — текущего). При ошибке оставляет
     * прежние серверы и запоминает текст ошибки.
     */
    suspend fun refresh(subscriptionId: String, engine: Engine? = null): Result<Subscription> =
        withContext(Dispatchers.IO) {
            val current = _subscriptions.value.firstOrNull { it.id == subscriptionId }
                ?: return@withContext Result.failure(IllegalArgumentException("Подписка не найдена"))
            if (!current.remote) return@withContext Result.success(current)
            // У подписки с закреплённым ядром серверы нужны только для него.
            val target = current.engine ?: engine ?: _settings.value.engine
            val result = runCatching { download(current, target) }
            val updated = result.getOrElse { current.copy(error = it.message ?: it.toString()) }
            replace(updated)
            result
        }

    suspend fun refreshAll() {
        _subscriptions.value.filter { it.remote && it.enabled }.forEach { refresh(it.id) }
    }

    /**
     * Докачивает подписки, у которых ещё нет серверов для ядра: нужно после
     * переключения ядра, ведь каждое получает подписку со своими заголовками.
     */
    suspend fun ensureNodes(engine: Engine): List<String> {
        // Без сети не дёргаем панели: до загрузки видны серверы прошлого ядра.
        if (!isOnline()) return emptyList()
        val errors = mutableListOf<String>()
        _subscriptions.value.filter { it.remote && it.engine == null && it.nodesFor(engine).isEmpty() }.forEach { subscription ->
            refresh(subscription.id, engine).onFailure { errors += "${subscription.name}: ${it.message}" }
        }
        return errors
    }

    /** Закрепляет ядро за подпиской (null — текущее ядро приложения) и докачивает её серверы. */
    suspend fun setSubscriptionEngine(subscriptionId: String, engine: Engine?): Result<Subscription> {
        _subscriptions.update { list -> list.map { if (it.id == subscriptionId) it.copy(engine = engine) else it } }
        persist()
        val subscription = _subscriptions.value.first { it.id == subscriptionId }
        val target = subscription.effectiveEngine(_settings.value.engine)
        return if (subscription.remote && subscription.nodesFor(target).isEmpty()) refresh(subscriptionId, target) else Result.success(subscription)
    }

    fun rename(subscriptionId: String, name: String) {
        _subscriptions.update { list -> list.map { if (it.id == subscriptionId) it.copy(name = name) else it } }
        persist()
    }

    /** Временно выключает подписку, не удаляя её; выбранный сервер уходит на включённую. */
    fun setEnabled(subscriptionId: String, enabled: Boolean) {
        _subscriptions.update { list -> list.map { if (it.id == subscriptionId) it.copy(enabled = enabled) else it } }
        persist()
        if (!enabled && _settings.value.selectedNodeId?.startsWith("$subscriptionId/") == true) {
            updateSettings { it.copy(selectedNodeId = allNodes.firstOrNull()?.id) }
        }
    }

    fun setCollapsed(subscriptionId: String, collapsed: Boolean) {
        _subscriptions.update { list -> list.map { if (it.id == subscriptionId) it.copy(collapsed = collapsed) else it } }
        persist()
    }

    fun delete(subscriptionId: String) {
        _subscriptions.update { list -> list.filterNot { it.id == subscriptionId } }
        persist()
        if (_settings.value.selectedNodeId?.startsWith("$subscriptionId/") == true) {
            updateSettings { it.copy(selectedNodeId = allNodes.firstOrNull()?.id) }
        }
    }

    // ---------- sub-lab ----------

    suspend fun subLabLogin(serverInput: String, login: String, password: String): SubLabLogin =
        withContext(Dispatchers.IO) {
            val server = SubLabClient.normalizeServer(serverInput)
            require(server.isNotEmpty()) { "Укажите адрес sub-lab" }
            val session = SubLabClient.login(server, login.trim(), password)
            updateSettings {
                it.copy(account = SubLabAccount(server = server, token = session.token, username = session.username, name = session.name))
            }
            session
        }

    /**
     * Сверяет подписки с аккаунтом sub-lab: добавляет новые, убирает те, к которым
     * доступ отозван, и скачивает серверы для текущего ядра.
     */
    suspend fun syncSubLab(): SyncReport = withContext(Dispatchers.IO) {
        val account = _settings.value.account
        check(account.loggedIn) { "Войдите в sub-lab" }
        val all = try {
            SubLabClient.subscriptions(account.server, account.token)
        } catch (error: SubLabException) {
            if (error.unauthorized) updateSettings { it.copy(account = it.account.copy(token = "")) }
            throw error
        }
        // Фильтр по тегам: остальные подписки аккаунта в приложение не попадают
        // (а уже добавленные уходят, как отозванные).
        val filter = _settings.value.subLabTags
        val remote = if (filter.isEmpty()) all else all.filter { item -> item.tags.any { it in filter } }
        updateSettings { it.copy(subLabKnownTags = all.flatMap { item -> item.tags }.toSet()) }
        val keyOf = { shortId: String, url: String -> shortId.ifEmpty { url } }
        val existing = _subscriptions.value.filter { it.source == SubscriptionSource.SUBLAB }
        val remoteKeys = remote.map { keyOf(it.shortId, it.url) }.toSet()
        val removed = existing.filter { keyOf(it.shortId, it.url) !in remoteKeys }
        val known = existing.associateBy { keyOf(it.shortId, it.url) }
        val added = mutableListOf<Subscription>()
        _subscriptions.update { list ->
            val kept = list.filterNot { item -> removed.any { it.id == item.id } }.map { item ->
                // Название и ссылка могли поменяться на сервере.
                val fresh = if (item.source == SubscriptionSource.SUBLAB) {
                    remote.firstOrNull { keyOf(it.shortId, it.url) == keyOf(item.shortId, item.url) }
                } else {
                    null
                }
                if (fresh != null) item.copy(name = fresh.title, url = fresh.url, tags = fresh.tags) else item
            }
            val fresh = remote.filter { keyOf(it.shortId, it.url) !in known }.map {
                Subscription(
                    id = UUID.randomUUID().toString(),
                    name = it.title,
                    url = it.url,
                    source = SubscriptionSource.SUBLAB,
                    shortId = it.shortId,
                    tags = it.tags,
                )
            }
            added += fresh
            kept + fresh
        }
        persist()
        val errors = mutableListOf<String>()
        _subscriptions.value.filter { it.source == SubscriptionSource.SUBLAB }.forEach { subscription ->
            refresh(subscription.id).onFailure { errors += "${subscription.name}: ${it.message}" }
        }
        updateSettings { it.copy(account = it.account.copy(syncedAt = System.currentTimeMillis())) }
        if (_settings.value.selectedNodeId == null || selectedNode()?.id != _settings.value.selectedNodeId) {
            allNodes.firstOrNull()?.let { selectNode(it) }
        }
        SyncReport(added = added.size, removed = removed.size, total = remote.size, errors = errors, filtered = all.size - remote.size)
    }

    /** Выход: сессия на сервере закрывается, подписки из sub-lab убираются. */
    suspend fun subLabLogout() = withContext(Dispatchers.IO) {
        val account = _settings.value.account
        if (account.loggedIn) SubLabClient.logout(account.server, account.token)
        _subscriptions.update { list -> list.filterNot { it.source == SubscriptionSource.SUBLAB } }
        persist()
        updateSettings { it.copy(account = SubLabAccount(server = account.server)) }
    }

    // ---------- загрузка ----------

    private fun download(base: Subscription, engine: Engine): Subscription {
        val headers = Mimicry.headers(context, engine, _settings.value)
        val fetched = SubscriptionFetcher.fetch(base.url, headers, _settings.value.subscriptionTimeoutSec.coerceIn(5, 300) * 1000)
        val nodes = ServerNode.listFromLibcore(base.id, CoreBridge.parseSubscription(fetched.body))
        return base.withNodes(engine, nodes).copy(
            name = base.name.ifBlank { fetched.title ?: hostOf(base.url) },
            updatedAt = System.currentTimeMillis(),
            upload = fetched.upload,
            download = fetched.download,
            total = fetched.total,
            expire = fetched.expire,
            announce = fetched.announce.orEmpty(),
            error = null,
        )
    }

    private fun replace(updated: Subscription) {
        _subscriptions.update { list -> list.map { if (it.id == updated.id) updated else it } }
        persist()
    }

    /** Есть ли подключение к интернету (любая сеть, кроме нашего же VPN). */
    fun isOnline(): Boolean {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return connectivity.allNetworks.any { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.ifEmpty { null } ?: "Подписка"

    private fun loadSubscriptions(): List<Subscription> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        List(array.length()) { Subscription.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun persist() {
        val array = JSONArray().apply { _subscriptions.value.forEach { put(it.toJson()) } }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
    }

    private fun loadSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            engine = Engine.of(prefs.getString("engine", defaults.engine.id)),
            selectedNodeId = prefs.getString("selectedNodeId", null),
            dns = prefs.getString("dns", defaults.dns) ?: defaults.dns,
            bypassLan = prefs.getBoolean("bypassLan", defaults.bypassLan),
            directRu = prefs.getBoolean("directRu", defaults.directRu),
            whitelistEnabled = prefs.getBoolean("whitelistEnabled", defaults.whitelistEnabled),
            whitelistDomains = prefs.getString("whitelistDomains", null)
                ?.split('\n')?.filter { it.isNotBlank() }
                ?: defaults.whitelistDomains,
            ipv6 = prefs.getBoolean("ipv6", defaults.ipv6),
            userAgentXray = prefs.getString("userAgentXray", "").orEmpty(),
            userAgentMihomo = prefs.getString("userAgentMihomo", "").orEmpty(),
            customHwid = prefs.getString("customHwid", "").orEmpty(),
            subscriptionTimeoutSec = prefs.getInt("subscriptionTimeoutSec", 20),
            subLabTags = prefs.getStringSet("subLabTags", emptySet()).orEmpty().toSet(),
            subLabKnownTags = prefs.getStringSet("subLabKnownTags", emptySet()).orEmpty().toSet(),
            logLevel = prefs.getString("logLevel", defaults.logLevel) ?: defaults.logLevel,
            appMode = AppMode.of(prefs.getString("appMode", null)),
            apps = prefs.getStringSet("apps", emptySet()).orEmpty().toSet(),
            wifiMode = WifiMode.of(prefs.getString("wifiMode", null)),
            wifiNetworks = prefs.getStringSet("wifiNetworks", emptySet()).orEmpty().toSet(),
            account = SubLabAccount(
                server = prefs.getString("sublabServer", "").orEmpty(),
                token = prefs.getString("sublabToken", "").orEmpty(),
                username = prefs.getString("sublabUsername", "").orEmpty(),
                name = prefs.getString("sublabName", "").orEmpty(),
                syncedAt = prefs.getLong("sublabSyncedAt", 0),
            ),
            bypass = BypassOptions(
                fragment = prefs.getBoolean("fragment", false),
                fragmentPackets = prefs.getString("fragmentPackets", null) ?: BypassOptions().fragmentPackets,
                fragmentLength = prefs.getString("fragmentLength", null) ?: BypassOptions().fragmentLength,
                fragmentInterval = prefs.getString("fragmentInterval", null) ?: BypassOptions().fragmentInterval,
                noise = prefs.getBoolean("noise", false),
                noiseType = prefs.getString("noiseType", null) ?: BypassOptions().noiseType,
                noisePacket = prefs.getString("noisePacket", null) ?: BypassOptions().noisePacket,
                noiseDelay = prefs.getString("noiseDelay", null) ?: BypassOptions().noiseDelay,
                mux = prefs.getBoolean("mux", false),
                muxConcurrency = prefs.getInt("muxConcurrency", BypassOptions().muxConcurrency),
            ),
            tilePrompted = prefs.getBoolean("tilePrompted", false),
            clipboardImport = prefs.getBoolean("clipboardImport", true),
            auto = AutoOptions(
                selectBest = prefs.getBoolean("autoSelectBest", false),
                allSubscriptions = prefs.getBoolean("autoAllSubscriptions", true),
                healthCheck = prefs.getBoolean("autoHealthCheck", true),
                intervalMinutes = prefs.getInt("autoIntervalMinutes", 5),
                failures = prefs.getInt("autoFailures", 3),
                failover = prefs.getBoolean("autoFailover", true),
            ),
            onboarded = prefs.getBoolean("onboarded", false),
            serverGrouping = ServerGrouping.of(prefs.getString("serverGrouping", null)),
            updateChannel = UpdateChannel.of(prefs.getString("updateChannel", null)) ?: UpdateChannel.default,
            autoUpdateCheck = prefs.getBoolean("autoUpdateCheck", true),
            lastUpdateCheck = prefs.getLong("lastUpdateCheck", 0),
            // Ключи с «2»: у прежних значений другой смысл (сортировка была и группировкой).
            serverSort = ServerSort.of(prefs.getString("serverSort2", null)),
            serverView = ServerView.of(prefs.getString("serverView2", null)),
            serverOrder = prefs.getString("serverOrder", null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty(),
            advanced = prefs.getBoolean("advanced", false),
            checks = CheckOptions().let { d ->
                CheckOptions(
                    pingMethod = PingMethod.of(prefs.getString("pingMethod", null)),
                    pingTimeoutMs = prefs.getInt("pingTimeoutMs", d.pingTimeoutMs),
                    testUrls = prefs.getString("testUrls", null)?.split('\n')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: d.testUrls,
                    testMethod = prefs.getString("testMethod", null) ?: d.testMethod,
                    testTimeoutMs = prefs.getInt("testTimeoutMs", d.testTimeoutMs),
                    afterConnect = prefs.getBoolean("checkAfterConnect", d.afterConnect),
                    afterConnectDelaySec = prefs.getInt("checkAfterConnectDelay", d.afterConnectDelaySec),
                    retrySeconds = prefs.getInt("checkRetrySeconds", d.retrySeconds),
                    dnsCheck = prefs.getBoolean("dnsCheck", d.dnsCheck),
                    dnsDomain = prefs.getString("dnsDomain", null) ?: d.dnsDomain,
                )
            },
        )
    }

    private fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString("engine", settings.engine.id)
            .putString("selectedNodeId", settings.selectedNodeId)
            .putString("dns", settings.dns)
            .putBoolean("bypassLan", settings.bypassLan)
            .putBoolean("directRu", settings.directRu)
            .putBoolean("whitelistEnabled", settings.whitelistEnabled)
            .putString("whitelistDomains", settings.whitelistDomains.joinToString("\n"))
            .putBoolean("ipv6", settings.ipv6)
            .putString("userAgentXray", settings.userAgentXray)
            .putString("userAgentMihomo", settings.userAgentMihomo)
            .putString("customHwid", settings.customHwid)
            .putInt("subscriptionTimeoutSec", settings.subscriptionTimeoutSec)
            .putStringSet("subLabTags", settings.subLabTags)
            .putStringSet("subLabKnownTags", settings.subLabKnownTags)
            .putString("logLevel", settings.logLevel)
            .putString("appMode", settings.appMode.id)
            .putStringSet("apps", settings.apps)
            .putString("wifiMode", settings.wifiMode.id)
            .putStringSet("wifiNetworks", settings.wifiNetworks)
            .putString("sublabServer", settings.account.server)
            .putString("sublabToken", settings.account.token)
            .putString("sublabUsername", settings.account.username)
            .putString("sublabName", settings.account.name)
            .putLong("sublabSyncedAt", settings.account.syncedAt)
            .putBoolean("fragment", settings.bypass.fragment)
            .putString("fragmentPackets", settings.bypass.fragmentPackets)
            .putString("fragmentLength", settings.bypass.fragmentLength)
            .putString("fragmentInterval", settings.bypass.fragmentInterval)
            .putBoolean("noise", settings.bypass.noise)
            .putString("noiseType", settings.bypass.noiseType)
            .putString("noisePacket", settings.bypass.noisePacket)
            .putString("noiseDelay", settings.bypass.noiseDelay)
            .putBoolean("mux", settings.bypass.mux)
            .putInt("muxConcurrency", settings.bypass.muxConcurrency)
            .putBoolean("tilePrompted", settings.tilePrompted)
            .putBoolean("clipboardImport", settings.clipboardImport)
            .putBoolean("autoSelectBest", settings.auto.selectBest)
            .putBoolean("autoAllSubscriptions", settings.auto.allSubscriptions)
            .putBoolean("autoHealthCheck", settings.auto.healthCheck)
            .putInt("autoIntervalMinutes", settings.auto.intervalMinutes)
            .putInt("autoFailures", settings.auto.failures)
            .putBoolean("autoFailover", settings.auto.failover)
            .putBoolean("onboarded", settings.onboarded)
            .putString("serverGrouping", settings.serverGrouping.id)
            .putString("updateChannel", settings.updateChannel.id)
            .putBoolean("autoUpdateCheck", settings.autoUpdateCheck)
            .putLong("lastUpdateCheck", settings.lastUpdateCheck)
            .putString("serverSort2", settings.serverSort.id)
            .putString("serverView2", settings.serverView.id)
            .putString("serverOrder", settings.serverOrder.joinToString("\n"))
            .putBoolean("advanced", settings.advanced)
            .putString("pingMethod", settings.checks.pingMethod.id)
            .putInt("pingTimeoutMs", settings.checks.pingTimeoutMs)
            .putString("testUrls", settings.checks.testUrls.joinToString("\n"))
            .putString("testMethod", settings.checks.testMethod)
            .putInt("testTimeoutMs", settings.checks.testTimeoutMs)
            .putBoolean("checkAfterConnect", settings.checks.afterConnect)
            .putInt("checkAfterConnectDelay", settings.checks.afterConnectDelaySec)
            .putInt("checkRetrySeconds", settings.checks.retrySeconds)
            .putBoolean("dnsCheck", settings.checks.dnsCheck)
            .putString("dnsDomain", settings.checks.dnsDomain)
            .apply()
    }
}
