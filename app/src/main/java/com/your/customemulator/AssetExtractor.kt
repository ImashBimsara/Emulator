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
            
            // ✅ box64 ෆෝල්ඩර් එකේ තියෙන ඔක්කොම (executable + libs) rootfs එකට copy කරන්න
            Logger.log(context, "Copying all box64 files to rootfs...")
            box64TempDir.copyRecursively(File(destDir, "rootfs"), overwrite = true)
            
            // ✅ box64 එක /usr/bin එකටත් copy කරන්න (proot එකට හම්බවෙන්න)
            val box64Source = File(box64TempDir, "usr/local/bin/box64")
            val usrBinDir = File(destDir, "rootfs/usr/bin")
            usrBinDir.mkdirs()
            if (box64Source.exists()) {
                box64Source.copyTo(File(usrBinDir, "box64"), overwrite = true)
                Logger.log(context, "✅ Box64 copied to /usr/bin")
            }
            
            // ✅ /root ෆෝල්ඩර් එක හදන්න (proot warning එක නවත්තන්න)
            val rootDir = File(destDir, "rootfs/root")
            if (!rootDir.exists()) {
                rootDir.mkdirs()
                Logger.log(context, "✅ Created /root directory")
            }

            // ✅ lib ෆෝල්ඩර් එක හදලා interpreter (linker) එක copy කරන්න
            val libDir = File(destDir, "rootfs/lib")
            if (!libDir.exists()) {
                libDir.mkdirs()
            }
            
            val guestLinker = File(destDir, "rootfs/usr/lib/ld-linux-aarch64.so.1")
            if (guestLinker.exists()) {
                guestLinker.copyTo(File(libDir, "ld-linux-aarch64.so.1"), overwrite = true)
                Logger.log(context, "✅ Copied ld-linux-aarch64.so.1 to /lib")
            } else {
                Logger.log(context, "❌ ld-linux-aarch64.so.1 not found in usr/lib!")
            }
            
            // ✅ box64 එකට execute permission එක දෙන්න (usr/local/bin/box64)
            val box64LocalFile = File(destDir, "rootfs/usr/local/bin/box64")
            if (box64LocalFile.exists()) {
                box64LocalFile.setExecutable(true)
                try {
                    val chmodProcess = ProcessBuilder("chmod", "755", box64LocalFile.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val exitCode = chmodProcess.waitFor()
                    if (exitCode == 0) {
                        Logger.log(context, "✅ Box64 chmod 755 successful at usr/local/bin")
                    } else {
                        Logger.log(context, "❌ Box64 chmod failed at usr/local/bin with code: $exitCode")
                    }
                } catch (e: Exception) {
                    Logger.log(context, "❌ Error running chmod for box64 at usr/local/bin: ${e.message}")
                }
            }

            // ✅ box64 එකට execute permission එක දෙන්න (usr/bin/box64)
            val box64File = File(usrBinDir, "box64")
            if (box64File.exists()) {
                box64File.setExecutable(true)
                try {
                    val chmodProcess = ProcessBuilder("chmod", "755", box64File.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val exitCode = chmodProcess.waitFor()
                    if (exitCode == 0) {
                        Logger.log(context, "✅ Box64 chmod 755 successful at usr/bin")
                    } else {
                        Logger.log(context, "❌ Box64 chmod failed at usr/bin with code: $exitCode")
                    }
                } catch (e: Exception) {
                    Logger.log(context, "❌ Error running chmod for box64 at usr/bin: ${e.message}")
                }
            }
            
            box64TempDir.deleteRecursively()
    
            // ✅ home/xuser ෆෝල්ඩර් එක හදන්න (Wine එකට ඕනේ)
            val xuserDir = File(destDir, "rootfs/home/xuser")
            if (!xuserDir.exists()) {
                xuserDir.mkdirs()
                Logger.log(context, "✅ Created home/xuser directory")
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
            
            // ✅ libtalloc.so.2 එක assets එකෙන් filesDir එකට copy කරන්න
            Logger.log(context, "Copying libtalloc.so.2 to filesDir...")
            val tallocDest = File(context.filesDir, "libtalloc.so.2")
            try {
                context.assets.open("libtalloc.so.2").use { input ->
                    FileOutputStream(tallocDest).use { output ->
                        input.copyTo(output)
                    }
                }
                Logger.log(context, "✅ libtalloc.so.2 copied successfully!")
            } catch (e: Exception) {
                Logger.log(context, "❌ Error copying libtalloc.so.2: ${e.message}")
            }

            // ✅ libandroid-shmem.so එක assets එකෙන් filesDir එකට copy කරන්න
            Logger.log(context, "Copying libandroid-shmem.so to filesDir...")
            val shmemDest = File(context.filesDir, "libandroid-shmem.so")
            try {
                context.assets.open("libandroid-shmem.so").use { input ->
                    FileOutputStream(shmemDest).use { output ->
                        input.copyTo(output)
                    }
                }
                Logger.log(context, "✅ libandroid-shmem.so copied successfully!")
            } catch (e: Exception) {
                Logger.log(context, "❌ Error copying libandroid-shmem.so: ${e.message}")
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
