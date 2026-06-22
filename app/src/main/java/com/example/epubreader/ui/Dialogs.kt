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
import com.example.epubreader.tts.EmbeddedModelRegistry
import com.example.epubreader.tts.KokoroModelRegistry
import com.example.epubreader.tts.SpeakerEntry
import com.example.epubreader.tts.SpeakerGender
import com.example.epubreader.tts.TtsEngineKind
import com.example.epubreader.tts.TtsModelPackDescriptor
import com.example.epubreader.tts.TtsPerformanceSnapshot
import com.example.epubreader.tts.VitsModelId
import com.example.epubreader.tts.VitsModelState
import com.example.epubreader.tts.VitsModelStatus

@Composable
fun SettingsDialog(
    current: ReaderSettingsEntity,
    modelStates: Map<VitsModelId, VitsModelState>,
    performance: TtsPerformanceSnapshot,
    onChange: (ReaderSettingsEntity) -> Unit,
    onUseSystemTts: () -> Unit,
    onUseVitsModel: (VitsModelId) -> Unit,
    onCancelVitsDownload: (VitsModelId) -> Unit,
    onDeleteVitsModel: (VitsModelId) -> Unit,
    getEmbeddedSpeakerId: (VitsModelId) -> Int,
    getEmbeddedRate: (VitsModelId) -> Float,
    onSetEmbeddedSpeakerId: (VitsModelId, Int) -> Unit,
    onSetEmbeddedRate: (VitsModelId, Float) -> Unit,
    onExportDiagnostics: () -> Unit,
    diagnosticExportMessage: String?,
    onClearDiagnostics: () -> Unit,
    diagnosticClearMessage: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(current) }
    var confirmDownloadModel by remember { mutableStateOf<VitsModelId?>(null) }
    var confirmClearLog by remember { mutableStateOf(false) }
    var kokoroSpeakerPickerForId by remember { mutableStateOf<VitsModelId?>(null) }
    LaunchedEffect(current.ttsEngine, current.vitsModelId) {
        value = value.copy(ttsEngine = current.ttsEngine, vitsModelId = current.vitsModelId)
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
                Text("句号停顿：${value.strongPauseMs}ms")
                Slider(value.strongPauseMs.toFloat(), { value = value.copy(strongPauseMs = it.toInt()) }, valueRange = 10f..1000f, steps = 98)
                Text("分号停顿：${value.semicolonPauseMs}ms")
                Slider(value.semicolonPauseMs.toFloat(), { value = value.copy(semicolonPauseMs = it.toInt()) }, valueRange = 10f..1000f, steps = 98)
                Text("逗号停顿：${value.commaPauseMs}ms")
                Slider(value.commaPauseMs.toFloat(), { value = value.copy(commaPauseMs = it.toInt()) }, valueRange = 10f..1000f, steps = 98)
                Text("顿号停顿：${value.ideographicCommaPauseMs}ms")
                Slider(value.ideographicCommaPauseMs.toFloat(), { value = value.copy(ideographicCommaPauseMs = it.toInt()) }, valueRange = 10f..1000f, steps = 98)
                Text("默认停顿：${value.defaultPauseMs}ms")
                Slider(value.defaultPauseMs.toFloat(), { value = value.copy(defaultPauseMs = it.toInt()) }, valueRange = 10f..1000f, steps = 98)
                Text(context.getString(R.string.tts_engine_label))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        value = value.copy(ttsEngine = MainViewModel.TTS_ENGINE_SYSTEM)
                        onUseSystemTts()
                    }) {
                        Text(if (value.ttsEngine == MainViewModel.TTS_ENGINE_SYSTEM)
                            context.getString(R.string.system_tts_checked) else context.getString(R.string.system_tts))
                    }
                }
                EmbeddedModelRegistry.all.forEach { descriptor ->
                    EmbeddedModelSection(
                        descriptor = descriptor,
                        state = modelStates[descriptor.id] ?: VitsModelState(VitsModelStatus.NOT_DOWNLOADED),
                        selected = value.ttsEngine == MainViewModel.TTS_ENGINE_VITS &&
                            value.vitsModelId == descriptor.id.stableValue,
                        performance = performance,
                        currentSid = getEmbeddedSpeakerId(descriptor.id),
                        currentRate = getEmbeddedRate(descriptor.id),
                        onSelectAttempt = {
                            val st = modelStates[descriptor.id]?.status ?: VitsModelStatus.NOT_DOWNLOADED
                            if (st == VitsModelStatus.READY) {
                                value = value.copy(
                                    ttsEngine = MainViewModel.TTS_ENGINE_VITS,
                                    vitsModelId = descriptor.id.stableValue,
                                )
                                onUseVitsModel(descriptor.id)
                                } else {
                                confirmDownloadModel = descriptor.id
                            }
                        },
                        onDownload = { onUseVitsModel(descriptor.id) },
                        onCancel = { onCancelVitsDownload(descriptor.id) },
                        onDelete = { onDeleteVitsModel(descriptor.id) },
                        onSetSpeakerId = { sid -> onSetEmbeddedSpeakerId(descriptor.id, sid) },
                        setRate = { rate -> onSetEmbeddedRate(descriptor.id, rate) },
                        onPickSpeaker = { kokoroSpeakerPickerForId = descriptor.id },
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
    confirmDownloadModel?.let { id ->
        val descriptor = EmbeddedModelRegistry.byId(id)
        AlertDialog(
            onDismissRequest = { confirmDownloadModel = null },
            title = { Text(context.getString(R.string.download_model)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.getString(R.string.download_model_message, descriptor.sizeLabel))
                    Text(
                        descriptor.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (descriptor.engineKind == TtsEngineKind.BERT_VITS2_MNN) {
                        Text(
                            "示例角色资产来自上游 Bert-VITS2-MNN（仅供学习交流，禁商业用途）",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "License: ${descriptor.license}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { confirmDownloadModel = null; onUseVitsModel(id) }) {
                    Text(context.getString(R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownloadModel = null }) { Text(context.getString(R.string.cancel)) }
            },
        )
    }
    kokoroSpeakerPickerForId?.let { id ->
        val descriptor = EmbeddedModelRegistry.byId(id)
        val speakers = descriptor.speakerMetadata.orEmpty()
        val zhSpeakers = speakers.filter { it.language == "ZH" }
        KokoroSpeakerPicker(
            speakers = zhSpeakers,
            currentSid = getEmbeddedSpeakerId(id),
            onPick = { sid ->
                onSetEmbeddedSpeakerId(id, sid)
                kokoroSpeakerPickerForId = null
            },
            onDismiss = { kokoroSpeakerPickerForId = null },
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
private fun EmbeddedModelSection(
    descriptor: TtsModelPackDescriptor,
    state: VitsModelState,
    selected: Boolean,
    performance: TtsPerformanceSnapshot,
    currentSid: Int,
    currentRate: Float,
    onSelectAttempt: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSetSpeakerId: (Int) -> Unit,
    setRate: (Float) -> Unit,
    onPickSpeaker: () -> Unit,
) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onSelectAttempt) {
            Text(if (selected) "✓ ${descriptor.id.displayName}" else descriptor.id.displayName)
        }
    }
    Text(
        descriptor.description,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (state.status) {
        VitsModelStatus.DOWNLOADING -> {
            Text(context.getString(R.string.model_downloading, state.progress))
            TextButton(onClick = onCancel) { Text(context.getString(R.string.cancel_download)) }
        }
        VitsModelStatus.READY -> {
            TextButton(onClick = onDelete) {
                Text(context.getString(R.string.delete_model_label, descriptor.sizeLabel))
            }
            if (descriptor.engineKind == TtsEngineKind.SHERPA_KOKORO && selected) {
                KokoroSelectionUi(
                    speakers = descriptor.speakerMetadata.orEmpty(),
                    currentSid = currentSid,
                    currentRate = currentRate,
                    setSpeakerId = onSetSpeakerId,
                    setRate = setRate,
                    onPickSpeaker = onPickSpeaker,
                )
            }
            if (selected && performance.modelId == descriptor.id.stableValue) {
                Text(
                    "当前：CPU / ${performance.cpuThreads} 线程" +
                        if (performance.realTimeFactor > 0f) {
                            "，RTF ${"%.2f".format(performance.realTimeFactor)}，预取 ${"%.0f".format(performance.prefetchHitRate * 100)}%" +
                                "，首音频 ${performance.firstAudioMillis} ms"
                        } else "",
                )
            }
        }
        VitsModelStatus.FAILED -> {
            Text(
                state.error ?: context.getString(R.string.model_not_downloaded),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDownload) { Text(context.getString(R.string.retry_download)) }
        }
        VitsModelStatus.NOT_DOWNLOADED -> {
            Text(context.getString(R.string.model_not_downloaded))
            TextButton(onClick = onDownload) { Text(context.getString(R.string.download)) }
        }
    }
}

@Composable
private fun KokoroSelectionUi(
    speakers: List<SpeakerEntry>,
    currentSid: Int,
    currentRate: Float,
    setSpeakerId: (Int) -> Unit,
    setRate: (Float) -> Unit,
    onPickSpeaker: () -> Unit,
) {
    var rate by remember(currentRate) { mutableStateOf(currentRate) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onPickSpeaker) { Text("中文说话人选择…") }
        Text("Kokoro 速度 ${"%.1f".format(rate)}x")
        Slider(rate, { rate = it; setRate(it) }, valueRange = 0.5f..2f)
    }
}

@Composable
private fun KokoroSpeakerPicker(
    speakers: List<SpeakerEntry>,
    currentSid: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择中文说话人") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column {
                    Text("女声（${speakers.count { it.gender == SpeakerGender.FEMALE }}）", fontWeight = FontWeight.Bold)
                    speakers.filter { it.gender == SpeakerGender.FEMALE }.forEach { s ->
                        TextButton(onClick = { onPick(s.id) }) {
                            Text(
                                if (s.id == currentSid) "✓ ${s.id}: ${s.name}"
                                else "${s.id}: ${s.name}",
                            )
                        }
                    }
                }
                Column {
                    Text("男声（${speakers.count { it.gender == SpeakerGender.MALE }}）", fontWeight = FontWeight.Bold)
                    speakers.filter { it.gender == SpeakerGender.MALE }.forEach { s ->
                        TextButton(onClick = { onPick(s.id) }) {
                            Text(
                                if (s.id == currentSid) "✓ ${s.id}: ${s.name}"
                                else "${s.id}: ${s.name}",
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
