package com.happy.consumption.ui

import com.happy.consumption.util.LogHelper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.happy.consumption.R
import com.happy.consumption.data.UserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppViewModel(private val manager: UserManager) : ViewModel() {
    private var recordsSession: com.happy.consumption.data.UserSession? = null
    private var recordsStore = androidx.lifecycle.ViewModelStore()
    fun recordsStoreFor(current: com.happy.consumption.data.UserSession): androidx.lifecycle.ViewModelStore {
        if (recordsSession != current) { recordsStore.clear(); recordsStore = androidx.lifecycle.ViewModelStore(); recordsSession = current }
        return recordsStore
    }
    override fun onCleared() { recordsStore.clear() }
    val session = manager.session
    val ready = MutableStateFlow(false)
    val startupFailed = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Int?>(null)
    val registrationVersion = MutableStateFlow(0)
    init {
        initialize()
        viewModelScope.launch { session.collect { current ->
            if (recordsSession != null && current != recordsSession) { recordsStore.clear(); recordsSession = null }
        } }
    }
    fun initialize() {
        if (busy.value) return
        busy.value = true
        startupFailed.value = false
        viewModelScope.launch {
            try { manager.initialize(); ready.value = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogHelper.error("AppViewModel", "Operation failed; recovery preserved", e); startupFailed.value = true }
            finally { busy.value = false }
        }
    }
    private fun operate(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        message.value = null
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogHelper.error("AppViewModel", "Operation failed; recovery preserved", e); message.value = errorMessage(e) }
            finally { busy.value = false }
        }
    }
    fun register(name: String, password: String, confirmation: String) {
        if (password != confirmation) { message.value = R.string.password_mismatch; return }
        operate { manager.register(name, password); registrationVersion.value++; message.value = R.string.registration_success }
    }
    fun login(name: String, password: String) = operate { manager.login(name, password) }
    fun logout() = operate { manager.logout() }
    fun deleteUser(expected: com.happy.consumption.data.UserSession, password: String) = operate { manager.deleteCurrentUser(expected, password) }
}
