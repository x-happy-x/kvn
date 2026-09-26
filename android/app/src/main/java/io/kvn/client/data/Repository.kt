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
data class SyncReport(val added: Int, val removed: Int, val total: Int, val errors: List<String>)

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

    /** Серверы текущего ядра. */
    val allNodes: List<ServerNode> get() = nodesFor(_settings.value.engine)

    fun nodesFor(engine: Engine): List<ServerNode> = _subscriptions.value.flatMap { it.nodesFor(engine) }

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
    suspend fun addSubscription(input: String, name: String?): Subscription = withContext(Dispatchers.IO) {
        val text = input.trim()
        val id = UUID.randomUUID().toString()
        val subscription = if (text.startsWith("http://") || text.startsWith("https://")) {
            download(Subscription(id = id, name = name.orEmpty(), url = text), _settings.value.engine)
        } else {
            val nodes = ServerNode.listFromLibcore(id, CoreBridge.parseSubscription(text))
            Subscription(
                id = id,
                name = name?.ifBlank { null } ?: "Свои серверы",
                url = "",
                updatedAt = System.currentTimeMillis(),
                nodesXray = nodes,
                nodesMihomo = nodes,
            )
        }
        _subscriptions.update { it + subscription }
        persist()
        if (_settings.value.selectedNodeId == null) {
            subscription.nodesFor(_settings.value.engine).firstOrNull()?.let { selectNode(it) }
        }
        subscription
    }

    /**
     * Обновляет подписку для ядра (по умолчанию — текущего). При ошибке оставляет
     * прежние серверы и запоминает текст ошибки.
     */
    suspend fun refresh(subscriptionId: String, engine: Engine = _settings.value.engine): Result<Subscription> =
        withContext(Dispatchers.IO) {
            val current = _subscriptions.value.firstOrNull { it.id == subscriptionId }
                ?: return@withContext Result.failure(IllegalArgumentException("Подписка не найдена"))
            if (!current.remote) return@withContext Result.success(current)
            val result = runCatching { download(current, engine) }
            val updated = result.getOrElse { current.copy(error = it.message ?: it.toString()) }
            replace(updated)
            result
        }

    suspend fun refreshAll(engine: Engine = _settings.value.engine) {
        _subscriptions.value.filter { it.remote }.forEach { refresh(it.id, engine) }
    }

    /**
     * Докачивает подписки, у которых ещё нет серверов для ядра: нужно после
     * переключения ядра, ведь каждое получает подписку со своими заголовками.
     */
    suspend fun ensureNodes(engine: Engine): List<String> {
        val errors = mutableListOf<String>()
        _subscriptions.value.filter { it.remote && it.nodesFor(engine).isEmpty() }.forEach { subscription ->
            refresh(subscription.id, engine).onFailure { errors += "${subscription.name}: ${it.message}" }
        }
        return errors
    }

    fun rename(subscriptionId: String, name: String) {
        _subscriptions.update { list -> list.map { if (it.id == subscriptionId) it.copy(name = name) else it } }
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
        val remote = try {
            SubLabClient.subscriptions(account.server, account.token)
        } catch (error: SubLabException) {
            if (error.unauthorized) updateSettings { it.copy(account = it.account.copy(token = "")) }
            throw error
        }
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
                if (fresh != null) item.copy(name = fresh.title, url = fresh.url) else item
            }
            val fresh = remote.filter { keyOf(it.shortId, it.url) !in known }.map {
                Subscription(
                    id = UUID.randomUUID().toString(),
                    name = it.title,
                    url = it.url,
                    source = SubscriptionSource.SUBLAB,
                    shortId = it.shortId,
                )
            }
            added += fresh
            kept + fresh
        }
        persist()
        val engine = _settings.value.engine
        val errors = mutableListOf<String>()
        _subscriptions.value.filter { it.source == SubscriptionSource.SUBLAB }.forEach { subscription ->
            refresh(subscription.id, engine).onFailure { errors += "${subscription.name}: ${it.message}" }
        }
        updateSettings { it.copy(account = it.account.copy(syncedAt = System.currentTimeMillis())) }
        if (_settings.value.selectedNodeId == null || selectedNode()?.id != _settings.value.selectedNodeId) {
            allNodes.firstOrNull()?.let { selectNode(it) }
        }
        SyncReport(added = added.size, removed = removed.size, total = remote.size, errors = errors)
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
        val fetched = SubscriptionFetcher.fetch(base.url, headers)
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
            ipv6 = prefs.getBoolean("ipv6", defaults.ipv6),
            userAgentXray = prefs.getString("userAgentXray", "").orEmpty(),
            userAgentMihomo = prefs.getString("userAgentMihomo", "").orEmpty(),
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
        )
    }

    private fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString("engine", settings.engine.id)
            .putString("selectedNodeId", settings.selectedNodeId)
            .putString("dns", settings.dns)
            .putBoolean("bypassLan", settings.bypassLan)
            .putBoolean("directRu", settings.directRu)
            .putBoolean("ipv6", settings.ipv6)
            .putString("userAgentXray", settings.userAgentXray)
            .putString("userAgentMihomo", settings.userAgentMihomo)
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
            .apply()
    }
}
