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
        Log.e(TAG, "Destination Path: ${destDir.absolutePath}")

        // දැනටමත් extract වෙලා නම් ආයේ කරන්නේ නෑ
        if (destDir.exists() && destDir.listFiles()?.isNotEmpty() == true) {
            Log.e(TAG, "Already extracted! Skipping...")
            return
        }
        
        destDir.mkdirs()

        try {
            // 1. Tzst files extract කිරීම
            Log.e(TAG, "Extracting rootfs.tzst...")
            extractTzst(context, "rootfs.tzst", File(destDir, "rootfs"))
            
            Log.e(TAG, "Extracting rootfs_patches.tzst...")
            extractTzst(context, "rootfs_patches.tzst", File(destDir, "rootfs_patches"))

            // 2. අනිත් සාමාන්‍ය folders copy කිරීම
            val normalAssets = arrayOf("box64", "dxwrapper", "graphics_driver", "inputcontrols", "soundfont", "wallpapers", "wincomponents")
            for (asset in normalAssets) {
                copyAssetFolder(context, asset, File(destDir, asset))
            }

            Log.e(TAG, "Extraction completed successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR: ${e.message}", e)
        }
    }

    // .tzst (Zstandard Tar) extract කරන function එක
    private fun extractTzst(context: Context, assetName: String, destFile: File) {
        try {
            val inputStream: InputStream = context.assets.open(assetName)
            val bufferedInputStream = BufferedInputStream(inputStream)
            val zstdInputStream = ZstdCompressorInputStream(bufferedInputStream)
            val tarInputStream = TarArchiveInputStream(zstdInputStream)

            var entry = tarInputStream.nextEntry
            while (entry != null) {
                val outFile = File(destFile, entry.name)
                
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    val out = FileOutputStream(outFile)
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (tarInputStream.read(buffer).also { len = it } > 0) {
                        out.write(buffer, 0, len)
                    }
                    out.close()
                }
                entry = tarInputStream.nextEntry
            }
            tarInputStream.close()
            Log.e(TAG, "Successfully extracted $assetName")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting $assetName: ${e.message}", e)
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
            val inputStream: InputStream = context.assets.open(assetPath)
            val outputStream = FileOutputStream(destFile)
            val buffer = ByteArray(8192)
            var length: Int
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            outputStream.close()
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file $assetPath", e)
        }
    }
}
