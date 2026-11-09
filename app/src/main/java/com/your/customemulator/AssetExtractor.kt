package com.your.customemulator

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object AssetExtractor {
    private const val TAG = "MY_EXTRACTOR_TEST"

    fun extractAssets(context: Context) {
        val destDir = File(context.filesDir, "imagefs")
        
        Log.e(TAG, "Extraction Process Started!")

        // දැනටමත් extract වෙලා නම් ආයේ කරන්නේ නෑ
        if (destDir.exists() && destDir.listFiles()?.isNotEmpty() == true) {
            Log.e(TAG, "Already extracted! Skipping...")
            return
        }
        
        destDir.mkdirs()

        try {
            // 1. rootfs එක extract කරනවා
            Log.e(TAG, "Extracting rootfs.tzst...")
            extractTzst(context, "rootfs.tzst", destDir)

            // 2. rootfs_patches
            Log.e(TAG, "Extracting rootfs_patches.tzst...")
            extractTzst(context, "rootfs_patches.tzst", destDir)

            // 3. අනිත් සාමාන්‍ය folders
            val normalAssets = arrayOf("box64", "dxwrapper", "graphics_driver", "inputcontrols", "soundfont", "wallpapers", "wincomponents")
            for (asset in normalAssets) {
                copyAssetFolder(context, asset, File(destDir, asset))
            }

            Log.e(TAG, "Extraction completed successfully!")
        } catch (e: Exception) {
            // Error එක MainActivity එකට යවන්න, එතකොට Toast එකේ පේනවා
            Log.e(TAG, "CRITICAL ERROR: ${e.message}", e)
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
                        while (entry != null) {
                            val outFile = File(destFile, entry.name)
                            
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
                            entry = tarInputStream.nextEntry
                        }
                    }
                }
            }
        }
        Log.e(TAG, "Successfully extracted $assetName")
    } catch (e: Exception) {
        Log.e(TAG, "Error extracting $assetName: ${e.message}", e)
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
            Log.e(TAG, "Error copying file $assetPath", e)
        }
    }
}
