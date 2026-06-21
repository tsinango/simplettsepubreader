package com.example.epubreader.ui

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.MainViewModel
import com.example.epubreader.R
import com.example.epubreader.ReaderPositionState
import com.example.epubreader.ReaderUiState
import com.example.epubreader.data.Chapter
import com.example.epubreader.data.ReaderSettingsEntity
import com.example.epubreader.data.SentenceRef
import com.example.epubreader.tts.VitsModelState
import com.example.epubreader.tts.TtsPerformanceSnapshot
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: MainViewModel,
    state: ReaderUiState,
    position: ReaderPositionState,
    settings: ReaderSettingsEntity,
    vitsModelState: VitsModelState,
    ttsPerformance: TtsPerformanceSnapshot,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val readingItems = remember(
        state.previousChapterIndex, state.previousSentences,
        state.chapterIndex, state.sentences,
        state.nextChapterIndex, state.nextSentences,
    ) { readingListItems(state) }
    val currentItemIndex = remember(readingItems, position.chapterIndex, position.sentenceIndex) {
        readingItems.currentSentenceItemIndex(position)
    }
    val listState = remember(state.book?.id) {
        LazyListState(
            firstVisibleItemIndex = if (currentItemIndex < 0) 0 else currentItemIndex,
        )
    }
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val uiScope = rememberCoroutineScope()
    var diagnosticExportMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticClearMessage by remember { mutableStateOf<String?>(null) }
    val diagnosticExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                val result = withContext(Dispatchers.IO) {
                    DiagnosticLogger.export(context, uri)
                }
                diagnosticExportMessage = result.fold(
                    onSuccess = { context.getString(R.string.diagnostics_exported) },
                    onFailure = {
                        context.getString(R.string.diagnostics_export_failed, it.message ?: "未知错误")
                    },
                )
            }
        }
    }
    fun onClearDiagnostics() {
        uiScope.launch {
            diagnosticClearMessage = null
            val result = withContext(Dispatchers.IO) { DiagnosticLogger.clear() }
            diagnosticClearMessage = result.fold(
                onSuccess = { context.getString(R.string.diagnostics_cleared) },
                onFailure = {
                    context.getString(R.string.diagnostics_clear_failed, it.message ?: "未知错误")
                },
            )
        }
    }
    val window = context.findActivity()?.window
    val chapters = state.parsed?.chapters.orEmpty()
    val currentChapterIndex = position.chapterIndex.coerceIn(chapters.indicesOrZero())
    val contentsListState = remember(state.book?.id) {
        LazyListState(firstVisibleItemIndex = currentChapterIndex)
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.persistCurrent()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            vm.persistCurrent()
            lifecycle.lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler {
        vm.persistCurrent()
        onBack()
    }
    LaunchedEffect(position.chapterIndex, position.sentenceIndex, readingItems.size, position.isSpeaking) {
        if (currentItemIndex < 0) return@LaunchedEffect
        if ((position.isSpeaking || !listState.isScrollInProgress) && !listState.isItemVisible(currentItemIndex)) {
            listState.scrollToItem(currentItemIndex)
        }
    }
    LaunchedEffect(listState, readingItems, position.isSpeaking, position.chapterIndex) {
        snapshotFlow {
            VisibleSentenceSnapshot(
                sentence = listState.layoutInfo.visibleItemsInfo
                    .asSequence()
                    .mapNotNull { readingItems.getOrNull(it.index) as? ReadingListItem.Sentence }
                    .firstOrNull(),
                isScrollInProgress = listState.isScrollInProgress,
            )
        }.distinctUntilChanged().collect { visible ->
            val visibleSentence = visible.sentence
            if (position.isSpeaking || visibleSentence == null) return@collect
            val currentChapterPath = state.parsed?.chapters?.getOrNull(position.chapterIndex)?.path
            if (visibleSentence.sentence.chapterPath != currentChapterPath) {
                vm.visibleSentence(visibleSentence.sentence)
            } else if (!visible.isScrollInProgress) {
                if (visibleSentence.chapterIndex == position.chapterIndex) {
                    vm.visibleSentenceInCurrentChapter(visibleSentence.localSentenceIndex)
                } else {
                    vm.visibleSentence(visibleSentence.sentence)
                }
            }
        }
    }
    LaunchedEffect(showContents, currentChapterIndex, chapters.size) {
        if (showContents && chapters.isNotEmpty()) {
            contentsListState.scrollToItem(currentChapterIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.book?.title ?: "正在打开…", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.persistCurrent(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, context.getString(R.string.return_button))
                    }
                },
                actions = {
                    IconButton(onClick = { showContents = true }) {
                        Icon(Icons.Default.Menu, context.getString(R.string.table_of_contents))
                    }
                    IconButton(onClick = { vm.refreshTtsPerformance(); showSettings = true }) {
                        Icon(Icons.Default.Settings, context.getString(R.string.settings))
                    }
                },
            )
        },
        bottomBar = {
            ReaderBottomBar(
                isSpeaking = position.isSpeaking,
                progress = position.progress,
                sleepTimerText = sleepTimerLabel(state, context),
                onPrevious = vm::previousSentence,
                onPlayPause = vm::togglePlayPause,
                onNext = vm::nextSentence,
                onSleepTimer = { showSleepTimer = true },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = settings.horizontalPadding.dp,
                vertical = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(readingItems, key = { _, item -> item.key }) { _, item ->
                when (item) {
                    is ReadingListItem.Header -> ChapterHeader(item.chapter, settings, position.progress)
                    is ReadingListItem.Sentence -> SentenceRow(
                        item.sentence,
                        position.isSpeaking &&
                            item.chapterIndex == position.chapterIndex &&
                            item.localSentenceIndex == position.sentenceIndex,
                        settings,
                    )
                }
            }
        }
    }

    if (showContents) {
        ContentsDialog(
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            contentsListState = contentsListState,
            onSelect = { vm.selectChapter(it); showContents = false },
            onDismiss = { showContents = false },
        )
    }

    if (showSettings) {
        SettingsDialog(
            current = settings,
            modelState = vitsModelState,
            performance = ttsPerformance,
            onChange = vm::updateSettings,
            onUseSystemTts = vm::useSystemTts,
            onUseVitsTts = vm::useVitsTts,
            onCancelDownload = vm::cancelVitsDownload,
            onDeleteModel = vm::deleteVitsModel,
            onExportDiagnostics = {
                diagnosticExportMessage = null
                diagnosticExporter.launch(DiagnosticLogger.defaultExportFileName())
            },
            diagnosticExportMessage = diagnosticExportMessage,
            onClearDiagnostics = ::onClearDiagnostics,
            diagnosticClearMessage = diagnosticClearMessage,
            onClose = { showSettings = false },
        )
    }

    if (showSleepTimer) {
        SleepTimerDialog(
            minutes = state.sleepTimerMinutes,
            remainingMinutes = state.sleepTimerRemainingMinutes,
            active = state.sleepTimerEndAtMillis != null,
            onDecrease = { vm.adjustSleepTimer(-5) },
            onIncrease = { vm.adjustSleepTimer(5) },
            onStart = vm::startSleepTimer,
            onCancel = vm::cancelSleepTimer,
            onClose = { showSleepTimer = false },
        )
    }
}

@Composable
private fun ChapterHeader(
    chapter: Chapter,
    settings: ReaderSettingsEntity,
    progress: Float,
) {
    val context = LocalContext.current
    Text(
        chapter.title,
        fontSize = (settings.fontSize + 6).sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = context.getString(R.string.reading_progress, formatProgress(progress)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SentenceRow(
    sentence: SentenceRef,
    isCurrent: Boolean,
    settings: ReaderSettingsEntity,
) {
    Text(
        text = sentence.text,
        fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * settings.lineHeight).sp,
        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else Color.Unspecified,
        modifier = Modifier
            .widthIn(max = 840.dp)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(8.dp),
    )
}

@Composable
private fun ReaderBottomBar(
    isSpeaking: Boolean,
    progress: Float,
    sleepTimerText: String,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSleepTimer: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderControlButton(context.getString(R.string.previous_sentence), Icons.Default.SkipPrevious, null, onPrevious)
        ReaderControlButton(
            if (isSpeaking) context.getString(R.string.pause) else context.getString(R.string.play),
            if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
            null,
            onPlayPause,
        )
        ReaderControlButton(context.getString(R.string.next_sentence), Icons.Default.SkipNext, null, onNext)
        Text(
            formatProgress(progress),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
        )
        ReaderControlButton(context.getString(R.string.sleep_timer), Icons.Default.Timer, sleepTimerText, onSleepTimer)
    }
}

@Composable
private fun ContentsDialog(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    contentsListState: LazyListState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.table_of_contents)) },
        text = {
            LazyColumn(state = contentsListState) {
                itemsIndexed(chapters) { index, item ->
                    val selected = index == currentChapterIndex
                    Text(
                        item.title,
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent,
                            )
                            .clickable { onSelect(index) }
                            .padding(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.close)) }
        },
    )
}

private fun LazyListState.isItemVisible(itemIndex: Int): Boolean {
    val visible = layoutInfo.visibleItemsInfo
    return visible.any { it.index == itemIndex }
}

private fun List<Chapter>.indicesOrZero(): IntRange =
    if (isEmpty()) 0..0 else indices

private fun sleepTimerLabel(state: ReaderUiState, context: Context): String =
    state.sleepTimerRemainingMinutes?.let {
        context.getString(R.string.sleep_timer_remaining, it)
    } ?: context.getString(R.string.sleep_timer_set, state.sleepTimerMinutes)

private fun formatProgress(progress: Float): String =
    String.format(Locale.US, "%.1f%%", progress.coerceIn(0f, 100f))
