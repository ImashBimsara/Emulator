package com.your.customemulator

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object AssetExtractor {
    private const val TAG = "AssetExtractor"
    private const val BUFFER_SIZE = 8192 // Winlator පාවිච්චි කරන size එකට සමානයි

    fun extractAssets(context: Context) {
        val destDir = File(context.filesDir, "imagefs")
        
        Logger.log(context, "--- Extraction Process Started (Winlator Style) ---")

        if (destDir.exists()) {
            Logger.log(context, "Deleting old imagefs folder...")
            destDir.deleteRecursively()
        }
        destDir.mkdirs()

        try {
            // 1. rootfs.tzst එක extract කරනවා
            Logger.log(context, "Extracting rootfs.tzst...")
            val rootfsFile = File(destDir, "rootfs")
            extractTzstLikeWinlator(context, "rootfs.tzst", rootfsFile)
            
            // 2. rootfs_patches.tzst එක extract කරනවා
            Logger.log(context, "Extracting rootfs_patches.tzst...")
            val patchesFile = File(destDir, "rootfs_patches")
            extractTzstLikeWinlator(context, "rootfs_patches.tzst", patchesFile)

            Logger.log(context, "--- Extraction Completed Successfully ---")
        } catch (e: Exception) {
            Logger.log(context, "CRITICAL ERROR: ${e.message}")
            e.stackTrace.forEach { element ->
                Logger.log(context, "  at $element")
            }
            throw e 
        }
    }

    // Winlator එකේ විදිහටම extract කරන function එක
    private fun extractTzstLikeWinlator(context: Context, assetName: String, destination: File) {
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

                                val file = File(destination, entry.name)

                                if (entry.isDirectory) {
                                    if (!file.isDirectory) file.mkdirs()
                                } else {
                                    if (entry.isSymbolicLink) {
                                        // Symlink එකක් නම් (Winlator කරන විදිහට)
                                        Logger.log(context, "Symlink found: ${entry.name} -> ${entry.linkName}")
                                        // Android වල root නැතුව symlink හදන්න බෑ, ඒ නිසා අපි ඒක skip කරනවා හෝ file copy කරනවා
                                    } else {
                                        // සාමාන්‍ය ෆයිල් එකක් නම්, Buffer එකක් පාවිච්චි කරලා ලියනවා
                                        file.parentFile?.mkdirs()
                                        BufferedOutputStream(FileOutputStream(file), BUFFER_SIZE).use { outStream ->
                                            val buffer = ByteArray(BUFFER_SIZE)
                                            var len: Int
                                            while (tarInputStream.read(buffer).also { len = it } > 0) {
                                                outStream.write(buffer, 0, len)
                                            }
                                        }
                                    }
                                }
                                
                                // (Winlator කරන විදිහට chmod 0771 - Android වල මේකට root ඕන නිසා අපි skip කරනවා)
                                
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
