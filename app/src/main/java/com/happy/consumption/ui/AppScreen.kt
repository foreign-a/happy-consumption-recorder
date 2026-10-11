package com.happy.consumption.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.happy.consumption.HappyApplication
import com.happy.consumption.R
import com.happy.consumption.data.UserRepository

private fun <T : ViewModel> factory(build: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = build() as V
}
@Composable fun AppScreen(app: HappyApplication) {
    val auth: AppViewModel = viewModel(factory = remember { factory { AppViewModel(app.userManager) } })
    val backup: BackupViewModel = viewModel(factory = remember { factory { BackupViewModel(app.userManager, app.contentResolver) } })
    val ready by auth.ready.collectAsState()
    val failed by auth.startupFailed.collectAsState()
    val session by auth.session.collectAsState()
    if (!ready) Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.storage_error))
            Button(onClick = auth::initialize) { Text(stringResource(R.string.retry)) }
        } else CircularProgressIndicator()
    } else if (session == null) LoginScreen(auth, app.language, backup)
    else {
        val current = session!!
        key(current.generation) {
            val currency by remember(current) { app.userManager.currencyFor(current) }.collectAsState(initial = com.happy.consumption.data.MoneyUnit.YUAN)
            val store = remember(current) { auth.recordsStoreFor(current) }
            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = store } }
            val records: AllRecordsViewModel = viewModel(viewModelStoreOwner = owner,
                factory = remember { factory { AllRecordsViewModel(UserRepository(app.userManager, app.database, current)) } })
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle, records) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) records.refreshToday() }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            CompositionLocalProvider(LocalMoneyUnit provides currency) {
                AllRecordsScreen(records, current.username, auth, app.language, backup)
            }
        }
    }
}
