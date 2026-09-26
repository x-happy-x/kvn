package io.kvn.client.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.data.DNS_PRESETS
import io.kvn.client.data.DnsCheck
import io.kvn.client.data.DnsPreset
import io.kvn.client.ui.components.pressable
import io.kvn.client.ui.theme.Palette

/**
 * Выбор DNS: готовые серверы (обычные, DoH, DoT) и свой адрес. «Проверить»
 * замеряет каждый напрямую и, если VPN подключён, через туннель.
 */
@Composable
fun DnsScreen(
    current: String,
    checks: Map<String, DnsCheck>,
    checking: Boolean,
    vpnConnected: Boolean,
    domain: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onCheck: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var custom by remember { mutableStateOf(if (DNS_PRESETS.any { it.address == current }) "" else current) }
    val entries = remember(current) {
        if (DNS_PRESETS.any { it.address == current }) DNS_PRESETS else listOf(DnsPreset("Свой", current)) + DNS_PRESETS
    }

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
            Text("DNS", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Violet)
                    .pressable(enabled = !checking, onClick = onCheck)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.TextPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (checking) "Проверяю…" else "Проверить все", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            "Через этот DNS ядро ищет адреса сайтов внутри VPN. Проверка спрашивает у каждого адрес «$domain»" +
                if (vpnConnected) " — напрямую и через VPN." else ". Подключите VPN, чтобы проверить и через него.",
            color = Palette.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.address }) { preset ->
                DnsRow(
                    modifier = Modifier.animateItem(),
                    preset = preset,
                    selected = preset.address == current,
                    direct = checks[preset.address],
                    viaVpn = checks["${preset.address}|vpn"],
                    onClick = { onSelect(preset.address) },
                )
            }
            item(key = "custom") {
                Column(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        label = { Text("Свой DNS") },
                        placeholder = { Text("1.2.3.4, tcp://…, tls://… или https://…/dns-query") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Palette.Violet,
                            unfocusedBorderColor = Palette.Stroke,
                            focusedContainerColor = Palette.Surface,
                            unfocusedContainerColor = Palette.Surface,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Использовать",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (custom.isBlank()) Palette.SurfaceHighest else Palette.Violet)
                            .pressable(enabled = custom.isNotBlank()) { onSelect(custom.trim()) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "DoT (tls://) работает только в Mihomo; в Xray для него используется обычный DNS того же сервера.",
                        color = Palette.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DnsRow(
    modifier: Modifier,
    preset: DnsPreset,
    selected: Boolean,
    direct: DnsCheck?,
    viaVpn: DnsCheck?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val border by animateColorAsState(if (selected) Palette.Violet else Palette.Stroke, tween(250), label = "dnsBorder")
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Palette.SurfaceHigh else Palette.Surface)
            .border(1.dp, border, shape)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(preset.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                preset.address,
                color = Palette.TextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            CheckLabel("напрямую", direct)
            if (viaVpn != null) CheckLabel("через VPN", viaVpn)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.CheckCircle, contentDescription = "Выбран", tint = Palette.Violet, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CheckLabel(title: String, check: DnsCheck?) {
    if (check == null) return
    Text(
        if (check.ok) "$title ${check.ms} мс" else "$title: ${check.error ?: "нет ответа"}",
        color = if (check.ok) Palette.Green else Palette.Red,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(150.dp),
    )
}
