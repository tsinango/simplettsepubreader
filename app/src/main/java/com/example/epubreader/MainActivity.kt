package com.example.epubreader

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.epubreader.data.ReaderSettingsEntity

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
    val reader by vm.reader.collectAsState()
    val settings by vm.settings.collectAsState()
    var currentBookId by remember { mutableStateOf<String?>(null) }
    val theme = settings?.theme ?: "SYSTEM"
    val dark = theme == "DARK" || (theme == "SYSTEM" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        if (currentBookId == null) {
            LibraryScreen(vm) { id ->
                currentBookId = id
                vm.open(id)
            }
        } else {
            ReaderScreen(vm, settings ?: ReaderSettingsEntity()) { currentBookId = null }
        }
        reader.error?.let { ErrorDialog(it, vm::clearError) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val books by vm.books.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::import)
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("安读") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch(arrayOf("application/epub+zip")) }) {
                Icon(Icons.Default.Add, "导入 EPUB")
            }
        },
    ) { padding ->
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("点击右下角导入 EPUB")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(book.id) }) {
                        Column(Modifier.padding(18.dp)) {
                            Text(book.title, fontWeight = FontWeight.Bold)
                            Text(book.author, style = MaterialTheme.typography.bodyMedium)
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
    val state by vm.reader.collectAsState()
    val chapter = state.parsed?.chapters?.getOrNull(state.chapterIndex)
    val sentences = remember(chapter) { chapter?.let { (vm.getApplication() as ReaderApplication).repository.sentences(it) }.orEmpty() }
    val listState = rememberLazyListState()
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
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
    LaunchedEffect(state.chapterIndex, state.sentenceIndex, sentences.size) {
        if (sentences.isNotEmpty()) listState.scrollToItem(state.sentenceIndex.coerceIn(sentences.indices))
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && sentences.isNotEmpty()) {
            vm.visibleSentence((listState.firstVisibleItemIndex - 1).coerceIn(sentences.indices))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.book?.title ?: "正在打开…", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.persistCurrent(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showContents = true }) { Icon(Icons.Default.Menu, "目录") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = vm::previousSentence) { Text("上一句") }
                IconButton(onClick = vm::play) { Icon(Icons.Default.PlayArrow, "朗读") }
                IconButton(onClick = vm::pause) { Icon(Icons.Default.Pause, "暂停") }
                IconButton(onClick = vm::nextSentence) { Text("下一句") }
            }
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
            item {
                Text(chapter?.title.orEmpty(), fontSize = (settings.fontSize + 6).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(sentences, key = { _, item -> "${item.paragraphIndex}:${item.sentenceIndex}" }) { index, item ->
                Text(
                    text = item.text,
                    fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp,
                    color = if (index == state.sentenceIndex) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    modifier = Modifier.widthIn(max = 840.dp),
                )
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
                        Text(item.title, Modifier.fillMaxWidth().clickable {
                            vm.selectChapter(index)
                            showContents = false
                        }.padding(12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showContents = false }) { Text("关闭") } },
        )
    }
    if (showSettings) SettingsDialog(settings, vm::updateSettings) { showSettings = false }
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
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun ErrorDialog(message: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("无法完成操作") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onClose) { Text("知道了") } },
    )
}
