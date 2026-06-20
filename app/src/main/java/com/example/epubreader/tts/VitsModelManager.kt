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

class VitsModelManager(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    internal data class ModelFileSpec(
        val name: String,
        val size: Long,
        val sha256: String,
    )

    internal data class ModelFileStatus(
        val spec: ModelFileSpec,
        val file: File,
        val valid: Boolean,
    )

    val state: Flow<VitsModelState> = workManager
        .getWorkInfosForUniqueWorkFlow(WORK_NAME)
        .map { infos -> stateFrom(infos.firstOrNull()) }

    fun currentState(): VitsModelState =
        if (isReady(context)) VitsModelState(VitsModelStatus.READY, 100)
        else VitsModelState(VitsModelStatus.NOT_DOWNLOADED)

    fun download() {
        val request = OneTimeWorkRequestBuilder<VitsModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel() = workManager.cancelUniqueWork(WORK_NAME)

    fun requestSwitchAfterDownload() {
        preferences.edit().putBoolean(KEY_SWITCH_AFTER_DOWNLOAD, true).apply()
    }

    fun clearSwitchAfterDownload() {
        preferences.edit().remove(KEY_SWITCH_AFTER_DOWNLOAD).apply()
    }

    fun shouldSwitchAfterDownload(): Boolean =
        preferences.getBoolean(KEY_SWITCH_AFTER_DOWNLOAD, false)

    fun delete() {
        cancel()
        modelDir(context).deleteRecursively()
    }

    private fun stateFrom(info: WorkInfo?): VitsModelState {
        if (isReady(context)) return VitsModelState(VitsModelStatus.READY, 100)
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
        internal const val WORK_NAME = "download-vits-fanchen-wnj"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_ERROR = "error"
        private const val PREFERENCES_NAME = "vits_model"
        private const val KEY_SWITCH_AFTER_DOWNLOAD = "switch_after_download"
        private const val READY_FILE = ".ready-75a59ed-v2"

        fun modelDir(context: Context) = File(context.filesDir, "models/vits-zh-hf-fanchen-wnj")
        fun isReady(context: Context): Boolean =
            File(modelDir(context), READY_FILE).isFile &&
                verifyModelFiles(context).all { it.valid }
        fun modelFile(context: Context) = File(modelDir(context), "vits-zh-hf-fanchen-wnj.onnx")
        fun tokensFile(context: Context) = File(modelDir(context), "tokens.txt")
        fun lexiconFile(context: Context) = File(modelDir(context), "lexicon.txt")
        fun phoneFstFile(context: Context) = File(modelDir(context), "phone.fst")
        fun dateFstFile(context: Context) = File(modelDir(context), "date.fst")
        fun numberFstFile(context: Context) = File(modelDir(context), "number.fst")
        internal fun readyFile(context: Context) = File(modelDir(context), READY_FILE)

        internal fun verifyModelFiles(context: Context): List<ModelFileStatus> =
            verifyModelFilesInDir(modelDir(context))

        internal fun verifyModelFilesInDir(dir: File): List<ModelFileStatus> =
            MODEL_SPECS.map { spec ->
                val file = File(dir, spec.name)
                val valid = file.isFile && file.length() == spec.size && sha256(file) == spec.sha256
                ModelFileStatus(spec, file, valid)
            }

        internal val MODEL_SPECS: List<ModelFileSpec> = listOf(
            ModelFileSpec("vits-zh-hf-fanchen-wnj.onnx", 121_076_185L, "ccd592a5f6fa3f7e8840405c3422ffed9eba58db253d4abd82c75280db98c644"),
            ModelFileSpec("tokens.txt", 331L, "34b035b9aeb070df6188b022f29c00e0e142c7ade9f25611ced65db5e9cc8402"),
            ModelFileSpec("lexicon.txt", 2_457_843L, "9af2824e49e731bf615927c768fdc36bbbe894cac57d8e0088d9c94331b07320"),
            ModelFileSpec("phone.fst", 88_630L, "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7"),
            ModelFileSpec("date.fst", 59_154L, "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718"),
            ModelFileSpec("number.fst", 64_482L, "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"),
        )

        private const val MODEL_FILE_SIZE = 121_076_185L
        private const val TOKENS_FILE_SIZE = 331L
        private const val LEXICON_FILE_SIZE = 2_457_843L
        private const val PHONE_FST_SIZE = 88_630L
        private const val DATE_FST_SIZE = 59_154L
        private const val NUMBER_FST_SIZE = 64_482L
        private const val PHONE_FST_SHA256 = "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7"
        private const val DATE_FST_SHA256 = "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718"
        private const val NUMBER_FST_SHA256 = "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"

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
        val dir = VitsModelManager.modelDir(applicationContext).apply { mkdirs() }
        VitsModelManager.readyFile(applicationContext).delete()
        val fileStatuses = VitsModelManager.verifyModelFiles(applicationContext)
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
            VitsModelManager.MODEL_SPECS.forEach { spec ->
                download(spec, File(dir, spec.name), completed)
                completed += spec.size
            }
            val ready = VitsModelManager.readyFile(applicationContext)
            val pendingReady = File(ready.parentFile, "${ready.name}.part")
            pendingReady.writeText(REVISION)
            check(pendingReady.renameTo(ready)) { "无法保存模型就绪标记" }
            setProgress(workDataOf(VitsModelManager.KEY_PROGRESS to 100))
            Result.success()
        } catch (e: Exception) {
            if (isStopped) Result.failure() else failure(e.message ?: "模型下载失败")
        }
    }

    private suspend fun download(spec: VitsModelManager.ModelFileSpec, target: File, completedBefore: Long) {
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
        val connection = (URL("$BASE_URL/${spec.name}").openConnection() as HttpURLConnection).apply {
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
            if (downloaded > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val expectedRange = "bytes $downloaded-"
                check(connection.getHeaderField("Content-Range")?.startsWith(expectedRange) == true) {
                    "${spec.name} 断点响应范围不正确"
                }
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial, downloaded > 0).buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var current = downloaded
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        current += count
                        val percent = (((completedBefore + current) * 100) /
                            VitsModelManager.MODEL_SIZE_BYTES).toInt().coerceIn(0, 99)
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

    private fun failure(message: String) = Result.failure(Data.Builder().putString(VitsModelManager.KEY_ERROR, message).build())

    companion object {
        private const val REVISION = "75a59ed26f999226f412eb9e1dff31c86b42f082"
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/vits-zh-hf-fanchen-wnj/resolve/$REVISION"
    }
}
