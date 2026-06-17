package com.example.epubreader

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.epubreader.data.ReaderSettingsEntity
import com.example.epubreader.data.SentenceRef
import java.util.Locale

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
                        Column(Modifier.padding(18.dp)) {
                            Text(book.title, fontWeight = FontWeight.Bold)
                            Text(book.author, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "阅读进度 ${formatProgress(progress[book.id] ?: 0f)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
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
    val chapter = state.parsed?.chapters?.getOrNull(state.chapterIndex)
    val sentences = state.sentences
    val listState = remember(state.book?.id, state.chapterIndex, sentences.size) {
        LazyListState(
            firstVisibleItemIndex = if (sentences.isEmpty()) {
                0
            } else {
                sentenceListItemIndex(state.sentenceIndex.coerceIn(sentences.indices))
            },
        )
    }
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var lastScrollDirection by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current

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
    BackHandler {
        vm.persistCurrent()
        onBack()
    }
    LaunchedEffect(state.chapterIndex, state.sentenceIndex, sentences.size) {
        if (sentences.isEmpty()) return@LaunchedEffect
        val target = state.sentenceIndex.coerceIn(sentences.indices)
        if (!listState.isItemVisible(target)) {
            listState.scrollToItem(sentenceListItemIndex(target))
        }
    }

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                lastScrollDirection = when {
                    index > previousIndex || (index == previousIndex && offset > previousOffset) -> 1
                    index < previousIndex || (index == previousIndex && offset < previousOffset) -> -1
                    else -> lastScrollDirection
                }
                previousIndex = index
                previousOffset = offset
            }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (state.isSpeaking || listState.isScrollInProgress || sentences.isEmpty()) return@LaunchedEffect
        when {
            lastScrollDirection > 0 && !listState.canScrollForward -> {
                lastScrollDirection = 0
                vm.moveChapterFromScroll(1)
            }
            lastScrollDirection < 0 && !listState.canScrollBackward -> {
                lastScrollDirection = 0
                vm.moveChapterFromScroll(-1)
            }
            else -> vm.visibleSentence((listState.firstVisibleItemIndex - 1).coerceIn(sentences.indices))
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
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
            )
        },
        bottomBar = {
            ReaderBottomBar(
                isSpeaking = state.isSpeaking,
                progress = state.progress,
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
            item {
                Text(
                    chapter?.title.orEmpty(),
                    fontSize = (settings.fontSize + 6).sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "阅读进度 ${formatProgress(state.progress)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(sentences, key = { _, item -> "${item.paragraphIndex}:${item.sentenceIndex}" }) { index, item ->
                SentenceRow(item, index == state.sentenceIndex, settings)
            }
        }
    }

    if (showContents) {
        AlertDialog(
            onDismissRequest = { showContents = false },
            title = { Text("目录") },
            text = {
                LazyColumn {
                    itemsIndexed(state.parsed?.chapters.orEmpty()) { index, item ->
                        Text(
                            item.title,
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectChapter(index)
                                    showContents = false
                                }
                                .padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContents = false }) { Text("关闭") }
            },
        )
    }

    if (showSettings) SettingsDialog(settings, vm::updateSettings) { showSettings = false }

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

private fun LazyListState.isItemVisible(index: Int): Boolean {
    val visible = layoutInfo.visibleItemsInfo
    return visible.any { it.index == sentenceListItemIndex(index) }
}

private fun sentenceListItemIndex(sentenceIndex: Int): Int = sentenceIndex + 1

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
    onChange: (ReaderSettingsEntity) -> Unit,
    onClose: () -> Unit,
) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("阅读设置") },
        text = {
            Column {
                Text("字号 ${value.fontSize.toInt()}")
                Slider(value.fontSize, { value = value.copy(fontSize = it) }, valueRange = 14f..34f)
                Text("行距 ${"%.1f".format(value.lineHeight)}")
                Slider(value.lineHeight, { value = value.copy(lineHeight = it) }, valueRange = 1.2f..2.2f)
                Text("朗读速度 ${"%.1f".format(value.speechRate)}")
                Slider(value.speechRate, { value = value.copy(speechRate = it) }, valueRange = .5f..2f)
                Text("主题")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("SYSTEM" to "跟随系统", "LIGHT" to "亮色", "DARK" to "深色").forEach { (id, label) ->
                        TextButton(onClick = { value = value.copy(theme = id) }) {
                            Text(if (value.theme == id) "✓ $label" else label)
                        }
                    }
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
