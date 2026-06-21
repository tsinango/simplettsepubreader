package com.example.epubreader.tts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class VitsModelStatus { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

data class VitsModelState(
    val status: VitsModelStatus,
    val progress: Int = 0,
    val error: String? = null,
)

/**
 * Downloads, verifies and reports the state of a single [VitsModelDescriptor].
 * Each instance is scoped to one model so downloads and ready markers stay
 * independent. The WNJ-scoped companion accessors are kept for backward
 * compatibility with existing tests and the benchmark instrumentation test.
 */
class VitsModelManager(
    private val context: Context,
    private val descriptor: VitsModelDescriptor,
) {
    private val workManager = WorkManager.getInstance(context)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val state: Flow<VitsModelState> = workManager
        .getWorkInfosForUniqueWorkFlow(descriptor.workName)
        .map { infos -> stateFrom(infos.firstOrNull()) }

    fun currentState(): VitsModelState =
        if (isReady(context, descriptor)) VitsModelState(VitsModelStatus.READY, 100)
        else VitsModelState(VitsModelStatus.NOT_DOWNLOADED)

    fun download() {
        val request = OneTimeWorkRequestBuilder<VitsModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setInputData(workDataOf(KEY_MODEL_ID to descriptor.id.stableValue))
            .build()
        workManager.enqueueUniqueWork(descriptor.workName, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel() = workManager.cancelUniqueWork(descriptor.workName)

    fun requestSwitchAfterDownload() {
        preferences.edit().putBoolean(switchKey(), true).apply()
    }

    fun clearSwitchAfterDownload() {
        preferences.edit().remove(switchKey()).apply()
    }

    fun shouldSwitchAfterDownload(): Boolean =
        preferences.getBoolean(switchKey(), false)

    fun delete() {
        cancel()
        modelDir(context, descriptor).deleteRecursively()
    }

    private fun switchKey(): String =
        if (descriptor.id == VitsModelId.FANCHEN_WNJ) {
            KEY_SWITCH_AFTER_DOWNLOAD
        } else {
            "$KEY_SWITCH_AFTER_DOWNLOAD-${descriptor.id.stableValue}"
        }

    private fun stateFrom(info: WorkInfo?): VitsModelState {
        if (isReady(context, descriptor)) return VitsModelState(VitsModelStatus.READY, 100)
        return when (info?.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                VitsModelState(VitsModelStatus.DOWNLOADING, info.progress.getInt(KEY_PROGRESS, 0))
            WorkInfo.State.FAILED -> VitsModelState(
                VitsModelStatus.FAILED,
                error = info.outputData.getString(KEY_ERROR) ?: "模型下载失败",
            )
            else -> VitsModelState(VitsModelStatus.NOT_DOWNLOADED)
        }
    }

    companion object {
        const val MODEL_SIZE_BYTES = 123_746_625L
        const val MODEL_SIZE_LABEL = "约 124 MB"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_ERROR = "error"
        internal const val KEY_MODEL_ID = "model_id"
        private const val PREFERENCES_NAME = "vits_model"
        private const val KEY_SWITCH_AFTER_DOWNLOAD = "switch_after_download"

        fun modelDir(context: Context): File = modelDir(context, VitsModelRegistry.WNJ)

        fun modelDir(context: Context, descriptor: VitsModelDescriptor): File =
            File(context.filesDir, descriptor.dirName)

        fun isReady(context: Context): Boolean = isReady(context, VitsModelRegistry.WNJ)

        fun isReady(context: Context, descriptor: VitsModelDescriptor): Boolean =
            File(modelDir(context, descriptor), descriptor.readyMarkerName).isFile &&
                verifyFilesInDir(modelDir(context, descriptor), descriptor.specs).all { it.valid }

        fun modelFile(context: Context): File =
            File(modelDir(context), VitsModelRegistry.WNJ.onnxFileName)
        fun tokensFile(context: Context): File =
            File(modelDir(context), VitsModelRegistry.WNJ.tokensFileName)
        fun lexiconFile(context: Context): File =
            File(modelDir(context), VitsModelRegistry.WNJ.lexiconFileName)
        fun phoneFstFile(context: Context): File = File(modelDir(context), "phone.fst")
        fun dateFstFile(context: Context): File = File(modelDir(context), "date.fst")
        fun numberFstFile(context: Context): File = File(modelDir(context), "number.fst")

        internal fun readyFile(context: Context): File =
            File(modelDir(context), VitsModelRegistry.WNJ.readyMarkerName)

        internal fun readyFile(context: Context, descriptor: VitsModelDescriptor): File =
            File(modelDir(context, descriptor), descriptor.readyMarkerName)

        internal fun verifyModelFiles(context: Context): List<ModelFileStatus> =
            verifyFilesInDir(modelDir(context), VitsModelRegistry.WNJ.specs)

        internal fun verifyModelFilesInDir(dir: File): List<ModelFileStatus> =
            verifyFilesInDir(dir, VitsModelRegistry.WNJ.specs)

        internal fun verifyFilesInDir(dir: File, specs: List<ModelFileSpec>): List<ModelFileStatus> =
            specs.map { spec ->
                val file = File(dir, spec.name)
                val valid = file.isFile && file.length() == spec.size && sha256(file) == spec.sha256
                ModelFileStatus(spec, file, valid)
            }

        internal val MODEL_SPECS: List<ModelFileSpec>
            get() = VitsModelRegistry.WNJ.specs

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class VitsModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = VitsModelId.fromStableValue(inputData.getString(VitsModelManager.KEY_MODEL_ID))
            ?: return@withContext failure("未知模型 ID")
        val descriptor = VitsModelRegistry.byId(id)
        val dir = VitsModelManager.modelDir(applicationContext, descriptor).apply { mkdirs() }
        VitsModelManager.readyFile(applicationContext, descriptor).delete()
        val specs = descriptor.specs
        val fileStatuses = VitsModelManager.verifyFilesInDir(dir, specs)
        val missingOrCorrupt = fileStatuses.filter { !it.valid }
        val requiredBytes = missingOrCorrupt.sumOf { it.spec.size } + 32L * 1024 * 1024
        if (missingOrCorrupt.isNotEmpty() && dir.usableSpace < requiredBytes) {
            val missingLabel = missingOrCorrupt.joinToString(", ") { it.spec.name }
            return@withContext failure(
                "存储空间不足，需要至少 ${requiredBytes / (1024 * 1024)} MB 用于补下载：$missingLabel",
            )
        }
        var completed = fileStatuses.filter { it.valid }.sumOf { it.spec.size }
        try {
            specs.forEach { spec ->
                download(descriptor, spec, File(dir, spec.name), completed)
                completed += spec.size
            }
            val ready = VitsModelManager.readyFile(applicationContext, descriptor)
            val pendingReady = File(ready.parentFile, "${ready.name}.part")
            pendingReady.writeText(descriptor.revision)
            check(pendingReady.renameTo(ready)) { "无法保存模型就绪标记" }
            setProgress(workDataOf(VitsModelManager.KEY_PROGRESS to 100))
            Result.success()
        } catch (e: Exception) {
            if (isStopped) Result.failure() else failure(e.message ?: "模型下载失败")
        }
    }

    private suspend fun download(
        descriptor: VitsModelDescriptor,
        spec: ModelFileSpec,
        target: File,
        completedBefore: Long,
    ) {
        if (target.isFile && target.length() == spec.size && VitsModelManager.sha256(target) == spec.sha256) return
        val partial = File(target.parentFile, "${target.name}.part")
        var downloaded = partial.takeIf { it.isFile }?.length()?.coerceAtMost(spec.size) ?: 0L
        if (partial.length() > spec.size) {
            partial.delete()
            downloaded = 0L
        }
        if (downloaded == spec.size) {
            if (VitsModelManager.sha256(partial) == spec.sha256) {
                if (target.exists()) target.delete()
                check(partial.renameTo(target)) { "无法保存 ${spec.name}" }
                return
            }
            partial.delete()
            downloaded = 0L
        }
        val connection = (URL("${descriptor.baseUrl}/${spec.name}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                error("下载 ${spec.name} 失败：HTTP $responseCode")
            }
            if (downloaded > 0 && responseCode == HttpURLConnection.HTTP_OK) {
                partial.delete()
                downloaded = 0L
            }
            val contentLength = connection.contentLengthLong
            val expectedTotal = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val contentRange = connection.getHeaderField("Content-Range")
                if (downloaded > 0) {
                    check(contentRange?.startsWith("bytes $downloaded-") == true) {
                        "${spec.name} 断点响应范围不正确"
                    }
                }
                contentRange?.substringAfter('/')?.toLongOrNull() ?: spec.size
            } else {
                spec.size
            }
            if (expectedTotal != spec.size) {
                error("${spec.name} 服务器返回的规格大小 $expectedTotal 与预期 $spec.size 不符")
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial, downloaded > 0).buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var current = downloaded
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        current += count
                        if (current > spec.size) {
                            error("${spec.name} 下载数据超过规格大小 ${spec.size}")
                        }
                        sink.write(buffer, 0, count)
                        val percent = (((completedBefore + current) * 100) /
                            descriptor.totalSizeBytes).toInt().coerceIn(0, 99)
                        setProgress(workDataOf(VitsModelManager.KEY_PROGRESS to percent))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (partial.length() != spec.size) {
            partial.delete()
            error("${spec.name} 文件大小不正确")
        }
        if (VitsModelManager.sha256(partial) != spec.sha256) {
            partial.delete()
            error("${spec.name} 校验失败")
        }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "无法保存 ${spec.name}" }
    }

    private fun failure(message: String) =
        Result.failure(Data.Builder().putString(VitsModelManager.KEY_ERROR, message).build())
}
