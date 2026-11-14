package com.your.customemulator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.clearLog(this)
        setupNativeEnvironment()

        val btnSelectGame = findViewById<Button>(R.id.btnSelectGame)
        val btnInstallGame = findViewById<Button>(R.id.btnInstallGame)
        val etGameName = findViewById<EditText>(R.id.etGameName)
        val etExePath = findViewById<EditText>(R.id.etExePath)

        // ✅ File Picker එක (Game Installer එක තෝරන්න)
        val pickInstallerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val fileName = getFileNameFromUri(this, uri)
                    etExePath.setText(fileName)
                    Toast.makeText(this, "Selected: $fileName", Toast.LENGTH_SHORT).show()
                    
                    // ෆයිල් එක App එකට copy කරන්න
                    val localFile = File(filesDir, "installer.exe")
                    copyFileToInternalStorage(this, uri, localFile)
                    
                    // දැන් Wine එකෙන් install කරන්න
                    installGameAutomatically(localFile.absolutePath)
                }
            }
        }

        btnInstallGame.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" // ඕනෑම ෆයිල් එකක් තෝරන්න පුළුවන්
            }
            pickInstallerLauncher.launch(intent)
        }

        // ✅ Game Shortcut එකක් හදන්න (ඔයාගේ කලින් එක)
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
    }

    // ✅ ෆයිල් එකේ නම ගන්න function එක
    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var fileName = "installer.exe"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
    }

    // ✅ ෆයිල් එක App එකට copy කරන function එක
    private fun copyFileToInternalStorage(context: Context, uri: Uri, destFile: File) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Logger.log(context, "✅ File copied to: ${destFile.absolutePath}")
        } catch (e: Exception) {
            Logger.log(context, "❌ Error copying file: ${e.message}")
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

                if (imagefsDir.exists()) {
                    Logger.log(this@MainActivity, "Deleting old imagefs folder...")
                    imagefsDir.deleteRecursively()
                }

                AssetExtractor.extractAssets(this@MainActivity)
                
                Logger.log(this@MainActivity, "Checking for Wine, Box64 and Proot...")
                val rootfsDir = File(imagefsDir, "rootfs")
                val wineFile = File(rootfsDir, "opt/wine/bin/wine")
                val box64File = File(rootfsDir, "usr/bin/box64")
                val prootFile = File(rootfsDir, "usr/bin/proot")
                
                Logger.log(this@MainActivity, "Wine exists: ${wineFile.exists()}")
                Logger.log(this@MainActivity, "Box64 exists: ${box64File.exists()}")
                Logger.log(this@MainActivity, "Proot exists: ${prootFile.exists()}")
                
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

    // ✅ Game Installer එක Wine එකෙන් run කරන function එක
    private fun installGameAutomatically(installerPath: String) {
        Thread {
            try {
                val imagefsDir = File(filesDir, "imagefs").absolutePath
                val rootfsDir = File(imagefsDir, "rootfs")
                
                // Wine එකට පේන විදිහට installer එක rootfs එක ඇතුළට copy කරන්න
                val internalInstaller = File(rootfsDir, "home/xuser/installer.exe")
                File(installerPath).copyTo(internalInstaller, overwrite = true)
                
                val winePath = File(rootfsDir, "opt/wine/bin/wine").absolutePath
                val box64Path = File(rootfsDir, "usr/bin/box64").absolutePath
                val prootPath = File(rootfsDir, "usr/bin/proot").absolutePath
                val winePrefix = File(filesDir, "wineprefix").absolutePath

                // Wine එකට rootfs එක ඇතුළේ path එක දෙන්න ඕනේ
                val wineInstallerPath = "/home/xuser/installer.exe"

                val installScript = StringBuilder().apply {
                    append("export PROOT_NO_SECURE=1\n")
                    append("export WINEPREFIX=$winePrefix\n")
                    append("export WINEDEBUG=-all\n")
                    append("export WINEARCH=win64\n")
                    append("export DISPLAY=:0\n")
                    append("$prootPath -r $rootfsDir $box64Path $winePath \"$wineInstallerPath\"\n")
                }

                Logger.log(this, "=== Starting Wine Installation ===")
                Logger.log(this, "Installer: $wineInstallerPath")
                Logger.log(this, "Proot: $prootPath")

                val process = ProcessBuilder("sh", "-c", installScript.toString())
                    .redirectErrorStream(true)
                    .start()
                    
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Logger.log(this, "WINE_INSTALL: $line")
                }
                val exitCode = process.waitFor()
                Logger.log(this, "Wine installation finished with code: $exitCode")
                
                runOnUiThread {
                    Toast.makeText(this, "Installation finished. Check log.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Logger.log(this, "Install Error: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Install Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
