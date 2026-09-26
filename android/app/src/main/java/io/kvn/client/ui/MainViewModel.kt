package io.kvn.client.ui

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.kvn.client.KvnApp
import io.kvn.client.core.CoreBridge
import io.kvn.client.core.Engine
import io.kvn.client.data.AppMode
import io.kvn.client.data.AppSettings
import io.kvn.client.data.DeepLinks
import io.kvn.client.data.ImportRequest
import io.kvn.client.data.NodeTest
import io.kvn.client.data.PingRecord
import io.kvn.client.data.PingStats
import io.kvn.client.data.Pinger
import io.kvn.client.data.DNS_PRESETS
import io.kvn.client.data.DnsCheck
import io.kvn.client.data.ScanPreset
import io.kvn.client.data.ScanResult
import io.kvn.client.data.ServerNode
import io.kvn.client.data.SubLabClient
import io.kvn.client.data.ServerSort
import io.kvn.client.data.Subscription
import io.kvn.client.data.Whitelist
import io.kvn.client.data.parseScanPresets
import io.kvn.client.vpn.VpnController
import io.kvn.client.vpn.VpnState
import io.kvn.client.vpn.WifiMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Скорость и объём трафика текущего подключения. */
data class Traffic(
    val up: Long = 0,
    val down: Long = 0,
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
)

/** Результат пинга: -1 — сервер недоступен. */
typealias Pings = Map<String, Int>

/** Установленное приложение для выборочного проксирования. */
data class InstalledApp(val packageName: String, val label: String, val system: Boolean)

/** Ход проверки ресурсов. */
data class ScanProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "")

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as KvnApp).repository
    private val pingStats = (application as KvnApp).pingStats
    private val prefs = application.getSharedPreferences("scan", Context.MODE_PRIVATE)

    val subscriptions: StateFlow<List<Subscription>> = repository.subscriptions
    val settings: StateFlow<AppSettings> = repository.settings
    val vpnState: StateFlow<VpnState> = VpnController.state

    val selectedNode: StateFlow<ServerNode?> = combine(repository.subscriptions, repository.settings) { _, _ ->
        repository.selectedNode()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, repository.selectedNode())

    private val _pings = MutableStateFlow<Pings>(emptyMap())
    val pings: StateFlow<Pings> = _pings.asStateFlow()

    private val _pinging = MutableStateFlow(false)
    val pinging: StateFlow<Boolean> = _pinging.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * Ошибка для открытой модалки (вход, добавление подписки): показывается
     * внутри неё — снэкбар прятался бы под окном.
     */
    private val _sheetError = MutableStateFlow<String?>(null)
    val sheetError: StateFlow<String?> = _sheetError.asStateFlow()

    fun clearSheetError() {
        _sheetError.value = null
    }

    private val _accountBusy = MutableStateFlow(false)
    val accountBusy: StateFlow<Boolean> = _accountBusy.asStateFlow()

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    val scanPresets: List<ScanPreset> by lazy { runCatching { parseScanPresets(CoreBridge.scanPresets()) }.getOrDefault(emptyList()) }

    private val _scanTargets = MutableStateFlow(loadScanTargets())
    val scanTargets: StateFlow<List<String>> = _scanTargets.asStateFlow()

    private val _scanResults = MutableStateFlow<Map<String, ScanResult>>(emptyMap())
    val scanResults: StateFlow<Map<String, ScanResult>> = _scanResults.asStateFlow()

    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    private val _nodeTests = MutableStateFlow<Map<String, NodeTest>>(emptyMap())
    val nodeTests: StateFlow<Map<String, NodeTest>> = _nodeTests.asStateFlow()

    private val _nodeTestProgress = MutableStateFlow(ScanProgress())
    val nodeTestProgress: StateFlow<ScanProgress> = _nodeTestProgress.asStateFlow()

    /** Версия статистики пингов: меняется после каждой серии, чтобы экран перечитал её. */
    private val _statsVersion = MutableStateFlow(0)
    val statsVersion: StateFlow<Int> = _statsVersion.asStateFlow()

    private var trafficJob: Job? = null
    private var scanJob: Job? = null
    private var nodeTestJob: Job? = null

    init {
        viewModelScope.launch {
            VpnController.events.collect { _messages.emit(it) }
        }
        viewModelScope.launch {
            vpnState.collect { state ->
                when (state) {
                    is VpnState.Connected -> startTrafficPolling()
                    is VpnState.Failed -> {
                        stopTrafficPolling()
                        _messages.tryEmit(state.message)
                    }
                    else -> stopTrafficPolling()
                }
            }
        }
    }

    // ---------- подключение ----------

    /** Нажатие на большую кнопку. [requestPermission] вызывается, если VPN ещё не разрешён. */
    fun toggle(needsPermission: Boolean, requestPermission: () -> Unit) {
        when (vpnState.value) {
            is VpnState.Connected, VpnState.Connecting, is VpnState.Paused -> VpnController.stop(getApplication())
            VpnState.Stopping -> Unit
            else -> {
                val node = repository.selectedNode()
                if (node == null) {
                    _messages.tryEmit("Сначала добавьте подписку")
                    return
                }
                if (!node.supports(settings.value.engine)) {
                    _messages.tryEmit("«${node.title}» не поддерживается ядром ${settings.value.engine.title}")
                    return
                }
                if (needsPermission) requestPermission() else VpnController.start(getApplication())
            }
        }
    }

    fun connectAfterPermission() = VpnController.start(getApplication())

    /**
     * Смена ядра. Подписка для него запрашивается заново — с заголовками Happ
     * (Xray) или FlClashX (Mihomo), — поэтому серверы могут отличаться.
     */
    fun setEngine(engine: Engine) {
        if (engine == settings.value.engine) return
        val previousName = repository.selectedNode()?.name
        repository.setEngine(engine)
        viewModelScope.launch {
            _refreshing.value = true
            val errors = repository.ensureNodes(engine)
            _refreshing.value = false
            errors.firstOrNull()?.let { _messages.tryEmit(it) }
            if (subscriptions.value.any { it.borrowsNodes(engine) }) {
                _messages.tryEmit("Для ${engine.title} подписки ещё не скачаны — пока показаны серверы прошлого ядра")
            }
            // Тот же сервер под другим ядром ищем по имени.
            val nodes = repository.allNodes
            val same = nodes.firstOrNull { it.name == previousName }
            if (same != null) repository.selectNode(same) else nodes.firstOrNull()?.let { repository.selectNode(it) }
            val node = repository.selectedNode()
            if (node != null && !node.supports(engine)) {
                _messages.tryEmit("«${node.title}» не работает на ${engine.title} — выберите другой сервер")
                if (VpnController.isActive) VpnController.stop(getApplication())
                return@launch
            }
            _pings.value = emptyMap()
            restartIfActive()
        }
    }

    fun select(node: ServerNode) {
        repository.selectNode(node)
        // Сервер подписки с закреплённым ядром переключает приложение на это ядро.
        val nodeEngine = repository.engineFor(node)
        if (nodeEngine != settings.value.engine) {
            repository.setEngine(nodeEngine)
            _messages.tryEmit("Ядро переключено на ${nodeEngine.title}")
            viewModelScope.launch { repository.ensureNodes(nodeEngine) }
        }
        if (!node.supports(nodeEngine)) {
            _messages.tryEmit("Этот сервер работает только на ${if (node.mihomo) "Mihomo" else "Xray"}")
            return
        }
        restartIfActive()
    }

    fun updateSettings(restart: Boolean = true, transform: (AppSettings) -> AppSettings) {
        repository.updateSettings(transform)
        if (restart) restartIfActive()
    }

    private fun restartIfActive() {
        when (vpnState.value) {
            is VpnState.Connected, is VpnState.Paused -> VpnController.restart(getApplication())
            else -> Unit
        }
    }

    // ---------- подписки ----------

    /**
     * Разбирает ссылку чужого клиента (happ://, clash://, sing-box://…) в то,
     * что можно добавить. null — это не ссылка клиента, её добавляют как есть.
     */
    fun parseImport(raw: String): ImportRequest? = runCatching {
        DeepLinks.parse(raw) { CoreBridge.decryptHapp(it) }
    }.getOrElse {
        _messages.tryEmit(it.message ?: "Не удалось разобрать ссылку")
        null
    }

    fun addSubscription(input: String, name: String?, engine: Engine?, onDone: () -> Unit) {
        viewModelScope.launch {
            _sheetError.value = null
            _refreshing.value = true
            runCatching {
                // Вставленную ссылку клиента сначала раскрываем до адреса подписки.
                val request = withContext(Dispatchers.IO) { DeepLinks.parse(input) { CoreBridge.decryptHapp(it) } }
                repository.addSubscription(
                    request?.input ?: input,
                    name?.ifBlank { null } ?: request?.name,
                    engine ?: request?.engine,
                )
            }
                .onSuccess {
                    _messages.tryEmit("Добавлено серверов: ${it.visibleNodes(settings.value.engine).size}")
                    onDone()
                    pingAll()
                }
                .onFailure { _sheetError.value = it.message ?: "Не удалось добавить подписку" }
            _refreshing.value = false
        }
    }

    fun refreshAll() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            if (settings.value.account.loggedIn) {
                runCatching { repository.syncSubLab() }.onFailure { _messages.tryEmit(it.message ?: "sub-lab недоступен") }
            }
            repository.refreshAll()
            _refreshing.value = false
            subscriptions.value.firstOrNull { it.error != null }?.let {
                _messages.tryEmit("${it.name}: ${it.error}")
            }
        }
    }

    fun refresh(subscription: Subscription) {
        viewModelScope.launch {
            _refreshing.value = true
            repository.refresh(subscription.id)
                .onSuccess { _messages.tryEmit("${it.name}: обновлено") }
                .onFailure { _messages.tryEmit(it.message ?: "Ошибка обновления") }
            _refreshing.value = false
        }
    }

    fun rename(subscription: Subscription, name: String) = repository.rename(subscription.id, name)

    fun setSubscriptionEngine(subscription: Subscription, engine: Engine?) {
        viewModelScope.launch {
            _refreshing.value = true
            repository.setSubscriptionEngine(subscription.id, engine)
                .onSuccess { _messages.tryEmit("${it.name}: ${engine?.title ?: "текущее ядро"}") }
                .onFailure { _messages.tryEmit(it.message ?: "Не удалось обновить подписку") }
            _refreshing.value = false
        }
    }

    /** Статистика пингов сервера (null — ещё не проверялся). */
    fun pingRecord(node: ServerNode): PingRecord? = pingStats.get(node)

    fun delete(subscription: Subscription) = repository.delete(subscription.id)

    fun setSubscriptionEnabled(subscription: Subscription, enabled: Boolean) {
        repository.setEnabled(subscription.id, enabled)
        _messages.tryEmit(if (enabled) "«${subscription.name}» включена" else "«${subscription.name}» выключена — её серверы не пингуются и не выбираются")
        if (!enabled) restartIfActive()
    }

    fun toggleCollapsed(subscription: Subscription) = repository.setCollapsed(subscription.id, !subscription.collapsed)

    /** Ссылка сервера для QR или null с сообщением, если протокол не выражается ссылкой. */
    fun shareLink(node: ServerNode): String? = runCatching { CoreBridge.shareLink(node.json) }
        .getOrElse {
            _messages.tryEmit(it.message ?: "Не удалось собрать ссылку")
            null
        }

    /** Текст с QR-кода: ссылку клиента раскрываем, остальное добавляем как есть. */
    fun importScanned(text: String): ImportRequest? {
        val value = text.trim()
        if (value.isEmpty()) return null
        return parseImport(value) ?: ImportRequest(input = value, name = null, engine = null, client = "QR-кода")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun pingAll() {
        if (_pinging.value) return
        val nodes = repository.allNodes
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            _pinging.value = true
            _pings.value = emptyMap()
            val current = settings.value
            // Через прокси каждый пинг поднимает отдельное ядро — параллельно меньше.
            val limited = Dispatchers.IO.limitedParallelism(if (current.checks.pingMethod.throughProxy) 4 else 8)
            // Сначала те, что чаще работают и отвечают быстрее: их результат виден сразу.
            pingStats.order(nodes).map { node ->
                launch(limited) {
                    val ms = Pinger.ping(node, repository.engineFor(node), current).let { if (it == 0) -1 else it }
                    if (node.server.isNotEmpty() || current.checks.pingMethod.throughProxy) pingStats.record(node, ms > 0, ms)
                    _pings.update { it + (node.id to ms) }
                }
            }.forEach { it.join() }
            pingStats.save()
            _statsVersion.update { it + 1 }
            _pinging.value = false
        }
    }

    /**
     * Выбирает лучший из отвечающих сейчас серверов: по статистике (как часто
     * работает и насколько быстро), а не по одному последнему пингу.
     */
    fun selectFastest() {
        val best = repository.allNodes
            .filter { it.supports(repository.engineFor(it)) }
            .filter { node -> (_pings.value[node.id] ?: 0) > 0 || _nodeTests.value[node.id]?.ok == true }
            .maxByOrNull { pingStats.get(it)?.score ?: 0.0 }
        if (best == null) {
            _messages.tryEmit("Сначала проверьте пинг")
            return
        }
        select(best)
    }

    // ---------- аккаунт sub-lab ----------

    fun subLabLogin(server: String, login: String, password: String, onDone: () -> Unit) {
        if (_accountBusy.value) return
        viewModelScope.launch {
            _accountBusy.value = true
            _sheetError.value = null
            runCatching {
                repository.subLabLogin(server, login, password)
                repository.syncSubLab()
            }.onSuccess { report ->
                _messages.tryEmit("Вход выполнен, подписок: ${report.total}")
                report.errors.firstOrNull()?.let { _messages.tryEmit(it) }
                onDone()
                pingAll()
            }.onFailure { _sheetError.value = it.message ?: "Не удалось войти" }
            _accountBusy.value = false
        }
    }

    /** Адрес sub-lab, угаданный по уже добавленным коротким ссылкам `/l/…`. */
    fun suggestedSubLabServer(): String =
        settings.value.account.server.ifEmpty {
            subscriptions.value.firstNotNullOfOrNull { SubLabClient.serverFromSubscriptionUrl(it.url) }.orEmpty()
        }

    fun subLabSync() {
        if (_accountBusy.value) return
        viewModelScope.launch {
            _accountBusy.value = true
            runCatching { repository.syncSubLab() }
                .onSuccess { report ->
                    val parts = buildList {
                        add("подписок: ${report.total}")
                        if (report.added > 0) add("новых: ${report.added}")
                        if (report.removed > 0) add("убрано: ${report.removed}")
                    }
                    _messages.tryEmit("sub-lab: ${parts.joinToString(", ")}")
                    report.errors.firstOrNull()?.let { _messages.tryEmit(it) }
                }
                .onFailure { _messages.tryEmit(it.message ?: "sub-lab недоступен") }
            _accountBusy.value = false
        }
    }

    fun subLabLogout() {
        viewModelScope.launch {
            _accountBusy.value = true
            repository.subLabLogout()
            _accountBusy.value = false
            _messages.tryEmit("Вы вышли из sub-lab")
        }
    }

    // ---------- приложения ----------

    fun loadApps() {
        if (_apps.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val own = getApplication<Application>().packageName
            val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != own }
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        // Системными считаем только те, у которых нет значка в лаунчере.
                        system = (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 &&
                            pm.getLaunchIntentForPackage(info.packageName) == null,
                    )
                }
                .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
            _apps.value = list
        }
    }

    // Список приложений применяется при выходе с экрана, а не на каждое нажатие:
    // иначе VPN перезапускался бы на каждой галочке.
    fun toggleApp(packageName: String) = updateSettings(restart = false) {
        it.copy(apps = if (packageName in it.apps) it.apps - packageName else it.apps + packageName)
    }

    fun setAppMode(mode: AppMode) = updateSettings(restart = false) { it.copy(appMode = mode) }

    /**
     * Приложения из белого списка — напрямую. В режиме «все» включается
     * «все, кроме выбранных»; в режиме «только выбранные» они убираются из
     * списка. Учитываются только установленные.
     */
    fun excludeWhitelistApps() {
        val installed = _apps.value?.map { it.packageName }?.toSet() ?: return
        val presets = Whitelist.PRESET_PACKAGES.intersect(installed)
        if (presets.isEmpty()) {
            _messages.tryEmit("Приложений из белого списка не найдено")
            return
        }
        updateSettings(restart = false) {
            when (it.appMode) {
                AppMode.ONLY -> it.copy(apps = it.apps - presets)
                else -> it.copy(appMode = AppMode.EXCEPT, apps = it.apps + presets)
            }
        }
        _messages.tryEmit("Напрямую: ${presets.size} ${io.kvn.client.ui.components.plural(presets.size.toLong(), "приложение", "приложения", "приложений")}")
    }

    // ---------- белый список сайтов ----------

    // Как и приложения, правки применяются при выходе с экрана.
    fun setWhitelistEnabled(enabled: Boolean) = updateSettings(restart = false) { it.copy(whitelistEnabled = enabled) }

    fun setDirectRu(enabled: Boolean) = updateSettings(restart = false) { it.copy(directRu = enabled) }

    /** Добавляет домены из вставленного текста; возвращает, сколько новых. */
    fun addWhitelistDomains(text: String): Int {
        val parsed = Whitelist.parseDomains(text)
        val current = settings.value.whitelistDomains
        val fresh = parsed.filter { it !in current }
        if (fresh.isNotEmpty()) updateSettings(restart = false) { it.copy(whitelistDomains = fresh + it.whitelistDomains) }
        return fresh.size
    }

    fun removeWhitelistDomain(domain: String) = updateSettings(restart = false) { it.copy(whitelistDomains = it.whitelistDomains - domain) }

    fun resetWhitelist() = updateSettings(restart = false) { it.copy(whitelistDomains = Whitelist.DEFAULT_DOMAINS) }

    // ---------- DNS ----------

    private val _dnsChecks = MutableStateFlow<Map<String, DnsCheck>>(emptyMap())
    /** Результаты проверки DNS: ключ — «адрес» или «адрес|vpn». */
    val dnsChecks: StateFlow<Map<String, DnsCheck>> = _dnsChecks.asStateFlow()

    private val _dnsChecking = MutableStateFlow(false)
    val dnsChecking: StateFlow<Boolean> = _dnsChecking.asStateFlow()

    /** Проверяет готовые DNS и выбранный: напрямую и, если VPN подключён, через него. */
    fun checkDnsServers() {
        if (_dnsChecking.value) return
        val servers = (DNS_PRESETS.map { it.address } + settings.value.dns).distinct()
        val viaVpn = vpnState.value is VpnState.Connected
        val domain = settings.value.checks.dnsDomain
        viewModelScope.launch {
            _dnsChecking.value = true
            _dnsChecks.value = emptyMap()
            servers.map { server ->
                launch(Dispatchers.IO) {
                    _dnsChecks.update { it + (server to DnsCheck.run(server, domain, false)) }
                    if (viaVpn) _dnsChecks.update { it + ("$server|vpn" to DnsCheck.run(server, domain, true)) }
                }
            }.forEach { it.join() }
            _dnsChecking.value = false
        }
    }

    fun setDns(server: String) = updateSettings { it.copy(dns = server.trim()) }

    fun updateChecks(transform: (io.kvn.client.data.CheckOptions) -> io.kvn.client.data.CheckOptions) =
        updateSettings(restart = false) { it.copy(checks = transform(it.checks)) }

    // ---------- вводная инструкция и вид ----------

    fun finishOnboarding(auto: Boolean, whitelist: Boolean) = updateSettings {
        it.copy(
            onboarded = true,
            auto = it.auto.copy(selectBest = auto, healthCheck = auto || it.auto.healthCheck, failover = auto || it.auto.failover),
            whitelistEnabled = whitelist,
        )
    }

    fun setServerSort(sort: ServerSort) = updateSettings(restart = false) { it.copy(serverSort = sort) }

    /** Применяет накопленные правки маршрутизации к работающему VPN. */
    fun applyRouting() = restartIfActive()

    // ---------- Wi-Fi ----------

    /** Имя сети, к которой телефон подключён сейчас (нужен доступ к геопозиции). */
    fun currentWifiName(): String? {
        val manager = getApplication<Application>().getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return WifiMonitor.cleanSsid(runCatching { manager.connectionInfo?.ssid }.getOrNull())
    }

    // ---------- проверка ресурсов ----------

    fun addScanTargets(targets: List<String>) {
        val cleaned = targets.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        _scanTargets.update { (it + cleaned).distinct() }
        saveScanTargets()
    }

    fun removeScanTarget(target: String) {
        _scanTargets.update { it - target }
        _scanResults.update { it - target }
        saveScanTargets()
    }

    fun clearScanTargets() {
        _scanTargets.value = emptyList()
        _scanResults.value = emptyMap()
        saveScanTargets()
    }

    /** Проверяет цели по три параллельно, чтобы не забивать канал. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun runScan(targets: List<String> = _scanTargets.value) {
        if (targets.isEmpty() || scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _scanProgress.value = ScanProgress(running = true, total = targets.size)
            _scanResults.update { it - targets.toSet() }
            val limited = Dispatchers.IO.limitedParallelism(3)
            targets.map { target ->
                launch(limited) {
                    _scanProgress.update { it.copy(current = target) }
                    val result = runCatching { ScanResult.fromJson(JSONObject(CoreBridge.scan(target))) }
                        .getOrElse { ScanResult.failed(target, it.message ?: "ошибка проверки") }
                    _scanResults.update { it + (target to result) }
                    _scanProgress.update { it.copy(done = it.done + 1) }
                }
            }.forEach { it.join() }
            _scanProgress.value = ScanProgress()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _scanProgress.value = ScanProgress()
    }

    private fun loadScanTargets(): List<String> {
        val saved = prefs.getString("targets", null) ?: return listOf("youtube.com", "instagram.com", "chatgpt.com", "gosuslugi.ru")
        return saved.split('\n').filter { it.isNotBlank() }
    }

    private fun saveScanTargets() {
        prefs.edit().putString("targets", _scanTargets.value.joinToString("\n")).apply()
    }

    // ---------- проверка серверов ----------

    /**
     * Настоящая проверка всех серверов: каждый открывает страницу через свой
     * прокси. Так видно те, что пингуются, но ничего не открывают.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun testAllNodes() {
        val nodes = repository.allNodes
        if (nodes.isEmpty() || nodeTestJob?.isActive == true) return
        nodeTestJob = viewModelScope.launch {
            _nodeTests.value = emptyMap()
            _nodeTestProgress.value = ScanProgress(running = true, total = nodes.size)
            val options = settings.value.coreOptions(1500)
            val limited = Dispatchers.IO.limitedParallelism(4)
            pingStats.order(nodes).map { node ->
                launch(limited) {
                    _nodeTestProgress.update { it.copy(current = node.title) }
                    val engine = repository.engineFor(node)
                    val result = if (!node.supports(engine)) {
                        NodeTest.failed("не работает на ${engine.title}")
                    } else {
                        runCatching { NodeTest.fromJson(JSONObject(CoreBridge.testNode(engine, node.json, options, settings.value.checks.testUrl, settings.value.checks.testTimeoutMs))) }
                            .getOrElse { NodeTest.failed(it.message ?: "ошибка проверки") }
                    }
                    if (result.verdict != "error") pingStats.record(node, result.ok, result.ms)
                    _nodeTests.update { it + (node.id to result) }
                    _nodeTestProgress.update { it.copy(done = it.done + 1) }
                }
            }.forEach { it.join() }
            pingStats.save()
            _statsVersion.update { it + 1 }
            _nodeTestProgress.value = ScanProgress()
            val silent = _nodeTests.value.values.count { it.verdict == "silent" }
            val ok = _nodeTests.value.values.count { it.ok }
            _messages.tryEmit("Работают: $ok из ${nodes.size}" + if (silent > 0) ", пингуются без ответа: $silent" else "")
        }
    }

    fun stopNodeTests() {
        nodeTestJob?.cancel()
        _nodeTestProgress.value = ScanProgress()
    }

    fun markTilePrompted() = updateSettings(restart = false) { it.copy(tilePrompted = true) }

    fun showMessage(message: String) {
        _messages.tryEmit(message)
    }

    // ---------- диагностика ----------

    suspend fun configPreview(): String = withContext(Dispatchers.IO) {
        val node = repository.selectedNode() ?: return@withContext "Сервер не выбран"
        runCatching {
            CoreBridge.buildConfig(settings.value.engine, node.json, settings.value.coreOptions(1500))
        }.getOrElse { it.message ?: it.toString() }
    }

    suspend fun logs(): String = withContext(Dispatchers.IO) {
        CoreBridge.logs().ifBlank { "Журнал пуст. Он появляется, пока ядро запущено." }
    }

    val versions: String by lazy {
        runCatching { "Xray ${CoreBridge.xrayVersion()} · Mihomo ${CoreBridge.mihomoVersion()}" }.getOrDefault("")
    }

    /**
     * Трафик считается по UID приложения: сам клиент исключён из туннеля, поэтому
     * всё, что он отправляет и получает, — это соединения ядра с сервером. Так
     * статистика одинакова для обоих ядер.
     */
    private fun startTrafficPolling() {
        if (trafficJob?.isActive == true) return
        trafficJob = viewModelScope.launch(Dispatchers.IO) {
            val uid = Process.myUid()
            val baseUp = TrafficStats.getUidTxBytes(uid)
            val baseDown = TrafficStats.getUidRxBytes(uid)
            if (baseUp == TrafficStats.UNSUPPORTED.toLong() || baseDown == TrafficStats.UNSUPPORTED.toLong()) return@launch
            var lastUp = 0L
            var lastDown = 0L
            while (isActive) {
                delay(1000)
                val up = TrafficStats.getUidTxBytes(uid) - baseUp
                val down = TrafficStats.getUidRxBytes(uid) - baseDown
                _traffic.value = Traffic(
                    up = up,
                    down = down,
                    upSpeed = (up - lastUp).coerceAtLeast(0),
                    downSpeed = (down - lastDown).coerceAtLeast(0),
                )
                lastUp = up
                lastDown = down
            }
        }
    }

    private fun stopTrafficPolling() {
        trafficJob?.cancel()
        trafficJob = null
        _traffic.value = Traffic()
    }
}
