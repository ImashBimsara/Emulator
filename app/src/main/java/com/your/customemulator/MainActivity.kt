package com.your.customemulator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
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

    private var wakeLock: PowerManager.WakeLock? = null

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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Screen එක නිදාගන්න දෙන්නේ නැතුව තියාගන්න WakeLock එකක් ගන්නවා
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CustomEmulator::ExtractionLock")
                wakeLock?.acquire(30 * 60 * 1000L) // විනාඩි 30ක් උපරිම

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Extracting Emulator Files... Please wait.", Toast.LENGTH_LONG).show()
                }

                // 2. Assets extract කරනවා
                AssetExtractor.extractAssets(this@MainActivity)
                
                // 3. Extract වුණාට පස්සේ ෆෝන් එකේ storage එකේ තියෙන files ගාන බලනවා
                val imagefsDir = File(filesDir, "imagefs")
                val filesCount = imagefsDir.listFiles()?.size ?: 0
                val isRootfsReady = File(imagefsDir, "rootfs").exists()
                val isWineReady = File(imagefsDir, "rootfs/usr/bin/wine64").exists()
                
                withContext(Dispatchers.Main) {
                    val statusMessage = "Done! Files: $filesCount\nRootfs: $isRootfsReady\nWine: $isWineReady"
                    Toast.makeText(this@MainActivity, statusMessage, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // 4. වැඩේ ඉවර වුණාම WakeLock එක අත්හැරිය යුතුයි
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
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
