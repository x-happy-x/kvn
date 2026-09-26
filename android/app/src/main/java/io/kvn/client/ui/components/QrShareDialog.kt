package io.kvn.client.ui.components

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.data.Qr
import io.kvn.client.ui.theme.Palette

/**
 * QR-код сервера или подписки: его сканирует любой клиент (Happ, v2rayNG,
 * Hiddify, KVN). Ссылку можно скопировать или отправить.
 */
@Composable
fun QrShareDialog(title: String, link: String, note: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bitmap = remember(link) { runCatching { Qr.encode(link).asImageBitmap() }.getOrNull() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.85f, uiSpring(), label = "qrScale")
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(250), label = "qrAlpha")
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(12.dp),
                ) {
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = "QR-код", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    } else {
                        Text("Ссылка слишком длинная для QR", color = Color.Black)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    note ?: "Отсканируйте камерой в KVN, Happ, v2rayNG или другом клиенте.",
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(link, color = Palette.TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(link))
                    // Своя же скопированная ссылка не должна открывать окно добавления.
                    context.getSharedPreferences("clipboard", android.content.Context.MODE_PRIVATE)
                        .edit().putString("last", link.trim().hashCode().toString()).apply()
                    copied = true
                }) { Text(if (copied) "Скопировано" else "Копировать") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, title))
                }) { Text("Отправить") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}
