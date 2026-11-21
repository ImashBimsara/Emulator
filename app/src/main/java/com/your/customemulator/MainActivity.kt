package com.your.customemulator

import android.content.Context
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

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

        // 2. Native Environment (Rootfs සහ Wine) ආරම්භ කිරීම
        setupNativeEnvironment()
    }

    // අලුත් Wine ZIP Parts ටික එකතු කර Extract කරන Function එක
    private fun extractWineParts(context: Context, destDir: String) {
        val assetManager = context.assets
        val wineDestDir = File(destDir, "opt/wine")

        // Wine කලින් Extract වෙලා නම් ආයෙත් කරන්නේ නැහැ
        if (wineDestDir.exists() && wineDestDir.listFiles()?.isNotEmpty() == true) {
            Logger.log(context, "Wine is already extracted.")
            return
        }

        Logger.log(context, "Starting to extract Custom Wine...")
        
        try {
            // 1. Assets/wine_parts වල තියෙන කෑලි ටික හොයාගැනීම
            val parts = assetManager.list("wine_parts")?.filter { it.startsWith("wine_part_") }?.sorted()
            if (parts.isNullOrEmpty()) {
                Logger.log(context, "❌ ERROR: No Wine parts found in assets/wine_parts/")
                return
            }

            // 2. කෑලි ටික එකතු කරලා තාවකාලික tar.gz එකක් සෑදීම
            val tempTarGz = File(context.cacheDir, "patched-wine.tar.gz")
            FileOutputStream(tempTarGz).use { output ->
                for (part in parts) {
                    assetManager.open("wine_parts/$part").use { input ->
                        input.copyTo(output)
                    }
                }
            }
            Logger.log(context, "Combined ${parts.size} parts successfully into ${tempTarGz.name}.")

            // 3. Apache Commons Compress භාවිතා කර tar.gz එක opt/wine ෆෝල්ඩරයට Extract කිරීම
            FileInputStream(tempTarGz).use { fi ->
                GZIPInputStream(fi).use { gzi ->
                    TarArchiveInputStream(gzi).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            // entry.name සාමාන්‍යයෙන් "opt/wine/..." ලෙස පැමිණෙන නිසා කෙලින්ම destDir (rootfs) වෙත දාන්න පුළුවන්
                            val outputFile = File(destDir, entry.name)
                            if (entry.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                outputFile.parentFile?.mkdirs()
                                FileOutputStream(outputFile).use { fos ->
                                    tar.copyTo(fos)
                                }
                                outputFile.setExecutable(true, false) // බයිනරි වලට permission දීම
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
            
            // 4. තාවකාලික tar.gz එක මකා දැමීම
            tempTarGz.delete()
            Logger.log(context, "Custom Wine Extraction Completed Successfully!")

        } catch (e: Exception) {
            Logger.log(context, "❌ Error extracting Wine: ${e.message}")
            e.printStackTrace()
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
                    // අපි පළවෙනි වතාවට අලුත් Wine එක දාන නිසා, පරණ එක ඩිලීට් කිරීම හොඳයි
                    Logger.log(this@MainActivity, "Deleting old imagefs folder...")
                    imagefsDir.deleteRecursively()
                }

                // 1. මුලින්ම Ubuntu Rootfs එක Extract කිරීම (ඔබේ පැරණි කේතය)
                AssetExtractor.extractAssets(this@MainActivity)

                val rootfsDir = File(imagefsDir, "rootfs")
                
                // 2. අලුත් Custom Wine එක Rootfs එක ඇතුළට (opt/wine) Extract කිරීම
                extractWineParts(this@MainActivity, rootfsDir.absolutePath)

                Logger.log(this@MainActivity, "Checking for Wine, Box64 and Proot...")
                val wineFile = File(rootfsDir, "opt/wine/bin/wine") // අලුත් Wine path එක

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

                // අලුතින් සාදාගත් Wine එකේ Path එක
                val wineInternalPath = "/opt/wine/bin/wine"

                val installerInternalPath = "c:\\windows\\system32\\wineboot.exe"

                val winePrefixHostPath = File(filesDir, "wineprefix").absolutePath
                val publicDir = getExternalFilesDir(null) ?: filesDir
                val logFilePath = File(publicDir, "wine_output.log").absolutePath

                Logger.log(this, "=== Starting Custom Wine Automatically via JNI ===")
                Logger.log(this, "Proot Lib: $prootLibPath")
                Logger.log(this, "Wine Path: $wineInternalPath")
                Logger.log(this, "Box64 Host Path: $box64HostPath")
                Logger.log(this, "Wine Output Log: $logFilePath")

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
