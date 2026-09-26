package io.kvn.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.core.Engine
import io.kvn.client.data.AppMode
import io.kvn.client.data.AppSettings
import io.kvn.client.data.AutoOptions
import io.kvn.client.data.BypassOptions
import io.kvn.client.data.CheckOptions
import io.kvn.client.data.PingMethod
import io.kvn.client.data.Mimicry
import io.kvn.client.data.WifiMode
import io.kvn.client.ui.components.EngineSwitch
import io.kvn.client.ui.components.Panel
import io.kvn.client.ui.components.SectionTitle
import io.kvn.client.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    settings: AppSettings,
    versions: String,
    accountBusy: Boolean,
    onEngine: (Engine) -> Unit,
    onUpdate: (restart: Boolean, transform: (AppSettings) -> AppSettings) -> Unit,
    onLogin: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenWhitelist: () -> Unit = {},
    onShowIntro: () -> Unit = {},
    onOpenDns: () -> Unit = {},
    onChecks: ((CheckOptions) -> CheckOptions) -> Unit = {},
    onAddTile: (() -> Unit)?,
    loadLogs: suspend () -> String,
    loadConfig: suspend () -> String,
) {
    var editing by remember { mutableStateOf<EditField?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var textDialog by remember { mutableStateOf<TextDialog?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "Настройки",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        )

        SectionTitle("Авто-режим")
        AutoPanel(settings.auto) { transform -> onUpdate(true) { it.copy(auto = transform(it.auto)) } }

        SectionTitle("Пинг и проверки")
        ChecksPanel(settings.checks, onChecks)

        SectionTitle("Аккаунт sub-lab")
        AccountPanel(settings, accountBusy, onLogin, onSync, onLogout)

        SectionTitle("Маршрутизация")
        Panel(Modifier.fillMaxWidth()) {
            ValueRow(Icons.Rounded.Apps, "Приложения через VPN", appsSummary(settings), onOpenApps)
            Divider()
            ValueRow(Icons.Rounded.Wifi, "Пауза в Wi-Fi", wifiSummary(settings), onOpenWifi)
            Divider()
            ToggleRow(Icons.Rounded.Lan, "Локальная сеть напрямую", "Роутер, принтер, NAS — мимо VPN", settings.bypassLan) { value ->
                onUpdate(true) { it.copy(bypassLan = value) }
            }
            Divider()
            ValueRow(Icons.Rounded.Flag, "Сайты напрямую", whitelistSummary(settings), onOpenWhitelist)
        }

        SectionTitle("Быстрый доступ")
        Panel(Modifier.fillMaxWidth()) {
            ValueRow(Icons.Rounded.School, "Как пользоваться", "Пройти вводную инструкцию заново", onShowIntro)
            Divider()
            ToggleRow(
                Icons.Rounded.ContentPaste,
                "Ссылки из буфера обмена",
                "Скопированная happ://, clash://, vless://… сразу открывает окно добавления",
                settings.clipboardImport,
            ) { value -> onUpdate(false) { it.copy(clipboardImport = value) } }
            if (onAddTile != null) {
                Divider()
                ValueRow(Icons.Rounded.ToggleOn, "Плитка в шторке", "Включать и выключать VPN из панели быстрых настроек", onAddTile)
            }
        }

        // Всё техническое спрятано: обычному пользователю хватает того, что выше.
        Spacer(Modifier.height(16.dp))
        Panel(Modifier.fillMaxWidth()) {
            ToggleRow(
                Icons.Rounded.Tune,
                "Настройки для опытных",
                "Ядро, IPv6, обход блокировок, DNS, журнал",
                settings.advanced,
            ) { value -> onUpdate(false) { it.copy(advanced = value) } }
        }

        AnimatedVisibility(
            visible = settings.advanced,
            enter = fadeIn(tween(250)) + expandVertically(tween(320)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(260)),
        ) {
            Column {
            SectionTitle("Ядро")
            Panel(Modifier.fillMaxWidth()) {
                EngineSwitch(selected = settings.engine, onSelect = onEngine, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(
                    when (settings.engine) {
                        Engine.XRAY -> "Xray-core: VLESS (Reality, XHTTP, Vision), VMess, Trojan, Shadowsocks, Hysteria2."
                        Engine.MIHOMO -> "Mihomo (Clash Meta): всё то же плюс TUIC, Hysteria, WireGuard, AnyTLS и Shadowsocks-плагины."
                    },
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Подписка запрашивается как ${Mimicry.clientName(settings.engine)}: при смене ядра она скачивается заново со своими заголовками.",
                    color = Palette.TextMuted,
                    fontSize = 12.sp,
                )
                if (versions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null, tint = Palette.TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(versions, color = Palette.TextMuted, fontSize = 12.sp)
                    }
                }
            }

            SectionTitle("IPv6")
            Panel(Modifier.fillMaxWidth()) {
                ToggleRow(Icons.Rounded.Language, "IPv6", "Пускать IPv6-трафик через туннель", settings.ipv6) { value ->
                    onUpdate(true) { it.copy(ipv6 = value) }
                }
            }

            SectionTitle("Обход блокировок (Xray)")
            BypassPanel(settings.bypass, settings.engine) { transform -> onUpdate(true) { it.copy(bypass = transform(it.bypass)) } }

            SectionTitle("Сеть")
            Panel(Modifier.fillMaxWidth()) {
                ValueRow(Icons.Rounded.Dns, "DNS", settings.dns, onOpenDns)
                Divider()
                ValueRow(Icons.Rounded.Person, "User-Agent для Xray", Mimicry.userAgent(Engine.XRAY, settings)) { editing = EditField.UA_XRAY }
                Divider()
                ValueRow(Icons.Rounded.Person, "User-Agent для Mihomo", Mimicry.userAgent(Engine.MIHOMO, settings)) { editing = EditField.UA_MIHOMO }
                Divider()
                // По x-hwid панели считают устройства: можно подставить тот же, что у Happ.
                ValueRow(
                    Icons.Rounded.Fingerprint,
                    "x-hwid",
                    Mimicry.hwid(context, settings) + if (settings.customHwid.isBlank()) " (ANDROID_ID)" else " (свой)",
                ) { editing = EditField.HWID }
                Divider()
                ValueRow(Icons.Rounded.Tune, "Уровень журнала", settings.logLevel) { editing = EditField.LOG_LEVEL }
            }

            SectionTitle("Диагностика")
            Panel(Modifier.fillMaxWidth()) {
                ValueRow(Icons.AutoMirrored.Rounded.Notes, "Журнал ядра", "") { textDialog = TextDialog("Журнал", loadLogs) }
                Divider()
                ValueRow(Icons.Rounded.Code, "Конфиг текущего сервера", "") { textDialog = TextDialog("Конфиг ${settings.engine.title}", loadConfig) }
            }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "KVN · Xray и Mihomo в одном приложении",
            color = Palette.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { field ->
        EditDialog(field, settings, onDismiss = { editing = null }) { value ->
            editing = null
            when (field) {
                EditField.DNS -> onUpdate(true) { it.copy(dns = value) }
                EditField.UA_XRAY -> onUpdate(false) { it.copy(userAgentXray = value) }
                EditField.UA_MIHOMO -> onUpdate(false) { it.copy(userAgentMihomo = value) }
                EditField.HWID -> onUpdate(false) { it.copy(customHwid = value) }
                EditField.LOG_LEVEL -> onUpdate(true) { it.copy(logLevel = value) }
            }
        }
    }

    textDialog?.let { dialog ->
        TextViewer(dialog) { textDialog = null }
    }
}

private fun appsSummary(settings: AppSettings): String = when (settings.appMode) {
    AppMode.ALL -> AppMode.ALL.title
    AppMode.ONLY -> "Только выбранные: ${settings.apps.size}"
    AppMode.EXCEPT -> "Кроме выбранных: ${settings.apps.size}"
}

private fun whitelistSummary(settings: AppSettings): String = buildList {
    if (settings.whitelistEnabled) add("Белый список: ${settings.whitelistDomains.size}") else add("Белый список выключен")
    if (settings.directRu) add("вся зона .ru")
}.joinToString(" · ")

private fun wifiSummary(settings: AppSettings): String = when (settings.wifiMode) {
    WifiMode.OFF -> WifiMode.OFF.title
    WifiMode.ANY -> "В любой сети Wi-Fi"
    WifiMode.LIST -> if (settings.wifiNetworks.isEmpty()) "Сети не выбраны" else settings.wifiNetworks.sorted().joinToString(", ")
}

@Composable
private fun AccountPanel(
    settings: AppSettings,
    busy: Boolean,
    onLogin: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
) {
    val account = settings.account
    Panel(Modifier.fillMaxWidth()) {
        if (!account.loggedIn) {
            ValueRow(
                Icons.Rounded.AccountCircle,
                "Войти в sub-lab",
                if (account.server.isNotEmpty()) "Сессия закончилась — войдите заново" else "Подписки из вашего аккаунта появятся сами",
                onLogin,
            )
            return@Panel
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            RowIcon(Icons.Rounded.AccountCircle)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.name.ifEmpty { account.username }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(account.server.removePrefix("https://"), color = Palette.TextSecondary, fontSize = 12.sp, maxLines = 1)
                if (account.syncedAt > 0) {
                    Text(
                        "Синхронизировано " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(account.syncedAt)),
                        color = Palette.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.VioletSoft)
        }
        Divider()
        ValueRow(Icons.Rounded.Sync, "Обновить список подписок", "", onSync)
        Divider()
        ValueRow(Icons.AutoMirrored.Rounded.Logout, "Выйти", "Подписки из sub-lab уберутся из приложения", onLogout)
    }
}

private enum class EditField(val title: String, val hint: String) {
    DNS("DNS-сервер", "1.1.1.1, 8.8.8.8 или https://1.1.1.1/dns-query"),
    UA_XRAY("User-Agent для Xray", "Пусто — как Happ (${Mimicry.HAPP_USER_AGENT})"),
    UA_MIHOMO("User-Agent для Mihomo", "Пусто — как FlClashX (${Mimicry.FLCLASHX_USER_AGENT})"),
    HWID("x-hwid", "Идентификатор устройства для панели подписки. Пусто — ANDROID_ID этого телефона. Изменение применится при следующем обновлении подписок."),
    LOG_LEVEL("Уровень журнала", "debug, info, warning или error"),
}

private class TextDialog(val title: String, val load: suspend () -> String)

@Composable
internal fun Divider() = HorizontalDivider(color = Palette.Stroke, modifier = Modifier.padding(vertical = 4.dp))

@Composable
internal fun RowIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.SurfaceHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Palette.VioletSoft, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Palette.TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.TextPrimary,
                checkedTrackColor = Palette.Violet,
                uncheckedThumbColor = Palette.TextSecondary,
                uncheckedTrackColor = Palette.SurfaceHighest,
                uncheckedBorderColor = Palette.Stroke,
            ),
        )
    }
}

@Composable
internal fun ValueRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (value.isNotEmpty()) Text(value, color = Palette.TextSecondary, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextMuted)
    }
}

@Composable
private fun EditDialog(field: EditField, settings: AppSettings, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val initial = when (field) {
        EditField.DNS -> settings.dns
        EditField.UA_XRAY -> settings.userAgentXray
        EditField.UA_MIHOMO -> settings.userAgentMihomo
        EditField.HWID -> settings.customHwid
        EditField.LOG_LEVEL -> settings.logLevel
    }
    val allowEmpty = field == EditField.UA_XRAY || field == EditField.UA_MIHOMO || field == EditField.HWID
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(field.title) },
        text = {
            Column {
                Text(field.hint, color = Palette.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                if (field == EditField.LOG_LEVEL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("debug", "info", "warning", "error").forEach { level ->
                            TextButton(onClick = { value = level }) {
                                Text(level, color = if (value == level) Palette.VioletSoft else Palette.TextSecondary)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
                }
                if (allowEmpty) {
                    TextButton(onClick = { value = "" }) { Text("По умолчанию") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (allowEmpty || value.isNotBlank()) onSave(value.trim()) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun TextViewer(dialog: TextDialog, onDismiss: () -> Unit) {
    var text by remember(dialog) { mutableStateOf("Загрузка…") }
    LaunchedEffect(dialog) { text = dialog.load() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(dialog.title) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Background)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.TextSecondary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** Параметр обхода блокировок, который правится в диалоге. */
private class BypassParam(
    val title: String,
    val hint: String,
    val value: String,
    val apply: (BypassOptions, String) -> BypassOptions,
)

/**
 * Опции как в Happ: фрагментация ClientHello, шум перед UDP и mux. В mihomo
 * аналогов нет, поэтому при нём настройки просто хранятся до переключения.
 */
@Composable
private fun BypassPanel(bypass: BypassOptions, engine: Engine, onChange: ((BypassOptions) -> BypassOptions) -> Unit) {
    var editing by remember { mutableStateOf<BypassParam?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        if (engine == Engine.MIHOMO) {
            Text("Сейчас выбрано ядро Mihomo: эти опции включатся при переключении на Xray.", color = Palette.Amber, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }
        ToggleRow(Icons.Rounded.ContentCut, "Фрагментация", "Режет TLS ClientHello на куски: ТСПУ не видит SNI", bypass.fragment) { value ->
            onChange { it.copy(fragment = value) }
        }
        if (bypass.fragment) {
            ParamRow("Пакеты", bypass.fragmentPackets) {
                editing = BypassParam("Какие пакеты резать", "tlshello — только ClientHello, или диапазон пакетов: 1-3", bypass.fragmentPackets) { b, v -> b.copy(fragmentPackets = v) }
            }
            ParamRow("Длина кусков, байт", bypass.fragmentLength) {
                editing = BypassParam("Длина кусков", "Диапазон в байтах, например 100-200 или 10-20", bypass.fragmentLength) { b, v -> b.copy(fragmentLength = v) }
            }
            ParamRow("Интервал, мс", bypass.fragmentInterval) {
                editing = BypassParam("Интервал между кусками", "Диапазон в миллисекундах, например 10-20", bypass.fragmentInterval) { b, v -> b.copy(fragmentInterval = v) }
            }
        }
        Divider()
        ToggleRow(Icons.Rounded.GraphicEq, "Шум (Noise)", "Случайные пакеты перед UDP/QUIC", bypass.noise) { value ->
            onChange { it.copy(noise = value) }
        }
        if (bypass.noise) {
            ParamRow("Тип", bypass.noiseType) {
                editing = BypassParam("Тип шума", "rand — случайные байты, str — строка, base64 — байты в base64", bypass.noiseType) { b, v -> b.copy(noiseType = v) }
            }
            ParamRow("Пакет", bypass.noisePacket) {
                editing = BypassParam("Пакет шума", "Для rand — длина, например 10-20; для str/base64 — содержимое", bypass.noisePacket) { b, v -> b.copy(noisePacket = v) }
            }
            ParamRow("Задержка, мс", bypass.noiseDelay) {
                editing = BypassParam("Задержка после шума", "Диапазон в миллисекундах, например 10-16", bypass.noiseDelay) { b, v -> b.copy(noiseDelay = v) }
            }
        }
        Divider()
        ToggleRow(Icons.Rounded.Layers, "Mux", "Несколько соединений в одном (не для XTLS Vision)", bypass.mux) { value ->
            onChange { it.copy(mux = value) }
        }
        if (bypass.mux) {
            ParamRow("Потоков в соединении", bypass.muxConcurrency.toString()) {
                editing = BypassParam("Mux: потоков в соединении", "От 1 до 128, обычно 8", bypass.muxConcurrency.toString()) { b, v ->
                    b.copy(muxConcurrency = v.toIntOrNull()?.coerceIn(1, 128) ?: b.muxConcurrency)
                }
            }
        }
    }
    editing?.let { param ->
        var value by remember(param) { mutableStateOf(param.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Palette.SurfaceHigh,
            title = { Text(param.title) },
            text = {
                Column {
                    Text(param.hint, color = Palette.TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    if (value.isNotBlank()) onChange { param.apply(it, value.trim()) }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ParamRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 46.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Palette.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.VioletSoft, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Авто-режим: лучший сервер при подключении, проверка соединения каждые N
 * минут и переключение на другой сервер, если текущий перестал отвечать.
 */
@Composable
private fun AutoPanel(auto: AutoOptions, onChange: ((AutoOptions) -> AutoOptions) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        ToggleRow(
            Icons.Rounded.AutoMode,
            "Лучший сервер при подключении",
            "По истории пингов, с проверкой, что через сервер открываются сайты",
            auto.selectBest,
        ) { value -> onChange { it.copy(selectBest = value) } }
        if (auto.selectBest) {
            ParamRow("Где искать", if (auto.allSubscriptions) "во всех подписках" else "в подписке сервера") {
                onChange { it.copy(allSubscriptions = !it.allSubscriptions) }
            }
        }
        Divider()
        ToggleRow(
            Icons.Rounded.HealthAndSafety,
            "Проверять соединение",
            "Периодически открывать страницу через VPN",
            auto.healthCheck,
        ) { value -> onChange { it.copy(healthCheck = value) } }
        if (auto.healthCheck) {
            ParamRow("Интервал", "каждые ${auto.intervalMinutes} мин") { picking = "interval" }
            ParamRow("Нерабочий после", "${auto.failures} ${io.kvn.client.ui.components.plural(auto.failures.toLong(), "неудачи", "неудач", "неудач")} подряд") { picking = "failures" }
            Divider()
            ToggleRow(
                Icons.Rounded.SwapHoriz,
                "Переключаться на другой сервер",
                "Если текущий перестал отвечать — найти рабочий и подключиться",
                auto.failover,
            ) { value -> onChange { it.copy(failover = value) } }
        }
    }
    picking?.let { kind ->
        val options = if (kind == "interval") listOf(1, 2, 5, 10, 15, 30, 60) else listOf(1, 2, 3, 5, 10)
        val current = if (kind == "interval") auto.intervalMinutes else auto.failures
        AlertDialog(
            onDismissRequest = { picking = null },
            containerColor = Palette.SurfaceHigh,
            title = { Text(if (kind == "interval") "Проверять каждые" else "Нерабочий после") },
            text = {
                Column {
                    if (kind == "failures") {
                        Text(
                            "После первой неудачи повторные проверки идут каждые 20 секунд.",
                            color = Palette.TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    options.forEach { value ->
                        TextButton(onClick = {
                            picking = null
                            onChange { if (kind == "interval") it.copy(intervalMinutes = value) else it.copy(failures = value) }
                        }) {
                            Text(
                                if (kind == "interval") "$value мин" else "$value ${io.kvn.client.ui.components.plural(value.toLong(), "неудачи", "неудач", "неудач")} подряд",
                                color = if (value == current) Palette.VioletSoft else Palette.TextPrimary,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Закрыть") } },
        )
    }
}

/** Что правится в диалоге панели проверок. */
private enum class CheckField { PING_METHOD, PING_TIMEOUT, URLS, TEST_TIMEOUT, DELAY, RETRY, DNS_DOMAIN }

/**
 * Пинг и проверки: способ пинга (как в Happ), адреса и метод проверки,
 * таймауты, проверка после подключения, повтор и проверка DNS через VPN.
 */
@Composable
private fun ChecksPanel(checks: CheckOptions, onChange: ((CheckOptions) -> CheckOptions) -> Unit) {
    var editing by remember { mutableStateOf<CheckField?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        ValueRow(Icons.Rounded.NetworkCheck, "Способ пинга", checks.pingMethod.title) { editing = CheckField.PING_METHOD }
        ParamRow("Таймаут пинга", "${checks.pingTimeoutMs / 1000.0} с") { editing = CheckField.PING_TIMEOUT }
        Divider()
        ValueRow(Icons.Rounded.Link, "Адреса проверки", checks.testUrls.joinToString(", ") { it.substringAfter("://").substringBefore('/') }) {
            editing = CheckField.URLS
        }
        ParamRow("Метод запроса", checks.testMethod) {
            onChange { it.copy(testMethod = if (it.testMethod == "GET") "HEAD" else "GET") }
        }
        ParamRow("Таймаут проверки", "${checks.testTimeoutMs / 1000} с") { editing = CheckField.TEST_TIMEOUT }
        Divider()
        ToggleRow(
            Icons.Rounded.Bolt,
            "Проверка после подключения",
            "Убедиться, что сайты открываются, сразу после включения VPN",
            checks.afterConnect,
        ) { value -> onChange { it.copy(afterConnect = value) } }
        if (checks.afterConnect) {
            ParamRow("Через", "${checks.afterConnectDelaySec} с") { editing = CheckField.DELAY }
        }
        ParamRow("Повтор после неудачи", "через ${checks.retrySeconds} с") { editing = CheckField.RETRY }
        Divider()
        ToggleRow(
            Icons.Rounded.Dns,
            "Проверять DNS",
            "При проверке соединения спрашивать выбранный DNS через VPN",
            checks.dnsCheck,
        ) { value -> onChange { it.copy(dnsCheck = value) } }
        if (checks.dnsCheck) {
            ParamRow("Домен для проверки", checks.dnsDomain) { editing = CheckField.DNS_DOMAIN }
        }
    }

    when (editing) {
        CheckField.PING_METHOD -> AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Palette.SurfaceHigh,
            title = { Text("Способ пинга") },
            text = {
                Column {
                    PingMethod.entries.forEach { method ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    editing = null
                                    onChange { it.copy(pingMethod = method) }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                method.title,
                                fontWeight = FontWeight.SemiBold,
                                color = if (method == checks.pingMethod) Palette.VioletSoft else Palette.TextPrimary,
                            )
                            Text(method.description, color = Palette.TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Закрыть") } },
        )
        CheckField.PING_TIMEOUT -> ChoiceDialog("Таймаут пинга", listOf(1000, 2000, 3000, 5000, 8000), checks.pingTimeoutMs, { "${it / 1000.0} с" }, { editing = null }) { v ->
            onChange { it.copy(pingTimeoutMs = v) }
        }
        CheckField.TEST_TIMEOUT -> ChoiceDialog("Таймаут проверки", listOf(3000, 5000, 8000, 10_000, 15_000, 20_000), checks.testTimeoutMs, { "${it / 1000} с" }, { editing = null }) { v ->
            onChange { it.copy(testTimeoutMs = v) }
        }
        CheckField.DELAY -> ChoiceDialog("Проверить через", listOf(2, 5, 10, 20, 30), checks.afterConnectDelaySec, { "$it с" }, { editing = null }) { v ->
            onChange { it.copy(afterConnectDelaySec = v) }
        }
        CheckField.RETRY -> ChoiceDialog("Повтор после неудачи", listOf(5, 10, 20, 30, 60), checks.retrySeconds, { "через $it с" }, { editing = null }) { v ->
            onChange { it.copy(retrySeconds = v) }
        }
        CheckField.URLS -> TextEditDialog(
            title = "Адреса проверки",
            hint = "По одному на строку. Соединение рабочее, если открылся хоть один. Лучше адреса с ответом 204: они почти без трафика.",
            initial = checks.testUrls.joinToString("\n"),
            singleLine = false,
            onDismiss = { editing = null },
            onReset = { onChange { it.copy(testUrls = CheckOptions.DEFAULT_TEST_URLS) } },
        ) { text ->
            val urls = text.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
            if (urls.isNotEmpty()) onChange { it.copy(testUrls = urls) }
        }
        CheckField.DNS_DOMAIN -> TextEditDialog(
            title = "Домен для проверки DNS",
            hint = "Адрес, который спрашиваем у DNS, например google.com",
            initial = checks.dnsDomain,
            singleLine = true,
            onDismiss = { editing = null },
            onReset = { onChange { it.copy(dnsDomain = CheckOptions().dnsDomain) } },
        ) { text -> if (text.isNotBlank()) onChange { it.copy(dnsDomain = text.trim()) } }
        null -> Unit
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, current: T, label: (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    TextButton(onClick = {
                        onDismiss()
                        onPick(option)
                    }) {
                        Text(label(option), color = if (option == current) Palette.VioletSoft else Palette.TextPrimary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun TextEditDialog(
    title: String,
    hint: String,
    initial: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, color = Palette.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = singleLine, maxLines = if (singleLine) 1 else 6)
                TextButton(onClick = {
                    onDismiss()
                    onReset()
                }) { Text("По умолчанию") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onSave(value)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
