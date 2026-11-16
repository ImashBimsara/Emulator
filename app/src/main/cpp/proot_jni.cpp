#include <jni.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <string>
#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>

#define LOG_TAG "ProotJNI"

#define LOGI(...) \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define LOGE(...) \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper function to copy file and make it executable
bool copyAndMakeExecutable(const char* srcPath, const char* destPath) {
    int srcFd = open(srcPath, O_RDONLY);
    if (srcFd < 0) {
        LOGE("Failed to open source file %s: %s", srcPath, strerror(errno));
        return false;
    }

    int destFd = open(destPath, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (destFd < 0) {
        LOGE("Failed to open destination file %s: %s", destPath, strerror(errno));
        close(srcFd);
        return false;
    }

    char buffer[8192];
    ssize_t bytesRead;
    while ((bytesRead = read(srcFd, buffer, sizeof(buffer))) > 0) {
        ssize_t bytesWritten = write(destFd, buffer, bytesRead);
        if (bytesWritten != bytesRead) {
            LOGE("Failed to write destination file %s: %s", destPath, strerror(errno));
            close(srcFd);
            close(destFd);
            return false;
        }
    }

    close(srcFd);
    close(destFd);

    // Ensure executable permissions (+x)
    if (chmod(destPath, 0755) != 0) {
        LOGE("Failed to chmod +x on %s: %s", destPath, strerror(errno));
        return false;
    }

    LOGI("Successfully copied and made executable: %s -> %s", srcPath, destPath);
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(
        JNIEnv* env,
        jobject,
        jstring jProotLibPath,
        jstring jRootfs,
        jstring jWinePrefix,
        jstring jBox64,
        jstring jWine,
        jstring jInstaller,
        jstring jLogFilePath,
        jstring jNativeLibDir,
        jstring jFilesDir) {

    // Convert JNI strings
    const char* proot = env->GetStringUTFChars(jProotLibPath, nullptr);
    const char* rootfs = env->GetStringUTFChars(jRootfs, nullptr);
    const char* prefix = env->GetStringUTFChars(jWinePrefix, nullptr);
    const char* box64Src = env->GetStringUTFChars(jBox64, nullptr);
    const char* wine = env->GetStringUTFChars(jWine, nullptr);
    const char* installer = env->GetStringUTFChars(jInstaller, nullptr);
    const char* log = env->GetStringUTFChars(jLogFilePath, nullptr);
    const char* nativeDir = env->GetStringUTFChars(jNativeLibDir, nullptr);
    const char* filesDir = env->GetStringUTFChars(jFilesDir, nullptr);

    // Release helper
    auto releaseStrings = [&]() {
        if (proot) env->ReleaseStringUTFChars(jProotLibPath, proot);
        if (rootfs) env->ReleaseStringUTFChars(jRootfs, rootfs);
        if (prefix) env->ReleaseStringUTFChars(jWinePrefix, prefix);
        if (box64Src) env->ReleaseStringUTFChars(jBox64, box64Src);
        if (wine) env->ReleaseStringUTFChars(jWine, wine);
        if (installer) env->ReleaseStringUTFChars(jInstaller, installer);
        if (log) env->ReleaseStringUTFChars(jLogFilePath, log);
        if (nativeDir) env->ReleaseStringUTFChars(jNativeLibDir, nativeDir);
        if (filesDir) env->ReleaseStringUTFChars(jFilesDir, filesDir);
    };

    // Validate JNI strings
    if (!proot || !rootfs || !prefix || !box64Src ||
        !wine || !installer || !log || !nativeDir || !filesDir) {
        LOGE("JNI string conversion failed");
        releaseStrings();
        return -1;
    }

    // Prepare executable Box64 path inside filesDir to bypass noexec restriction
    std::string box64Dest = std::string(filesDir) + "/box64";
    if (!copyAndMakeExecutable(box64Src, box64Dest.c_str())) {
        LOGE("Failed to prepare executable Box64 in filesDir");
        releaseStrings();
        return -4;
    }

    // Create library search path
    std::string ldPath =
            std::string(filesDir) + ":" +
            std::string(nativeDir);

    LOGI("LD_LIBRARY_PATH: %s", ldPath.c_str());

    // Fork process
    pid_t pid = fork();

    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        releaseStrings();
        return -2;
    }

    if (pid == 0) {
        // Redirect stdout and stderr to log file
        int fd = open(
                log,
                O_WRONLY | O_CREAT | O_TRUNC,
                0666
        );

        if (fd >= 0) {
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            close(fd);
        }

        // Environment variables
        setenv("PROOT_TMP_DIR", filesDir, 1);
        setenv("PROOT_NO_SECURE", "1", 1);

        setenv("WINEPREFIX", prefix, 1);
        setenv("WINEDEBUG", "-all", 1);
        setenv("WINEARCH", "win64", 1);

        setenv("DISPLAY", ":0", 1);

        if (setenv("LD_LIBRARY_PATH", ldPath.c_str(), 1) != 0) {
            dprintf(
                    STDERR_FILENO,
                    "Failed to set LD_LIBRARY_PATH: %s\n",
                    strerror(errno)
            );
            _exit(20);
        }

        // Verify copied Box64 executable
        struct stat st;
        if (stat(box64Dest.c_str(), &st) != 0) {
            dprintf(
                    STDERR_FILENO,
                    "Box64 executable not found in filesDir: %s\n",
                    strerror(errno)
            );
            _exit(10);
        }

        // Verify Proot
        if (stat(proot, &st) != 0) {
            dprintf(
                    STDERR_FILENO,
                    "Proot not found: %s\n",
                    strerror(errno)
            );
            _exit(11);
        }

        // Verify libtalloc
        std::string tallocPath =
                std::string(filesDir) + "/libtalloc.so.2";

        if (stat(tallocPath.c_str(), &st) != 0) {
            dprintf(
                    STDERR_FILENO,
                    "libtalloc.so.2 not found: %s\n",
                    strerror(errno)
            );
            _exit(13);
        }

        dprintf(STDERR_FILENO, "Starting Proot with executable Box64...\n");
        dprintf(STDERR_FILENO, "Rootfs: %s\n", rootfs);
        dprintf(STDERR_FILENO, "Box64 (Exec): %s\n", box64Dest.c_str());
        dprintf(STDERR_FILENO, "Wine: %s\n", wine);

        // Proot arguments using the executable copy in filesDir
        char* const argv[] = {
                (char*)"proot",
                (char*)"--link2symlink",
                (char*)"-0",
                (char*)"-r",
                (char*)rootfs,
                (char*)"-b",
                (char*)"/system",
                (char*)"-b",
                (char*)"/apex",
                (char*)"-b",
                (char*)"/vendor",
                (char*)"-b",
                (char*)"/dev",
                (char*)"-b",
                (char*)"/proc",
                (char*)"-b",
                (char*)"/sys",
                (char*)"-b",
                (char*)"/data",
                (char*)"-w",
                (char*)"/",
                // Box64 and Wine (using box64Dest instead of raw jniLibs path)
                (char*)box64Dest.c_str(),
                (char*)wine,
                (char*)installer,
                nullptr
        };

        // Execute Proot
        execv(proot, argv);

        // Only reached if execv fails
        dprintf(
                STDERR_FILENO,
                "Proot execv failed: %s\n",
                strerror(errno)
        );

        _exit(12);
    }

    // Parent process
    int status = 0;
    jint result = -3;

    if (waitpid(pid, &status, 0) == -1) {
        LOGE("waitpid failed: %s", strerror(errno));
    } else if (WIFEXITED(status)) {
        int exitCode = WEXITSTATUS(status);
        LOGI("Proot exit code: %d", exitCode);
        result = exitCode;
    } else if (WIFSIGNALED(status)) {
        int signalNumber = WTERMSIG(status);
        LOGE("Proot killed by signal: %d", signalNumber);
        result = -100 - signalNumber;
    }

    releaseStrings();
    return result;
}
