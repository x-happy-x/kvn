package io.kvn.client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.data.AppSettings
import io.kvn.client.data.Whitelist
import io.kvn.client.ui.components.Panel
import io.kvn.client.ui.components.plural
import io.kvn.client.ui.components.pressable
import io.kvn.client.ui.theme.Palette

/**
 * Белый список сайтов: они идут мимо VPN вместе с поддоменами. Список
 * редактируется — можно вставить сразу много строк, в том числе в формате
 * Clash (`- +.wb.ru`).
 */
@Composable
fun WhitelistScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onDirectRu: (Boolean) -> Unit,
    onAdd: (String) -> Int,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Violet,
        unfocusedBorderColor = Palette.Stroke,
        focusedContainerColor = Palette.Surface,
        unfocusedContainerColor = Palette.Surface,
    )

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = Palette.TextSecondary)
            }
            Text("Сайты напрямую", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmReset = true }) { Text("Сбросить", color = Palette.TextMuted) }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "toggles") {
                Panel(Modifier.fillMaxWidth()) {
                    ToggleRow(
                        Icons.Rounded.Public,
                        "Белый список напрямую",
                        "Банки, Госуслуги, маркетплейсы и другие сайты ниже — мимо VPN",
                        settings.whitelistEnabled,
                        onEnabled,
                    )
                    Divider()
                    ToggleRow(
                        Icons.Rounded.Flag,
                        "Все сайты .ru, .рф, .su",
                        "Грубее: напрямую пойдёт вся российская зона",
                        settings.directRu,
                        onDirectRu,
                    )
                }
            }
            item(key = "add") {
                Column(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Добавить сайты") },
                        placeholder = { Text("example.ru — можно списком, по строке") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors,
                        maxLines = 5,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Добавить",
                            color = Palette.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (input.isBlank()) Palette.SurfaceHighest else Palette.Violet)
                                .pressable(enabled = input.isNotBlank()) {
                                    val count = onAdd(input)
                                    added = if (count > 0) "Добавлено: $count" else "Уже в списке или не похоже на адрес сайта"
                                    if (count > 0) input = ""
                                }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        added?.let { Text(it, color = Palette.TextMuted, fontSize = 12.sp) }
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Palette.TextMuted) },
                    placeholder = { Text("Поиск по списку") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                )
                val count = settings.whitelistDomains.size.toLong()
                Text(
                    "$count ${plural(count, "сайт", "сайта", "сайтов")} · каждый вместе с поддоменами",
                    color = Palette.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            val needle = query.trim().lowercase()
            val visible = settings.whitelistDomains.filter { needle.isEmpty() || it.contains(needle) }
            items(visible, key = { it }) { domain ->
                Row(
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Surface)
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        domain,
                        fontSize = 14.sp,
                        color = if (settings.whitelistEnabled) Palette.TextPrimary else Palette.TextMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (domain !in Whitelist.DEFAULT_DOMAINS) {
                        Text("свой", color = Palette.Cyan, fontSize = 11.sp)
                    }
                    IconButton(onClick = { onRemove(domain) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Убрать", tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(if (needle.isEmpty()) "Список пуст" else "Ничего не найдено", color = Palette.TextMuted)
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.SurfaceHigh,
            title = { Text("Вернуть стандартный список?") },
            text = { Text("Свои сайты пропадут, удалённые из стандартного списка — вернутся.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}
