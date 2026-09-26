package io.kvn.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
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

/**
 * Проверка доступности ресурсов, как в HomeNet: для каждого сайта — путь
 * напрямую через оператора и путь через VPN, этапы DNS → TCP → TLS → HTTP →
 * объём, и итог: открыт, заблокирован, обходится через VPN или не работает.
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
) {
    var input by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var serversMode by rememberSaveable { mutableStateOf(false) }

    if (serversMode) {
        ServersCheck(
            nodes = nodes,
            tests = nodeTests,
            progress = nodeTestProgress,
            onModeSites = { serversMode = false },
            onRun = onTestNodes,
            onStop = onStopNodeTests,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Проверка",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp),
            )
            ModeSwitch(servers = false, onChange = { serversMode = it })
            Spacer(Modifier.height(8.dp))
            Text(
                if (vpnConnected) {
                    "Каждый сайт проверяется напрямую через оператора и через VPN — видно, что заблокировано и помогает ли прокси."
                } else {
                    "VPN выключен — проверяется только прямой путь через оператора. Подключитесь, чтобы сравнить с VPN."
                },
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = if (progress.running) onStop else onRun,
                    enabled = targets.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (progress.running) Palette.SurfaceHighest else Palette.Violet),
                ) {
                    Icon(if (progress.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (progress.running) "Остановить" else "Проверить всё (${targets.size})")
                }
                Spacer(Modifier.weight(1f))
                if (targets.isNotEmpty() && !progress.running) {
                    TextButton(onClick = onClear) { Text("Очистить", color = Palette.TextMuted) }
                }
            }
            if (progress.running) {
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
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        val ordered = targets.sortedByDescending { verdictRank(results[it]?.verdict) }
        items(ordered, key = { it }) { target ->
            TargetCard(
                target = target,
                result = results[target],
                running = progress.running && results[target] == null,
                expanded = expanded == target,
                onToggle = { expanded = if (expanded == target) null else target },
                onRun = { onRunOne(target) },
                onRemove = { onRemove(target) },
            )
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
            if (result != null) ScanDetails(result, onRun, onRemove)
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
private fun ScanDetails(result: ScanResult, onRun: () -> Unit, onRemove: () -> Unit) {
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
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Убрать", tint = Palette.TextMuted)
            }
        }
    }
}

@Composable
private fun ModeSwitch(servers: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        listOf(false to "Сайты", true to "Серверы").forEach { (value, title) ->
            FilterChip(
                selected = servers == value,
                onClick = { onChange(value) },
                label = { Text(title) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.Violet,
                    selectedLabelColor = Palette.TextPrimary,
                    containerColor = Palette.Surface,
                    labelColor = Palette.TextSecondary,
                ),
            )
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
    onModeSites: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val ordered = nodes.sortedWith(compareBy({ testRank(tests[it.id]) }, { tests[it.id]?.ms ?: Int.MAX_VALUE }))
    val silent = tests.values.count { it.verdict == "silent" }
    val working = tests.values.count { it.ok }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Проверка",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp),
            )
            ModeSwitch(servers = true, onChange = { if (!it) onModeSites() })
            Spacer(Modifier.height(8.dp))
            Text(
                "Каждый сервер открывает страницу через себя, отдельно от VPN. Так видно те, что пингуются, но ничего не открывают. Результаты попадают в статистику, и пинг потом начинается с надёжных.",
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = if (progress.running) onStop else onRun,
                enabled = nodes.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (progress.running) Palette.SurfaceHighest else Palette.Violet),
            ) {
                Icon(if (progress.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (progress.running) "Остановить" else "Проверить все серверы (${nodes.size})")
            }
            if (progress.running) {
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
                Text("${progress.done} из ${progress.total} · ${progress.current}", color = Palette.TextMuted, fontSize = 12.sp)
            } else if (tests.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Работают: $working · пингуются без ответа: $silent · всего: ${tests.size}",
                    color = Palette.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
        items(ordered, key = { it.id }) { node -> NodeTestRow(node, tests[node.id], progress.running) }
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
