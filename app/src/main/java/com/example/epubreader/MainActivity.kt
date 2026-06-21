package com.example.epubreader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.epubreader.data.ReaderSettingsEntity
import com.example.epubreader.ui.ErrorDialog
import com.example.epubreader.ui.LibraryScreen
import com.example.epubreader.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { ReaderApp() }
    }
}

@Composable
private fun ReaderApp(vm: MainViewModel = viewModel()) {
    val reader by vm.reader.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val position by vm.readerPosition.collectAsStateWithLifecycle()
    val vitsModelState by vm.vitsModelState.collectAsStateWithLifecycle()
    val ttsPerformance by vm.ttsPerformance.collectAsStateWithLifecycle()
    var currentBookId by remember { mutableStateOf<String?>(null) }
    val theme = settings?.theme ?: "SYSTEM"
    val dark = theme == "DARK" || (theme == "SYSTEM" && isSystemInDarkTheme())

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        if (currentBookId == null) {
            LibraryScreen(vm, library) { id ->
                currentBookId = id
                vm.open(id)
            }
        } else {
            val s = settings ?: ReaderSettingsEntity()
            ReaderScreen(
                vm = vm,
                state = reader,
                position = position,
                settings = s,
                vitsModelState = vitsModelState,
                ttsPerformance = ttsPerformance,
                onBack = {
                    currentBookId = null
                },
            )
        }
        reader.error?.let { ErrorDialog(it, vm::clearError) }
    }
}
