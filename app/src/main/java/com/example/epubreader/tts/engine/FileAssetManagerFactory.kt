package com.example.epubreader.tts.engine

import android.content.res.AssetManager
import android.os.Build
import com.example.epubreader.DiagnosticLogger
import java.io.File

/**
 * Minimal AssetManager file-path adapter used by [BertVits2MnnEngine].
 *
 * Upstream `Bert-VITS2-MNN` historically expects an `AssetManager` for its
 * MNN modules, distilled BERT, tokenizer and cppjieba dictionary. The reader
 * stores imported packs under the app's `filesDir/models/<pack-dir>` and
 * refuses to copy multi-MB blobs into APK assets. This factory builds a real
 * `android.content.res.AssetManager` whose underlying path set points at the
 * imported pack directory via the hidden `AssetManager.addAssetPath(String)`
 * entry point, so callers (the upstream AAR constructor that accepts an
 * `AssetManager`) see those files just like APK assets.
 *
 * Residual risk (explicit, not hidden):
 *   - `addAssetPath` is a hidden API. On Android 12+ it is still on the
 *     supported allow-list for reflection from the application namespace
 *     (it is on the "max-target-O" / SDK interface surface), but it has not
 *     been verified end-to-end against a real `bertvits2-infer-wrapper` AAR
 *     on a device. A device test must confirm the AAR's `BertVITS2SimpleInferImpl`
 *     can read MNN/BERT/tokenizer/dict blobs through this AssetManager.
 *   - If the AAR is patched upstream to accept a `File` root directly, the
 *     consumer should call the patched constructor instead and stop using
 *     this adapter.
 */
object FileAssetManagerFactory {
    /**
     * Returns an `AssetManager` whose open(String)/openFd(String) resolve to
     * files under [root]. Returns null if the hidden API call fails so the
     * caller can fall back to WNJ/system TTS rather than crash.
     */
    fun create(root: File): AssetManager? {
        if (!root.isDirectory) return null
        return try {
            @Suppress("DEPRECATION")
            val am = AssetManager::class.java.getDeclaredConstructor().newInstance() as AssetManager
            val addAsset = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            addAsset.isAccessible = true
            val cookie = addAsset.invoke(am, root.absolutePath) as Int
            DiagnosticLogger.event(
                "BV2_ADAPTER",
                "addAssetPath succeeded root=${root.absolutePath} cookie=$cookie sdk=${Build.VERSION.SDK_INT}",
            )
            am
        } catch (t: Throwable) {
            DiagnosticLogger.error(
                "BV2_ADAPTER",
                "addAssetPath_failed root=${root.absolutePath}",
                t,
            )
            null
        }
    }
}