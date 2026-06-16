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
    private var parsed: ParsedBook? = null
    private var queue: List<SentenceRef> = emptyList()
    private var index = 0
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
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                playing = false
                updateNotification("朗读遇到错误，位置已保存")
            }
            override fun onDone(utteranceId: String?) {
                scope.launch {
                    index++
                    if (playing) speakCurrent()
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
            updateNotification("系统朗读引擎不可用")
        } else if (playing) {
            scope.launch { applySettings(); speakCurrent() }
        }
    }

    private suspend fun loadAndPlay() {
        val id = bookId ?: return
        val book = repository.book(id) ?: return stopPlayback()
        parsed = repository.parsed(book)
        val locator = repository.locator(id)
        queue = parsed!!.chapters.flatMap(repository::sentences)
        index = queue.indexOfFirst {
            it.chapterPath == locator?.chapterPath &&
                it.paragraphIndex == locator.paragraphIndex &&
                it.sentenceIndex == locator.sentenceIndex
        }.coerceAtLeast(0)
        playing = true
        applySettings()
        if (initialized) speakCurrent()
    }

    private suspend fun applySettings() {
        val settings = repository.settings.first()
        tts?.setSpeechRate(settings?.speechRate ?: 1f)
        tts?.setPitch(settings?.pitch ?: 1f)
        settings?.voiceName?.let { name ->
            tts?.voices?.firstOrNull { it.name == name }?.let { tts?.voice = it }
        }
    }

    private suspend fun speakCurrent() {
        val id = bookId ?: return
        val sentence = queue.getOrNull(index) ?: return stopPlayback()
        if (audioManager.requestAudioFocus(audioFocusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playing = false
            return updateNotification("等待音频控制权")
        }
        repository.saveProgress(id, sentence, "TTS")
        updateNotification(sentence.text.take(80))
        tts?.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId(sentence))
    }

    private fun pause() {
        playing = false
        tts?.stop()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        updateNotification("已暂停")
    }

    private suspend fun move(delta: Int) {
        if (queue.isEmpty()) {
            loadAndPlay()
            return
        }
        index = (index + delta).coerceIn(queue.indices)
        playing = true
        speakCurrent()
    }

    private fun stopPlayback() {
        playing = false
        tts?.stop()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun utteranceId(ref: SentenceRef) =
        "${ref.chapterPath}:${ref.paragraphIndex}:${ref.sentenceIndex}"

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("安读")
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
        const val EXTRA_BOOK_ID = "bookId"
        private const val CHANNEL_ID = "reader_tts"
        private const val NOTIFICATION_ID = 42
    }
}
