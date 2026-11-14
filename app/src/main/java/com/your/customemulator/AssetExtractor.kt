package com.your.customemulator

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {
    private const val TAG = "AssetExtractor"
    private const val BUFFER_SIZE = 8192

    fun extractAssets(context: Context) {
        val destDir = File(context.filesDir, "imagefs")
        
        Logger.log(context, "--- Extraction Process Started ---")
    
        if (destDir.exists()) {
            Logger.log(context, "Deleting old imagefs folder...")
            destDir.deleteRecursively()
        }
        destDir.mkdirs()
    
        try {
            // 1. rootfs.tzst
            Logger.log(context, "Extracting rootfs.tzst...")
            extractTzst(context, "rootfs.tzst", File(destDir, "rootfs"))
    
            // 2. rootfs_patches.tzst
            Logger.log(context, "Extracting rootfs_patches.tzst...")
            extractTzst(context, "rootfs_patches.tzst", File(destDir, "rootfs_patches"))
    
            // 3. box64-0.4.4.tzst
            Logger.log(context, "Extracting box64-0.4.4.tzst...")
            val box64TempDir = File(destDir, "box64_temp")
            box64TempDir.mkdirs()
            extractTzst(context, "box64/box64-0.4.4.tzst", box64TempDir)
            
            val box64Source = File(box64TempDir, "usr/local/bin/box64")
            val usrBinDir = File(destDir, "rootfs/usr/bin")
            usrBinDir.mkdirs()
            
            if (box64Source.exists()) {
                Logger.log(context, "✅ Copying box64 from ${box64Source.absolutePath} to usr/bin...")
                box64Source.copyTo(File(usrBinDir, "box64"), overwrite = true)
                Logger.log(context, "✅ Box64 copied successfully!")
            } else {
                Logger.log(context, "❌ box64 executable not found at: ${box64Source.absolutePath}")
                box64TempDir.walk().forEach { file ->
                    Logger.log(context, "  Found in temp: ${file.relativeTo(box64TempDir)}")
                }
            }
            
            box64TempDir.deleteRecursively()
    
            // ✅ home/xuser ෆෝල්ඩර් එක හදන්න (Wine එකට ඕනේ)
            val xuserDir = File(destDir, "rootfs/home/xuser")
            if (!xuserDir.exists()) {
                xuserDir.mkdirs()
                Logger.log(context, "✅ Created home/xuser directory")
            }
    
            // ✅ Box64 එකට execute permission එක දෙන්න
            val box64File = File(usrBinDir, "box64")
            if (box64File.exists()) {
                box64File.setExecutable(true)
                try {
                    val chmodProcess = ProcessBuilder("chmod", "755", box64File.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val exitCode = chmodProcess.waitFor()
                    if (exitCode == 0) {
                        Logger.log(context, "✅ Box64 chmod 755 successful")
                    } else {
                        Logger.log(context, "❌ Box64 chmod failed with code: $exitCode")
                    }
                } catch (e: Exception) {
                    Logger.log(context, "❌ Error running chmod for box64: ${e.message}")
                }
                Logger.log(context, "✅ Box64 executable permission set")
            }
            
            // ✅ Wine එකට execute permission එක දෙන්න
            val wineFile = File(destDir, "rootfs/opt/wine/bin/wine")
            if (wineFile.exists()) {
                wineFile.setExecutable(true)
                Logger.log(context, "✅ Wine executable permission set")
            }
            
            // ✅ Wine ෆෝල්ඩර් එකේ තියෙන අනිත් ෆයිල්ස් වලටත් permission දෙන්න
            val wineDir = File(destDir, "rootfs/opt/wine/bin")
            if (wineDir.exists()) {
                wineDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true)
                    }
                }
                Logger.log(context, "✅ All wine bin files made executable")
            }
    
            Logger.log(context, "--- Extraction Completed Successfully ---")
        } catch (e: Exception) {
            Logger.log(context, "CRITICAL ERROR: ${e.message}")
            throw e 
        }
    }

    private fun extractTzst(context: Context, assetName: String, destFile: File) {
        try {
            context.assets.open(assetName).use { inputStream ->
                BufferedInputStream(inputStream, BUFFER_SIZE).use { bufferedInputStream ->
                    ZstdCompressorInputStream(bufferedInputStream).use { zstdInputStream ->
                        TarArchiveInputStream(zstdInputStream).use { tarInputStream ->
                            
                            var entry = tarInputStream.nextEntry as? TarArchiveEntry
                            var count = 0
                            
                            while (entry != null) {
                                if (!tarInputStream.canReadEntryData(entry)) {
                                    entry = tarInputStream.nextEntry as? TarArchiveEntry
                                    continue
                                }
    
                                val outFile = File(destFile, entry.name)
    
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    BufferedOutputStream(FileOutputStream(outFile), BUFFER_SIZE).use { out ->
                                        val buffer = ByteArray(BUFFER_SIZE)
                                        var len: Int
                                        while (tarInputStream.read(buffer).also { len = it } > 0) {
                                            out.write(buffer, 0, len)
                                        }
                                    }
                                }
                                
                                count++
                                entry = tarInputStream.nextEntry as? TarArchiveEntry
                            }
                            Logger.log(context, "Successfully extracted $assetName. Total: $count entries")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(context, "Error extracting $assetName: ${e.message}")
            throw e 
        }
    }
}
