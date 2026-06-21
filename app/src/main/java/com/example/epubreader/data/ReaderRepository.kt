package com.example.epubreader.data

import android.content.Context
import android.net.Uri
import com.example.epubreader.DiagnosticLogger
import com.example.epubreader.epub.EpubParser
import com.example.epubreader.epub.SentenceSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.zip.ZipFile

class ReaderRepository(
    private val context: Context,
    private val dao: ReaderDao,
    private val parser: EpubParser = EpubParser(),
) {
    private val progressMutex = Mutex()
    private val cacheLock = Any()
    private val parsedCache = lruCache<String, ParsedBook>(3)
    private val sentenceCache = lruCache<String, List<SentenceRef>>(48)
    private val progressIndexCache = lruCache<String, BookProgressIndex>(3)
    private var lastSavedLocatorKey: String? = null
    val books: Flow<List<BookEntity>> = dao.books()
    val locators: Flow<List<ReadingLocatorEntity>> = dao.locators()
    val settings: Flow<ReaderSettingsEntity?> = dao.settings()

    suspend fun import(uri: Uri): BookEntity = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val temp = File.createTempFile("import-", ".epub", dir)
        var importedFile: File? = null
        var keepImportedFile = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val maxFileBytes = MAX_EPUB_FILE_BYTES
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (totalBytes > maxFileBytes) {
                            error("EPUB 文件过大（超过 ${maxFileBytes / (1024 * 1024)} MB）")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("无法读取所选文件")
            val id = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
            val file = File(dir, "$id.epub")
            val alreadyImported = file.exists()
            if (alreadyImported) {
                temp.delete()
            } else if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
            }
            importedFile = file.takeUnless { alreadyImported }
            val parsed = parser.parse(file)
            cacheParsed(id, parsed)
            val coverPath = saveCover(id, file, parsed.cover)
            return@withContext BookEntity(
                id = id,
                title = parsed.title,
                author = parsed.author,
                localPath = file.absolutePath,
                coverPath = coverPath,
                importedAt = System.currentTimeMillis(),
            ).also {
                dao.saveBook(it)
                keepImportedFile = true
            }
        } finally {
            temp.delete()
            if (!keepImportedFile) importedFile?.delete()
        }
    }

    suspend fun book(id: String) = dao.book(id)
    suspend fun parsed(book: BookEntity): ParsedBook =
        synchronized(cacheLock) { parsedCache[book.id] } ?: withContext(Dispatchers.IO) {
            parser.parse(File(book.localPath)).also { cacheParsed(book.id, it) }
        }

    suspend fun locator(bookId: String) = dao.locator(bookId)
    suspend fun deleteBook(bookId: String): BookEntity? = withContext(Dispatchers.IO) {
        val book = dao.book(bookId) ?: return@withContext null
        synchronized(cacheLock) {
            parsedCache.remove(bookId)
            sentenceCache.entries.removeAll { it.key.startsWith("$bookId:") }
            progressIndexCache.entries.removeAll { it.key.startsWith("$bookId:") }
            if (lastSavedLocatorKey?.startsWith("$bookId:") == true) {
                lastSavedLocatorKey = null
            }
        }
        dao.deleteBookWithLocator(bookId)
        val epubFile = File(book.localPath)
        if (epubFile.exists() && !epubFile.delete()) {
            DiagnosticLogger.event("DELETE", "failed_to_delete_epub ${epubFile.name}")
        }
        book.coverPath?.let { path ->
            runCatching { File(path).delete() }.onFailure {
                DiagnosticLogger.event("DELETE", "failed_to_delete_cover $path")
            }
        }
        return@withContext book
    }

    suspend fun saveSettings(value: ReaderSettingsEntity) = dao.saveSettings(value)

    suspend fun saveProgress(
        bookId: String,
        sentence: SentenceRef,
        source: String,
    ) = progressMutex.withLock {
        val locatorKey = "$bookId:${sentence.chapterPath}:${sentence.paragraphIndex}:${sentence.sentenceIndex}"
        if (locatorKey == lastSavedLocatorKey) return@withLock
        if (dao.book(bookId) == null) return@withLock
        val now = System.currentTimeMillis()
        val current = dao.locator(bookId)
        if (current == null || now >= current.updatedAt) {
            dao.saveLocator(
                ReadingLocatorEntity(
                    bookId = bookId,
                    chapterPath = sentence.chapterPath,
                    paragraphIndex = sentence.paragraphIndex,
                    sentenceIndex = sentence.sentenceIndex,
                    characterOffset = 0,
                    context = sentence.text.take(96),
                    source = source,
                    updatedAt = now,
                ),
            )
            lastSavedLocatorKey = locatorKey
        }
    }

    suspend fun sentences(chapter: Chapter): List<SentenceRef> =
        withContext(Dispatchers.Default) {
            val key = sentenceCacheKey(chapter)
            synchronized(cacheLock) { sentenceCache[key] } ?: buildSentences(chapter).also {
                synchronized(cacheLock) { sentenceCache[key] = it }
            }
        }

    fun cachedSentences(chapter: Chapter): List<SentenceRef> {
        val key = sentenceCacheKey(chapter)
        return synchronized(cacheLock) { sentenceCache[key] } ?: buildSentences(chapter).also {
            synchronized(cacheLock) { sentenceCache[key] = it }
        }
    }

    fun restore(chapter: Chapter, locator: ReadingLocatorEntity?): SentenceRef? {
        if (locator == null) return null
        val sentences = cachedSentences(chapter)
        val exact = sentences.firstOrNull {
            it.paragraphIndex == locator.paragraphIndex && it.sentenceIndex == locator.sentenceIndex
        }
        if (exact != null && exact.text.take(96) == locator.context) return exact
        return sentences.maxByOrNull { commonPrefix(it.text, locator.context) }
            ?.takeIf { commonPrefix(it.text, locator.context) >= 8 }
    }

    fun progressPercent(
        parsed: ParsedBook,
        chapterIndex: Int,
        sentenceIndex: Int,
    ): Float {
        val index = progressIndex(parsed)
        if (index.total <= 0) return 0f
        val safeChapterIndex = chapterIndex.coerceIn(0, parsed.chapters.lastIndex)
        val currentCount = index.chapterCounts.getOrElse(safeChapterIndex) { 0 }
        val completedBeforeChapter = index.completedBeforeChapter.getOrElse(safeChapterIndex) { 0 }
        if (currentCount <= 0) return percentFromIndex(completedBeforeChapter, index.total)

        val globalIndex = completedBeforeChapter + sentenceIndex.coerceIn(0, currentCount - 1)
        return percentFromIndex(globalIndex, index.total)
    }

    fun progressPercent(parsed: ParsedBook, locator: ReadingLocatorEntity?): Float {
        if (locator == null) return 0f
        val chapterIndex = parsed.chapters.indexOfFirst { it.path == locator.chapterPath }
        if (chapterIndex < 0) return 0f
        val sentenceIndex = cachedSentences(parsed.chapters[chapterIndex]).indexOfFirst {
            it.paragraphIndex == locator.paragraphIndex && it.sentenceIndex == locator.sentenceIndex
        }
        if (sentenceIndex < 0) return 0f
        return progressPercent(parsed, chapterIndex, sentenceIndex)
    }

    private fun commonPrefix(a: String, b: String): Int =
        a.zip(b).takeWhile { (left, right) -> left == right }.size

    private fun percentFromIndex(globalIndex: Int, total: Int): Float =
        if (total <= 1) {
            100f
        } else {
            ((globalIndex.coerceIn(0, total - 1) * 100f) / (total - 1))
                .coerceIn(0f, 100f)
        }

    private fun cacheParsed(bookId: String, parsed: ParsedBook) {
        synchronized(cacheLock) {
            parsedCache[bookId] = parsed
        }
    }

    private fun progressIndex(parsed: ParsedBook): BookProgressIndex {
        val key = progressIndexKey(parsed)
        synchronized(cacheLock) { progressIndexCache[key] }?.let { return it }
        val chapterCounts = parsed.chapters.map { cachedSentences(it).size }
        var runningTotal = 0
        val completedBeforeChapter = chapterCounts.map { count ->
            runningTotal.also { runningTotal += count }
        }
        val index = BookProgressIndex(chapterCounts, completedBeforeChapter, runningTotal)
        synchronized(cacheLock) { progressIndexCache[key] = index }
        return index
    }

    private fun progressIndexKey(parsed: ParsedBook): String =
        "${parsed.title}:${parsed.author}:${parsed.chapters.size}:${parsed.chapters.joinToString(",") { it.path }}"

    private fun saveCover(bookId: String, bookFile: File, cover: BookCover?): String? {
        cover ?: return null
        val extension = coverExtension(cover).ifBlank { "img" }
        val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
        val coverFile = File(coverDir, "$bookId.$extension")
        return runCatching {
            ZipFile(bookFile).use { zip ->
                val entry = zip.getEntry(cover.path) ?: return@runCatching null
                if (entry.size > MAX_COVER_BYTES) {
                    DiagnosticLogger.event("COVER", "too_large ${cover.path} size=${entry.size}")
                    return@runCatching null
                }
                zip.getInputStream(entry).use { input ->
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    coverFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_COVER_BYTES) {
                                coverFile.delete()
                                DiagnosticLogger.event("COVER", "exceeded_limit ${cover.path}")
                                return@runCatching null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            coverFile.absolutePath
        }.getOrNull()
    }

    private fun coverExtension(cover: BookCover): String =
        when {
            cover.mediaType.equals("image/jpeg", ignoreCase = true) -> "jpg"
            cover.mediaType.equals("image/png", ignoreCase = true) -> "png"
            cover.mediaType.equals("image/webp", ignoreCase = true) -> "webp"
            cover.mediaType.equals("image/gif", ignoreCase = true) -> "gif"
            else -> cover.path.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
                ?: ""
        }

    private fun buildSentences(chapter: Chapter): List<SentenceRef> =
        chapter.paragraphs.flatMapIndexed { p, text ->
            SentenceSplitter.split(text).mapIndexed { s, sentence ->
                SentenceRef(chapter.path, p, s, sentence)
            }
        }

    private fun <K, V> lruCache(maxSize: Int) = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxSize
    }

    private fun sentenceCacheKey(chapter: Chapter): String =
        "${chapter.path}:${chapter.paragraphs.joinToString("|") { it.take(40) }}"

    private data class BookProgressIndex(
        val chapterCounts: List<Int>,
        val completedBeforeChapter: List<Int>,
        val total: Int,
    )

    companion object {
        private const val MAX_EPUB_FILE_BYTES = 200L * 1024 * 1024
        private const val MAX_COVER_BYTES = 10L * 1024 * 1024
    }
}
