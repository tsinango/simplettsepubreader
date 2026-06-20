package com.example.epubreader

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private const val LOG_DIRECTORY = "diagnostics"
    private const val CURRENT_LOG = "tts-current.log"
    private const val PREVIOUS_LOG = "tts-previous.log"
    private const val MAX_LOG_BYTES = 1024L * 1024L
    private const val MAX_EXIT_TRACE_BYTES = 64 * 1024
    private const val PREFERENCES = "diagnostic_log"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"

    @Volatile private var applicationContext: Context? = null
    @Volatile private var store: DiagnosticFileStore? = null

    fun initialize(context: Context) {
        if (applicationContext != null) return
        synchronized(this) {
            if (applicationContext != null) return
            val appContext = context.applicationContext
            applicationContext = appContext
            store = DiagnosticFileStore(
                directory = File(appContext.filesDir, LOG_DIRECTORY),
                currentName = CURRENT_LOG,
                previousName = PREVIOUS_LOG,
                maxBytes = MAX_LOG_BYTES,
            )
            event(
                "APP",
                "session_start version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                    "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} " +
                    "abis=${Build.SUPPORTED_ABIS.joinToString()}",
            )
            recordPreviousExit(appContext)
            installUncaughtExceptionHandler()
        }
    }

    fun event(category: String, message: String) {
        val context = applicationContext ?: return
        runCatching {
            store?.append(
                DiagnosticLogFormatter.line(
                    category = category,
                    message = DiagnosticLogFormatter.redact(
                        message,
                        listOf(context.filesDir.absolutePath, context.cacheDir.absolutePath),
                    ),
                    wallTimeMillis = System.currentTimeMillis(),
                    uptimeMillis = SystemClock.elapsedRealtime(),
                    threadName = Thread.currentThread().name,
                ),
            )
        }
    }

    fun error(category: String, message: String, throwable: Throwable? = null) {
        val details = if (throwable == null) {
            message
        } else {
            val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            "$message\n$stack"
        }
        event(category, details)
    }

    fun bookToken(bookId: String?): String =
        bookId?.let(DiagnosticLogFormatter::shortHash) ?: "none"

    fun export(context: Context, uri: Uri): Result<Unit> = runCatching {
        val destination = context.contentResolver.openOutputStream(uri, "w")
            ?: error("无法打开目标文件")
        destination.use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                writer.appendLine("TTS Reader diagnostics")
                writer.appendLine("exported=${DiagnosticLogFormatter.timestamp(System.currentTimeMillis())}")
                writer.appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                writer.appendLine("android=${Build.VERSION.SDK_INT}")
                writer.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                writer.appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
                writer.appendLine("privacy=book text, title and absolute app paths are excluded")
                writer.appendLine()
                store?.exportTo(writer)
            }
        }
    }

    fun defaultExportFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "tts-reader-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))}.txt"

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is DiagnosticUncaughtExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(DiagnosticUncaughtExceptionHandler(previous))
    }

    private fun recordPreviousExit(context: Context) {
        runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val exit = activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull()
                ?: return
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (exit.timestamp <= preferences.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)) return
            preferences.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, exit.timestamp).apply()
            event(
                "PROCESS_EXIT",
                "timestamp=${DiagnosticLogFormatter.timestamp(exit.timestamp)} " +
                    "reason=${exitReason(exit.reason)} status=${exit.status} " +
                    "importance=${exit.importance} pss=${exit.pss} rss=${exit.rss} " +
                    "description=${exit.description.orEmpty()}",
            )
            exit.traceInputStream?.use { input ->
                val trace = ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(4096)
                    var remaining = MAX_EXIT_TRACE_BYTES
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    output.toByteArray()
                }
                if (trace.isNotEmpty()) {
                    event("PROCESS_EXIT_TRACE", encodeTrace(trace))
                }
            }
        }.onFailure { error("PROCESS_EXIT", "failed_to_read_previous_exit", it) }
    }

    private fun encodeTrace(bytes: ByteArray): String {
        val text = bytes.toString(StandardCharsets.UTF_8)
        val printable = text.count { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
        return if (text.isNotEmpty() && printable * 100 / text.length >= 90) {
            text
        } else {
            "base64=${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        else -> reason.toString()
    }

    private class DiagnosticUncaughtExceptionHandler(
        private val delegate: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            error("UNCAUGHT", "thread=${thread.name}", throwable)
            delegate?.uncaughtException(thread, throwable)
        }
    }
}

internal class DiagnosticFileStore(
    private val directory: File,
    private val currentName: String,
    private val previousName: String,
    private val maxBytes: Long,
) {
    private val current get() = File(directory, currentName)
    private val previous get() = File(directory, previousName)

    @Synchronized
    fun append(value: String) {
        directory.mkdirs()
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (current.isFile && current.length() + bytes.size > maxBytes) {
            previous.delete()
            current.renameTo(previous)
        }
        FileOutputStream(current, true).use { it.write(bytes) }
    }

    @Synchronized
    fun exportTo(writer: Appendable) {
        listOf(previous, current).filter(File::isFile).forEach { file ->
            writer.append("===== ${file.name} =====\n")
            file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { writer.append(it).append('\n') }
            }
        }
    }
}

internal object DiagnosticLogFormatter {
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    }

    fun line(
        category: String,
        message: String,
        wallTimeMillis: Long,
        uptimeMillis: Long,
        threadName: String,
    ): String =
        "${timestamp(wallTimeMillis)} uptime=$uptimeMillis thread=$threadName [$category] $message\n"

    fun timestamp(value: Long): String = timestampFormat.get().format(Date(value))

    fun redact(value: String, privateRoots: List<String>): String =
        privateRoots.filter(String::isNotBlank).fold(value) { result, root ->
            result.replace(root, "<app-private>")
        }

    fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
