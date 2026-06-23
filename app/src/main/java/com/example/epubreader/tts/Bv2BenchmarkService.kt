package com.example.epubreader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.epubreader.tts.engine.BertVits2Backend
import com.example.epubreader.tts.engine.BertVits2MnnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Benchmarks BV2 inference on a fixed test text across N iterations.
 * Callable via ADB:
 *
 *   adb shell am start-foreground-service \
 *       --ei backend 5 --ei warmupIters 3 --ei benchIters 30 \
 *       -n <pkg>/.tts.Bv2BenchmarkService
 *
 * MNN's ENABLE_OPENCL_TIME_PROFILER prints per-event GPU kernel times to
 * logcat after each inference. Post-process to build Top-20 cumulative tables.
 */
class Bv2BenchmarkService : Service() {

    companion object {
        private const val CHANNEL_ID = "bv2bench"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "BV2 Benchmark", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val backendId = intent?.getIntExtra("backend", BertVits2Backend.OPENCL_FLOW.nativeId)
            ?: BertVits2Backend.OPENCL_FLOW.nativeId
        val warmupIters = intent?.getIntExtra("warmupIters", 3) ?: 3
        val benchIters = intent?.getIntExtra("benchIters", 10) ?: 10
        val testText = intent?.getStringExtra("testText") ?: "测试文本"
        val spkid = intent?.getIntExtra("spkid", 1) ?: 1

        if (Build.VERSION.SDK_INT >= 26) {
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("BV2 Benchmark")
                .setContentText("backend=$backendId iters=$benchIters")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
            startForeground(2, notification)
        }

        Log.i("BENCH", "Start backend=$backendId warmup=$warmupIters bench=$benchIters")
        scope.launch {
            try {
                runBenchmark(backendId, warmupIters, benchIters, testText, spkid)
            } catch (e: Exception) {
                Log.e("BENCH", "benchmark failed", e)
            } finally {
                if (Build.VERSION.SDK_INT >= 26) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runBenchmark(
        backendId: Int,
        warmupIters: Int,
        benchIters: Int,
        testText: String,
        spkid: Int,
    ) {
        val backend = BertVits2Backend.entries.first { it.nativeId == backendId }
        val ctx = applicationContext

        val engine = BertVits2MnnEngine(
            descriptor = BertVits2MnnModelRegistry.bertVits2Mnn22k,
        )
        engine.configureBackend(backend, cpuThreads = 6)
        engine.initialize(ctx, numThreads = 6)
        Log.i("BENCH", "Engine init OK, activeBackend=${engine.activeBackend()}")

        for (i in 0 until warmupIters) {
            val result = engine.synthesize(testText, spkid, speed = 1.0f)
            Log.i("BENCH", "WARMUP iter=$i samples=${result.samples.size ?: -1}")
        }
        Log.i("BENCH", "Warmup done")

        for (i in 0 until benchIters) {
            val t0 = System.currentTimeMillis()
            val result = engine.synthesize(testText, spkid, speed = 1.0f)
            val t1 = System.currentTimeMillis()
            Log.i("BENCH", "BENCH iter=$i wallMs=${t1 - t0} samples=${result.samples.size ?: -1}")
        }

        Log.i("BENCH", "Done backend=$backendId benchIters=$benchIters")
        engine.release()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
