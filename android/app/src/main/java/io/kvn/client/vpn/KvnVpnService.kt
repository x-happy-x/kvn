package io.kvn.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.kvn.client.KvnApp
import io.kvn.client.MainActivity
import io.kvn.client.R
import io.kvn.client.core.CoreBridge
import io.kvn.client.data.AppMode
import io.kvn.client.data.AppSettings
import io.kvn.client.data.DnsCheck
import io.kvn.client.data.WifiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * VPN-сервис: открывает TUN и отдаёт его дескриптор выбранному ядру.
 *
 * Трафик самого приложения исключён из туннеля, поэтому соединения ядер с
 * серверами идут напрямую и не зацикливаются. В доверенной сети Wi-Fi сервис
 * остаётся включённым, но снимает туннель (состояние Paused) и поднимает его
 * снова, когда телефон уходит из этой сети.
 */
class KvnVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null

    /** Параметры, с которыми открыт текущий TUN: при их смене его надо пересоздать. */
    private var tunKey: String? = null

    /** Пользователь хочет, чтобы VPN работал (в том числе на паузе). */
    private var enabled = false

    private var wifi: WifiMonitor? = null
    private var wifiJob: Job? = null

    /** Периодическая проверка соединения (авто-режим). */
    private var healthJob: Job? = null
    private val selector by lazy { AutoSelector(KvnApp.instance.repository, KvnApp.instance.pingStats) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { lock.withLock { shutdown() } }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                promoteToForeground(getString(R.string.app_name))
                scope.launch { lock.withLock { apply(reuseTun = true) } }
            }
            else -> {
                // Запуск из системы («постоянная VPN») приходит без action.
                promoteToForeground(getString(R.string.app_name))
                scope.launch { lock.withLock { apply(reuseTun = false) } }
            }
        }
        return START_STICKY
    }

    /** Поднимает туннель или ставит его на паузу — смотря где сейчас телефон. */
    private suspend fun apply(reuseTun: Boolean) {
        enabled = true
        val settings = KvnApp.instance.repository.settings.value
        val monitor = syncWifiMonitor(settings)
        val reason = monitor?.let { WifiMonitor.pauseReason(it.state.value, settings) }
        if (reason != null) {
            pause(reason)
            return
        }
        // Перезапуск (смена ядра, сервера, правил) — выбор пользователя не трогаем.
        if (!reuseTun) autoSelect()
        connect(reuseTun)
    }

    /**
     * Авто-режим: до подключения выбирает лучший сервер по статистике и
     * проверяет его настоящим запросом. Туннеля ещё нет, а само приложение из
     * него исключено, поэтому проверка идёт напрямую.
     */
    private suspend fun autoSelect() {
        val repository = KvnApp.instance.repository
        if (!repository.settings.value.auto.selectBest) return
        VpnController.update(VpnState.Connecting)
        promoteToForeground("Ищу лучший сервер…")
        val best = selector.pickBest(around = repository.selectedNode())
        if (best != null) {
            repository.selectNode(best)
        } else {
            VpnController.notify("Авто-режим: ни один сервер не открыл страницу, подключаюсь к выбранному")
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun syncWifiMonitor(settings: AppSettings): WifiMonitor? {
        if (settings.wifiMode == WifiMode.OFF) {
            stopWifiMonitor()
            return null
        }
        wifi?.let { return it }
        val monitor = WifiMonitor(this).also { it.start() }
        wifi = monitor
        // Первое событие приходит асинхронно; без ожидания VPN успел бы подняться
        // в доверенной сети и тут же встать на паузу.
        withTimeoutOrNull(1_500) { monitor.state.first { it.connected } }
        wifiJob = scope.launch {
            monitor.state.drop(1).debounce(2_000).collect {
                lock.withLock { if (enabled) onWifiChanged() }
            }
        }
        return monitor
    }

    private fun stopWifiMonitor() {
        wifiJob?.cancel()
        wifiJob = null
        wifi?.stop()
        wifi = null
    }

    private fun onWifiChanged() {
        val monitor = wifi ?: return
        val settings = KvnApp.instance.repository.settings.value
        val reason = WifiMonitor.pauseReason(monitor.state.value, settings)
        val paused = VpnController.state.value is VpnState.Paused
        when {
            reason != null -> pause(reason)
            paused -> connect(reuseTun = false)
        }
    }

    private fun pause(reason: String) {
        releaseTun()
        VpnController.update(VpnState.Paused(reason))
        promoteToForeground("Пауза: $reason")
    }

    private fun connect(reuseTun: Boolean) {
        val repository = KvnApp.instance.repository
        val node = repository.selectedNode()
        if (node == null) {
            fail("Добавьте подписку и выберите сервер")
            return
        }
        // У подписки может быть закреплено своё ядро — тогда переключаемся на него.
        val nodeEngine = repository.engineFor(node)
        if (nodeEngine != repository.settings.value.engine) repository.setEngine(nodeEngine)
        val settings = repository.settings.value
        if (!node.supports(settings.engine)) {
            fail("«${node.title}» (${node.type}) не работает на ядре ${settings.engine.title}")
            return
        }

        VpnController.update(VpnState.Connecting)
        try {
            val key = listOf(settings.ipv6, settings.dns, settings.appMode.id, settings.apps.sorted()).joinToString("|")
            val current = tun
            val descriptor = if (reuseTun && current != null && tunKey == key) {
                current
            } else {
                // Старое ядро держит копию fd, поэтому сначала останавливаем его.
                releaseTun()
                openTun(settings).also {
                    tun = it
                    tunKey = key
                }
            }
            CoreBridge.start(settings.engine, node.json, descriptor.fd, settings.coreOptions(MTU))
            VpnController.update(VpnState.Connected(System.currentTimeMillis(), settings.engine, node.title))
            promoteToForeground("${node.title} · ${settings.engine.title}")
            startHealthLoop()
        } catch (error: Exception) {
            Log.e(TAG, "connect failed", error)
            fail(error.message ?: error.toString())
        }
    }

    /**
     * Каждые N минут проверяет, что через VPN открываются сайты. После
     * неудачи перепроверяет чаще; если сервер не ответил заданное число раз
     * подряд — ищет другой рабочий сервер и переключается на него.
     */
    /**
     * Контроль соединения: проверка вскоре после подключения и затем каждые
     * N минут. Проверка — открыть адреса проверки через VPN (хватит одного) и,
     * если включено, получить ответ выбранного DNS через туннель.
     */
    private fun startHealthLoop() {
        healthJob?.cancel()
        val settings = KvnApp.instance.repository.settings.value
        val auto = settings.auto
        val checks = settings.checks
        if (!auto.healthCheck && !checks.afterConnect) return
        healthJob = scope.launch {
            var failures = 0
            var first = true
            while (isActive) {
                val wait = when {
                    failures > 0 -> checks.retrySeconds.coerceAtLeast(5) * 1000L
                    first && checks.afterConnect -> checks.afterConnectDelaySec.coerceAtLeast(1) * 1000L
                    else -> auto.intervalMinutes.coerceAtLeast(1) * 60_000L
                }
                first = false
                delay(wait)
                if (VpnController.state.value !is VpnState.Connected) return@launch
                val error = checkOnce(settings)
                if (error == null) {
                    failures = 0
                    // Только проверка после подключения: периодические выключены.
                    if (!auto.healthCheck) return@launch
                    continue
                }
                failures++
                Log.w(TAG, "health check failed ($failures/${auto.failures}): $error")
                if (failures < auto.failures.coerceAtLeast(1)) continue
                if (!auto.failover) {
                    VpnController.notify("Соединение не работает ($failures раз подряд): $error")
                    failures = 0
                    if (!auto.healthCheck) return@launch
                    continue
                }
                // Переключение — отдельной задачей: connect() перезапустит эту проверку.
                scope.launch { lock.withLock { if (enabled) failover() } }
                return@launch
            }
        }
    }

    /** Одна проверка соединения; null — всё работает, иначе текст ошибки. */
    private fun checkOnce(settings: AppSettings): String? {
        val checks = settings.checks
        var lastError: String? = null
        val urls = checks.testUrls.ifEmpty { listOf("") }
        val httpOk = urls.any { url ->
            runCatching { CoreBridge.checkConnection(url, checks.testTimeoutMs, checks.testMethod) }
                .onFailure { lastError = it.message }
                .isSuccess
        }
        if (!httpOk) return lastError ?: "сайты не открываются"
        if (checks.dnsCheck) {
            val dns = DnsCheck.run(settings.dns, checks.dnsDomain, viaVpn = true)
            if (!dns.ok) return "DNS ${settings.dns}: ${dns.error}"
        }
        return null
    }

    private suspend fun failover() {
        val repository = KvnApp.instance.repository
        val current = repository.selectedNode() ?: return
        current.let { KvnApp.instance.pingStats.record(it, false, 0) }
        promoteToForeground("«${current.title}» не отвечает — ищу другой сервер…")
        val next = selector.pickBest(exclude = setOf(current.id), around = current)
        if (next == null) {
            VpnController.notify("«${current.title}» не отвечает, а рабочих серверов не нашлось — попробую позже")
            promoteToForeground("${current.title} · не отвечает")
            startHealthLoop()
            return
        }
        repository.selectNode(next)
        VpnController.notify("«${current.title}» не отвечал — переключился на «${next.title}»")
        connect(reuseTun = true)
    }

    private fun openTun(settings: AppSettings): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(tunDns(settings.dns))
            .setConfigureIntent(openAppIntent())
        applyAppMode(builder, settings)
        if (settings.ipv6) {
            builder.addAddress(TUN_ADDRESS6, 126).addRoute("::", 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder.establish() ?: throw IllegalStateException("Нет разрешения на VPN")
    }

    /**
     * Выборочное проксирование. Разрешённые и запрещённые приложения Android
     * смешивать не даёт: в режиме «только выбранные» само приложение и так
     * остаётся вне туннеля, в остальных — исключается явно.
     */
    private fun applyAppMode(builder: Builder, settings: AppSettings) {
        val apps = settings.apps.filter { it != packageName }
        when (settings.appMode) {
            AppMode.ALL -> builder.addDisallowedApplication(packageName)
            AppMode.EXCEPT -> {
                builder.addDisallowedApplication(packageName)
                // Удалённое приложение из списка не должно ломать подключение.
                apps.forEach { runCatching { builder.addDisallowedApplication(it) } }
            }
            AppMode.ONLY -> {
                val allowed = apps.count { runCatching { builder.addAllowedApplication(it) }.isSuccess }
                check(allowed > 0) { "Выберите приложения, которые пойдут через VPN" }
            }
        }
    }

    /** Системе нужен IP; если в настройках DoH/доменное имя — берём публичный DNS. */
    private fun tunDns(dns: String): String {
        val host = dns.substringAfter("://").substringBefore('/').substringBefore(':')
        return if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) host else "1.1.1.1"
    }

    private fun fail(message: String) {
        enabled = false
        stopWifiMonitor()
        VpnController.update(VpnState.Failed(message))
        releaseTun()
        stopForegroundCompat()
        stopSelf()
    }

    private fun shutdown() {
        enabled = false
        stopWifiMonitor()
        VpnController.update(VpnState.Stopping)
        releaseTun()
        VpnController.update(VpnState.Idle)
        stopForegroundCompat()
        stopSelf()
    }

    private fun releaseTun() {
        healthJob?.cancel()
        healthJob = null
        runCatching { CoreBridge.stop() }.onFailure { Log.w(TAG, "core stop", it) }
        runCatching { tun?.close() }
        tun = null
        tunKey = null
    }

    override fun onRevoke() {
        // Другое VPN-приложение забрало туннель или пользователь отключил нас в настройках.
        scope.launch { lock.withLock { shutdown() } }
    }

    override fun onDestroy() {
        stopWifiMonitor()
        if (tun != null || VpnController.state.value is VpnState.Paused) {
            releaseTun()
            VpnController.update(VpnState.Idle)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KvnVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Отключить", stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "io.kvn.client.START"
        const val ACTION_RESTART = "io.kvn.client.RESTART"
        const val ACTION_STOP = "io.kvn.client.STOP"

        private const val TAG = "KvnVpn"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500
        private const val TUN_ADDRESS = "172.19.0.1"
        private const val TUN_ADDRESS6 = "fdfe:dcba:9876::1"
    }
}
