package com.happy.consumption.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.happy.consumption.R
import com.happy.consumption.data.*
import com.happy.consumption.util.LogHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream

class BackupViewModel(private val manager: UserManager, private val resolver: ContentResolver) : ViewModel() {
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Int?>(null)
    private var password: CharArray? = null
    private var owner: UserSession? = null
    fun prepare(secret: String, exporting: Boolean): Boolean {
        if (busy.value) return false
        if (secret.length < 8) { message.value = R.string.backup_short_password; return false }
        owner = manager.session.value
        if (exporting && owner == null) { message.value = R.string.session_expired; return false }
        password = secret.toCharArray(); busy.value = true; message.value = null
        return true
    }
    fun cancelPending() { password?.fill('\u0000'); password = null; owner = null; busy.value = false }
    override fun onCleared() { cancelPending() }
    fun document(uri: Uri?, exporting: Boolean) {
        if (uri == null) { cancelPending(); return }
        val secret = password ?: return
        val expected = owner
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (exporting) {
                        val payload = manager.exportBackup(expected ?: throw AccessDeniedException())
                        val encrypted = try { BackupCipher.encrypt(payload, secret) } finally { payload.fill(0) }
                        try { resolver.openOutputStream(uri, "wt")?.use { it.write(encrypted) }
                            ?: throw java.io.IOException("Backup output unavailable") }
                        finally { encrypted.fill(0) }
                    } else {
                        val encrypted = resolver.openInputStream(uri)?.use { input ->
                            val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                if (result.size() + count > BackupCipher.MAX_FILE_BYTES) throw UserOperationException(UserError.BACKUP_INVALID)
                                result.write(buffer, 0, count)
                            }; result.toByteArray()
                        } ?: throw java.io.IOException("Backup input unavailable")
                        val payload = try { BackupCipher.decrypt(encrypted, secret) }
                            catch (_: Exception) { throw UserOperationException(UserError.BACKUP_INVALID) }
                            finally { encrypted.fill(0) }
                        try { manager.importBackup(payload) } finally { payload.fill(0) }
                    }
                }
                message.value = R.string.backup_success
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LogHelper.error("Backup", "Operation failed; retry required", e)
                message.value = when (e) {
                    is UserOperationException, is AccessDeniedException -> errorMessage(e)
                    is IllegalArgumentException -> R.string.backup_invalid
                    else -> R.string.backup_io_error
                }
            } finally { cancelPending() }
        }
    }
}

@Composable fun BackupControl(model: BackupViewModel, loggedIn: Boolean) {
    var choosing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Boolean?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val busy by model.busy.collectAsState(); val message by model.message.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { model.document(it, true) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.document(it, false) }
    Column {
        TextButton(onClick = { model.message.value = null; choosing = true }, enabled = !busy, modifier = Modifier.testTag("backup_control")) { Text(stringResource(R.string.backup)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message != null && exporting == null) Text(stringResource(message!!))
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.backup)) },
        text = { Column {
            if (loggedIn) TextButton(onClick = { choosing = false; exporting = true }) { Text(stringResource(R.string.backup_export)) }
            TextButton(onClick = { choosing = false; exporting = false }) { Text(stringResource(R.string.backup_import)) }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } })
    exporting?.let { export ->
        fun close() { exporting = null; password = ""; confirmation = "" }
        AlertDialog(onDismissRequest = ::close, title = { Text(stringResource(if (export) R.string.backup_export else R.string.backup_import)) },
            text = { Column {
                Text(stringResource(if (export) R.string.backup_hint else R.string.backup_import_hint))
                OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.backup_password)) }, modifier = Modifier.testTag("backup_password"))
                if (export) OutlinedTextField(confirmation, { confirmation = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.backup_password_confirm)) }, modifier = Modifier.testTag("backup_password_confirm"))
                if (message != null) Text(stringResource(message!!), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                if (export && password != confirmation) model.message.value = R.string.password_mismatch
                else if (model.prepare(password, export)) {
                    close()
                    if (export) save.launch("happy-consumption-1.1.hcrbackup") else open.launch(arrayOf("application/octet-stream", "*/*"))
                }
            }, enabled = !busy) { Text(stringResource(if (export) R.string.backup_export else R.string.backup_import)) } },
            dismissButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.cancel)) } })
    }
}
