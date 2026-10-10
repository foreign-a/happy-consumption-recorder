package com.happy.consumption

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.happy.consumption.data.*
import com.happy.consumption.util.LogHelper
import kotlinx.coroutines.*

private val Context.userDataStore by preferencesDataStore(name = "users")
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class HappyApplication : Application() {
    val language by lazy { LanguageSettings(settingsDataStore) }
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        LogHelper.initialize(filesDir)
        startupScope.launch { language.load() }
    }
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "consumption.db").build() }
    val userManager by lazy { UserManager(userDataStore, database, filesDir) }
}
