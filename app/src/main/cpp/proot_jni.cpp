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
#include <sys/mman.h>

#define LOG_TAG "ProotJNI"

#define LOGI(...) \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define LOGE(...) \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

    // Create library search path for PRoot to find libtalloc on the Android host
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

        // Configure the Termux PRoot Loader (Trampoline) to bypass Android execution limits
        std::string loaderPath = std::string(nativeDir) + "/libloader.so";
        setenv("PROOT_LOADER", loaderPath.c_str(), 1);
        setenv("PROOT_LOADER_64", loaderPath.c_str(), 1);

        // Set LD_LIBRARY_PATH for the host (so PRoot finds libtalloc.so.2)
        if (setenv("LD_LIBRARY_PATH", ldPath.c_str(), 1) != 0) {
            dprintf(STDERR_FILENO, "Failed to set LD_LIBRARY_PATH: %s\n", strerror(errno));
            _exit(20);
        }

        // Verify Proot
        struct stat st;
        if (stat(proot, &st) != 0) {
            dprintf(STDERR_FILENO, "Proot not found: %s\n", strerror(errno));
            _exit(11);
        }

        // Verify libtalloc
        std::string tallocPath = std::string(filesDir) + "/libtalloc.so.2";
        if (stat(tallocPath.c_str(), &st) != 0) {
            dprintf(STDERR_FILENO, "libtalloc.so.2 not found: %s\n", strerror(errno));
            _exit(13);
        }

        // ලොග් පණිවිඩය වෙනස් කළා (අලුත් කේතය Run වන බව තහවුරු කරගැනීමට)
        dprintf(STDERR_FILENO, "Starting Proot with Native Box64, Loader Trampoline, and SH env cleanup...\n");
        dprintf(STDERR_FILENO, "Rootfs: %s\n", rootfs);
        dprintf(STDERR_FILENO, "Box64 (Linux): %s\n", box64Src);
        dprintf(STDERR_FILENO, "Loader: %s\n", loaderPath.c_str());
        dprintf(STDERR_FILENO, "Wine: %s\n", wine);

        // Proot arguments
        char* const argv[] = {
                (char*)"proot",
                (char*)"--link2symlink",
                (char*)"-0",
                (char*)"-r",
                (char*)rootfs,
                (char*)"-b", (char*)"/system",
                (char*)"-b", (char*)"/apex",
                (char*)"-b", (char*)"/vendor",
                (char*)"-b", (char*)"/dev",
                (char*)"-b", (char*)"/proc",
                (char*)"-b", (char*)"/sys",
                (char*)"-b", (char*)"/data",
                (char*)"-w", (char*)"/",
                
                // PRoot ඇතුළත (Guest එකේ) Shell එක භාවිතා කර LD_LIBRARY_PATH ඉවත් කිරීම
                (char*)"/bin/sh",
                (char*)"-c",
                (char*)"unset LD_LIBRARY_PATH && exec \"$@\"",
                (char*)"sh",      // Shell එකේ $0
                (char*)box64Src,  // Shell එකේ $1 (Box64)
                (char*)wine,      // Shell එකේ $2 (Wine)
                (char*)installer, // Shell එකේ $3 (Installer)
                nullptr
        };

        // Execute Proot
        execv(proot, argv);

        // Only reached if execv fails
        dprintf(STDERR_FILENO, "Proot execv failed: %s\n", strerror(errno));
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
