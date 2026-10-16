package com.prosketch.app.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash handling: the stack trace is written to files/crash/last.txt and the open
 * artwork is saved synchronously before the process dies; the next launch offers to report it.
 */
object CrashLog {
    private var installed = false

    private fun file(ctx: Context) = File(ctx.filesDir, "crash/last.txt")

    fun install(ctx: Context, emergencySave: () -> Unit) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val f = file(app)
                f.parentFile?.mkdirs()
                f.writeText(buildString {
                    appendLine("ProSketch ${Updater.currentVersion(app)}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                    appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(sw.toString().take(12_000))
                })
            }
            runCatching { emergencySave() }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report of the previous crash, if the app crashed last time. */
    fun pending(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()

    fun clear(ctx: Context) { file(ctx).delete() }
}
