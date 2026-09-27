package io.kvn.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import io.kvn.client.data.ServerStat
import io.kvn.client.data.StatsReport
import io.kvn.client.data.SubscriptionStat
import io.kvn.client.ui.components.FlagBadge
import io.kvn.client.ui.components.plural
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.data.ScanPath
import io.kvn.client.data.ScanPreset
import io.kvn.client.data.NodeTest
import io.kvn.client.data.ScanResult
import io.kvn.client.data.ServerNode
import io.kvn.client.ui.ScanProgress
import io.kvn.client.ui.theme.Palette

/** Вкладки экрана проверок. */
private enum class CheckTab(val title: String) { SITES("Сайты"), SERVERS("Серверы"), STATS("Статистика") }

/** Пояснение к вкладке: что проверяется и на что влияет (показывается по «?»). */
@Composable
private fun TabHelp(tab: CheckTab, onDismiss: () -> Unit) {
    val (icon, title, what, effect) = when (tab) {
        CheckTab.SITES -> HelpText(
            Icons.Rounded.Language,
            "Открываются ли сайты",
            "Каждый сайт — двумя путями: напрямую через оператора и через VPN. По шагам: адрес (DNS) → соединение → TLS → ответ сайта → загрузка.",
            "Сама проверка ничего не меняет. Она показывает, что режет оператор и помогает ли VPN. Сайт, который открывается напрямую, можно в подробностях добавить в «Сайты напрямую».",
        )
        CheckTab.SERVERS -> HelpText(
            Icons.Rounded.Dns,
            "Работают ли серверы",
            "Каждый сервер по очереди открывает адрес проверки через себя — в отдельном экземпляре ядра, подключённый VPN не трогается. Видно и те, что пингуются, но ничего не открывают.",
            "Результаты идут в статистику: авто-режим, «Найти лучший» и порядок пинга выбирают надёжные серверы первыми, а «молчащие» опускаются вниз.",
        )
        CheckTab.STATS -> HelpText(
            Icons.Rounded.Insights,
            "Как копится статистика",
            "Каждый пинг, проверка серверов и выбор в авто-режиме записываются — отдельно для Wi-Fi и мобильной сети. Надёжность — доля успешных проверок, свежие весят больше старых.",
            "По ней работают авто-режим и «Найти лучший», с неё начинается пинг, по ней группировка и порядок «По доступности».",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        icon = { Icon(icon, null, tint = Palette.VioletSoft) },
        title = { Text(title) },
        text = {
            Column {
                InfoLine("Что проверяется", what)
                Spacer(Modifier.height(10.dp))
                InfoLine("На что влияет", effect)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}

private data class HelpText(val icon: ImageVector, val title: String, val what: String, val effect: String)

/**
 * Экран «Проверка»: три вкладки. «Сайты» — открываются ли ресурсы напрямую и
 * через VPN (как в HomeNet), «Серверы» — пропускает ли трафик каждый сервер,
 * «Статистика» — что копится из всех пингов и проверок. В начале каждой
 * вкладки сказано, что проверяется и на что это влияет.
 */
@Composable
fun ScanScreen(
    presets: List<ScanPreset>,
    targets: List<String>,
    results: Map<String, ScanResult>,
    progress: ScanProgress,
    vpnConnected: Boolean,
    onAdd: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onRun: () -> Unit,
    onRunOne: (String) -> Unit,
    onStop: () -> Unit,
    nodes: List<ServerNode> = emptyList(),
    nodeTests: Map<String, NodeTest> = emptyMap(),
    nodeTestProgress: ScanProgress = ScanProgress(),
    onTestNodes: () -> Unit = {},
    onStopNodeTests: () -> Unit = {},
    stats: StatsReport? = null,
    onResetStats: () -> Unit = {},
    onAddToWhitelist: (String) -> Unit = {},
    networkLabel: String = "",
) {
    var tab by rememberSaveable { mutableStateOf(CheckTab.SITES) }
    var help by remember { mutableStateOf(false) }
    if (help) TabHelp(tab) { help = false }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Проверка", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // Что проверяет открытая вкладка — во всплывающем окне.
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Palette.Surface)
                    .clickable { help = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("?", color = Palette.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        TabSwitch(tab) { tab = it }
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(280)) { direction * it / 6 } + fadeIn(tween(280))) togetherWith
                    (slideOutHorizontally(tween(280)) { -direction * it / 6 } + fadeOut(tween(180)))
            },
            label = "checkTab",
        ) { current ->
            when (current) {
                CheckTab.SITES -> SitesCheck(presets, targets, results, progress, vpnConnected, onAdd, onRemove, onClear, onRun, onRunOne, onStop, onAddToWhitelist)
                CheckTab.SERVERS -> ServersCheck(nodes, nodeTests, nodeTestProgress, onTestNodes, onStopNodeTests)
                CheckTab.STATS -> StatsView(stats, networkLabel, onResetStats)
            }
        }
    }
}

/** Переключатель вкладок: «пилюля» плавно переезжает под выбранную. */
@Composable
private fun TabSwitch(selected: CheckTab, onSelect: (CheckTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, RoundedCornerShape(16.dp))
            .padding(4.dp),
    ) {
        CheckTab.entries.forEach { tab ->
            val active = tab == selected
            val background by animateColorAsState(if (active) Palette.Violet else Color.Transparent, tween(250), label = "tabBg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.title,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) Palette.TextPrimary else Palette.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, text: String) {
    Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextMuted, letterSpacing = 0.6.sp)
    Text(text, fontSize = 13.sp, color = Palette.TextSecondary, lineHeight = 18.sp)
}

/** Плашка-счётчик: число и подпись, цвет — статус. */
@Composable
private fun CountTile(value: String, label: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 11.sp, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
    }
}

@Composable
private fun RunButton(running: Boolean, enabled: Boolean, title: String, onRun: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = if (running) onStop else onRun,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (running) Palette.SurfaceHighest else Palette.Violet),
    ) {
        Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (running) "Остановить" else title)
    }
}

@Composable
private fun ProgressLine(progress: ScanProgress) {
    if (!progress.running) return
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = Palette.Cyan,
        trackColor = Palette.SurfaceHighest,
    )
    Text(
        "${progress.done} из ${progress.total} · ${progress.current}",
        color = Palette.TextMuted,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SitesCheck(
    presets: List<ScanPreset>,
    targets: List<String>,
    results: Map<String, ScanResult>,
    progress: ScanProgress,
    vpnConnected: Boolean,
    onAdd: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onRun: () -> Unit,
    onRunOne: (String) -> Unit,
    onStop: () -> Unit,
    onAddToWhitelist: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "info") {
            if (!vpnConnected) {
                Text(
                    "VPN выключен — проверяется только прямой путь. Подключитесь, чтобы сравнить с VPN.",
                    color = Palette.Amber,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item(key = "controls") {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { preset ->
                        AssistChip(
                            onClick = { onAdd(preset.targets) },
                            label = { Text("+ ${preset.name}", fontSize = 12.sp) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Palette.Surface, labelColor = Palette.TextSecondary),
                            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Palette.Stroke),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("сайт.ru или https://host/путь") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Palette.Violet,
                            unfocusedBorderColor = Palette.Stroke,
                            focusedContainerColor = Palette.Surface,
                            unfocusedContainerColor = Palette.Surface,
                        ),
                    )
                    TextButton(
                        onClick = {
                            onAdd(input.split(' ', ',', '\n'))
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) { Text("Добавить") }
                }
                Spacer(Modifier.height(8.dp))
                RunButton(progress.running, targets.isNotEmpty(), "Проверить сайты (${targets.size})", onRun, onStop)
                ProgressLine(progress)
                if (results.isNotEmpty() && !progress.running) {
                    Spacer(Modifier.height(10.dp))
                    val values = results.values
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountTile("${values.count { it.verdict == "open" }}", "открыты", Palette.Green, Modifier.weight(1f))
                        CountTile("${values.count { it.verdict == "bypassed" }}", "через VPN", Palette.Cyan, Modifier.weight(1f))
                        CountTile("${values.count { it.verdict in setOf("blocked", "down", "error", "proxy-broken") }}", "не открыты", Palette.Red, Modifier.weight(1f))
                    }
                }
                if (targets.isNotEmpty() && !progress.running) {
                    TextButton(onClick = onClear) { Text("Очистить список", color = Palette.TextMuted) }
                }
            }
        }

        val ordered = targets.sortedByDescending { verdictRank(results[it]?.verdict) }
        items(ordered, key = { it }) { target ->
            Box(Modifier.animateItem()) {
                TargetCard(
                    target = target,
                    result = results[target],
                    running = progress.running && results[target] == null,
                    expanded = expanded == target,
                    onToggle = { expanded = if (expanded == target) null else target },
                    onRun = { onRunOne(target) },
                    onRemove = { onRemove(target) },
                    onAddToWhitelist = { onAddToWhitelist(target) },
                )
            }
        }
    }
}

private fun verdictRank(verdict: String?): Int = when (verdict) {
    "down", "blocked", "error" -> 5
    "proxy-broken" -> 4
    "bypassed" -> 3
    "open" -> 1
    else -> 0
}

private fun verdictColor(verdict: String?): Color = when (verdict) {
    "open", "ok" -> Palette.Green
    "bypassed" -> Palette.Cyan
    "proxy-broken", "skip" -> Palette.Amber
    null, "" -> Palette.TextMuted
    else -> Palette.Red
}

private fun verdictLabel(verdict: String?): String = when (verdict) {
    "open" -> "открыт"
    "bypassed" -> "через VPN"
    "proxy-broken" -> "VPN мешает"
    "down" -> "не работает"
    "blocked" -> "заблокирован"
    "error" -> "ошибка"
    else -> "—"
}

@Composable
private fun TargetCard(
    target: String,
    result: ScanResult?,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onRemove: () -> Unit,
    onAddToWhitelist: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, shape)
            .clickable(enabled = result != null, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(verdictColor(result?.verdict)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(target, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    running -> "проверяется…"
                    result == null -> "не проверялся"
                    else -> result.summary
                }
                Text(subtitle, color = Palette.TextSecondary, fontSize = 12.sp, maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis)
            }
            if (running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Cyan)
            } else {
                Text(verdictLabel(result?.verdict), color = verdictColor(result?.verdict), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (result != null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                result.paths.forEach { path -> PathBadge(path) }
            }
        }
        AnimatedVisibility(visible = expanded && result != null) {
            if (result != null) ScanDetails(result, onRun, onRemove, onAddToWhitelist)
        }
    }
}

@Composable
private fun PathBadge(path: ScanPath) {
    val color = verdictColor(path.verdict)
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            (if (path.id == "direct") "Напрямую" else "VPN") + ": " + if (path.verdict == "ok") "да" else "нет",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ScanDetails(result: ScanResult, onRun: () -> Unit, onRemove: () -> Unit, onAddToWhitelist: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        result.paths.forEach { path ->
            Text(path.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
            Text(path.summary, fontSize = 12.sp, color = verdictColor(path.verdict))
            Spacer(Modifier.height(4.dp))
            path.steps.forEach { step ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when (step.status) {
                                    "ok" -> Palette.Green
                                    "warn" -> Palette.Amber
                                    "fail" -> Palette.Red
                                    else -> Palette.TextMuted
                                },
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(step.title, fontSize = 12.sp, color = Palette.TextSecondary, modifier = Modifier.width(96.dp))
                    Text(
                        listOfNotNull(step.detail.ifEmpty { null }, step.ms.takeIf { it > 0 }?.let { "${it.toInt()} мс" }).joinToString(" · "),
                        fontSize = 12.sp,
                        color = Palette.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (result.dns.isNotEmpty()) {
            Text("DNS", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
            result.dns.forEach { answer ->
                Text(
                    "${answer.resolver}: " + (answer.ips.joinToString(", ").ifEmpty { answer.error.ifEmpty { "пусто" } }),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when (answer.verdict) {
                        "bogus", "empty", "error" -> Palette.Red
                        "differs" -> Palette.Amber
                        else -> Palette.TextMuted
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        result.hints.forEach { hint ->
            Text("• $hint", fontSize = 12.sp, color = Palette.Amber)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRun) { Text("Проверить ещё раз") }
            // Открывается напрямую — можно не гонять через VPN.
            if (result.paths.any { it.id == "direct" && it.verdict == "ok" }) {
                TextButton(onClick = onAddToWhitelist) { Text("Пускать напрямую") }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Убрать", tint = Palette.TextMuted)
            }
        }
    }
}

private fun testRank(test: NodeTest?): Int = when (test?.verdict) {
    "silent" -> 0
    "down" -> 1
    "error" -> 2
    "ok" -> 3
    else -> 4
}

/**
 * Проверка серверов: каждый открывает страницу через свой прокси. Вверху —
 * «молчащие»: TCP до них проходит, но через них ничего не открывается.
 */
@Composable
private fun ServersCheck(
    nodes: List<ServerNode>,
    tests: Map<String, NodeTest>,
    progress: ScanProgress,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val ordered = nodes.sortedWith(compareBy({ testRank(tests[it.id]) }, { tests[it.id]?.ms ?: Int.MAX_VALUE }))
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "controls") {
            Column {
                if (tests.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountTile("${tests.values.count { it.ok }}", "работают", Palette.Green, Modifier.weight(1f))
                        CountTile("${tests.values.count { it.verdict == "silent" }}", "не открывают", Palette.Red, Modifier.weight(1f))
                        CountTile("${tests.values.count { it.verdict == "down" || it.verdict == "error" }}", "недоступны", Palette.Amber, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                }
                RunButton(progress.running, nodes.isNotEmpty(), "Проверить серверы (${nodes.size})", onRun, onStop)
                ProgressLine(progress)
            }
        }
        items(ordered, key = { it.id }) { node ->
            Box(Modifier.animateItem()) { NodeTestRow(node, tests[node.id], progress.running) }
        }
    }
}

@Composable
private fun NodeTestRow(node: ServerNode, test: NodeTest?, running: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    val color = when (test?.verdict) {
        "ok" -> Palette.Green
        "silent" -> Palette.Red
        "down", "error" -> Palette.Amber
        else -> Palette.TextMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = when {
                test == null && running -> "в очереди"
                test == null -> "не проверялся"
                else -> listOfNotNull(
                    when {
                        test.tcpMs > 0 -> "TCP ${test.tcpMs} мс"
                        test.tcpMs < 0 -> "TCP нет"
                        else -> null
                    },
                    test.error.ifEmpty { null },
                ).joinToString(" · ")
            }
            Text(details, color = Palette.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            when (test?.verdict) {
                "ok" -> "${test?.ms} мс"
                "silent" -> "не открывает"
                "down" -> "недоступен"
                "error" -> "ошибка"
                else -> "—"
            },
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Статистика: сводка, надёжные и проблемные серверы, подписки. Полосы — одна
 * мера (доля успешных проверок), цвет — статус с подписью в процентах.
 */
@Composable
private fun StatsView(stats: StatsReport?, networkLabel: String, onReset: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "network") {
            Text(
                "История для сети: $networkLabel",
                color = Palette.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (stats == null || stats.empty) {
            item(key = "empty") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.Insights, null, tint = Palette.TextMuted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Статистики пока нет", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Нажмите «Пинг всех» на экране серверов или проверьте серверы на соседней вкладке.",
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@LazyColumn
        }
        item(key = "hero") {
            // Одинаковая высота плиток: по самой высокой.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroTile("${(stats.reliability * 100).roundToInt()}%", "надёжность", Modifier.weight(1f).fillMaxHeight())
                HeroTile("${stats.workingServers}/${stats.checkedServers}", "работают", Modifier.weight(1f).fillMaxHeight())
                HeroTile(if (stats.avgMs > 0) "${stats.avgMs}" else "—", "мс, пинг", Modifier.weight(1f).fillMaxHeight())
            }
            Text(
                "Проверок: ${stats.totalChecks} · проверено ${stats.checkedServers} из ${stats.totalServers} серверов",
                color = Palette.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
        if (stats.reliable.isNotEmpty()) {
            item(key = "reliable-title") { StatsTitle("Чаще всего работают") }
            items(stats.reliable, key = { "r-" + it.node.id }) { stat ->
                ServerStatRow(Modifier.animateItem(), stat)
            }
        }
        if (stats.subscriptions.isNotEmpty()) {
            item(key = "subs-title") { StatsTitle("Подписки") }
            items(stats.subscriptions, key = { "s-" + it.subscription.id }) { stat ->
                SubscriptionStatRow(Modifier.animateItem(), stat)
            }
        }
        if (stats.problematic.isNotEmpty()) {
            item(key = "problem-title") { StatsTitle("Часто не отвечают") }
            items(stats.problematic, key = { "p-" + it.node.id }) { stat ->
                ServerStatRow(Modifier.animateItem(), stat)
            }
        }
        item(key = "reset") {
            TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Сбросить статистику", color = Palette.TextMuted)
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.SurfaceHigh,
            title = { Text("Сбросить статистику?") },
            text = { Text("История всех пингов и проверок пропадёт, авто-режим начнёт узнавать серверы заново.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text("Сбросить", color = Palette.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun StatsTitle(text: String) {
    Text(
        text.uppercase(),
        color = Palette.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** Крупное число сводки. */
@Composable
private fun HeroTile(value: String, label: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Palette.SurfaceHigh, Palette.Surface)))
            .border(1.dp, Palette.Stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.TextPrimary, maxLines = 1)
        Text(label, fontSize = 11.sp, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Цвет статуса надёжности: всегда рядом с подписью в процентах. */
private fun rateColor(rate: Double): Color = when {
    rate >= 0.8 -> Palette.Green
    rate > StatsReport.WORKING_RATE -> Palette.Amber
    else -> Palette.Red
}

/** Горизонтальная полоса доли 0..1 со скруглённым концом; заполняется плавно. */
@Composable
private fun RateBar(rate: Double, color: Color, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fraction by animateFloatAsState(if (shown) rate.toFloat().coerceIn(0f, 1f) else 0f, tween(700), label = "rate")
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.SurfaceHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

@Composable
private fun ServerStatRow(modifier: Modifier, stat: ServerStat) {
    val rate = stat.record.successRate
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagBadge(stat.node.flag, size = 28)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stat.node.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        stat.subscription,
                        stat.record.avgMs.takeIf { it > 0 }?.let { "~${it.roundToInt()} мс" },
                        "${stat.record.checks} ${plural(stat.record.checks.toLong(), "проверка", "проверки", "проверок")}",
                    ).joinToString(" · "),
                    color = Palette.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${(rate * 100).roundToInt()}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        RateBar(rate, rateColor(rate))
    }
}

@Composable
private fun SubscriptionStatRow(modifier: Modifier, stat: SubscriptionStat) {
    val share = if (stat.checked == 0) 0.0 else stat.working.toDouble() / stat.checked
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stat.subscription.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (stat.checked == 0) {
                        "ещё не проверялась · ${stat.total} ${plural(stat.total.toLong(), "сервер", "сервера", "серверов")}"
                    } else {
                        listOfNotNull(
                            "работают ${stat.working} из ${stat.checked}",
                            stat.avgMs.takeIf { it > 0 }?.let { "~$it мс" },
                            stat.best?.let { "лучший: ${it.node.title}" },
                        ).joinToString(" · ")
                    },
                    color = Palette.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (stat.checked > 0) {
                Text("${(share * 100).roundToInt()}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
            }
        }
        if (stat.checked > 0) {
            Spacer(Modifier.height(8.dp))
            RateBar(share, rateColor(share))
        }
    }
}
