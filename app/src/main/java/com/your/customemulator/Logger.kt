package com.your.customemulator

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private const val LOG_FILE_NAME = "emulator_log.txt"

    fun log(context: Context, message: String) {
        try {
            val logFile = File(context.getExternalFilesDir(null), LOG_FILE_NAME)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logMessage = "[$timestamp] $message\n"
            
            // ෆයිල් එකට ලියනවා (append mode)
            logFile.appendText(logMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearLog(context: Context) {
        try {
            val logFile = File(context.getExternalFilesDir(null), LOG_FILE_NAME)
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
