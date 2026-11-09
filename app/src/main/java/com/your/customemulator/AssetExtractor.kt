package com.your.customemulator // ඔයාගේ package name එක දාන්න

import android.content.Context
import android.util.Log
import com.github.junrar.Archive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object AssetExtractor {
    private const val TAG = "AssetExtractor"

    // ප්‍රධාන extract කරන function එක
    fun extractAssets(context: Context) {
        val destDir = File(context.filesDir, "imagefs")
        
        // අපි කලින් extract කරලා ඉවරයි නම්, ආයේ කරන්නේ නෑ
        if (destDir.exists() && destDir.listFiles()?.isNotEmpty() == true) {
            Log.d(TAG, "Already extracted.")
            return
        }
        
        destDir.mkdirs()
        Log.d(TAG, "Extracting assets to: ${destDir.absolutePath}")

        // 1. RAR files (rootfs) extract කිරීම
        extractRar(context, "rootfs", File(destDir, "rootfs"))
        extractRar(context, "rootfs_patches", File(destDir, "rootfs_patches"))

        // 2. අනිත් සාමාන්‍ය folders සහ files extract කිරීම
        val normalAssets = arrayOf("box64", "dxwrapper", "graphics_driver", "inputcontrols", "soundfont", "wallpapers", "wincomponents", "common_dlls", "gpu_cards", "pulseaudio", "wine_debug_channels", "wine_startmenu")
        
        for (asset in normalAssets) {
            copyAssetFolder(context, asset, File(destDir, asset))
        }
        
        // තනි files ටික copy කිරීම
        val singleFiles = arrayOf("container_pattern", "gamepad_models", "gpu_cards")
        for (file in singleFiles) {
            copyAssetFile(context, file, File(destDir, file))
        }

        Log.d(TAG, "Extraction completed!")
    }

    // RAR extract කරන function එක
    private fun extractRar(context: Context, assetName: String, destFile: File) {
        try {
            val inputStream = context.assets.open(assetName)
            val archive = Archive(inputStream)
            
            for (fileHeader in archive.fileHeaders) {
                val outFile = File(destFile, fileHeader.fileName)
                if (fileHeader.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    val out = FileOutputStream(outFile)
                    archive.extractFile(fileHeader, out)
                    out.close()
                }
            }
            archive.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting $assetName", e)
        }
    }

    // සාමාන්‍ය folder එකක් copy කරන function එක
    private fun copyAssetFolder(context: Context, assetPath: String, destFolder: File) {
        val files = context.assets.list(assetPath)
        if (files.isNullOrEmpty()) {
            // මේක folder එකක් නෙවෙයි, file එකක්
            copyAssetFile(context, assetPath, destFolder)
        } else {
            destFolder.mkdirs()
            for (file in files) {
                copyAssetFolder(context, "$assetPath/$file", File(destFolder, file))
            }
        }
    }

    // තනි file එකක් copy කරන function එක
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
            Log.e(TAG, "Error copying $assetPath", e)
        }
    }
}
