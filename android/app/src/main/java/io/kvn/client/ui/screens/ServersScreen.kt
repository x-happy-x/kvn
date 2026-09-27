package io.kvn.client.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.QrCodeScanner
import io.kvn.client.ui.components.QrShareDialog
import io.kvn.client.ui.components.uiSpring
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewHeadline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import io.kvn.client.data.ServerView
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import io.kvn.client.data.ServerGrouping
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
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

/** Строка списка: сервер, его подписка и история проверок. */
private data class ServerEntry(val node: ServerNode, val subscription: Subscription, val record: PingRecord?)

/** Элемент сетки: заголовок группы, заголовок подписки, архив или сервер. */
private sealed interface GridItem {
    val key: String

    data class Group(val id: String, val title: String, val subtitle: String, val accent: Boolean) : GridItem {
        override val key get() = "group-$id"
    }

    data class Header(val subscription: Subscription) : GridItem {
        override val key get() = "header-${subscription.id}"
    }

    data class Archive(val count: Int, val expanded: Boolean) : GridItem {
        override val key get() = "archive"
    }

    /** [group] — внутри какой группы сервер можно перетаскивать. */
    data class Server(val entry: ServerEntry, val caption: String?, val group: String) : GridItem {
        override val key get() = entry.node.id
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onRefreshAll: () -> Unit,
    onRefresh: (Subscription) -> Unit,
    onRename: (Subscription, String) -> Unit,
    onDelete: (Subscription) -> Unit,
    onSetEngine: (Subscription, Engine?) -> Unit,
    onAdd: () -> Unit,
    nodeTests: Map<String, NodeTest> = emptyMap(),
    statsVersion: Int = 0,
    pingRecord: (ServerNode) -> PingRecord? = { null },
    grouping: ServerGrouping = ServerGrouping.SUBSCRIPTIONS,
    onGrouping: (ServerGrouping) -> Unit = {},
    sort: ServerSort = ServerSort.DEFAULT,
    onSort: (ServerSort) -> Unit = {},
    view: ServerView = ServerView.COMPACT,
    onView: (ServerView) -> Unit = {},
    order: List<String> = emptyList(),
    onReorder: (List<String>) -> Unit = {},
    pingsStale: Boolean = false,
    onOpen: () -> Unit = {},
    onToggleEnabled: (Subscription, Boolean) -> Unit = { _, _ -> },
    onToggleCollapsed: (Subscription) -> Unit = {},
    shareLink: (ServerNode) -> String? = { null },
    /** Аккаунт sub-lab для заголовка группы, например «Иван · sub.example.com». */
    subLabLabel: String? = null,
    subLabTags: Set<String> = emptySet(),
) {
    // Открыли экран, а пинги старые или из другой сети — пингуем заново.
    LaunchedEffect(Unit) { onOpen() }

    // Что показываем QR-кодом: заголовок и ссылка.
    var sharing by remember { mutableStateOf<Pair<String, String>?>(null) }
    val shareNode: (ServerNode) -> Unit = { node -> shareLink(node)?.let { sharing = node.name to it } }
    sharing?.let { (title, link) -> QrShareDialog(title, link) { sharing = null } }

    var archiveOpen by rememberSaveable { mutableStateOf(false) }
    val built = remember(subscriptions, engine, pings, grouping, sort, order, statsVersion, subLabLabel, subLabTags, archiveOpen) {
        buildGrid(subscriptions, engine, pings, grouping, sort, order, pingRecord, subLabLabel, subLabTags, archiveOpen)
    }
    // Копия для перетаскивания: меняется на лету, сохраняется при отпускании.
    var displayed by remember(built) { mutableStateOf(built) }
    val serverCount = remember(subscriptions, engine) { subscriptions.filter { it.enabled }.sumOf { it.visibleNodes(engine).size } }
    val reorderable = sort == ServerSort.DEFAULT

    Column(Modifier.fillMaxSize()) {
        Toolbar(
            serverCount = serverCount,
            refreshing = refreshing,
            grouping = grouping,
            sort = sort,
            view = view,
            onRefreshAll = onRefreshAll,
            onGrouping = onGrouping,
            onSort = onSort,
            onView = onView,
            onAdd = onAdd,
        )

        if (subscriptions.isEmpty()) {
            EmptyServers(onAdd)
            return@Column
        }

        val gridState = rememberLazyGridState()
        val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
            val list = displayed
            val fromIndex = list.indexOfFirst { it.key == from.key }
            val toIndex = list.indexOfFirst { it.key == to.key }
            val moving = list.getOrNull(fromIndex) as? GridItem.Server
            val target = list.getOrNull(toIndex) as? GridItem.Server
            // Перетаскивание только внутри своей группы (подписки или корзины пинга).
            if (moving != null && target != null && moving.group == target.group) {
                displayed = list.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            }
        }
        val saveOrder = { onReorder(displayed.filterIsInstance<GridItem.Server>().map { it.entry.node.id }) }

        // Пинг — свайпом вниз.
        PullToRefreshBox(
            isRefreshing = pinging,
            onRefresh = onPingAll,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Карточки — по две в ряд, заголовки всегда на всю ширину.
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (view == ServerView.CARDS) 2 else 1),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (view == ServerView.COMPACT) 4.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = displayed,
                    key = { it.key },
                    span = { item -> GridItemSpan(if (item is GridItem.Server) 1 else maxLineSpan) },
                ) { item ->
                    when (item) {
                        is GridItem.Group -> GroupHeader(Modifier.animateItem(), item.title, item.subtitle, item.accent)
                        is GridItem.Archive -> ArchiveHeader(Modifier.animateItem(), item.count, item.expanded) { archiveOpen = !archiveOpen }
                        is GridItem.Header -> Box(Modifier.animateItem()) {
                            SubscriptionHeader(
                                item.subscription, engine, onRefresh, onRename, onDelete, onSetEngine,
                                onToggleEnabled = onToggleEnabled,
                                onToggleCollapsed = onToggleCollapsed,
                                onShare = { sharing = item.subscription.name to item.subscription.url },
                            )
                        }
                        is GridItem.Server -> ReorderableItem(reorderState, key = item.key, enabled = reorderable) { dragging ->
                            val (node, subscription, record) = item.entry
                            val enabled = subscription.enabled
                            val onClick = { if (enabled) onSelect(node) else onToggleEnabled(subscription, true) }
                            val handle: (@Composable () -> Unit)? = if (reorderable) {
                                {
                                    Icon(
                                        Icons.Rounded.DragIndicator,
                                        contentDescription = "Перетащить",
                                        tint = Palette.TextMuted,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .draggableHandle(onDragStopped = { saveOrder() }),
                                    )
                                }
                            } else {
                                null
                            }
                            val lift by animateFloatAsState(if (dragging) 1.03f else 1f, uiSpring(), label = "lift")
                            val common = Modifier.graphicsLayer {
                                scaleX = lift
                                scaleY = lift
                            }
                            val ping = pings[node.id]
                            when (view) {
                                ServerView.LIST -> ServerRow(
                                    modifier = common,
                                    node = node,
                                    selected = node.id == selectedId,
                                    engine = subscription.effectiveEngine(engine),
                                    ping = ping,
                                    pingStale = pingsStale,
                                    record = record,
                                    test = nodeTests[node.id],
                                    caption = item.caption,
                                    disabled = !enabled,
                                    handle = handle,
                                    onClick = onClick,
                                    onLongClick = { shareNode(node) },
                                )
                                ServerView.COMPACT -> ServerCompactRow(
                                    modifier = common,
                                    node = node,
                                    selected = node.id == selectedId,
                                    supported = node.supports(subscription.effectiveEngine(engine)) && enabled,
                                    ping = ping,
                                    pingStale = pingsStale,
                                    caption = item.caption,
                                    handle = handle,
                                    onClick = onClick,
                                    onLongClick = { shareNode(node) },
                                )
                                ServerView.CARDS -> ServerCard(
                                    modifier = common,
                                    node = node,
                                    selected = node.id == selectedId,
                                    supported = node.supports(subscription.effectiveEngine(engine)) && enabled,
                                    ping = ping,
                                    pingStale = pingsStale,
                                    record = record,
                                    handle = handle,
                                    onClick = onClick,
                                    onLongClick = { shareNode(node) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Корзины группировки по пингу. */
private fun pingBucket(ms: Int?): Pair<String, String> = when {
    ms == null -> "9" to "Не проверены"
    ms < 0 -> "8" to "Нет ответа"
    ms < 100 -> "1" to "До 100 мс"
    ms < 250 -> "2" to "100–250 мс"
    ms < 500 -> "3" to "250–500 мс"
    else -> "4" to "Дольше 500 мс"
}

/** Корзины группировки по надёжности. */
private fun reliabilityBucket(record: PingRecord?): Pair<String, String> = when {
    record == null || record.checks < 3 -> "9" to "Мало данных"
    record.successRate >= 0.8 -> "1" to "Надёжные · 80% и выше"
    record.successRate > 0.5 -> "2" to "Средние · 50–80%"
    else -> "3" to "Ненадёжные · до 50%"
}

/**
 * Список для сетки: группы (подписки, корзины пинга или надёжности), внутри —
 * свой порядок, пинг или надёжность. Выключенные подписки — в архиве внизу.
 */
private fun buildGrid(
    subscriptions: List<Subscription>,
    engine: Engine,
    pings: Pings,
    grouping: ServerGrouping,
    sort: ServerSort,
    order: List<String>,
    pingRecord: (ServerNode) -> PingRecord?,
    subLabLabel: String?,
    subLabTags: Set<String>,
    archiveOpen: Boolean,
): List<GridItem> {
    val active = subscriptions.filter { it.enabled }
    val archived = subscriptions.filter { !it.enabled }
    val orderIndex = order.withIndex().associate { (index, id) -> id to index }

    fun sortEntries(entries: List<ServerEntry>): List<ServerEntry> {
        val supported = compareBy<ServerEntry> { if (it.node.supports(it.subscription.effectiveEngine(engine))) 0 else 1 }
        return when (sort) {
            ServerSort.DEFAULT -> entries.withIndex()
                .sortedBy { (position, entry) -> orderIndex[entry.node.id] ?: (order.size + position) }
                .map { it.value }
            ServerSort.PING -> entries.sortedWith(
                supported
                    .thenBy { entry ->
                        val ms = pings[entry.node.id]
                        when {
                            ms == null -> 1
                            ms > 0 -> 0
                            else -> 2
                        }
                    }
                    .thenBy { entry -> pings[entry.node.id]?.takeIf { it > 0 } ?: entry.record?.avgMs?.toInt()?.takeIf { it > 0 } ?: Int.MAX_VALUE },
            )
            ServerSort.RELIABILITY -> entries.sortedWith(
                supported
                    .thenBy { entry -> if ((pings[entry.node.id] ?: 0) < 0) 1 else 0 }
                    .thenByDescending { entry -> entry.record?.successRate ?: 0.4 }
                    .thenByDescending { entry -> entry.record?.score ?: 0.0 },
            )
        }
    }

    val out = mutableListOf<GridItem>()
    when (grouping) {
        ServerGrouping.SUBSCRIPTIONS -> {
            val fromSubLab = active.filter { it.source == SubscriptionSource.SUBLAB }
            val own = active.filter { it.source != SubscriptionSource.SUBLAB }
            fun addSubscription(subscription: Subscription) {
                out += GridItem.Header(subscription)
                if (subscription.collapsed) return
                val entries = subscription.visibleNodes(engine).map { ServerEntry(it, subscription, pingRecord(it)) }
                sortEntries(entries).forEach { out += GridItem.Server(it, null, subscription.id) }
            }
            if (fromSubLab.isNotEmpty()) {
                val subtitle = listOfNotNull(subLabLabel, subLabTags.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(" ") { "#$it" }).joinToString(" · ")
                out += GridItem.Group("sublab", "sub-lab", subtitle, accent = true)
                fromSubLab.forEach(::addSubscription)
                if (own.isNotEmpty()) out += GridItem.Group("own", "Свои подписки", "", accent = false)
            }
            own.forEach(::addSubscription)
        }
        ServerGrouping.PING, ServerGrouping.RELIABILITY -> {
            val entries = active.flatMap { subscription ->
                subscription.visibleNodes(engine).map { ServerEntry(it, subscription, pingRecord(it)) }
            }
            val bucketOf: (ServerEntry) -> Pair<String, String> = if (grouping == ServerGrouping.PING) {
                { pingBucket(pings[it.node.id]) }
            } else {
                { reliabilityBucket(it.record) }
            }
            entries.groupBy(bucketOf).toSortedMap(compareBy { it.first }).forEach { (bucket, members) ->
                out += GridItem.Group(bucket.first, bucket.second, "${members.size} ${plural(members.size.toLong(), "сервер", "сервера", "серверов")}", accent = false)
                sortEntries(members).forEach { out += GridItem.Server(it, it.subscription.name, bucket.first) }
            }
        }
    }
    if (archived.isNotEmpty()) {
        out += GridItem.Archive(archived.size, archiveOpen)
        if (archiveOpen) archived.forEach { out += GridItem.Header(it) }
    }
    return out
}

/**
 * Шапка экрана: заголовок, счётчик и действия значками — обновить подписки,
 * вид, группировка, порядок и «добавить». Пинг — свайпом вниз по списку.
 */
@Composable
private fun Toolbar(
    serverCount: Int,
    refreshing: Boolean,
    grouping: ServerGrouping,
    sort: ServerSort,
    view: ServerView,
    onRefreshAll: () -> Unit,
    onGrouping: (ServerGrouping) -> Unit,
    onSort: (ServerSort) -> Unit,
    onView: (ServerView) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Серверы", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "$serverCount ${plural(serverCount.toLong(), "сервер", "сервера", "серверов")} · ${grouping.title.lowercase()} · ${sort.title.lowercase()}",
                color = Palette.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ToolIcon(Icons.Rounded.Refresh, "Обновить подписки", busy = refreshing, onClick = onRefreshAll)
        MenuIcon(
            icon = when (view) {
                ServerView.LIST -> Icons.AutoMirrored.Rounded.ViewList
                ServerView.COMPACT -> Icons.Rounded.ViewHeadline
                ServerView.CARDS -> Icons.Rounded.GridView
            },
            label = "Вид: ${view.title}",
            options = ServerView.entries,
            selected = view,
            title = { it.title },
            onPick = onView,
        )
        MenuIcon(Icons.Rounded.Workspaces, "Группировка: ${grouping.title}", ServerGrouping.entries, grouping, { it.title }, onGrouping)
        MenuIcon(Icons.AutoMirrored.Rounded.Sort, "Порядок: ${sort.title}", ServerSort.entries, sort, { it.title }, onSort)
        Spacer(Modifier.width(4.dp))
        ToolIcon(Icons.Rounded.Add, "Добавить подписку", accent = true, onClick = onAdd)
    }
}

/** Значок с выпадающим списком вариантов (вид, группировка, порядок). */
@Composable
private fun <T> MenuIcon(icon: ImageVector, label: String, options: List<T>, selected: T, title: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolIcon(icon, label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(label.substringBefore(':'), color = Palette.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(title(option), color = if (option == selected) Palette.VioletSoft else Palette.TextPrimary) },
                    trailingIcon = if (option == selected) {
                        { Icon(Icons.Rounded.Check, null, tint = Palette.VioletSoft) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/** Кнопка-значок шапки; подпись видна по долгому нажатию. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolIcon(icon: ImageVector, label: String, busy: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(if (accent) Palette.Violet else Palette.Surface)
                .pressable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Cyan)
            } else {
                Icon(icon, contentDescription = label, tint = if (accent) Palette.TextPrimary else Palette.VioletSoft, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Архив выключенных подписок: свёрнут, раскрывается нажатием. */
@Composable
private fun ArchiveHeader(modifier: Modifier, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val arrow by animateFloatAsState(if (expanded) 0f else -90f, uiSpring(), label = "archiveArrow")
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Stroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Inventory2, null, tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Архив · $count", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary)
            Text("Выключенные подписки: не пингуются и не выбираются", fontSize = 11.sp, color = Palette.TextMuted)
        }
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Свернуть" else "Развернуть",
            tint = Palette.TextMuted,
            modifier = Modifier.graphicsLayer { rotationZ = arrow },
        )
    }
}

/** Заголовок группы подписок: sub-lab выделен акцентной полосой. */
@Composable
private fun GroupHeader(modifier: Modifier, title: String, subtitle: String, accent: Boolean) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (accent) Palette.Violet.copy(alpha = 0.14f) else Palette.Surface)
            .border(1.dp, if (accent) Palette.Violet.copy(alpha = 0.5f) else Palette.Stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (accent) Palette.Violet else Palette.TextMuted),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (accent) Palette.VioletSoft else Palette.TextSecondary)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 11.sp, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Компактная строка: флаг, название и пинг в одну линию. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCompactRow(
    modifier: Modifier,
    node: ServerNode,
    selected: Boolean,
    supported: Boolean,
    ping: Int?,
    pingStale: Boolean = false,
    caption: String? = null,
    handle: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(if (selected) Palette.Violet.copy(alpha = 0.18f) else Palette.Surface, tween(250), label = "compactBg")
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .graphicsLayer { alpha = if (supported) 1f else 0.5f }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagBadge(node.flag, size = 24)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.title,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            caption?.let { Text(it, fontSize = 10.sp, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        PingText(ping, stale = pingStale)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Palette.Violet),
            )
        }
        if (handle != null) {
            Spacer(Modifier.width(6.dp))
            handle()
        }
    }
}

/** Карточка сервера для сетки в два столбца. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    modifier: Modifier,
    node: ServerNode,
    selected: Boolean,
    supported: Boolean,
    ping: Int?,
    pingStale: Boolean = false,
    record: PingRecord?,
    handle: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val border by animateColorAsState(if (selected) Palette.Violet else Palette.Stroke, tween(300), label = "cardBorder")
    val background by animateColorAsState(if (selected) Palette.SurfaceHigh else Palette.Surface, tween(300), label = "cardBg")
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .graphicsLayer { alpha = if (supported) 1f else 0.5f }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagBadge(node.flag, size = 32)
            Spacer(Modifier.weight(1f))
            PingText(ping, stale = pingStale)
            handle?.let {
                Spacer(Modifier.width(4.dp))
                it()
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(node.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(38.dp))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(protocolLabel(node.type))
            Spacer(Modifier.weight(1f))
            if (record != null && record.checks >= 3) {
                Text(
                    "${(record.successRate * 100).toInt()}%",
                    color = if (record.successRate >= 0.8) Palette.TextMuted else Palette.Amber,
                    fontSize = 11.sp,
                )
            }
        }
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
    onToggleEnabled: (Subscription, Boolean) -> Unit,
    onToggleCollapsed: (Subscription) -> Unit,
    onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (subscription.collapsed) -90f else 0f, uiSpring(), label = "arrow")
    var choosingEngine by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggleCollapsed(subscription) }
            .padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Стрелка поворачивается при сворачивании.
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = if (subscription.collapsed) "Развернуть" else "Свернуть",
            tint = Palette.TextMuted,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = arrow },
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                subscription.name,
                color = if (subscription.enabled) Palette.TextPrimary else Palette.TextMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val count = subscription.visibleNodes(engine).size.toLong()
            val details = buildList {
                if (!subscription.enabled) add("выключена")
                subscription.engine?.let { add("ядро ${it.title}") }
                if (subscription.borrowsNodes(engine)) add("серверы прошлого ядра")
                if (subscription.tags.isNotEmpty()) add(subscription.tags.joinToString(" ") { "#$it" })
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
                    text = { Text(if (subscription.enabled) "Выключить на время" else "Включить") },
                    leadingIcon = { Icon(if (subscription.enabled) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle, null) },
                    onClick = {
                        menu = false
                        onToggleEnabled(subscription, !subscription.enabled)
                    },
                )
                if (subscription.url.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Поделиться по QR") },
                        leadingIcon = { Icon(Icons.Rounded.QrCode, null) },
                        onClick = {
                            menu = false
                            onShare()
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

@OptIn(ExperimentalFoundationApi::class)
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
    disabled: Boolean = false,
    pingStale: Boolean = false,
    handle: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val supported = node.supports(engine) && !disabled
    val shape = RoundedCornerShape(18.dp)
    val borderColor by animateColorAsState(if (selected) Palette.Violet else Palette.Stroke, tween(300), label = "border")
    val background by animateColorAsState(if (selected) Palette.SurfaceHigh else Palette.Surface, tween(300), label = "bg")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .graphicsLayer { alpha = if (disabled) 0.55f else 1f }
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
            PingText(ping, stale = pingStale)
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
        if (handle != null) {
            Spacer(Modifier.width(8.dp))
            handle()
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
