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

        // පරණ Logs ටික මකලා අලුතෙන් පටන් ගන්න
        Logger.clearLog(this)

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
            Logger.log(this@MainActivity, "App started. Checking files...")
            
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Checking files...", Toast.LENGTH_SHORT).show()
            }

            val imagefsDir = File(filesDir, "imagefs")
            val assetFiles = assets.list("") ?: arrayOf()
            val hasRootfs = assetFiles.contains("rootfs.tzst")

            if (!hasRootfs) {
                Logger.log(this@MainActivity, "ERROR: rootfs.tzst NOT FOUND in assets!")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: rootfs.tzst NOT FOUND!", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            Logger.log(this@MainActivity, "Files found. Starting extraction...")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Extracting... Please wait.", Toast.LENGTH_LONG).show()
            }

            // Extract කරන්න කලින් පරණ ෆයිල්ස් තියෙනවා නම් මකන්න
            if (imagefsDir.exists()) {
                Logger.log(this@MainActivity, "Deleting old imagefs folder...")
                imagefsDir.deleteRecursively()
            }

            // Extraction එක run කරන්න
            AssetExtractor.extractAssets(this@MainActivity)
            
            // ========== Wine, Box64 සහ Proot චෙක් කරන්න ==========
            Logger.log(this@MainActivity, "Checking for Wine, Box64 and Proot...")
            
            val rootfsDir = File(imagefsDir, "rootfs")
            
            // 1. ෆයිල්ස් තියෙනවද බලන්න
            val wineFile = File(rootfsDir, "opt/wine/bin/wine")
            val box64File = File(rootfsDir, "usr/bin/box64")
            val prootFile = File(rootfsDir, "usr/bin/proot")
            
            Logger.log(this@MainActivity, "Wine exists: ${wineFile.exists()}")
            Logger.log(this@MainActivity, "Box64 exists: ${box64File.exists()}")
            Logger.log(this@MainActivity, "Proot exists: ${prootFile.exists()}")
            
            // 2. ඒවා Run කරන්න පුළුවන් (Executable) ද බලන්න
            if (wineFile.exists()) {
                Logger.log(this@MainActivity, "Wine can execute: ${wineFile.canExecute()}")
            }
            if (box64File.exists()) {
                Logger.log(this@MainActivity, "Box64 can execute: ${box64File.canExecute()}")
            }
            if (prootFile.exists()) {
                Logger.log(this@MainActivity, "Proot can execute: ${prootFile.canExecute()}")
            }
            
            withContext(Dispatchers.Main) {
                val allReady = wineFile.exists() && box64File.exists() && prootFile.exists()
                if (allReady) {
                    Toast.makeText(this@MainActivity, "SUCCESS! Wine, Box64 and Proot are ready.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Extraction done but some files are missing. Check log.", Toast.LENGTH_LONG).show()
                }
            }

        } catch (e: Exception) {
            Logger.log(this@MainActivity, "CRASH ERROR: ${e.message}")
            e.stackTrace.forEach { element ->
                Logger.log(this@MainActivity, "  at $element")
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "CRASH: ${e.message}", Toast.LENGTH_LONG).show()
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
            
            // Wine තියෙන ඇත්ත තැන
            val winePath = File(imagefsDir, "rootfs/opt/wine/bin/wine").absolutePath
            
            // Box64 එක තියෙන තැන
            val box64Path = File(imagefsDir, "rootfs/usr/bin/box64").absolutePath
            
            // Wine Prefix එක
            val winePrefix = File(filesDir, "wineprefix").absolutePath

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
