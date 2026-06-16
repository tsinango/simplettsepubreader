package com.example.epubreader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.epubreader.MainActivity
import com.example.epubreader.ReaderApplication
import com.example.epubreader.data.ParsedBook
import com.example.epubreader.data.ReaderRepository
import com.example.epubreader.data.ReadingLocatorEntity
import com.example.epubreader.data.SentenceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class ReaderTtsService : Service(), TextToSpeech.OnInitListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: ReaderRepository
    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var playing = false
    private var bookId: String? = null
    private var loadedBookId: String? = null
    private var parsed: ParsedBook? = null
    private var chapterIndex = 0
    private var chapterSentences: List<SentenceRef> = emptyList()
    private var sentenceIndex = 0
    private var utteranceSerial = 0
    private var currentUtteranceId: String? = null
    private var lastSavedKey: String? = null
    private var lastBroadcastState: String? = null
    private var hasAudioFocus = false
    private val audioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener {
                if (it < 0) pause()
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        repository = (application as ReaderApplication).repository
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                broadcastState()
            }
            override fun onError(utteranceId: String?) {
                playing = false
                broadcastState()
                updateNotification("朗读遇到错误，位置已保存")
            }
            override fun onDone(utteranceId: String?) {
                scope.launch {
                    if (!playing || utteranceId != currentUtteranceId) return@launch
                    if (moveIndex(1)) speakCurrent() else stopPlayback()
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_BOOK_ID)?.let { bookId = it }
        when (intent?.action) {
            ACTION_PLAY -> {
                startForeground(NOTIFICATION_ID, notification("正在准备朗读"))
                scope.launch { loadAndPlay() }
            }
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> scope.launch { move(1) }
            ACTION_PREVIOUS -> scope.launch { move(-1) }
            ACTION_SETTINGS_CHANGED -> scope.launch { reloadSettings() }
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        val languageStatus = if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.ERROR
        } else {
            TextToSpeech.ERROR
        }
        initialized = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
            languageStatus != TextToSpeech.LANG_NOT_SUPPORTED &&
            languageStatus != TextToSpeech.ERROR
        if (!initialized) {
            playing = false
            broadcastState()
            updateNotification("系统朗读引擎不可用")
        } else if (playing) {
            scope.launch { applySettings(); speakCurrent() }
        }
    }

    private suspend fun loadAndPlay() {
        val id = bookId ?: return
        val parsedBook = ensureBook(id) ?: return stopPlayback()
        val locator = repository.locator(id)
        positionFromLocator(parsedBook, locator)
        playing = true
        applySettings()
        broadcastState()
        if (initialized) speakCurrent()
    }

    private suspend fun ensureBook(id: String): ParsedBook? {
        if (loadedBookId == id && parsed != null) return parsed
        val book = repository.book(id) ?: return null
        val parsedBook = repository.parsed(book)
        parsed = parsedBook
        loadedBookId = id
        chapterIndex = 0
        sentenceIndex = 0
        chapterSentences = emptyList()
        lastSavedKey = null
        lastBroadcastState = null
        return parsedBook
    }

    private suspend fun positionFromLocator(parsedBook: ParsedBook, locator: ReadingLocatorEntity?) {
        val locatedChapterIndex = parsedBook.chapters.indexOfFirst { it.path == locator?.chapterPath }
            .coerceAtLeast(0)
        if (!loadChapterAt(locatedChapterIndex)) {
            loadNearestChapter(locatedChapterIndex, 1)
        }
        sentenceIndex = if (locator == null) {
            0
        } else {
            chapterSentences.indexOfFirst {
                it.paragraphIndex == locator.paragraphIndex && it.sentenceIndex == locator.sentenceIndex
            }.coerceAtLeast(0)
        }
    }

    private suspend fun loadChapterAt(index: Int): Boolean {
        val chapter = parsed?.chapters?.getOrNull(index) ?: return false
        chapterSentences = repository.sentences(chapter)
        chapterIndex = index
        sentenceIndex = sentenceIndex.coerceIn(chapterSentences.indicesOrZero())
        return chapterSentences.isNotEmpty()
    }

    private suspend fun loadNearestChapter(startIndex: Int, direction: Int): Boolean {
        val parsedBook = parsed ?: return false
        var nextIndex = startIndex
        while (nextIndex in parsedBook.chapters.indices) {
            if (loadChapterAt(nextIndex)) return true
            nextIndex += direction
        }
        return false
    }

    private suspend fun applySettings() {
        val settings = repository.settings.first()
        tts?.setSpeechRate(settings?.speechRate ?: 1f)
        tts?.setPitch(settings?.pitch ?: 1f)
        settings?.voiceName?.let { name ->
            tts?.voices?.firstOrNull { it.name == name }?.let { tts?.voice = it }
        }
    }

    private suspend fun reloadSettings() {
        applySettings()
        if (playing && initialized) speakCurrent()
    }

    private suspend fun speakCurrent() {
        val id = bookId ?: return
        val sentence = chapterSentences.getOrNull(sentenceIndex) ?: return stopPlayback()
        if (!ensureAudioFocus()) {
            playing = false
            broadcastState()
            return updateNotification("等待音频控制权")
        }
        val sentenceKey = sentence.key()
        if (lastSavedKey != sentenceKey) {
            repository.saveProgress(id, sentence, "TTS")
            lastSavedKey = sentenceKey
        }
        broadcastState()
        updateNotification(sentence.text.take(80))
        val utteranceId = nextUtteranceId(sentence)
        currentUtteranceId = utteranceId
        tts?.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun pause() {
        playing = false
        tts?.stop()
        currentUtteranceId = null
        abandonAudioFocus()
        broadcastState()
        updateNotification("已暂停")
    }

    private suspend fun move(delta: Int) {
        if (chapterSentences.isEmpty()) {
            loadAndPlay()
            return
        }
        if (!moveIndex(delta)) return
        playing = true
        speakCurrent()
    }

    private suspend fun moveIndex(delta: Int): Boolean {
        val localIndex = sentenceIndex + delta
        if (localIndex in chapterSentences.indices) {
            sentenceIndex = localIndex
            return true
        }
        val direction = delta.sign()
        val targetChapter = chapterIndex + direction
        if (!loadNearestChapter(targetChapter, direction)) return false
        sentenceIndex = if (delta > 0) 0 else chapterSentences.lastIndex
        return true
    }

    private fun stopPlayback() {
        playing = false
        tts?.stop()
        currentUtteranceId = null
        abandonAudioFocus()
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
    }

    private fun utteranceId(ref: SentenceRef) =
        ref.key()

    private fun nextUtteranceId(ref: SentenceRef): String {
        utteranceSerial++
        return "${utteranceId(ref)}:$utteranceSerial"
    }

    private fun SentenceRef.key() = "$chapterPath:$paragraphIndex:$sentenceIndex"

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }

    private fun List<SentenceRef>.indicesOrZero(): IntRange =
        if (isEmpty()) 0..0 else indices

    private fun broadcastState() {
        val sentence = chapterSentences.getOrNull(sentenceIndex)
        val stateKey = listOf(bookId, playing, sentence?.key()).joinToString("|")
        if (stateKey == lastBroadcastState) return
        lastBroadcastState = stateKey
        val intent = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_BOOK_ID, bookId)
            .putExtra(EXTRA_PLAYING, playing)
        if (sentence != null) {
            intent.putExtra(EXTRA_CHAPTER_PATH, sentence.chapterPath)
            intent.putExtra(EXTRA_PARAGRAPH_INDEX, sentence.paragraphIndex)
            intent.putExtra(EXTRA_SENTENCE_INDEX, sentence.sentenceIndex)
        }
        sendBroadcast(intent)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TTS Reader")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "上一句", action(ACTION_PREVIOUS, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "继续",
                action(if (playing) ACTION_PAUSE else ACTION_PLAY, 2),
            )
            .addAction(android.R.drawable.ic_media_next, "下一句", action(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 4))
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(name: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ReaderTtsService::class.java).setAction(name)
        bookId?.let { intent.putExtra(EXTRA_BOOK_ID, it) }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "朗读", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "reader.PLAY"
        const val ACTION_PAUSE = "reader.PAUSE"
        const val ACTION_NEXT = "reader.NEXT"
        const val ACTION_PREVIOUS = "reader.PREVIOUS"
        const val ACTION_STOP = "reader.STOP"
        const val ACTION_SETTINGS_CHANGED = "reader.SETTINGS_CHANGED"
        const val ACTION_STATE_CHANGED = "reader.STATE_CHANGED"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_CHAPTER_PATH = "chapterPath"
        const val EXTRA_PARAGRAPH_INDEX = "paragraphIndex"
        const val EXTRA_SENTENCE_INDEX = "sentenceIndex"
        private const val CHANNEL_ID = "reader_tts"
        private const val NOTIFICATION_ID = 42
    }
}
