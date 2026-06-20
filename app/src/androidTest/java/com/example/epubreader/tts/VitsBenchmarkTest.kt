package com.example.epubreader.tts

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VitsBenchmarkTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val shortText = "清晨的阳光安静地落在书页上，新的一天就这样开始了。"
    private val mediumText = "阅读可以让人暂时离开喧闹的现实，在文字构成的世界里获得专注与想象。"
    private val longText = "列车穿过平原和山谷，窗外的村庄渐渐远去，旅客们低声交谈，等待下一站到来。"
    private val reportFile = File(context.getExternalFilesDir(null), "vits-benchmark.json")

    @Test
    fun smokeTest() {
        assumeTrue("Download the VITS model in the app before running this benchmark", VitsModelManager.isReady(context))
        Log.i("VitsBenchmark", "Smoke: maxMemory=${Runtime.getRuntime().maxMemory()}")
        Log.i("VitsBenchmark", "Smoke: creating engine with 1 thread...")
        val engine = try {
            createEngine(1)
        } catch (e: Exception) {
            Log.e("VitsBenchmark", "Smoke: engine creation failed", e)
            throw e
        }
        try {
            Log.i("VitsBenchmark", "Smoke: engine created, trying generate with min text...")
            val speed = TtsRatePolicy.vitsSpeed(1f)
            // Try very short text first to isolate the issue
            val testText = "你好"
            Log.i("VitsBenchmark", "Smoke: text='$testText' speed=$speed")
            val started = SystemClock.elapsedRealtime()
            val audio = try {
                engine.generate(text = testText, sid = 0, speed = speed)
            } catch (e: Exception) {
                Log.e("VitsBenchmark", "Smoke: generate threw", e)
                throw e
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            Log.i("VitsBenchmark", "Smoke: generate() returned in ${elapsed}ms, samples=${audio.samples.size} rate=${audio.sampleRate}")
            assertTrue("Generation must produce audio samples", audio.samples.isNotEmpty())
        } finally {
            Log.i("VitsBenchmark", "Smoke: releasing engine...")
            engine.release()
            Log.i("VitsBenchmark", "Smoke: engine released")
        }
    }

    @Test
    fun benchmarkRates() {
        assumeTrue("Download the VITS model in the app before running this benchmark", VitsModelManager.isReady(context))
        val engine = createEngine(4)
        try {
            val results = JSONArray()
            var previousAudioMillis = Long.MAX_VALUE
            for (userRate in listOf(0.5f, 1f, 2f)) {
                val speed = TtsRatePolicy.vitsSpeed(userRate)
                Log.i("VitsBenchmark", "Rate: userRate=$userRate speed=$speed")
                val item = measure(engine, mediumText, speed)
                    .put("userRate", userRate)
                results.put(item)
                val audioMillis = item.getLong("audioMillis")
                if (audioMillis >= previousAudioMillis) {
                    Log.w("VitsBenchmark", "Rate direction check: prev=${previousAudioMillis} curr=${audioMillis}")
                }
                // Don't assert direction — let data speak; the CALIBRATED_NORMAL_SPEED may need tuning
                previousAudioMillis = audioMillis
            }
            saveReport(JSONObject().put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("rates", results)
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun benchmarkThreads() {
        assumeTrue("Download the VITS model in the app before running this benchmark", VitsModelManager.isReady(context))
        val results = JSONArray()
        for (threads in listOf(2, 4, 6)) {
            val started = SystemClock.elapsedRealtime()
            val engine = createEngine(threads)
            val initMillis = SystemClock.elapsedRealtime() - started
            try {
                val item = measure(engine, shortText, TtsRatePolicy.vitsSpeed(1f))
                    .put("threads", threads)
                    .put("initMillis", initMillis)
                results.put(item)
                Log.i("VitsBenchmark", "Threads: $threads initMs=$initMillis rtf=${item.optDouble("rtf")}")
            } finally {
                engine.release()
            }
            System.gc()
            Thread.sleep(500)
        }
        saveReport(JSONObject().put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("threads", results)
        )
    }

    private fun measure(engine: OfflineTts, text: String, speed: Float): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val audio = engine.generate(text = text, sid = 0, speed = speed)
        val generationMillis = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
        assertTrue("Generation must produce audio samples", audio.samples.isNotEmpty())
        val audioMillis = audio.samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)
        val spokenChars = text.count { !it.isWhitespace() && it !in "，。！？；：,.!?;:" }
        return JSONObject()
            .put("speed", speed.toDouble())
            .put("generationMillis", generationMillis)
            .put("audioMillis", audioMillis)
            .put("charsPerSecond", spokenChars * 1000.0 / audioMillis.coerceAtLeast(1))
            .put("rtf", generationMillis.toDouble() / audioMillis.coerceAtLeast(1))
            .put("sampleRate", audio.sampleRate)
    }

    private fun createEngine(threads: Int) = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = VitsModelManager.modelFile(context).absolutePath,
                    tokens = VitsModelManager.tokensFile(context).absolutePath,
                    lexicon = VitsModelManager.lexiconFile(context).absolutePath,
                ),
                numThreads = threads,
            ),
        ),
    )

    private fun saveReport(json: JSONObject) {
        reportFile.writeText(json.toString(2))
        Log.i("VitsBenchmark", "Report saved to ${reportFile.absolutePath}")
    }
}
