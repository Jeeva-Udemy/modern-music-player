package com.claude.musicplayer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes crash/error info to a file you can actually read afterward.
 *
 * IMPORTANT: raw paths like "/storage/emulated/" are not valid write
 * targets and require a runtime permission the app may not have yet at
 * the moment it crashes — writes there fail silently, which is why no
 * log file was showing up. This instead uses the app's own external
 * files directory, which every app can write to with zero permissions
 * on Android 4.4+ (scoped storage explicitly exempts it).
 *
 * On a typical device you'll find the file at:
 *   /storage/emulated/0/Android/data/com.claude.musicplayer/files/crash_log.txt
 * Browse there with any file manager (enable "show hidden/system files"
 * if your file manager doesn't show the Android/data folder by default).
 */
object CrashLogger {
    private const val TAG = "MusicPlayerCrash"
    private const val FILE_NAME = "crash_log.txt"

    fun logFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun log(context: Context, message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        try {
            val file = logFile(context) ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val stackTrace = throwable?.let {
                val sw = StringWriter()
                it.printStackTrace(PrintWriter(sw))
                sw.toString()
            } ?: ""
            file.appendText("[$timestamp] $message\n$stackTrace\n")
        } catch (e: Exception) {
            // If we can't write the log, there's nothing more useful to do.
        }
    }

    /** Installs a handler that logs any uncaught exception before the app closes. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log(appContext, "FATAL uncaught exception on thread ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
