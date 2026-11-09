package com.your.customemulator

import android.content.Context
import android.util.Log
import com.github.junrar.Archive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object AssetExtractor {
    // අපි මේ TAG එක Logcat එකේ හොයන්න ඕනේ
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
            // 1. අපි පළවෙනි පාරට ටෙස්ට් එකක් විදිහට box64 folder එක copy කරමු
            Log.e(TAG, "Copying box64 folder...")
            copyAssetFolder(context, "box64", File(destDir, "box64"))
            Log.e(TAG, "box64 folder copy finished.")

            // 2. ඊට පස්සේ RAR එක extract කරමු
            Log.e(TAG, "Extracting rootfs.rar...")
            extractRar(context, "rootfs", File(destDir, "rootfs"))
            Log.e(TAG, "rootfs extraction finished.")

        } catch (e: Exception) {
            // වැරැද්දක් ආවොත් මේකෙන් Logcat එකට රතු පාටින් එනවා
            Log.e(TAG, "ERROR DETECTED: ${e.message}", e)
        }
    }

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
            Log.e(TAG, "Successfully extracted $assetName")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting $assetName", e)
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
