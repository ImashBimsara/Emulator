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
    
            // ✅ proot එක copy කරලා execute permission එක දෙන්න (මේක දැන් try එක ඇතුළේ)
            Logger.log(context, "Copying proot to usr/bin...")
            val prootSource = File(destDir, "rootfs/usr/bin/proot")
            if (!prootSource.exists()) {
                try {
                    context.assets.open("proot").use { input ->
                        FileOutputStream(prootSource).use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Execute permission එක දෙන්න (chmod 755)
                    prootSource.setExecutable(true)
                    Logger.log(context, "✅ Proot copied and made executable")
                } catch (e: Exception) {
                    Logger.log(context, "❌ Error copying proot: ${e.message}")
                }
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
