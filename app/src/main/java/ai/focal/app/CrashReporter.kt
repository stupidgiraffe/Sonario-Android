package ai.focal.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal crash diagnostics. On an uncaught exception, writes the full stack
 * trace to a file the user (or you) can retrieve, then lets the default handler
 * run. On the next launch, MainActivity checks for that file and, if present,
 * shows the error on screen instead of the app silently crash-looping.
 *
 * The report remains in app-private storage and is shown once in-app. Its text
 * is redacted before persistence so provider credentials cannot be copied from
 * an exception message or stack trace.
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // never let the reporter itself mask the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun crashFile(context: Context): File {
        return File(context.filesDir, FILE)
    }

    private fun legacyCrashFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, FILE)
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("Focal crash report")
            appendLine("Time: $stamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            append(sw.toString())
        }
        crashFile(context).writeText(SensitiveDataRedactor.redact(text, MAX_REPORT_CHARS))
    }

    /** Returns the last crash text if one was recorded, else null. */
    fun consumeLastCrash(context: Context): String? {
        val f = listOfNotNull(crashFile(context), legacyCrashFile(context))
            .firstOrNull(File::isFile) ?: return null
        return try {
            val text = SensitiveDataRedactor.redact(f.readText(), MAX_REPORT_CHARS)
            f.delete()   // consume: show once, then clear
            text
        } catch (_: Throwable) { null }
    }

    private const val MAX_REPORT_CHARS = 64 * 1024
}
