package io.kvn.client

import android.Manifest
import android.app.StatusBarManager
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import android.graphics.BitmapFactory
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.kvn.client.data.Qr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kvn.client.core.Engine
import io.kvn.client.data.DeepLinks
import io.kvn.client.data.ImportRequest
import io.kvn.client.ui.MainViewModel
import io.kvn.client.ui.components.uiSpring
import io.kvn.client.ui.screens.AddSubscriptionSheet
import io.kvn.client.ui.screens.AppsScreen
import io.kvn.client.ui.screens.DnsScreen
import io.kvn.client.ui.screens.HomeScreen
import io.kvn.client.ui.screens.LoginSheet
import io.kvn.client.ui.screens.OnboardingNext
import io.kvn.client.ui.screens.OnboardingScreen
import io.kvn.client.ui.screens.ScanScreen
import io.kvn.client.ui.screens.ServersScreen
import io.kvn.client.ui.screens.SettingsScreen
import io.kvn.client.ui.screens.WhitelistScreen
import io.kvn.client.ui.screens.WifiScreen
import io.kvn.client.ui.theme.KvnTheme
import io.kvn.client.ui.theme.Palette
import io.kvn.client.vpn.VpnState
import io.kvn.client.vpn.VpnTileService
import kotlinx.coroutines.flow.MutableStateFlow

/** Разрешения, от которых зависит интерфейс; пересчитываются при возврате в приложение. */
data class Permissions(val location: Boolean = false, val backgroundLocation: Boolean = false)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /**
     * Ссылка другого клиента (happ://, clash://, sing-box://…, kvn://import) —
     * открывает окно добавления с подставленным адресом и ядром.
     */
    private val pendingImport = MutableStateFlow<ImportRequest?>(null)

    private val permissions = MutableStateFlow(Permissions())

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) viewModel.connectAfterPermission()
    }

    /** Сканер QR (zxing): сам спрашивает доступ к камере. */
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onQrText)
    }

    /** QR-код на картинке из галереи или скриншоте. */
    private val qrImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { Qr.decode(it) }
                }.getOrNull()
            }
            if (text != null) onQrText(text) else viewModel.showMessage("На картинке не нашлось QR-кода")
        }
    }

    private fun onQrText(text: String) {
        viewModel.importScanned(text)?.let { pendingImport.value = it.copy(client = "QR-кода") }
    }

    private fun scanQr() {
        qrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Наведите камеру на QR-код подписки или сервера")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun pickQrImage() {
        qrImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
    }

    private val backgroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions()
        if (!granted) openAppSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        askNotificationPermission()
        handleIntent(intent)

        setContent {
            KvnTheme {
                App(
                    viewModel = viewModel,
                    permissions = permissions.collectAsStateWithLifecycle().value,
                    pendingImport = pendingImport.collectAsStateWithLifecycle().value,
                    onImportConsumed = { pendingImport.value = null },
                    onToggle = ::toggleVpn,
                    onRequestLocation = ::requestLocation,
                    onRequestBackgroundLocation = ::requestBackgroundLocation,
                    onAddTile = ::requestAddTile,
                    onScanQr = ::scanQr,
                    onPickQrImage = ::pickQrImage,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    /**
     * Буфер обмена Android отдаёт только окну в фокусе, поэтому проверяем его
     * здесь, а не в onResume.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    /**
     * Скопированная ссылка клиента (happ://, clash://…) или сервера (vless://…)
     * сразу открывает окно добавления, как в Happ. Одну и ту же ссылку
     * предлагаем один раз.
     */
    private fun checkClipboard() {
        if (!viewModel.settings.value.clipboardImport || pendingImport.value != null) return
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        val description = manager.primaryClipDescription ?: return
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return
        val text = runCatching { manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()?.trim() ?: return
        if (text.length > 64 * 1024 || !DeepLinks.isSupported(text)) return
        val prefs = getSharedPreferences("clipboard", MODE_PRIVATE)
        val fingerprint = text.hashCode().toString()
        if (prefs.getString("last", null) == fingerprint) return
        prefs.edit().putString("last", fingerprint).apply()
        viewModel.parseImport(text)?.let { pendingImport.value = it.copy(client = "буфера обмена (${it.client})") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) {
            intent.removeExtra(EXTRA_CONNECT)
            toggleVpn()
        }
        val link = intent.dataString
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { intent.action == Intent.ACTION_SEND }
            ?: return
        viewModel.parseImport(link)?.let { pendingImport.value = it }
    }

    /**
     * Плитку в шторку Android 13+ добавляет по запросу приложения; на старых
     * версиях её перетаскивают вручную в редакторе быстрых настроек.
     */
    private fun requestAddTile() {
        viewModel.markTilePrompted()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            viewModel.showMessage("Откройте шторку, нажмите карандаш и перетащите плитку KVN")
            return
        }
        val manager = getSystemService(StatusBarManager::class.java) ?: return
        manager.requestAddTileService(
            ComponentName(this, VpnTileService::class.java),
            getString(R.string.app_name),
            Icon.createWithResource(this, R.drawable.ic_stat_vpn),
            mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                viewModel.showMessage("Плитка KVN уже в шторке")
            }
        }
    }

    private fun toggleVpn() {
        val prepare = VpnService.prepare(this)
        viewModel.toggle(needsPermission = prepare != null) { prepare?.let { vpnPermission.launch(it) } }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermissions() {
        val location = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = location &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        permissions.value = Permissions(location = location, backgroundLocation = background)
    }

    private fun requestLocation() {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** Фоновый доступ Android выдаёт только после обычного, а с 11-й версии — через настройки. */
    private fun requestBackgroundLocation() {
        if (!permissions.value.location) {
            requestLocation()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_CONNECT = "connect"
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home),
    SERVERS("Серверы", Icons.Rounded.Dns),
    SCAN("Проверка", Icons.Rounded.Radar),
    SETTINGS("Настройки", Icons.Rounded.Settings),
}

/** Экраны, открывающиеся поверх вкладок. */
private enum class Overlay { NONE, APPS, WIFI, WHITELIST, DNS }

@Composable
private fun App(
    viewModel: MainViewModel,
    permissions: Permissions,
    pendingImport: ImportRequest?,
    onImportConsumed: () -> Unit,
    onToggle: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onAddTile: () -> Unit,
    onScanQr: () -> Unit,
    onPickQrImage: () -> Unit,
) {
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.vpnState.collectAsStateWithLifecycle()
    val node by viewModel.selectedNode.collectAsStateWithLifecycle()
    val pings by viewModel.pings.collectAsStateWithLifecycle()
    val pinging by viewModel.pinging.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val accountBusy by viewModel.accountBusy.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val scanTargets by viewModel.scanTargets.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val nodeTests by viewModel.nodeTests.collectAsStateWithLifecycle()
    val nodeTestProgress by viewModel.nodeTestProgress.collectAsStateWithLifecycle()
    val statsVersion by viewModel.statsVersion.collectAsStateWithLifecycle()
    val sheetError by viewModel.sheetError.collectAsStateWithLifecycle()
    val dnsChecks by viewModel.dnsChecks.collectAsStateWithLifecycle()
    val dnsChecking by viewModel.dnsChecking.collectAsStateWithLifecycle()
    val findingBest by viewModel.findingBest.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addUrl by rememberSaveable { mutableStateOf("") }
    var addName by rememberSaveable { mutableStateOf("") }
    var addEngine by rememberSaveable { mutableStateOf("") }
    var addSource by rememberSaveable { mutableStateOf("") }
    var loggingIn by rememberSaveable { mutableStateOf(false) }
    var showIntro by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(pendingImport) {
        if (pendingImport != null) {
            addUrl = pendingImport.input
            addName = pendingImport.name.orEmpty()
            addEngine = pendingImport.engine?.id.orEmpty()
            addSource = pendingImport.client
            adding = true
            onImportConsumed()
        }
    }
    // Плитку в шторку предлагаем один раз — после первого удачного подключения.
    LaunchedEffect(state is VpnState.Connected) {
        if (state is VpnState.Connected && !settings.tilePrompted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onAddTile()
        }
    }
    // Пинги при первом открытии, чтобы в списке сразу было видно живые серверы.
    LaunchedEffect(subscriptions.isNotEmpty()) {
        if (subscriptions.isNotEmpty() && pings.isEmpty()) viewModel.pingAll()
    }

    Scaffold(
        containerColor = Palette.Background,
        snackbarHost = {
            SnackbarHost(snackbar) {
                Snackbar(it, containerColor = Palette.SurfaceHighest, contentColor = Palette.TextPrimary, shape = RoundedCornerShape(14.dp))
            }
        },
        bottomBar = {
            if (overlay == Overlay.NONE) BottomBar(tab) { tab = it }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.Background)
                .statusBarsPadding()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            // Экраны поверх вкладок выезжают справа, вкладки сменяются в сторону перехода.
            AnimatedContent(
                targetState = overlay,
                transitionSpec = {
                    if (targetState != Overlay.NONE) {
                        (slideInHorizontally(tween(320)) { it } + fadeIn(tween(320))) togetherWith
                            (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(220)))
                    } else {
                        (slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(320))) togetherWith
                            (slideOutHorizontally(tween(320)) { it } + fadeOut(tween(220)))
                    }
                },
                label = "overlay",
            ) { currentOverlay ->
            when (currentOverlay) {
                Overlay.APPS -> {
                    LaunchedEffect(Unit) { viewModel.loadApps() }
                    AppsScreen(
                        settings = settings,
                        apps = apps,
                        onBack = {
                            overlay = Overlay.NONE
                            viewModel.applyRouting()
                        },
                        onMode = viewModel::setAppMode,
                        onToggle = viewModel::toggleApp,
                        onExcludeWhitelist = viewModel::excludeWhitelistApps,
                    )
                }
                Overlay.DNS -> DnsScreen(
                    current = settings.dns,
                    checks = dnsChecks,
                    checking = dnsChecking,
                    vpnConnected = state is VpnState.Connected,
                    domain = settings.checks.dnsDomain,
                    onBack = { overlay = Overlay.NONE },
                    onSelect = viewModel::setDns,
                    onCheck = viewModel::checkDnsServers,
                )
                Overlay.WHITELIST -> WhitelistScreen(
                    settings = settings,
                    onBack = {
                        overlay = Overlay.NONE
                        viewModel.applyRouting()
                    },
                    onEnabled = viewModel::setWhitelistEnabled,
                    onDirectRu = viewModel::setDirectRu,
                    onAdd = viewModel::addWhitelistDomains,
                    onRemove = viewModel::removeWhitelistDomain,
                    onReset = viewModel::resetWhitelist,
                )
                Overlay.WIFI -> WifiScreen(
                    settings = settings,
                    hasLocation = permissions.location,
                    hasBackgroundLocation = permissions.backgroundLocation,
                    currentNetwork = viewModel::currentWifiName,
                    onBack = {
                        overlay = Overlay.NONE
                        viewModel.applyRouting()
                    },
                    onMode = { mode -> viewModel.updateSettings(restart = false) { it.copy(wifiMode = mode) } },
                    onNetworks = { networks -> viewModel.updateSettings(restart = false) { it.copy(wifiNetworks = networks) } },
                    onRequestLocation = onRequestLocation,
                    onRequestBackgroundLocation = onRequestBackgroundLocation,
                )
                Overlay.NONE -> AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val direction = if (forward) 1 else -1
                        (slideInHorizontally(tween(300)) { direction * it / 5 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(300)) { -direction * it / 5 } + fadeOut(tween(200)))
                    },
                    label = "tab",
                ) { current ->
                    when (current) {
                        Tab.HOME -> HomeScreen(
                            state = state,
                            engine = settings.engine,
                            node = node,
                            ping = node?.let { pings[it.id] },
                            subscription = subscriptions.firstOrNull { it.id == node?.subscriptionId },
                            traffic = traffic,
                            auto = settings.auto.selectBest || (settings.auto.healthCheck && settings.auto.failover),
                            onToggle = onToggle,
                            onOpenSettings = { tab = Tab.SETTINGS },
                            onOpenServers = { tab = Tab.SERVERS },
                            onAddSubscription = { adding = true },
                            onHelp = { showIntro = true },
                            findingBest = findingBest,
                            onFindBest = viewModel::findBest,
                        )
                        Tab.SERVERS -> ServersScreen(
                            subscriptions = subscriptions,
                            selectedId = node?.id,
                            engine = settings.engine,
                            pings = pings,
                            pinging = pinging,
                            refreshing = refreshing,
                            onSelect = viewModel::select,
                            onPingAll = viewModel::pingAll,
                            onRefreshAll = viewModel::refreshAll,
                            onRefresh = viewModel::refresh,
                            onRename = viewModel::rename,
                            onDelete = viewModel::delete,
                            onSetEngine = viewModel::setSubscriptionEngine,
                            onAdd = { adding = true },
                            nodeTests = nodeTests,
                            statsVersion = statsVersion,
                            pingRecord = viewModel::pingRecord,
                            sort = settings.serverSort,
                            onSort = viewModel::setServerSort,
                            onToggleEnabled = viewModel::setSubscriptionEnabled,
                            onToggleCollapsed = viewModel::toggleCollapsed,
                            shareLink = viewModel::shareLink,
                            view = settings.serverView,
                            onView = viewModel::setServerView,
                            subLabLabel = settings.account.takeIf { it.server.isNotEmpty() }?.let { account ->
                                listOf(account.name.ifEmpty { account.username }, account.server.removePrefix("https://"))
                                    .filter { it.isNotEmpty() }.joinToString(" · ")
                            },
                            subLabTags = settings.subLabTags,
                        )
                        Tab.SCAN -> ScanScreen(
                            presets = viewModel.scanPresets,
                            targets = scanTargets,
                            results = scanResults,
                            progress = scanProgress,
                            vpnConnected = state is VpnState.Connected,
                            onAdd = viewModel::addScanTargets,
                            onRemove = viewModel::removeScanTarget,
                            onClear = viewModel::clearScanTargets,
                            onRun = { viewModel.runScan() },
                            onRunOne = { viewModel.runScan(listOf(it)) },
                            onStop = viewModel::stopScan,
                            nodes = remember(subscriptions, settings.engine) { subscriptions.filter { it.enabled }.flatMap { it.visibleNodes(settings.engine) } },
                            nodeTests = nodeTests,
                            nodeTestProgress = nodeTestProgress,
                            onTestNodes = viewModel::testAllNodes,
                            onStopNodeTests = viewModel::stopNodeTests,
                            stats = remember(statsVersion, subscriptions, settings.engine) { viewModel.statsReport() },
                            onResetStats = viewModel::resetStats,
                            onAddToWhitelist = viewModel::addToWhitelist,
                        )
                        Tab.SETTINGS -> SettingsScreen(
                            settings = settings,
                            versions = viewModel.versions,
                            accountBusy = accountBusy,
                            onEngine = viewModel::setEngine,
                            onUpdate = { restart, transform -> viewModel.updateSettings(restart, transform) },
                            onLogin = { loggingIn = true },
                            onSync = viewModel::subLabSync,
                            onLogout = viewModel::subLabLogout,
                            onOpenApps = { overlay = Overlay.APPS },
                            onOpenWifi = { overlay = Overlay.WIFI },
                            onOpenWhitelist = { overlay = Overlay.WHITELIST },
                            onShowIntro = { showIntro = true },
                            onOpenDns = { overlay = Overlay.DNS },
                            onChecks = viewModel::updateChecks,
                            onSubLabTags = viewModel::setSubLabTags,
                            onAddTile = onAddTile,
                            loadLogs = viewModel::logs,
                            loadConfig = viewModel::configPreview,
                        )
                    }
                }
            }
            }
        }
    }

    // Вводная инструкция: сама при первом запуске, заново — кнопкой «?» на главной.
    AnimatedVisibility(
        visible = !settings.onboarded || showIntro,
        enter = fadeIn(tween(300)) + slideInVertically(tween(380)) { it / 8 },
        exit = fadeOut(tween(260)) + slideOutVertically(tween(320)) { it / 8 },
    ) {
        OnboardingScreen(
            subscriptionCount = subscriptions.size,
            initialAuto = if (settings.onboarded) settings.auto.selectBest else true,
            initialWhitelist = settings.whitelistEnabled,
            onFinish = { auto, whitelist, next ->
                viewModel.finishOnboarding(auto, whitelist)
                showIntro = false
                tab = Tab.HOME
                when (next) {
                    OnboardingNext.ADD_SUBSCRIPTION -> adding = true
                    OnboardingNext.LOGIN -> loggingIn = true
                    OnboardingNext.NONE -> Unit
                }
            },
        )
    }

    if (adding) {
        val reset = {
            viewModel.clearSheetError()
            adding = false
            addUrl = ""
            addName = ""
            addEngine = ""
            addSource = ""
        }
        AddSubscriptionSheet(
            error = sheetError,
            initialUrl = addUrl,
            initialName = addName,
            initialEngine = addEngine.ifEmpty { null }?.let { Engine.of(it) },
            source = addSource.ifEmpty { null },
            busy = refreshing,
            onDismiss = reset,
            onSubmit = { input, name, engine ->
                viewModel.addSubscription(input, name, engine) {
                    reset()
                    tab = Tab.SERVERS
                }
            },
            onScanQr = onScanQr,
            onPickQrImage = onPickQrImage,
        )
    }

    if (loggingIn) {
        LoginSheet(
            initialServer = remember { viewModel.suggestedSubLabServer() },
            initialLogin = settings.account.username,
            busy = accountBusy,
            error = sheetError,
            onDismiss = {
                viewModel.clearSheetError()
                loggingIn = false
            },
            onSubmit = { server, login, password ->
                viewModel.subLabLogin(server, login, password) { loggingIn = false }
            },
        )
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Background)
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.Stroke, RoundedCornerShape(22.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val weight by animateFloatAsState(if (active) 1.6f else 1f, uiSpring(), label = "tabWeight")
                val pill by animateColorAsState(if (active) Palette.SurfaceHighest else Color.Transparent, tween(250), label = "tabPill")
                val tint by animateColorAsState(if (active) Palette.VioletSoft else Palette.TextMuted, tween(250), label = "tabTint")
                Row(
                    Modifier
                        .weight(weight)
                        .clip(RoundedCornerShape(16.dp))
                        .background(pill)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.title,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    AnimatedVisibility(
                        visible = active,
                        enter = fadeIn(tween(220)) + expandHorizontally(tween(260)),
                        exit = fadeOut(tween(120)) + shrinkHorizontally(tween(200)),
                    ) {
                        Row {
                            Spacer(Modifier.size(8.dp))
                            Text(tab.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
