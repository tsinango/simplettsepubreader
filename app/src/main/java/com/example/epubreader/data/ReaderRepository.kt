package com.example.epubreader.data

import android.content.Context
import android.net.Uri
import com.example.epubreader.epub.EpubParser
import com.example.epubreader.epub.SentenceSplitter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

class ReaderRepository(
    private val context: Context,
    private val dao: ReaderDao,
    private val parser: EpubParser = EpubParser(),
) {
    private val progressMutex = Mutex()
    private val cacheLock = Any()
    private val parsedCache = lruCache<String, ParsedBook>(3)
    private val sentenceCache = lruCache<String, List<SentenceRef>>(48)
    private var lastSavedLocatorKey: String? = null
    val books: Flow<List<BookEntity>> = dao.books()
    val settings: Flow<ReaderSettingsEntity?> = dao.settings()

    suspend fun import(uri: Uri): BookEntity {
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val temp = File.createTempFile("import-", ".epub", dir)
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("无法读取所选文件")
        val id = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
        val file = File(dir, "$id.epub")
        if (file.exists()) {
            temp.delete()
        } else {
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
        val parsed = parser.parse(file)
        cacheParsed(id, parsed)
        return BookEntity(
            id = id,
            title = parsed.title,
            author = parsed.author,
            localPath = file.absolutePath,
            importedAt = System.currentTimeMillis(),
        ).also { dao.saveBook(it) }
    }

    suspend fun book(id: String) = dao.book(id)
    suspend fun parsed(book: BookEntity): ParsedBook =
        synchronized(cacheLock) { parsedCache[book.id] } ?: parser.parse(File(book.localPath)).also {
            cacheParsed(book.id, it)
        }

    suspend fun locator(bookId: String) = dao.locator(bookId)
    suspend fun saveSettings(value: ReaderSettingsEntity) = dao.saveSettings(value)

    suspend fun saveProgress(
        bookId: String,
        sentence: SentenceRef,
        source: String,
    ) = progressMutex.withLock {
        val locatorKey = "$bookId:${sentence.key()}"
        if (locatorKey == lastSavedLocatorKey) return@withLock
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
        synchronized(cacheLock) { sentenceCache[sentenceCacheKey(chapter)] } ?: buildSentences(chapter).also {
            synchronized(cacheLock) { sentenceCache[sentenceCacheKey(chapter)] = it }
        }

    fun cachedSentences(chapter: Chapter): List<SentenceRef> =
        synchronized(cacheLock) { sentenceCache[sentenceCacheKey(chapter)] } ?: buildSentences(chapter).also {
            synchronized(cacheLock) { sentenceCache[sentenceCacheKey(chapter)] = it }
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

    private fun commonPrefix(a: String, b: String): Int =
        a.zip(b).takeWhile { (left, right) -> left == right }.size

    private fun cacheParsed(bookId: String, parsed: ParsedBook) {
        synchronized(cacheLock) {
            parsedCache[bookId] = parsed
        }
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
        "${chapter.path}:${chapter.paragraphs.hashCode()}"

    private fun SentenceRef.key() = "$chapterPath:$paragraphIndex:$sentenceIndex"
}
