package com.your.customemulator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

    companion object {
        init {
            try {
                System.loadLibrary("prootjni")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    external fun runProotJNI(
        prootLibPath: String,
        rootfs: String,
        box64: String,
        wine: String,
        installer: String
    ): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.clearLog(this)
        setupNativeEnvironment()

        val btnSelectGame = findViewById<Button>(R.id.btnSelectGame)
        val btnInstallGame = findViewById<Button>(R.id.btnInstallGame)
        val etGameName = findViewById<EditText>(R.id.etGameName)
        val tvSelectedInstaller = findViewById<TextView>(R.id.tvSelectedInstaller)

        val pickInstallerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val fileName = getFileNameFromUri(this, uri)
                    tvSelectedInstaller.text = fileName
                    Toast.makeText(this, "Selected: $fileName", Toast.LENGTH_SHORT).show()
                    
                    val localFile = File(filesDir, "installer.exe")
                    copyFileToInternalStorage(this, uri, localFile)
                    
                    installGameAutomatically(localFile.absolutePath)
                }
            }
        }

        btnInstallGame.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            pickInstallerLauncher.launch(intent)
        }

        btnSelectGame.setOnClickListener {
            val gameName = etGameName.text.toString()
            val installerName = tvSelectedInstaller.text.toString()

            if (gameName.isNotEmpty() && installerName.isNotEmpty()) {
                createDirectGameShortcut(this, gameName, installerName, android.R.drawable.ic_media_play)
                Toast.makeText(this, "Shortcut added to Home Screen!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter game name and select installer", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val prootLibFile = File(nativeLibDir, "libproot.so")
                
                Logger.log(this@MainActivity, "Wine exists: ${wineFile.exists()}")
                Logger.log(this@MainActivity, "Box64 exists: ${box64File.exists()}")
                Logger.log(this@MainActivity, "Proot (lib) exists: ${prootLibFile.exists()}")
                
                withContext(Dispatchers.Main) {
                    val allReady = wineFile.exists() && box64File.exists() && prootLibFile.exists()
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
            try {
                val imagefsDir = File(filesDir, "imagefs").absolutePath
                val rootfsDir = File(imagefsDir, "rootfs").absolutePath // ✅ String එකක් කළා
                
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val prootLibPath = File(nativeLibDir, "libproot.so").absolutePath
                
                val internalInstaller = File(rootfsDir, "home/xuser/installer.exe")
                File(installerPath).copyTo(internalInstaller, overwrite = true)
                
                val winePath = File(rootfsDir, "opt/wine/bin/wine").absolutePath
                val box64Path = File(rootfsDir, "usr/bin/box64").absolutePath
                val wineInstallerPath = "/home/xuser/installer.exe"

                Logger.log(this, "=== Starting Wine Installation via JNI ===")
                Logger.log(this, "Proot Lib: $prootLibPath")
                Logger.log(this, "Installer: $wineInstallerPath")

                // ✅ JNI function එක call කරන්න (දැන් rootfsDir කියන්නේ String එකක්)
                val result = runProotJNI(prootLibPath, rootfsDir, box64Path, winePath, wineInstallerPath)
                
                Logger.log(this, "JNI returned: $result")
                
                runOnUiThread {
                    Toast.makeText(this, "JNI installation finished. Check log.", Toast.LENGTH_LONG).show()
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
