package io.kvn.client.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kvn.client.ui.components.ErrorBanner
import io.kvn.client.ui.theme.Palette

/** Вход в sub-lab по логину и паролю. Пароль уходит только на сервер и не сохраняется. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSheet(
    initialServer: String,
    initialLogin: String,
    busy: Boolean,
    error: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (server: String, login: String, password: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var server by remember { mutableStateOf(initialServer) }
    var login by remember { mutableStateOf(initialLogin) }
    var password by remember { mutableStateOf("") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Violet,
        unfocusedBorderColor = Palette.Stroke,
        focusedContainerColor = Palette.Background,
        unfocusedContainerColor = Palette.Background,
        cursorColor = Palette.VioletSoft,
    )
    val ready = server.isNotBlank() && login.isNotBlank() && password.isNotEmpty() && !busy

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Palette.SurfaceHigh) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text("Вход в sub-lab", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Логин и пароль от вашего аккаунта. Подписки, доступные вам в sub-lab, появятся в приложении и будут обновляться вместе с ним.",
                color = Palette.TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Адрес sub-lab") },
                placeholder = { Text("sub.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = colors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text("Логин") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = colors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = colors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
            ErrorBanner(error)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSubmit(server, login, password) },
                enabled = ready,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Violet),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.TextPrimary)
                } else {
                    Text("Войти")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
