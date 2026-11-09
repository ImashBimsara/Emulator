package com.your.customemulator

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {
    private const val TAG = "MY_EXTRACTOR_TEST"

    // extractAssets එකට tzstFile එක පැරාමීටරයක් විදිහට එනවා
    fun extractAssets(context: Context, tzstFile: File) {
        val destDir = File(context.filesDir, "imagefs")
        
        Log.e(TAG, "Extraction Process Started!")

        if (destDir.exists() && destDir.listFiles()?.isNotEmpty() == true) {
            Log.e(TAG, "Already extracted! Skipping...")
            return
        }
        
        destDir.mkdirs()

        try {
            Log.e(TAG, "Extracting rootfs...")
            extractTzst(tzstFile, destDir)

            Log.e(TAG, "Extraction completed successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR: ${e.message}", e)
            throw e 
        }
    }

    private fun extractTzst(tzstFile: File, destFile: File) {
        try {
            tzstFile.inputStream().use { inputStream ->
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
            Log.e(TAG, "Successfully extracted ${tzstFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ${tzstFile.name}: ${e.message}", e)
            throw e 
        }
    }
}
