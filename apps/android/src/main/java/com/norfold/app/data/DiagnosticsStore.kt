package com.norfold.app.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticsState(
    val enabled: Boolean = true,
    val includeDeviceInfo: Boolean = true,
    val askBeforeSharing: Boolean = true,
    val lastCrashAt: Long? = null,
    val lastCrashSummary: String? = null,
    val pendingCrashPrompt: Boolean = false,
    val logBytes: Long = 0L,
)

class DiagnosticsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("norfold_diagnostics", Context.MODE_PRIVATE)
    private val logFile: File = File(context.filesDir, "norfold-diagnostics.log")

    fun state(): DiagnosticsState = DiagnosticsState(
        enabled = prefs.getBoolean(KeyEnabled, true),
        includeDeviceInfo = prefs.getBoolean(KeyDeviceInfo, true),
        askBeforeSharing = prefs.getBoolean(KeyAskBeforeSharing, true),
        lastCrashAt = prefs.getLong(KeyLastCrashAt, 0L).takeIf { it > 0L },
        lastCrashSummary = prefs.getString(KeyLastCrashSummary, null),
        pendingCrashPrompt = prefs.getBoolean(KeyPendingCrashPrompt, false),
        logBytes = logFile.takeIf { it.exists() }?.length() ?: 0L,
    )

    fun update(enabled: Boolean? = null, includeDeviceInfo: Boolean? = null, askBeforeSharing: Boolean? = null): DiagnosticsState {
        prefs.edit()
            .apply { enabled?.let { putBoolean(KeyEnabled, it) } }
            .apply { includeDeviceInfo?.let { putBoolean(KeyDeviceInfo, it) } }
            .apply { askBeforeSharing?.let { putBoolean(KeyAskBeforeSharing, it) } }
            .apply()
        log("Diagnostics settings updated")
        return state()
    }

    fun log(message: String, throwable: Throwable? = null) {
        if (!prefs.getBoolean(KeyEnabled, true)) return
        val line = buildString {
            append(timestamp())
            append("  ")
            append(message.take(800))
            throwable?.let {
                append("\n")
                append(it.stackTraceToString().take(8_000))
            }
            append("\n")
        }
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(line)
            trimLog()
        }
    }

    fun recordCrash(throwable: Throwable) {
        val summary = "${throwable::class.java.simpleName}: ${throwable.message.orEmpty()}".take(240)
        prefs.edit()
            .putLong(KeyLastCrashAt, System.currentTimeMillis())
            .putString(KeyLastCrashSummary, summary)
            .putBoolean(KeyPendingCrashPrompt, true)
            .apply()
        log("Uncaught crash: $summary", throwable)
    }

    fun acknowledgeCrashPrompt(): DiagnosticsState {
        prefs.edit().putBoolean(KeyPendingCrashPrompt, false).apply()
        return state()
    }

    fun clear(): DiagnosticsState {
        runCatching { logFile.delete() }
        prefs.edit()
            .remove(KeyLastCrashAt)
            .remove(KeyLastCrashSummary)
            .remove(KeyLastExitTimestamp)
            .putBoolean(KeyPendingCrashPrompt, false)
            .apply()
        return state()
    }

    fun shareText(): String = buildString {
        appendLine("Norfold diagnostics")
        appendLine("Generated: ${timestamp()}")
        appendLine(appVersionLine())
        appendLine("Logging enabled: ${prefs.getBoolean(KeyEnabled, true)}")
        if (prefs.getBoolean(KeyDeviceInfo, true)) {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        }
        state().lastCrashSummary?.let { appendLine("Last crash: $it") }
        appendLine()
        append(logFile.takeIf { it.exists() }?.readText()?.takeLast(MaxShareChars) ?: "No log entries.")
    }

    fun shareIntent(): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Norfold diagnostics")
        .putExtra(Intent.EXTRA_TEXT, shareText())

    fun detectPreviousProcessExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            if (exit.timestamp <= prefs.getLong(KeyLastExitTimestamp, 0L)) return
            val reason = exit.reason
            if (reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == android.app.ApplicationExitInfo.REASON_ANR
            ) {
                val summary = "Previous process exit: ${reasonLabel(reason)} · importance=${exit.importance}"
                prefs.edit()
                    .putLong(KeyLastCrashAt, exit.timestamp)
                    .putString(KeyLastCrashSummary, summary)
                    .putBoolean(KeyPendingCrashPrompt, true)
                    .putLong(KeyLastExitTimestamp, exit.timestamp)
                    .apply()
                log(summary)
            } else {
                prefs.edit().putLong(KeyLastExitTimestamp, exit.timestamp).apply()
            }
        }
    }

    private fun appVersionLine(): String = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = PackageInfoCompat.getLongVersionCode(packageInfo)
        "App: ${context.packageName} ${packageInfo.versionName ?: "unknown"} ($code)"
    }.getOrDefault("App: ${context.packageName}")

    private fun reasonLabel(reason: Int): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        when (reason) {
            android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
            else -> "reason=$reason"
        }
    } else {
        "reason=$reason"
    }

    private fun trimLog() {
        if (!logFile.exists() || logFile.length() <= MaxLogBytes) return
        logFile.writeText(logFile.readText().takeLast(MaxLogBytes.toInt()))
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    companion object {
        private const val KeyEnabled = "enabled"
        private const val KeyDeviceInfo = "includeDeviceInfo"
        private const val KeyAskBeforeSharing = "askBeforeSharing"
        private const val KeyLastCrashAt = "lastCrashAt"
        private const val KeyLastCrashSummary = "lastCrashSummary"
        private const val KeyPendingCrashPrompt = "pendingCrashPrompt"
        private const val KeyLastExitTimestamp = "lastExitTimestamp"
        private const val MaxLogBytes = 256_000L
        private const val MaxShareChars = 180_000

        fun install(context: Context) {
            val store = DiagnosticsStore(context.applicationContext)
            store.detectPreviousProcessExit()
            store.log("App process started")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                store.recordCrash(throwable)
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
