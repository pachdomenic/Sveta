package com.tuki.sveta.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tuki.sveta.AppContainer
import com.tuki.sveta.data.store.SecretStore
import com.tuki.sveta.data.store.Settings
import com.tuki.sveta.ui.components.Hint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Account fields shared by onboarding and settings. Saving triggers a fresh login. */
@Composable
fun EcoflowForm(app: AppContainer, settings: Settings, modifier: Modifier = Modifier, showHost: Boolean = true, onSaved: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf(settings.ecoflowEmail) }
    var serial by rememberSaveable { mutableStateOf(settings.ecoflowSerial) }
    var host by rememberSaveable { mutableStateOf(settings.ecoflowHost) }
    // Never prefilled: the stored password is only ever sent to EcoFlow, not shown back.
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val hasStored = remember(settings.ecoflowEmail) { app.secrets.has(SecretStore.ECOFLOW_PASSWORD) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("E-mail акаунта EcoFlow") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(if (hasStored) "Пароль (збережено, введи щоб змінити)" else "Пароль") },
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Сховати пароль" else "Показати пароль")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = serial, onValueChange = { serial = it.uppercase().filter { c -> c.isLetterOrDigit() } },
            label = { Text("Серійний номер станції") },
            supportingText = { Text("У застосунку EcoFlow: станція → налаштування → «Про пристрій». Для DELTA Max починається з DAEB.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showHost) {
            OutlinedTextField(
                value = host, onValueChange = { host = it.trim() },
                label = { Text("Сервер API") },
                supportingText = { Text("Типовий: ${Settings.DEFAULT_ECOFLOW_HOST}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Hint("Пароль зберігається лише на цьому пристрої в зашифрованому вигляді й надсилається тільки на сервер EcoFlow.")
        Button(
            enabled = email.isNotBlank() && serial.length >= 8 && (password.isNotEmpty() || hasStored),
            onClick = {
                scope.launch {
                    if (password.isNotEmpty()) withContext(Dispatchers.IO) { app.secrets.put(SecretStore.ECOFLOW_PASSWORD, password) }
                    app.settings.update {
                        it.copy(ecoflowEmail = email.trim(), ecoflowSerial = serial.trim(), ecoflowHost = host.ifBlank { Settings.DEFAULT_ECOFLOW_HOST })
                    }
                    password = ""
                    app.ecoflow.reconnect()
                    onSaved()
                }
            },
        ) { Text("Зберегти й підключитись") }
    }
}
