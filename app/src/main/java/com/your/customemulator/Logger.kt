package com.your.customemulator

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private const val LOG_FILE_NAME = "emulator_log.txt"
    private const val TAG = "EmulatorLog"

    fun log(context: Context, message: String) {
        try {
            // සජීවීව Logcat හි බලාගැනීම සඳහා
            Log.i(TAG, message)

            val publicDir = context.getExternalFilesDir(null)
            if (publicDir != null) {
                val logFile = File(publicDir, LOG_FILE_NAME)
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logMessage = "[$timestamp] $message\n"
                
                // ෆයිල් එකට ලියනවා (append mode)
                logFile.appendText(logMessage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearLog(context: Context) {
        try {
            val publicDir = context.getExternalFilesDir(null)
            if (publicDir != null) {
                val logFile = File(publicDir, LOG_FILE_NAME)
                if (logFile.exists()) {
                    logFile.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
