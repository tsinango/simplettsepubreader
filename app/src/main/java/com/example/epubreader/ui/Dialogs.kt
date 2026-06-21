package com.example.epubreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.epubreader.MainViewModel
import com.example.epubreader.R
import com.example.epubreader.data.ReaderSettingsEntity
import com.example.epubreader.tts.VitsModelManager
import com.example.epubreader.tts.VitsModelState
import com.example.epubreader.tts.VitsModelStatus
import com.example.epubreader.tts.TtsPerformanceSnapshot

@Composable
fun SettingsDialog(
    current: ReaderSettingsEntity,
    modelState: VitsModelState,
    performance: TtsPerformanceSnapshot,
    onChange: (ReaderSettingsEntity) -> Unit,
    onUseSystemTts: () -> Unit,
    onUseVitsTts: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit,
    onExportDiagnostics: () -> Unit,
    diagnosticExportMessage: String?,
    onClearDiagnostics: () -> Unit,
    diagnosticClearMessage: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(current) }
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmClearLog by remember { mutableStateOf(false) }
    LaunchedEffect(current.ttsEngine) {
        value = value.copy(ttsEngine = current.ttsEngine)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(context.getString(R.string.reading_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(context.getString(R.string.font_size, value.fontSize.toInt()))
                Slider(value.fontSize, { value = value.copy(fontSize = it) }, valueRange = 14f..34f)
                Text(context.getString(R.string.line_height, "%.1f".format(value.lineHeight)))
                Slider(value.lineHeight, { value = value.copy(lineHeight = it) }, valueRange = 1.2f..2.2f)
                Text(context.getString(R.string.speech_rate, "%.1f".format(value.speechRate)))
                Slider(value.speechRate, { value = value.copy(speechRate = it) }, valueRange = .5f..2f)
                Text(context.getString(R.string.tts_engine_label))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        value = value.copy(ttsEngine = MainViewModel.TTS_ENGINE_SYSTEM)
                        onUseSystemTts()
                    }) {
                        Text(if (value.ttsEngine == MainViewModel.TTS_ENGINE_SYSTEM)
                            context.getString(R.string.system_tts_checked) else context.getString(R.string.system_tts))
                    }
                    TextButton(onClick = {
                        if (modelState.status == VitsModelStatus.READY) {
                            value = value.copy(ttsEngine = MainViewModel.TTS_ENGINE_VITS)
                            onUseVitsTts()
                        } else {
                            confirmDownload = true
                        }
                    }) {
                        Text(if (value.ttsEngine == MainViewModel.TTS_ENGINE_VITS)
                            context.getString(R.string.vits_tts_checked) else context.getString(R.string.vits_tts))
                    }
                }
                when (modelState.status) {
                    VitsModelStatus.DOWNLOADING -> {
                        Text(context.getString(R.string.model_downloading, modelState.progress))
                        TextButton(onClick = onCancelDownload) { Text(context.getString(R.string.cancel_download)) }
                    }
                    VitsModelStatus.READY ->
                        TextButton(onClick = onDeleteModel) { Text(context.getString(R.string.delete_model)) }
                    VitsModelStatus.FAILED -> {
                        Text(modelState.error ?: context.getString(R.string.model_not_downloaded),
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { confirmDownload = true }) { Text(context.getString(R.string.retry_download)) }
                    }
                    VitsModelStatus.NOT_DOWNLOADED -> Text(context.getString(R.string.model_not_downloaded))
                }
                if (modelState.status == VitsModelStatus.READY) {
                    Text(
                        "当前：CPU / ${performance.cpuThreads} 线程" +
                            if (performance.realTimeFactor > 0f) {
                                "，RTF ${"%.2f".format(performance.realTimeFactor)}，预取 ${"%.0f".format(performance.prefetchHitRate * 100)}%" +
                                    "，首音频 ${performance.firstAudioMillis} ms"
                            } else "",
                    )
                }
                Text(context.getString(R.string.theme_label))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("SYSTEM" to R.string.theme_system, "LIGHT" to R.string.theme_light, "DARK" to R.string.theme_dark).forEach { (id, labelRes) ->
                        TextButton(onClick = { value = value.copy(theme = id) }) {
                            Text(if (value.theme == id) "✓ ${context.getString(labelRes)}" else context.getString(labelRes))
                        }
                    }
                }
                Text(context.getString(R.string.diagnostics_label))
                TextButton(onClick = onExportDiagnostics) { Text(context.getString(R.string.export_diagnostics)) }
                diagnosticExportMessage?.let { message ->
                    Text(
                        message,
                        color = if (message.contains("失败"))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { confirmClearLog = true }) { Text(context.getString(R.string.clear_diagnostics)) }
                diagnosticClearMessage?.let { message ->
                    Text(
                        message,
                        color = if (message.contains("失败"))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onChange(value); onClose() }) { Text(context.getString(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(context.getString(R.string.cancel)) }
        },
    )
    if (confirmDownload) {
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text(context.getString(R.string.download_model)) },
            text = { Text(context.getString(R.string.download_model_message, VitsModelManager.MODEL_SIZE_LABEL)) },
            confirmButton = {
                Button(onClick = { confirmDownload = false; onUseVitsTts() }) { Text(context.getString(R.string.download)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = false }) { Text(context.getString(R.string.cancel)) }
            },
        )
    }
    if (confirmClearLog) {
        AlertDialog(
            onDismissRequest = { confirmClearLog = false },
            title = { Text(context.getString(R.string.clear_diagnostics_title)) },
            text = { Text(context.getString(R.string.clear_diagnostics_message)) },
            confirmButton = {
                Button(onClick = { confirmClearLog = false; onClearDiagnostics() }) { Text(context.getString(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLog = false }) { Text(context.getString(R.string.cancel)) }
            },
        )
    }
}

@Composable
fun SleepTimerDialog(
    minutes: Int,
    remainingMinutes: Int?,
    active: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(context.getString(R.string.sleep_timer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (active) context.getString(R.string.sleep_timer_remaining, remainingMinutes ?: minutes)
                else context.getString(R.string.sleep_timer_set, minutes))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onDecrease) { Text("-5") }
                    Text(context.getString(R.string.minutes_format, minutes), fontWeight = FontWeight.Bold)
                    Button(onClick = onIncrease) { Text("+5") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(); onClose() }) {
                Text(if (active) context.getString(R.string.reset) else context.getString(R.string.start))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) {
                    TextButton(onClick = { onCancel(); onClose() }) { Text(context.getString(R.string.turn_off)) }
                }
                TextButton(onClick = onClose) { Text(context.getString(R.string.cancel)) }
            }
        },
    )
}

@Composable
fun ErrorDialog(message: String, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(context.getString(R.string.error_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onClose) { Text(context.getString(R.string.dismiss)) }
        },
    )
}
