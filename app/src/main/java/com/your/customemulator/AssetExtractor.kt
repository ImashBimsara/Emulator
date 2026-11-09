package com.your.customemulator

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {
    private const val TAG = "AssetExtractor"

    fun extractAssets(context: Context) {
        val destDir = File(context.filesDir, "imagefs")
        
        Logger.log(context, "--- Extraction Process Started ---")

        // පරණ ෆෝල්ඩර් එක මකන්න
        if (destDir.exists()) {
            Logger.log(context, "Deleting old imagefs folder...")
            destDir.deleteRecursively()
        }
        destDir.mkdirs()

        try {
            Logger.log(context, "Extracting rootfs.tzst...")
            extractTzst(context, "rootfs.tzst", File(destDir, "rootfs"))
            Logger.log(context, "Finished rootfs.tzst extraction")

        } catch (e: Exception) {
            Logger.log(context, "CRITICAL ERROR in AssetExtractor: ${e.message}")
            e.stackTrace.forEach { element ->
                Logger.log(context, "  at $element")
            }
            throw e 
        }
    }

    private fun extractTzst(context: Context, assetName: String, destFile: File) {
        try {
            Logger.log(context, "Opening $assetName from assets...")
            
            context.assets.open(assetName).use { inputStream ->
                Logger.log(context, "Creating BufferedInputStream...")
                BufferedInputStream(inputStream, 16384).use { bufferedInputStream ->
                    
                    Logger.log(context, "Creating ZstdCompressorInputStream...")
                    ZstdCompressorInputStream(bufferedInputStream).use { zstdInputStream ->
                        
                        Logger.log(context, "Creating TarArchiveInputStream...")
                        TarArchiveInputStream(zstdInputStream).use { tarInputStream ->
                            
                            var entry = tarInputStream.nextEntry
                            var count = 0
                            
                            while (entry != null) {
                                var entryName = entry.name
                                if (entryName.startsWith("./")) {
                                    entryName = entryName.substring(2)
                                }
                                
                                val outFile = File(destFile, entryName)
                                
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { out ->
                                        val buffer = ByteArray(8192)
                                        var len: Int
                                        while (tarInputStream.read(buffer).also { len = it } > 0) {
                                            out.write(buffer, 0, len)
                                        }
                                    }
                                }
                                count++
                                
                                // සෑම 1000කට වරක් log කරන්න
                                if (count % 1000 == 0) {
                                    Logger.log(context, "Extracted $count entries so far...")
                                }
                                
                                entry = tarInputStream.nextEntry
                            }
                            Logger.log(context, "Successfully extracted $assetName. Total entries: $count")
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
