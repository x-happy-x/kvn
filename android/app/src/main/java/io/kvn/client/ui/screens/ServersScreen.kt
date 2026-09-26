package io.kvn.client.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.core.Engine
import io.kvn.client.data.ServerNode
import io.kvn.client.data.ServerSort
import io.kvn.client.data.NodeTest
import io.kvn.client.data.PingRecord
import io.kvn.client.data.Subscription
import io.kvn.client.data.SubscriptionSource
import io.kvn.client.ui.Pings
import io.kvn.client.ui.components.Chip
import io.kvn.client.ui.components.FlagBadge
import io.kvn.client.ui.components.PingText
import io.kvn.client.ui.components.expireLabel
import io.kvn.client.ui.components.formatBytes
import io.kvn.client.ui.components.plural
import io.kvn.client.ui.components.pressable
import io.kvn.client.ui.components.protocolLabel
import io.kvn.client.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

@Composable
fun ServersScreen(
    subscriptions: List<Subscription>,
    selectedId: String?,
    engine: Engine,
    pings: Pings,
    pinging: Boolean,
    refreshing: Boolean,
    onSelect: (ServerNode) -> Unit,
    onPingAll: () -> Unit,
    onFastest: () -> Unit,
    onRefreshAll: () -> Unit,
    onRefresh: (Subscription) -> Unit,
    onRename: (Subscription, String) -> Unit,
    onDelete: (Subscription) -> Unit,
    onSetEngine: (Subscription, Engine?) -> Unit,
    onAdd: () -> Unit,
    nodeTests: Map<String, NodeTest> = emptyMap(),
    statsVersion: Int = 0,
    pingRecord: (ServerNode) -> PingRecord? = { null },
    sort: ServerSort = ServerSort.SUBSCRIPTIONS,
    onSort: (ServerSort) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Серверы",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        // Подписанные действия вместо одних значков: что делает кнопка, видно сразу.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(Icons.Rounded.NetworkCheck, if (pinging) "Пингую…" else "Пинг всех", busy = pinging, onClick = onPingAll)
            ActionButton(Icons.Rounded.Bolt, "Выбрать лучший", onClick = onFastest)
            ActionButton(Icons.Rounded.Refresh, if (refreshing) "Обновляю…" else "Обновить подписки", busy = refreshing, onClick = onRefreshAll)
            ActionButton(Icons.Rounded.Add, "Добавить", accent = true, onClick = onAdd)
        }
        Text(
            "«Пинг всех» проверяет доступность серверов, «Выбрать лучший» — берёт самый надёжный и быстрый по истории проверок.",
            color = Palette.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
        )
        SortRow(sort, onSort)

        if (subscriptions.isEmpty()) {
            EmptyServers(onAdd)
            return@Column
        }

        // Общий список для сортировки по пингу или доступности: подписка видна в строке.
        val flat = remember(subscriptions, engine, pings, sort, statsVersion) {
            if (sort == ServerSort.SUBSCRIPTIONS) {
                emptyList()
            } else {
                val all = subscriptions.flatMap { subscription ->
                    subscription.visibleNodes(engine).map { node -> Triple(node, subscription, pingRecord(node)) }
                }
                when (sort) {
                    ServerSort.PING -> all.sortedWith(
                        compareBy<Triple<ServerNode, Subscription, PingRecord?>>(
                            { (node, subscription, _) -> if (node.supports(subscription.effectiveEngine(engine))) 0 else 1 },
                            { (node, _, _) ->
                                val ms = pings[node.id]
                                when {
                                    ms == null -> 1
                                    ms > 0 -> 0
                                    else -> 2
                                }
                            },
                            { (node, _, record) -> pings[node.id]?.takeIf { it > 0 } ?: record?.avgMs?.toInt()?.takeIf { it > 0 } ?: Int.MAX_VALUE },
                        ),
                    )
                    else -> all.sortedWith(
                        compareBy<Triple<ServerNode, Subscription, PingRecord?>>(
                            { (node, subscription, _) -> if (node.supports(subscription.effectiveEngine(engine))) 0 else 1 },
                            { (node, _, _) -> if ((pings[node.id] ?: 0) < 0) 1 else 0 },
                        ).thenByDescending { (_, _, record) -> record?.successRate ?: 0.4 }
                            .thenByDescending { (_, _, record) -> record?.score ?: 0.0 },
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sort != ServerSort.SUBSCRIPTIONS) {
                items(flat, key = { it.first.id }) { (node, subscription, record) ->
                    ServerRow(
                        modifier = Modifier.animateItem(),
                        node = node,
                        selected = node.id == selectedId,
                        engine = subscription.effectiveEngine(engine),
                        ping = pings[node.id],
                        record = record,
                        test = nodeTests[node.id],
                        caption = subscription.name,
                        onClick = { onSelect(node) },
                    )
                }
                return@LazyColumn
            }
            subscriptions.forEach { subscription ->
                item(key = "header-${subscription.id}") {
                  Box(Modifier.animateItem()) {
                    SubscriptionHeader(subscription, engine, onRefresh, onRename, onDelete, onSetEngine)
                  }
                }
                items(subscription.visibleNodes(engine), key = { it.id }) { node ->
                    // statsVersion — ключ перечитывания статистики после новой серии пингов.
                    val record = remember(node.id, statsVersion) { pingRecord(node) }
                    ServerRow(
                        modifier = Modifier.animateItem(),
                        node = node,
                        selected = node.id == selectedId,
                        engine = subscription.effectiveEngine(engine),
                        ping = pings[node.id],
                        record = record,
                        test = nodeTests[node.id],
                        onClick = { onSelect(node) },
                    )
                }
            }
        }
    }
}

/** Переключатель порядка: по подпискам, по пингу или по доступности. */
@Composable
private fun SortRow(sort: ServerSort, onSort: (ServerSort) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Порядок:", color = Palette.TextMuted, fontSize = 12.sp)
        ServerSort.entries.forEach { option ->
            val active = option == sort
            val background by animateColorAsState(if (active) Palette.Violet.copy(alpha = 0.22f) else Palette.Surface, tween(220), label = "sortBg")
            val border by animateColorAsState(if (active) Palette.Violet else Palette.Stroke, tween(220), label = "sortBorder")
            Text(
                option.title,
                color = if (active) Palette.TextPrimary else Palette.TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .border(1.dp, border, RoundedCornerShape(10.dp))
                    .pressable { onSort(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    title: String,
    busy: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (accent) Palette.Violet else Palette.Surface)
            .border(1.dp, if (accent) Palette.Violet else Palette.Stroke, shape)
            .pressable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Cyan)
        } else {
            Icon(icon, contentDescription = null, tint = if (accent) Palette.TextPrimary else Palette.VioletSoft, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.TextPrimary)
    }
}

@Composable
private fun SubscriptionHeader(
    subscription: Subscription,
    engine: Engine,
    onRefresh: (Subscription) -> Unit,
    onRename: (Subscription, String) -> Unit,
    onDelete: (Subscription) -> Unit,
    onSetEngine: (Subscription, Engine?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var choosingEngine by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                subscription.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val count = subscription.visibleNodes(engine).size.toLong()
            val details = buildList {
                subscription.engine?.let { add("ядро ${it.title}") }
                if (subscription.borrowsNodes(engine)) add("серверы прошлого ядра")
                if (subscription.source == SubscriptionSource.SUBLAB) add("sub-lab")
                add("$count ${plural(count, "сервер", "сервера", "серверов")}")
                if (subscription.total > 0) add("${formatBytes(subscription.used)} / ${formatBytes(subscription.total)}")
                expireLabel(subscription.expire)?.let { add(it) }
                if (subscription.updatedAt > 0) {
                    add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(subscription.updatedAt)))
                }
            }
            Text(details.joinToString(" · "), color = Palette.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subscription.error?.let {
                Text(it, color = Palette.Red, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Действия", tint = Palette.TextMuted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (subscription.url.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Обновить") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                        onClick = {
                            menu = false
                            onRefresh(subscription)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Ядро: ${subscription.engine?.title ?: "текущее"}") },
                    leadingIcon = { Icon(Icons.Rounded.Memory, null) },
                    onClick = {
                        menu = false
                        choosingEngine = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Переименовать") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = {
                        menu = false
                        renaming = true
                    },
                )
                // Подписки аккаунта sub-lab вернутся при следующей синхронизации —
                // убирать их нужно в sub-lab или выходом из аккаунта.
                if (subscription.source != SubscriptionSource.SUBLAB) {
                    DropdownMenuItem(
                        text = { Text("Удалить", color = Palette.Red) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Palette.Red) },
                        onClick = {
                            menu = false
                            confirmDelete = true
                        },
                    )
                }
            }
        }
    }

    if (choosingEngine) {
        AlertDialog(
            onDismissRequest = { choosingEngine = false },
            containerColor = Palette.SurfaceHigh,
            title = { Text("Ядро подписки") },
            text = {
                Column {
                    Text(
                        "С закреплённым ядром приложение само переключится на него при подключении к серверу этой подписки.",
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf<Engine?>(null, Engine.XRAY, Engine.MIHOMO).forEach { option ->
                        TextButton(onClick = {
                            choosingEngine = false
                            onSetEngine(subscription, option)
                        }) {
                            Text(
                                option?.title ?: "Текущее ядро приложения",
                                color = if (subscription.engine == option) Palette.VioletSoft else Palette.TextPrimary,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingEngine = false }) { Text("Закрыть") } },
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(subscription.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = Palette.SurfaceHigh,
            title = { Text("Название подписки") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    if (name.isNotBlank()) onRename(subscription, name.trim())
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Отмена") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.SurfaceHigh,
            title = { Text("Удалить подписку?") },
            text = { Text("«${subscription.name}» и все её серверы пропадут из приложения.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(subscription)
                }) { Text("Удалить", color = Palette.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ServerRow(
    modifier: Modifier = Modifier,
    node: ServerNode,
    selected: Boolean,
    engine: Engine,
    ping: Int?,
    record: PingRecord?,
    test: NodeTest?,
    caption: String? = null,
    onClick: () -> Unit,
) {
    val supported = node.supports(engine)
    val shape = RoundedCornerShape(18.dp)
    val borderColor by animateColorAsState(if (selected) Palette.Violet else Palette.Stroke, tween(300), label = "border")
    val background by animateColorAsState(if (selected) Palette.SurfaceHigh else Palette.Surface, tween(300), label = "bg")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagBadge(node.flag, size = 38)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.title,
                color = if (supported) Palette.TextPrimary else Palette.TextMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(protocolLabel(node.type))
                if (!node.xray) Chip("только Mihomo", color = Palette.Amber)
                if (!node.mihomo) Chip("только Xray", color = Palette.Amber)
                when (test?.verdict) {
                    "silent" -> Chip("не открывает", color = Palette.Red)
                    "ok" -> test?.let { Chip("${it.ms} мс", color = Palette.Green) }
                    else -> Unit
                }
                caption?.let {
                    Text(it, color = Palette.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            PingText(ping)
            // Надёжность по истории: показываем, когда проверок набралось достаточно.
            if (record != null && record.checks >= 3) {
                Text(
                    "${(record.successRate * 100).toInt()}% ок",
                    color = if (record.successRate >= 0.8) Palette.TextMuted else Palette.Amber,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) Palette.Violet else Palette.Stroke, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(if (selected) Palette.Violet else Palette.Surface),
            )
        }
    }
}

@Composable
private fun EmptyServers(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FlagBadge(null, size = 72)
        Spacer(Modifier.height(16.dp))
        Text("Пока пусто", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Добавьте ссылку на подписку — подойдут ссылки sub-lab, Remnawave, Marzban и любые списки vless:// / vmess:// / trojan:// / ss://",
            color = Palette.TextSecondary,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onAdd) { Text("Добавить подписку", color = Palette.VioletSoft) }
    }
}
