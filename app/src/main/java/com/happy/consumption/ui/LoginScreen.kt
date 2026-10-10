package com.happy.consumption.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.happy.consumption.R

@Composable private fun PasswordField(value: String, change: (String) -> Unit, label: Int, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, change, label = { Text(stringResource(label)) }, singleLine = true, enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(stringResource(if (visible) R.string.hide_password else R.string.show_password)) } })
}
@Composable fun LoginScreen(auth: AppViewModel, language: com.happy.consumption.data.LanguageSettings) {
    var name by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }; var newName by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }
    val busy by auth.busy.collectAsState(); val message by auth.message.collectAsState()
    val version by auth.registrationVersion.collectAsState()
    LaunchedEffect(version) { if (version > 0) { creating = false; newPassword = ""; confirmation = "" } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        LanguageControl(language)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        PasswordField(password, { password = it }, R.string.password, !busy)
        if (message != null) Text(stringResource(message!!), color = MaterialTheme.colorScheme.error)
        Button(onClick = { auth.login(name, password) }, enabled = !busy) { Text(stringResource(R.string.login)) }
        TextButton(onClick = { auth.message.value = null; creating = true }, enabled = !busy) { Text(stringResource(R.string.create_user)) }
        if (busy) CircularProgressIndicator()
    }
    if (creating) AlertDialog(onDismissRequest = { if (!busy) { creating = false; newPassword = ""; confirmation = "" } }, title = { Text(stringResource(R.string.create_user)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true, enabled = !busy)
            PasswordField(newPassword, { newPassword = it }, R.string.password, !busy)
            PasswordField(confirmation, { confirmation = it }, R.string.confirm_password, !busy)
            if (message != null) Text(stringResource(message!!), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { auth.register(newName, newPassword, confirmation) }, enabled = !busy) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = { creating = false; newPassword = ""; confirmation = ""; auth.message.value = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
