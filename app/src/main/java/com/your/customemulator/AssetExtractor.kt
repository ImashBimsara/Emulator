
package com.your.customemulator

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AssetExtractor {

    private const val TAG = "AssetExtractor"
    private const val BUFFER_SIZE = 8192

    fun extractAssets(context: Context) {

        val destDir = File(context.filesDir, "imagefs")

        Logger.log(context, "--- Extraction Process Started ---")

        try {

            // 1. Remove old imagefs
            if (destDir.exists()) {
                Logger.log(context, "Deleting old imagefs folder...")

                if (!destDir.deleteRecursively()) {
                    throw IOException("Failed to delete old imagefs")
                }
            }

            if (!destDir.mkdirs() && !destDir.exists()) {
                throw IOException("Failed to create imagefs directory")
            }

            // 2. Extract rootfs
            Logger.log(context, "Extracting rootfs.tzst...")

            extractTzst(
                context,
                "rootfs.tzst",
                File(destDir, "rootfs")
            )

            // 3. Extract rootfs patches
            Logger.log(context, "Extracting rootfs_patches.tzst...")

            extractTzst(
                context,
                "rootfs_patches.tzst",
                File(destDir, "rootfs_patches")
            )

            val rootfs = File(destDir, "rootfs")

            // 4. Create required directories
            val usrBinDir = File(rootfs, "usr/bin")

            if (!usrBinDir.exists()) {
                usrBinDir.mkdirs()
            }

            Logger.log(context, "Created /usr/bin")

            val rootDir = File(rootfs, "root")

            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }

            Logger.log(context, "Created /root")

            val homeDir = File(rootfs, "home/xuser")

            if (!homeDir.exists()) {
                homeDir.mkdirs()
            }

            Logger.log(context, "Created /home/xuser")

            /*
             * IMPORTANT:
             *
             * Do not delete rootfs/lib.
             *
             * The original rootfs contains:
             * /lib -> usr/lib
             *
             * The guest linker already exists at:
             * /usr/lib/ld-linux-aarch64.so.1
             *
             * Therefore /lib/ld-linux-aarch64.so.1
             * resolves through the existing symbolic link.
             */

            val guestLinker = File(
                rootfs,
                "usr/lib/ld-linux-aarch64.so.1"
            )

            if (guestLinker.exists()) {
                Logger.log(context, "Guest ARM64 linker found")
            } else {
                Logger.log(context, "WARNING: Guest linker not found!")
            }

            // 5. Copy Android host linker
            val systemBinDir = File(rootfs, "system/bin")

            if (!systemBinDir.exists()) {
                systemBinDir.mkdirs()
            }

            val hostLinker = File("/system/bin/linker64")

            if (hostLinker.exists()) {

                hostLinker.copyTo(
                    File(systemBinDir, "linker64"),
                    overwrite = true
                )

                Logger.log(context, "Copied Android host linker")
            }

            // 6. Copy Android APEX linker
            val apexDir = File(
                rootfs,
                "apex/com.android.runtime/bin"
            )

            if (!apexDir.exists()) {
                apexDir.mkdirs()
            }

            val hostApexLinker = File(
                "/apex/com.android.runtime/bin/linker64"
            )

            if (hostApexLinker.exists()) {

                hostApexLinker.copyTo(
                    File(apexDir, "linker64"),
                    overwrite = true
                )

                Logger.log(context, "Copied APEX linker")
            }

            // 7. Set Wine executable permission
            val wineFile = File(
                rootfs,
                "opt/wine/bin/wine"
            )

            if (wineFile.exists()) {

                Os.chmod(wineFile.absolutePath, 0b111101101)

                Logger.log(context, "Wine executable permission set")

            } else {

                Logger.log(context, "WARNING: Wine executable not found!")
            }

            // 8. Set Wine bin permissions
            val wineDir = File(
                rootfs,
                "opt/wine/bin"
            )

            if (wineDir.exists()) {

                wineDir.listFiles()?.forEach { file ->

                    if (file.isFile) {
                        Os.chmod(file.absolutePath, 0b111101101)
                    }
                }

                Logger.log(context, "Wine bin permissions updated")
            }

            // 9. Copy libtalloc.so.2
            copyAsset(
                context,
                "libtalloc.so.2",
                File(context.filesDir, "libtalloc.so.2")
            )

            // 10. Copy libandroid-shmem.so
            copyAsset(
                context,
                "libandroid-shmem.so",
                File(context.filesDir, "libandroid-shmem.so")
            )

            Logger.log(
                context,
                "--- Extraction Completed Successfully ---"
            )

        } catch (e: Exception) {

            Logger.log(
                context,
                "CRITICAL ERROR: ${e.message}"
            )

            throw e
        }
    }


    private fun copyAsset(
        context: Context,
        assetName: String,
        destination: File
    ) {

        try {

            context.assets.open(assetName).use { input ->

                FileOutputStream(destination).use { output ->

                    input.copyTo(output)
                }
            }

            Logger.log(
                context,
                "$assetName copied successfully"
            )

        } catch (e: Exception) {

            Logger.log(
                context,
                "Error copying $assetName: ${e.message}"
            )
        }
    }


    private fun extractTzst(
        context: Context,
        assetName: String,
        destFile: File
    ) {

        try {

            if (!destFile.exists()) {
                destFile.mkdirs()
            }

            context.assets.open(assetName).use { inputStream ->

                BufferedInputStream(
                    inputStream,
                    BUFFER_SIZE
                ).use { buffered ->

                    ZstdCompressorInputStream(buffered).use { zstd ->

                        TarArchiveInputStream(zstd).use { tar ->

                            var entry = tar.nextEntry as? TarArchiveEntry

                            var count = 0
                            var fileCount = 0
                            var symlinkCount = 0
                            var directoryCount = 0

                            val rootPath = destFile.absoluteFile.toPath()
                                .normalize()

                            val directoryModes = mutableListOf<Pair<File, Int>>()

                            while (entry != null) {

                                // Normalize archive path
                                val relativePath = entry.name
                                    .replace('\\', '/')
                                    .removePrefix("./")

                                // Reject absolute paths
                                if (relativePath.startsWith("/")) {
                                    throw SecurityException(
                                        "Absolute archive path rejected: ${entry.name}"
                                    )
                                }

                                val relative = java.nio.file.Paths
                                    .get(relativePath)
                                    .normalize()

                                // Reject path traversal
                                if (
                                    relative.isAbsolute ||
                                    relative.startsWith("..")
                                ) {
                                    throw SecurityException(
                                        "Invalid archive path: ${entry.name}"
                                    )
                                }

                                val outputPath = rootPath
                                    .resolve(relative)
                                    .normalize()

                                if (!outputPath.startsWith(rootPath)) {
                                    throw SecurityException(
                                        "Path traversal detected: ${entry.name}"
                                    )
                                }

                                val outFile = outputPath.toFile()

                                // Directory
                                if (entry.isDirectory) {

                                    if (!outFile.exists()) {
                                        outFile.mkdirs()
                                    }

                                    directoryModes.add(
                                        Pair(
                                            outFile,
                                            entry.mode and 0xFFF
                                        )
                                    )

                                    directoryCount++
                                }

                                // Symbolic link
                                else if (entry.isSymbolicLink) {

                                    outFile.parentFile?.mkdirs()

                                    // Remove an existing destination safely
                                    if (
                                        outFile.exists() ||
                                        java.nio.file.Files.isSymbolicLink(
                                            outFile.toPath()
                                        )
                                    ) {
                                        java.nio.file.Files.delete(
                                            outFile.toPath()
                                        )
                                    }

                                    // Preserve the original link target
                                    val linkTarget = entry.linkName

                                    if (
                                        linkTarget.isBlank() ||
                                        linkTarget.startsWith("/")
                                    ) {
                                        throw SecurityException(
                                            "Invalid symbolic link target: $linkTarget"
                                        )
                                    }

                                    Os.symlink(
                                        linkTarget,
                                        outFile.absolutePath
                                    )

                                    symlinkCount++
                                }

                                // Hard link
                                else if (entry.isLink) {

                                    throw IllegalStateException(
                                        "Hard link found in archive: ${entry.name}"
                                    )
                                }

                                // Regular file
                                else {

                                    if (!tar.canReadEntryData(entry)) {
                                        throw IOException(
                                            "Cannot read archive entry: ${entry.name}"
                                        )
                                    }

                                    outFile.parentFile?.mkdirs()

                                    // Do not overwrite a symbolic link
                                    if (
                                        java.nio.file.Files.isSymbolicLink(
                                            outFile.toPath()
                                        )
                                    ) {
                                        throw IOException(
                                            "Refusing to overwrite symbolic link: ${entry.name}"
                                        )
                                    }

                                    BufferedOutputStream(
                                        FileOutputStream(outFile),
                                        BUFFER_SIZE
                                    ).use { output ->

                                        val buffer = ByteArray(BUFFER_SIZE)

                                        while (true) {

                                            val length = tar.read(buffer)

                                            if (length == -1) break

                                            output.write(
                                                buffer,
                                                0,
                                                length
                                            )
                                        }
                                    }

                                    // Preserve original file permissions
                                    Os.chmod(
                                        outFile.absolutePath,
                                        entry.mode and 0xFFF
                                    )

                                    fileCount++
                                }

                                count++

                                entry = tar.nextEntry as? TarArchiveEntry
                            }

                            // Apply directory permissions after extracting children
                            directoryModes
                                .asReversed()
                                .forEach { (directory, mode) ->

                                    Os.chmod(
                                        directory.absolutePath,
                                        mode
                                    )
                                }

                            Logger.log(
                                context,
                                "Extracted $assetName: $count entries"
                            )

                            Logger.log(
                                context,
                                "Directories: $directoryCount"
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
