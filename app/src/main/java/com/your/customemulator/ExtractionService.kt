package com.your.customemulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExtractionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "extraction_channel")
                .setContentTitle("Extracting Emulator Files")
                .setContentText("Please wait, this may take a few minutes...")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Extracting Emulator Files")
                .setContentText("Please wait...")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .build()
        }

        startForeground(1, notification)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                AssetExtractor.extractAssets(this@ExtractionService)
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ExtractionService, "Emulator Core Files Ready!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ExtractionService, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "extraction_channel",
                "File Extraction",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
