package io.kvn.client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.kvn.client.data.AppMode
import io.kvn.client.data.AppSettings
import io.kvn.client.data.Whitelist
import io.kvn.client.ui.components.Chip
import io.kvn.client.ui.components.pressable
import io.kvn.client.ui.InstalledApp
import io.kvn.client.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Выборочное проксирование: режим и список приложений. */
@Composable
fun AppsScreen(
    settings: AppSettings,
    apps: List<InstalledApp>?,
    onBack: () -> Unit,
    onMode: (AppMode) -> Unit,
    onToggle: (String) -> Unit,
    onExcludeWhitelist: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = Palette.TextSecondary)
            }
            Text("Приложения", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.appMode == mode,
                    onClick = { onMode(mode) },
                    label = { Text(mode.title, fontSize = 12.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.Violet,
                        selectedLabelColor = Palette.TextPrimary,
                        containerColor = Palette.Surface,
                        labelColor = Palette.TextSecondary,
                    ),
                )
            }
        }
        Text(
            when (settings.appMode) {
                AppMode.ALL -> "Через VPN идут все приложения. Выберите режим, чтобы отметить исключения."
                AppMode.ONLY -> "Через VPN пойдут только отмеченные приложения, остальные — напрямую."
                AppMode.EXCEPT -> "Отмеченные приложения пойдут напрямую, остальные — через VPN."
            },
            color = Palette.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        WhitelistBanner(settings, apps, onExcludeWhitelist)

        if (settings.appMode != AppMode.ALL) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Palette.TextMuted) },
                placeholder = { Text("Поиск") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Violet,
                    unfocusedBorderColor = Palette.Stroke,
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                ),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showSystem = !showSystem }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = showSystem, onCheckedChange = { showSystem = it }, colors = checkboxColors())
                Text("Показывать системные", color = Palette.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("Отмечено: ${settings.apps.size}", color = Palette.TextMuted, fontSize = 12.sp)
            }

            if (apps == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.VioletSoft)
                }
                return@Column
            }
            val needle = query.trim().lowercase()
            val visible = apps.filter { app ->
                (showSystem || !app.system || app.packageName in settings.apps) &&
                    (needle.isEmpty() || app.label.lowercase().contains(needle) || app.packageName.contains(needle))
            }.sortedWith(
                compareByDescending<InstalledApp> { it.packageName in settings.apps }
                    .thenByDescending { it.packageName in Whitelist.PRESET_PACKAGES },
            )
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(app, checked = app.packageName in settings.apps) { onToggle(app.packageName) }
                }
            }
        }
    }
}

/**
 * Быстрый выбор приложений из белых списков (банки, Госуслуги, маркетплейсы):
 * одним нажатием они идут мимо VPN.
 */
@Composable
private fun WhitelistBanner(settings: AppSettings, apps: List<InstalledApp>?, onExclude: () -> Unit) {
    val installed = apps?.filter { it.packageName in Whitelist.PRESET_PACKAGES } ?: return
    if (installed.isEmpty()) return
    val direct = installed.count { app ->
        when (settings.appMode) {
            AppMode.ALL -> false
            AppMode.EXCEPT -> app.packageName in settings.apps
            AppMode.ONLY -> app.packageName !in settings.apps
        }
    }
    val done = direct == installed.size
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .padding(14.dp),
    ) {
        Text("Приложения из белых списков", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Банки, Госуслуги, маркетплейсы и соцсети часто не работают через VPN. Установлено: " +
                installed.joinToString(", ") { app -> Whitelist.PRESET_APPS.first { it.packageName == app.packageName }.title },
            color = Palette.TextSecondary,
            fontSize = 12.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            if (done) "✓ Все идут напрямую" else "Пустить напрямую (${installed.size - direct})",
            color = if (done) Palette.Green else Palette.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (done) Palette.Green.copy(alpha = 0.12f) else Palette.Violet)
                .pressable(enabled = !done, onClick = onExclude)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun checkboxColors() = CheckboxDefaults.colors(
    checkedColor = Palette.Violet,
    uncheckedColor = Palette.TextMuted,
    checkmarkColor = Palette.TextPrimary,
)

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.packageName in Whitelist.PRESET_PACKAGES) {
                    Chip("белый список", color = Palette.Green)
                    Spacer(Modifier.width(6.dp))
                }
                Text(app.packageName, color = Palette.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onClick() }, colors = checkboxColors())
    }
}

/** Значок приложения грузится лениво и только для видимых строк. */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.SurfaceHighest),
        contentAlignment = Alignment.Center,
    ) {
        icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp)) }
    }
}
