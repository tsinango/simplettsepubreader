package com.example.epubreader

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.epubreader.data.BookEntity
import com.example.epubreader.data.Chapter
import com.example.epubreader.data.ReaderSettingsEntity
import com.example.epubreader.data.SentenceRef
import com.example.epubreader.tts.VitsModelManager
import com.example.epubreader.tts.VitsModelState
import com.example.epubreader.tts.VitsModelStatus
import com.example.epubreader.tts.TtsPerformanceSnapshot
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
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
    val bookProgress by vm.bookProgress.collectAsStateWithLifecycle()
    var currentBookId by remember { mutableStateOf<String?>(null) }
    val theme = settings?.theme ?: "SYSTEM"
    val dark = theme == "DARK" || (theme == "SYSTEM" && isSystemInDarkTheme())

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        if (currentBookId == null) {
            LibraryScreen(vm, bookProgress) { id ->
                currentBookId = id
                vm.open(id)
            }
        } else {
            ReaderScreen(vm, settings ?: ReaderSettingsEntity()) {
                currentBookId = null
            }
        }
        reader.error?.let { ErrorDialog(it, vm::clearError) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    vm: MainViewModel,
    progress: Map<String, Float>,
    onOpen: (String) -> Unit,
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::import)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TTS Reader") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch(arrayOf("application/epub+zip")) }) {
                Icon(Icons.Default.Add, "导入 EPUB")
            }
        },
    ) { padding ->
        if (books.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("点击右下角导入 EPUB")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(book.id) },
                    ) {
                        BookListRow(book, progress[book.id] ?: 0f)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookListRow(
    book: BookEntity,
    progress: Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book.title, book.coverPath)
        Column(Modifier.weight(1f)) {
            Text(book.title, fontWeight = FontWeight.Bold)
            Text(book.author, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "阅读进度 ${formatProgress(progress)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookCover(title: String, coverPath: String?) {
    val bitmap = remember(coverPath) {
        coverPath
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    Box(
        modifier = Modifier
            .width(72.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "$title 封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                "封面",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    vm: MainViewModel,
    settings: ReaderSettingsEntity,
    onBack: () -> Unit,
) {
    val state by vm.reader.collectAsStateWithLifecycle()
    val position by vm.readerPosition.collectAsStateWithLifecycle()
    val vitsModelState by vm.vitsModelState.collectAsStateWithLifecycle()
    val ttsPerformance by vm.ttsPerformance.collectAsStateWithLifecycle()
    val readingItems = remember(
        state.previousChapterIndex,
        state.previousSentences,
        state.chapterIndex,
        state.sentences,
        state.nextChapterIndex,
        state.nextSentences,
    ) {
        readingListItems(state)
    }
    val currentItemIndex = remember(readingItems, position.chapterIndex, position.sentenceIndex) {
        readingItems.currentSentenceItemIndex(position)
    }
    val listState = remember(state.book?.id) {
        LazyListState(
            firstVisibleItemIndex = if (currentItemIndex < 0) {
                0
            } else {
                currentItemIndex
            },
        )
    }
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    var diagnosticExportMessage by remember { mutableStateOf<String?>(null) }
    val diagnosticExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                val result = withContext(Dispatchers.IO) {
                    DiagnosticLogger.export(context, uri)
                }
                diagnosticExportMessage = result.fold(
                    onSuccess = { "诊断日志已保存" },
                    onFailure = { "保存失败：${it.message ?: "未知错误"}" },
                )
            }
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
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showContents = true }) {
                        Icon(Icons.Default.Menu, "目录")
                    }
                    IconButton(onClick = { vm.refreshTtsPerformance(); showSettings = true }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
            )
        },
        bottomBar = {
            ReaderBottomBar(
                isSpeaking = position.isSpeaking,
                progress = position.progress,
                sleepTimerText = sleepTimerLabel(state),
                onPrevious = vm::previousSentence,
                onPlayPause = vm::togglePlayPause,
                onNext = vm::nextSentence,
                onSleepTimer = { showSleepTimer = true },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = settings.horizontalPadding.dp,
                vertical = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(readingItems, key = { _, item -> item.key }) { _, item ->
                when (item) {
                    is ReadingListItem.Header -> ChapterHeader(item.chapter.title, settings, position.progress)
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
        AlertDialog(
            onDismissRequest = { showContents = false },
            title = { Text("目录") },
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
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable {
                                    vm.selectChapter(index)
                                    showContents = false
                                }
                                .padding(12.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContents = false }) { Text("关闭") }
            },
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
    title: String,
    settings: ReaderSettingsEntity,
    progress: Float,
) {
    Text(
        title,
        fontSize = (settings.fontSize + 6).sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "阅读进度 ${formatProgress(progress)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
}

private fun sleepTimerLabel(state: ReaderUiState): String =
    state.sleepTimerRemainingMinutes?.let { "剩${it}分" } ?: "${state.sleepTimerMinutes}分"

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
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderControlButton("上一句", Icons.Default.SkipPrevious, null, onPrevious)
        ReaderControlButton(
            if (isSpeaking) "暂停" else "朗读",
            if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
            null,
            onPlayPause,
        )
        ReaderControlButton("下一句", Icons.Default.SkipNext, null, onNext)
        Text(
            formatProgress(progress),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
        )
        ReaderControlButton("睡眠定时", Icons.Default.Timer, sleepTimerText, onSleepTimer)
    }
}

private fun LazyListState.isItemVisible(itemIndex: Int): Boolean {
    val visible = layoutInfo.visibleItemsInfo
    return visible.any { it.index == itemIndex }
}

private fun <T> List<T>.indicesOrZero(): IntRange =
    if (isEmpty()) 0..0 else indices

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class VisibleSentenceSnapshot(
    val sentence: ReadingListItem.Sentence?,
    val isScrollInProgress: Boolean,
)

private sealed class ReadingListItem {
    abstract val key: String

    data class Header(
        val chapterIndex: Int,
        val chapter: Chapter,
    ) : ReadingListItem() {
        override val key: String = "header:${chapter.path}"
    }

    data class Sentence(
        val chapterIndex: Int,
        val localSentenceIndex: Int,
        val sentence: SentenceRef,
    ) : ReadingListItem() {
        override val key: String =
            "sentence:${sentence.chapterPath}:${sentence.paragraphIndex}:${sentence.sentenceIndex}"
    }
}

private fun readingListItems(state: ReaderUiState): List<ReadingListItem> {
    val parsed = state.parsed ?: return emptyList()
    val items = mutableListOf<ReadingListItem>()
    fun appendChapter(chapterIndex: Int?, sentences: List<SentenceRef>) {
        if (chapterIndex == null || sentences.isEmpty()) return
        val chapter = parsed.chapters.getOrNull(chapterIndex) ?: return
        items += ReadingListItem.Header(chapterIndex, chapter)
        sentences.forEachIndexed { localIndex, sentence ->
            items += ReadingListItem.Sentence(chapterIndex, localIndex, sentence)
        }
    }
    appendChapter(state.previousChapterIndex, state.previousSentences)
    appendChapter(state.chapterIndex, state.sentences)
    appendChapter(state.nextChapterIndex, state.nextSentences)
    return items
}

private fun List<ReadingListItem>.currentSentenceItemIndex(position: ReaderPositionState): Int =
    indexOfFirst {
        it is ReadingListItem.Sentence &&
            it.chapterIndex == position.chapterIndex &&
            it.localSentenceIndex == position.sentenceIndex
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
        color = if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            Color.Unspecified
        },
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
private fun ReaderControlButton(
    label: String,
    icon: ImageVector,
    text: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, label)
        }
        if (text != null) {
            Text(text, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SleepTimerDialog(
    minutes: Int,
    remainingMinutes: Int?,
    active: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("睡眠 Timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (active) "剩余 ${remainingMinutes ?: minutes} 分钟" else "定时 $minutes 分钟")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onDecrease) { Text("-5") }
                    Text("$minutes 分钟", fontWeight = FontWeight.Bold)
                    Button(onClick = onIncrease) { Text("+5") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(); onClose() }) {
                Text(if (active) "重置" else "开启")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) {
                    TextButton(onClick = { onCancel(); onClose() }) { Text("关闭") }
                }
                TextButton(onClick = onClose) { Text("取消") }
            }
        },
    )
}

@Composable
private fun SettingsDialog(
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
    onClose: () -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    var confirmDownload by remember { mutableStateOf(false) }
    LaunchedEffect(current.ttsEngine) {
        value = value.copy(ttsEngine = current.ttsEngine)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("阅读设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("字号 ${value.fontSize.toInt()}")
                Slider(value.fontSize, { value = value.copy(fontSize = it) }, valueRange = 14f..34f)
                Text("行距 ${"%.1f".format(value.lineHeight)}")
                Slider(value.lineHeight, { value = value.copy(lineHeight = it) }, valueRange = 1.2f..2.2f)
                Text("朗读速度 ${"%.1f".format(value.speechRate)}")
                Slider(value.speechRate, { value = value.copy(speechRate = it) }, valueRange = .5f..2f)
                Text("朗读引擎")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        value = value.copy(ttsEngine = MainViewModel.TTS_ENGINE_SYSTEM)
                        onUseSystemTts()
                    }) {
                        Text(if (value.ttsEngine == MainViewModel.TTS_ENGINE_SYSTEM) "✓ 系统 TTS" else "系统 TTS")
                    }
                    TextButton(onClick = {
                        if (modelState.status == VitsModelStatus.READY) {
                            value = value.copy(ttsEngine = MainViewModel.TTS_ENGINE_VITS)
                            onUseVitsTts()
                        } else {
                            confirmDownload = true
                        }
                    }) {
                        Text(if (value.ttsEngine == MainViewModel.TTS_ENGINE_VITS) "✓ 内置 VITS" else "内置 VITS")
                    }
                }
                when (modelState.status) {
                    VitsModelStatus.DOWNLOADING -> {
                        Text("模型下载中 ${modelState.progress}%")
                        TextButton(onClick = onCancelDownload) { Text("取消下载") }
                    }
                    VitsModelStatus.READY ->
                        TextButton(onClick = onDeleteModel) { Text("删除内置模型（释放约 124 MB）") }
                    VitsModelStatus.FAILED -> {
                        Text(modelState.error ?: "模型下载失败", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { confirmDownload = true }) { Text("重试下载") }
                    }
                    VitsModelStatus.NOT_DOWNLOADED -> Text("模型未下载")
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
                Text("主题")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("SYSTEM" to "跟随系统", "LIGHT" to "亮色", "DARK" to "深色").forEach { (id, label) ->
                        TextButton(onClick = { value = value.copy(theme = id) }) {
                            Text(if (value.theme == id) "✓ $label" else label)
                        }
                    }
                }
                Text("诊断")
                TextButton(onClick = onExportDiagnostics) { Text("导出诊断日志") }
                diagnosticExportMessage?.let { message ->
                    Text(
                        message,
                        color = if (message.startsWith("保存失败")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onChange(value); onClose() }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        },
    )
    if (confirmDownload) {
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("下载内置语音模型") },
            text = { Text("需要下载${VitsModelManager.MODEL_SIZE_LABEL}，允许使用 Wi‑Fi 或移动网络。下载完成后将自动切换到内置 VITS。") },
            confirmButton = {
                Button(onClick = { confirmDownload = false; onUseVitsTts() }) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ErrorDialog(message: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("无法完成操作") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onClose) { Text("知道了") }
        },
    )
}

private fun formatProgress(progress: Float): String =
    String.format(Locale.US, "%.1f%%", progress.coerceIn(0f, 100f))
