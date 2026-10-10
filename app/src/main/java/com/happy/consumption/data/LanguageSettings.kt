package com.happy.consumption.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.happy.consumption.util.LogHelper

class LanguageSettings(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("language")
    private val mutex = Mutex()
    val tag = MutableStateFlow<String?>(null)
    val failed = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (tag.value != null) return@withLock
            busy.value = true
            try {
                val language = store.data.first()[key] ?: "zh"
                check(language in listOf("zh", "en", "ja"))
                tag.value = language; failed.value = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogHelper.error("Language", "Read failed; retry required", e); failed.value = true }
            finally { busy.value = false }
        }
    }
    suspend fun select(language: String) = withContext(Dispatchers.IO) {
        require(language in listOf("zh", "en", "ja"))
        mutex.withLock {
            busy.value = true
            try {
                store.edit { it[key] = language }
                tag.value = language; failed.value = false
                LogHelper.info("Language", "Changed language=$language result=success")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogHelper.error("Language", "Save failed; retry required", e); failed.value = true }
            finally { busy.value = false }
        }
    }
}
