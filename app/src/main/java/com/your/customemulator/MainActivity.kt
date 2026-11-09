package com.your.customemulator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // App එක open වෙද්දීම Native Files (Wine/Box64) Extract කරන්න පටන් ගන්නවා
        setupNativeEnvironment()

        val btnSelectGame = findViewById<Button>(R.id.btnSelectGame)
        val btnInstallGame = findViewById<Button>(R.id.btnInstallGame)
        val etGameName = findViewById<EditText>(R.id.etGameName)
        val etExePath = findViewById<EditText>(R.id.etExePath)

        btnSelectGame.setOnClickListener {
            val gameName = etGameName.text.toString()
            val exePath = etExePath.text.toString()

            if (gameName.isNotEmpty() && exePath.isNotEmpty()) {
                createDirectGameShortcut(this, gameName, exePath, android.R.drawable.ic_media_play)
                Toast.makeText(this, "Shortcut added to Home Screen!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter game name and .exe path", Toast.LENGTH_SHORT).show()
            }
        }

        btnInstallGame.setOnClickListener {
            val installerPath = etExePath.text.toString()
            if (installerPath.isNotEmpty()) {
                installGameAutomatically(installerPath)
            } else {
                Toast.makeText(this, "Please enter installer/setup path", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupNativeEnvironment() {
        // මේක අපි දැන් Foreground Service එකක් හරහා කරන නිසා, App එක crash වෙන්නේ නෑ
        val intent = Intent(this, ExtractionService::class.java)
        startService(intent)
    }

    // අපි අලුතින් හදන Extraction Service එක මෙතන තමයි
    class ExtractionService : android.app.Service() {
        override fun onBind(intent: Intent?): android.os.IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val notification = android.app.Notification.Builder(this, "extraction_channel")
                .setContentTitle("Extracting Emulator Files")
                .setContentText("Please wait, this may take a few minutes...")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .build()

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
    }

    private fun createDirectGameShortcut(context: Context, gameName: String, gameExePath: String, iconResId: Int) {
        val shortcutIntent = Intent(context, EmulatorActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("EXTRA_GAME_PATH", gameExePath)
        }

        val shortcutInfo = ShortcutInfoCompat.Builder(context, "game_${gameName.hashCode()}")
            .setShortLabel(gameName)
            .setLongLabel("Play $gameName")
            .setIcon(IconCompat.createWithResource(context, iconResId))
            .setIntent(shortcutIntent)
            .build()

        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
    }

    private fun installGameAutomatically(installerPath: String) {
        Thread {
            // අපේ අලුත් Paths ටික
            val imagefsDir = File(filesDir, "imagefs").absolutePath
            val winePrefix = File(filesDir, "wineprefix").absolutePath
            val box64Path = File(imagefsDir, "rootfs/usr/bin/box64").absolutePath
            val winePath = File(imagefsDir, "rootfs/usr/bin/wine64").absolutePath

            val installScript = StringBuilder().apply {
                append("export PROOT_NO_SECURE=1\n")
                append("export WINEPREFIX=$winePrefix\n")
                append("export WINEDEBUG=-all\n")
                append("$box64Path $winePath \"$installerPath\" /verysilent /suppressmsgboxes /norestart\n")
            }

            try {
                val process = ProcessBuilder("sh", "-c", installScript.toString())
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                runOnUiThread {
                    Toast.makeText(this, "Game Installed Successfully!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
