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
            // 1. rootfs.tzst එක extract කරනවා
            Logger.log(context, "Extracting rootfs.tzst...")
            extractTzst(context, "rootfs.tzst", File(destDir, "rootfs"))
            
            // 2. rootfs_patches.tzst එක extract කරනවා (තියෙනවා නම්)
            val assets = context.assets.list("") ?: arrayOf()
            if (assets.contains("rootfs_patches.tzst")) {
                Logger.log(context, "Extracting rootfs_patches.tzst...")
                extractTzst(context, "rootfs_patches.tzst", File(destDir, "rootfs_patches"))
            }

            Logger.log(context, "--- Extraction Completed Successfully ---")
        } catch (e: Exception) {
            Logger.log(context, "CRITICAL ERROR: ${e.message}")
            e.stackTrace.forEach { element ->
                Logger.log(context, "  at $element")
            }
            throw e 
        }
    }

    private fun extractTzst(context: Context, assetName: String, destFile: File) {
        try {
            context.assets.open(assetName).use { inputStream ->
                BufferedInputStream(inputStream, BUFFER_SIZE).use { bufferedInputStream ->
                    ZstdCompressorInputStream(bufferedInputStream).use { zstdInputStream ->
                        TarArchiveInputStream(zstdInputStream).use { tarInputStream ->
                            
                            var entry: TarArchiveEntry? = tarInputStream.nextEntry
                            var count = 0
                            
                            while (entry != null) {
                                if (!tarInputStream.canReadEntryData(entry)) {
                                    entry = tarInputStream.nextEntry
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
                                if (count % 1000 == 0) {
                                    Logger.log(context, "$assetName: Extracted $count entries...")
                                }
                                
                                entry = tarInputStream.nextEntry
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
