package io.kvn.client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.data.AppSettings
import io.kvn.client.data.WifiMode
import io.kvn.client.ui.components.Panel
import io.kvn.client.ui.components.SectionTitle
import io.kvn.client.ui.theme.Palette

/**
 * Пауза VPN в Wi-Fi. В доверенной сети туннель снимается и поднимается снова,
 * когда телефон из неё уходит. Имя сети Android отдаёт только с доступом к
 * геопозиции, а в фоне — с доступом «всегда».
 */
@Composable
fun WifiScreen(
    settings: AppSettings,
    hasLocation: Boolean,
    hasBackgroundLocation: Boolean,
    currentNetwork: () -> String?,
    onBack: () -> Unit,
    onMode: (WifiMode) -> Unit,
    onNetworks: (Set<String>) -> Unit,
    onRequestLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var manual by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = Palette.TextSecondary)
            }
            Text("Пауза в Wi-Fi", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Дома или на работе VPN может быть не нужен: в выбранных сетях туннель встаёт на паузу, а за их пределами включается снова.",
            color = Palette.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionTitle("Когда ставить на паузу")
            Panel(Modifier.fillMaxWidth()) {
                WifiMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onMode(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.wifiMode == mode,
                            onClick = { onMode(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = Palette.Violet, unselectedColor = Palette.TextMuted),
                        )
                        Text(mode.title, fontSize = 15.sp)
                    }
                }
            }

            if (settings.wifiMode == WifiMode.LIST) {
                SectionTitle("Доступ к имени сети")
                Panel(Modifier.fillMaxWidth()) {
                    PermissionRow(
                        granted = hasLocation,
                        title = "Геопозиция",
                        subtitle = "Без неё Android скрывает имя сети Wi-Fi",
                        onRequest = onRequestLocation,
                    )
                    Divider()
                    PermissionRow(
                        granted = hasBackgroundLocation,
                        title = "Геопозиция в фоне",
                        subtitle = "Выберите «Разрешить всегда», чтобы пауза срабатывала при свёрнутом приложении",
                        onRequest = onRequestBackgroundLocation,
                    )
                }

                SectionTitle("Доверенные сети")
                Panel(Modifier.fillMaxWidth()) {
                    if (settings.wifiNetworks.isEmpty()) {
                        Text("Пока ни одной сети", color = Palette.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    settings.wifiNetworks.sorted().forEach { network ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RowIcon(Icons.Rounded.Wifi)
                            Spacer(Modifier.width(12.dp))
                            Text(network, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onNetworks(settings.wifiNetworks - network) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Убрать", tint = Palette.TextMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val name = currentNetwork()
                            hint = when {
                                name != null -> {
                                    onNetworks(settings.wifiNetworks + name)
                                    null
                                }
                                !hasLocation -> "Сначала разрешите доступ к геопозиции"
                                else -> "Не удалось узнать имя сети: телефон не в Wi-Fi или выключена геолокация"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Добавить текущую сеть", color = Palette.TextPrimary)
                    }
                    hint?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Palette.Amber, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            placeholder = { Text("Имя сети (SSID)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Palette.Violet,
                                unfocusedBorderColor = Palette.Stroke,
                            ),
                        )
                        TextButton(
                            onClick = {
                                val name = manual.trim()
                                if (name.isNotEmpty()) onNetworks(settings.wifiNetworks + name)
                                manual = ""
                            },
                        ) { Text("Добавить") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionRow(granted: Boolean, title: String, subtitle: String, onRequest: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onRequest)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(Icons.Rounded.LocationOn)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Palette.TextSecondary, fontSize = 12.sp)
        }
        Text(
            if (granted) "есть" else "разрешить",
            color = if (granted) Palette.Green else Palette.VioletSoft,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
