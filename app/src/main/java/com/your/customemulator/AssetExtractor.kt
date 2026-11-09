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

            Logger.log(context, "Copying normal assets...")
            val normalAssets = arrayOf("box64", "dxwrapper", "graphics_driver", "inputcontrols", "soundfont", "wallpapers", "wincomponents")
            for (asset in normalAssets) {
                Logger.log(context, "Copying folder: $asset")
                copyAssetFolder(context, asset, File(destDir, asset))
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
                BufferedInputStream(inputStream, 8192).use { bufferedInputStream ->
                    ZstdCompressorInputStream(bufferedInputStream).use { zstdInputStream ->
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
                                        val buffer = ByteArray(4096)
                                        var len: Int
                                        while (tarInputStream.read(buffer).also { len = it } > 0) {
                                            out.write(buffer, 0, len)
                                        }
                                    }
                                }
                                count++
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

    private fun copyAssetFolder(context: Context, assetPath: String, destFolder: File) {
        val files = context.assets.list(assetPath)
        if (files.isNullOrEmpty()) {
            copyAssetFile(context, assetPath, destFolder)
        } else {
            destFolder.mkdirs()
            for (file in files) {
                copyAssetFolder(context, "$assetPath/$file", File(destFolder, file))
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        try {
            context.assets.open(assetPath).use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var length: Int
                    while (inputStream.read(buffer).also { length = it } > 0) {
                        outputStream.write(buffer, 0, length)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(context, "Error copying file $assetPath: ${e.message}")
        }
    }
}
