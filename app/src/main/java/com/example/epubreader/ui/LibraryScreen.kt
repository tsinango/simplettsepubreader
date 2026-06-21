package com.example.epubreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.example.epubreader.LibraryUiState
import com.example.epubreader.MainViewModel
import com.example.epubreader.R
import com.example.epubreader.data.BookEntity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: MainViewModel,
    library: LibraryUiState,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::import)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var menuBookId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }

    LaunchedEffect(library.deleteResult) {
        val msg = library.deleteResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearDeleteResult()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(context.getString(R.string.app_name)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch(arrayOf("application/epub+zip")) }) {
                Icon(Icons.Default.Add, context.getString(R.string.import_epub))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (library.books.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(context.getString(R.string.empty_library))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(library.books, key = { it.id }) { book ->
                    val disabled = book.id in library.deletingBookIds
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !disabled) { onOpen(book.id) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BookListRow(book, library.bookProgress[book.id] ?: 0f, Modifier.weight(1f))
                            Box {
                                IconButton(
                                    onClick = { menuBookId = book.id },
                                    enabled = !disabled,
                                ) {
                                    Icon(Icons.Default.MoreVert, context.getString(R.string.more_actions))
                                }
                                DropdownMenu(
                                    expanded = menuBookId == book.id,
                                    onDismissRequest = { menuBookId = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(context.getString(R.string.delete_book)) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = {
                                            menuBookId = null
                                            deleteTarget = book
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(context.getString(R.string.delete_book_title)) },
            text = {
                Text(context.getString(R.string.delete_book_message, book.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        vm.deleteBook(book.id)
                    },
                ) {
                    Text(
                        context.getString(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(context.getString(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun BookListRow(
    book: BookEntity,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book.title, book.coverPath)
        Column(Modifier.weight(1f)) {
            Text(book.title, fontWeight = FontWeight.Bold)
            Text(book.author, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = context.getString(R.string.reading_progress, formatProgress(progress)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatProgress(progress: Float): String =
    String.format(Locale.US, "%.1f%%", progress.coerceIn(0f, 100f))
