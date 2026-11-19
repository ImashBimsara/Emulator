package com.your.customemulator

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    // X11 Display සඳහා අවශ්‍ය SurfaceView එක
    private lateinit var surfaceView: SurfaceView

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
        winePrefix: String,
        box64: String,
        wine: String,
        installer: String,
        logFilePath: String,
        nativeLibDir: String,
        filesDir: String
    ): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.clearLog(this)

        // 1. SurfaceView එක සකස් කිරීම
        surfaceView = findViewById(R.id.emulatorSurfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Logger.log(this@MainActivity, "Surface created successfully! Ready for X11 rendering.")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Logger.log(this@MainActivity, "Surface size changed to: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Logger.log(this@MainActivity, "Surface destroyed.")
            }
        })

        // 2. Native Environment (Rootfs extraction) ආරම්භ කිරීම
        setupNativeEnvironment()
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
                
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val box64File = File(nativeLibDir, "libbox64.so")
                val prootLibFile = File(nativeLibDir, "libproot.so")
                
                Logger.log(this@MainActivity, "Wine exists: ${wineFile.exists()}")
                Logger.log(this@MainActivity, "Box64 (lib) exists: ${box64File.exists()}")
                Logger.log(this@MainActivity, "Proot (lib) exists: ${prootLibFile.exists()}")
                
                withContext(Dispatchers.Main) {
                    val allReady = wineFile.exists() && box64File.exists() && prootLibFile.exists()
                    if (allReady) {
                        Toast.makeText(this@MainActivity, "SUCCESS! Starting Wine Automatically...", Toast.LENGTH_LONG).show()
                        
                        // 3. Extract වීම සාර්ථක වූ වහාම Wine ආරම්භ කිරීම
                        startWineAutomatically()
                        
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

    private fun startWineAutomatically() {
        Thread {
            try {
                val imagefsDir = File(filesDir, "imagefs").absolutePath
                val rootfsDir = File(imagefsDir, "rootfs").absolutePath
                
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val prootLibPath = File(nativeLibDir, "libproot.so").absolutePath
                val box64HostPath = File(nativeLibDir, "libbox64.so").absolutePath
                
                val wineInternalPath = "/opt/wine/bin/wine"
                
                // දැනට Installer එකක් නැති නිසා, Wine එකේ Default Explorer එක හෝ Wineboot ධාවනය කරමු
                val installerInternalPath = "c:\\windows\\system32\\wineboot.exe"
                
                val winePrefixHostPath = File(filesDir, "wineprefix").absolutePath
                val logFilePath = File(filesDir, "wine_output.log").absolutePath
    
                Logger.log(this, "=== Starting Wine Automatically via JNI ===")
                Logger.log(this, "Proot Lib: $prootLibPath")
                Logger.log(this, "Box64 Host Path: $box64HostPath")

                // DIAGNOSTIC LOGS
                val box64FullPath = File(box64HostPath)
                if (box64FullPath.exists()) {
                    try {
                        val bytes = ByteArray(4)
                        FileInputStream(box64FullPath).use { input ->
                            input.read(bytes)
                        }
                        Logger.log(this, "Box64 magic bytes: ${bytes.joinToString(" ") { String.format("%02X", it) }}")
                    } catch (e: Exception) {
                        Logger.log(this, "❌ Error reading box64 bytes: ${e.message}")
                    }
                }
    
                // C++ වෙත දත්ත යැවීම
                val result = runProotJNI(
                    prootLibPath, 
                    rootfsDir, 
                    winePrefixHostPath, 
                    box64HostPath, 
                    wineInternalPath, 
                    installerInternalPath,
                    logFilePath,
                    nativeLibDir,
                    filesDir.absolutePath 
                )
                
                Logger.log(this, "JNI returned: $result")
                
                val wineOutputFile = File(logFilePath)
                if (wineOutputFile.exists()) {
                    Logger.log(this, "=== Wine Output ===")
                    wineOutputFile.readLines().forEach { line ->
                        Logger.log(this, "WINE_OUTPUT: $line")
                    }
                }
                
                runOnUiThread {
                    Toast.makeText(this, "Wine execution finished. Check log.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Logger.log(this, "Wine Error: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Wine Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
