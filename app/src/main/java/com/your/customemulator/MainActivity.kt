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

        // App එක open වෙද්දීම Error එකක් ආවොත් ඒක පෙන්නන්න
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Checking files...", Toast.LENGTH_SHORT).show()
                }

                // 1. ෆෝල්ඩර් එක තියෙනවද බලනවා
                val imagefsDir = File(filesDir, "imagefs")
                if (!imagefsDir.exists()) {
                    imagefsDir.mkdirs()
                }

                // 2. assets එකේ rootfs.tzst එක තියෙනවද බලනවා
                val assetFiles = assets.list("") ?: arrayOf()
                val hasRootfs = assetFiles.contains("rootfs.tzst")
                val hasPatches = assetFiles.contains("rootfs_patches.tzst")

                withContext(Dispatchers.Main) {
                    if (!hasRootfs) {
                        Toast.makeText(this@MainActivity, "Error: rootfs.tzst NOT FOUND in assets!", Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                }

                // 3. Extract කරන්න පටන් ගන්නවා
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Found files. Extracting... Please wait.", Toast.LENGTH_LONG).show()
                }

                AssetExtractor.extractAssets(this@MainActivity)
                
                // 4. ඉවර වුණාට පස්සේ check කරනවා
                val isWineReady = File(imagefsDir, "rootfs/usr/bin/wine64").exists()
                
                withContext(Dispatchers.Main) {
                    if (isWineReady) {
                        Toast.makeText(this@MainActivity, "SUCCESS! Wine is ready.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Extraction failed. Wine not found.", Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                // මොනවා හරි Error එකක් ආවොත් ඒක පෙන්නන්න
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "CRASH ERROR: ${e.message}", Toast.LENGTH_LONG).show()
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
