package com.your.customemulator

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import android.system.Os

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
    
            // ✅ 3. /usr/bin ෆෝල්ඩර් එක හදන්න (box64 එක proot එකෙන් bind වෙනවා)
            val usrBinDir = File(destDir, "rootfs/usr/bin")
            usrBinDir.mkdirs()
            Logger.log(context, "✅ Created /usr/bin directory")
            
            // ✅ /root ෆෝල්ඩර් එක හදන්න
            val rootDir = File(destDir, "rootfs/root")
            if (!rootDir.exists()) {
                rootDir.mkdirs()
                Logger.log(context, "✅ Created /root directory")
            }

            // ✅ /lib ෆෝල්ඩර් එක හදලා linker copy කරන්න
            val rootfsLibDir = File(destDir, "rootfs/lib")
            if (rootfsLibDir.exists()) {
                rootfsLibDir.delete()
            }
            rootfsLibDir.mkdirs()
            Logger.log(context, "✅ Created /lib directory in rootfs")

            val guestLinker = File(destDir, "rootfs/usr/lib/ld-linux-aarch64.so.1")
            if (guestLinker.exists()) {
                guestLinker.copyTo(File(rootfsLibDir, "ld-linux-aarch64.so.1"), overwrite = true)
                Logger.log(context, "✅ Copied ld-linux-aarch64.so.1 to /lib")
            }

            // ✅ Android Linker එක rootfs එකට copy කරන්න
            val systemBinDir = File(destDir, "rootfs/system/bin")
            systemBinDir.mkdirs()
            val hostLinker = File("/system/bin/linker64")
            if (hostLinker.exists()) {
                hostLinker.copyTo(File(systemBinDir, "linker64"), overwrite = true)
                Logger.log(context, "✅ Copied host /system/bin/linker64 to rootfs")
            }

            val apexDir = File(destDir, "rootfs/apex/com.android.runtime/bin")
            apexDir.mkdirs()
            val hostApexLinker = File("/apex/com.android.runtime/bin/linker64")
            if (hostApexLinker.exists()) {
                hostApexLinker.copyTo(File(apexDir, "linker64"), overwrite = true)
                Logger.log(context, "✅ Copied host /apex/.../linker64 to rootfs")
            }
            
            // ✅ home/xuser ෆෝල්ඩර් එක හදන්න
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
            
            val wineDir = File(destDir, "rootfs/opt/wine/bin")
            if (wineDir.exists()) {
                wineDir.listFiles()?.forEach { file ->
                    if (file.isFile) file.setExecutable(true)
                }
                Logger.log(context, "✅ All wine bin files made executable")
            }
            
            // ✅ libtalloc.so.2 copy කරන්න
            val tallocDest = File(context.filesDir, "libtalloc.so.2")
            try {
                context.assets.open("libtalloc.so.2").use { input ->
                    FileOutputStream(tallocDest).use { output -> input.copyTo(output) }
                }
                Logger.log(context, "✅ libtalloc.so.2 copied successfully!")
            } catch (e: Exception) {
                Logger.log(context, "❌ Error copying libtalloc.so.2: ${e.message}")
            }

            // ✅ libandroid-shmem.so copy කරන්න
            val shmemDest = File(context.filesDir, "libandroid-shmem.so")
            try {
                context.assets.open("libandroid-shmem.so").use { input ->
                    FileOutputStream(shmemDest).use { output -> input.copyTo(output) }
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

    
    private fun extractTzst(
        context: Context,
        assetName: String,
        destFile: File
    ) {
        try {
            context.assets.open(assetName).use { inputStream ->
                BufferedInputStream(inputStream, BUFFER_SIZE).use { buffered ->
    
                    ZstdCompressorInputStream(buffered).use { zstd ->
    
                        TarArchiveInputStream(zstd).use { tar ->
    
                            var entry = tar.nextEntry as? TarArchiveEntry
                            var count = 0
                            var symlinkCount = 0
                            var fileCount = 0
    
                            val root = destFile.canonicalFile
    
                            while (entry != null) {
    
                                // Normalize archive path
                                val relativePath = entry.name
                                    .removePrefix("./")
                                    .trimStart('/')
    
                                val outFile = File(root, relativePath)
                                val canonical = outFile.canonicalFile
    
                                // Prevent path traversal
                                if (canonical != root &&
                                    !canonical.path.startsWith(root.path + File.separator)
                                ) {
                                    throw SecurityException(
                                        "Invalid archive path: ${entry.name}"
                                    )
                                }
    
                                if (entry.isDirectory) {
    
                                    outFile.mkdirs()
    
                                } else if (entry.isSymbolicLink) {
    
                                    outFile.parentFile?.mkdirs()
    
                                    if (outFile.exists() || Os.readlinkOrNull(outFile.absolutePath) != null) {
                                        outFile.delete()
                                    }
    
                                    Os.symlink(
                                        entry.linkName,
                                        outFile.absolutePath
                                    )
    
                                    symlinkCount++
    
                                } else if (entry.isLink) {
    
                                    throw IllegalStateException(
                                        "Hard link found: ${entry.name}"
                                    )
    
                                } else {
    
                                    if (!tar.canReadEntryData(entry)) {
                                        throw IllegalStateException(
                                            "Cannot read: ${entry.name}"
                                        )
                                    }
    
                                    outFile.parentFile?.mkdirs()
    
                                    BufferedOutputStream(
                                        FileOutputStream(outFile),
                                        BUFFER_SIZE
                                    ).use { output ->
    
                                        val buffer = ByteArray(BUFFER_SIZE)
                                        var len: Int
    
                                        while (
                                            tar.read(buffer).also { len = it } != -1
                                        ) {
                                            output.write(buffer, 0, len)
                                        }
                                    }
    
                                    // Preserve archive permissions
                                    Os.chmod(
                                        outFile.absolutePath,
                                        entry.mode and 0xFFF
                                    )
    
                                    fileCount++
                                }
    
                                count++
                                entry = tar.nextEntry as? TarArchiveEntry
                            }
    
                            Logger.log(
                                context,
                                "Extracted $assetName: $count entries"
                            )
    
                            Logger.log(
                                context,
                                "Regular files: $fileCount"
                            )
    
                            Logger.log(
                                context,
                                "Symbolic links: $symlinkCount"
                            )
                        }
                    }
                }
            }
    
        } catch (e: Exception) {
    
            Logger.log(
                context,
                "Extraction error ($assetName): ${e.message}"
            )
    
            throw e
        }
    }

}
