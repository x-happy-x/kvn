package io.kvn.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.core.Engine
import io.kvn.client.ui.components.ErrorBanner
import io.kvn.client.ui.theme.Palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionSheet(
    initialUrl: String,
    initialName: String = "",
    initialEngine: Engine? = null,
    source: String? = null,
    busy: Boolean,
    error: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (input: String, name: String, engine: Engine?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf(initialName) }
    var engine by remember { mutableStateOf(initialEngine) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Violet,
        unfocusedBorderColor = Palette.Stroke,
        focusedContainerColor = Palette.Background,
        unfocusedContainerColor = Palette.Background,
        cursorColor = Palette.VioletSoft,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.SurfaceHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text("Новая подписка", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                source?.let { "Ссылка из $it. Проверьте и добавьте." }
                    ?: "Ссылка на подписку, ссылка другого клиента (happ://, clash://…) или сами серверы. Формат определится автоматически.",
                color = Palette.TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("https://… или vless://…") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название (необязательно)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
            )
            Spacer(Modifier.height(14.dp))
            Text("Ядро для этой подписки", color = Palette.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Engine?>(null, Engine.XRAY, Engine.MIHOMO).forEach { option ->
                    FilterChip(
                        selected = engine == option,
                        onClick = { engine = option },
                        label = { Text(option?.title ?: "Текущее") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.Violet,
                            selectedLabelColor = Palette.TextPrimary,
                            containerColor = Palette.Background,
                            labelColor = Palette.TextSecondary,
                        ),
                    )
                }
            }
            Text(
                if (engine == null) "Серверы работают на ядре, выбранном в настройках." else "При подключении к серверам этой подписки включится ${engine?.title}.",
                color = Palette.TextMuted,
                fontSize = 12.sp,
            )
            ErrorBanner(error)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { input = it.trim() } },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp), tint = Palette.TextSecondary)
                    Spacer(Modifier.size(8.dp))
                    Text("Из буфера", color = Palette.TextPrimary)
                }
                Button(
                    onClick = { onSubmit(input, name, engine) },
                    enabled = input.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Violet),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.TextPrimary)
                    } else {
                        Text("Добавить")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
