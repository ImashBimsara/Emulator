
#include <jni.h>
#include <unistd.h>
#include <sys/wait.h>
#include <string>
#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/stat.h>

#define LOG_TAG "ProotJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

    const char* proot = env->GetStringUTFChars(jProotLibPath, nullptr);
    const char* rootfs = env->GetStringUTFChars(jRootfs, nullptr);
    const char* prefix = env->GetStringUTFChars(jWinePrefix, nullptr);
    const char* box64 = env->GetStringUTFChars(jBox64, nullptr);
    const char* wine = env->GetStringUTFChars(jWine, nullptr);
    const char* installer = env->GetStringUTFChars(jInstaller, nullptr);
    const char* log = env->GetStringUTFChars(jLogFilePath, nullptr);
    const char* nativeDir = env->GetStringUTFChars(jNativeLibDir, nullptr);
    const char* filesDir = env->GetStringUTFChars(jFilesDir, nullptr);

    if (!proot || !rootfs || !prefix || !box64 ||
        !wine || !installer || !log || !nativeDir || !filesDir) {
        LOGE("JNI string conversion failed");
        return -1;
    }

    pid_t pid = fork();

    if (pid == 0) {

        int fd = open(log, O_WRONLY | O_CREAT | O_TRUNC, 0666);

        if (fd >= 0) {
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            close(fd);
        }

        // Environment
        setenv("PROOT_TMP_DIR", filesDir, 1);
        setenv("PROOT_NO_SECURE", "1", 1);
        setenv("WINEPREFIX", prefix, 1);
        setenv("WINEDEBUG", "-all", 1);
        setenv("WINEARCH", "win64", 1);
        setenv("DISPLAY", ":0", 1);

        std::string ldPath =
                std::string(filesDir) + ":" + nativeDir;

        setenv("LD_LIBRARY_PATH", ldPath.c_str(), 1);

        // Verify paths
        struct stat st;

        if (stat(box64, &st) != 0) {
            dprintf(2, "Box64 not found: %s\n", strerror(errno));
            _exit(10);
        }

        if (stat(proot, &st) != 0) {
            dprintf(2, "Proot not found: %s\n", strerror(errno));
            _exit(11);
        }

        // Run Proot
        char* const argv[] = {
                (char*)"proot",
                (char*)"--link2symlink",
                (char*)"-0",
                (char*)"-r", (char*)rootfs,

                (char*)"-b", (char*)"/system",
                (char*)"-b", (char*)"/apex",
                (char*)"-b", (char*)"/vendor",
                (char*)"-b", (char*)"/dev",
                (char*)"-b", (char*)"/proc",
                (char*)"-b", (char*)"/sys",
                (char*)"-b", (char*)"/data",

                (char*)"-w", (char*)"/",

                // Do not execute the Android native-library
                // path directly through Proot.
                (char*)box64,
                (char*)wine,
                (char*)installer,
                nullptr
        };

        execv(proot, argv);

        dprintf(2, "Proot execv failed: %s\n", strerror(errno));

        _exit(12);

    } else if (pid > 0) {

        int status = 0;

        if (waitpid(pid, &status, 0) == -1) {
            LOGE("waitpid failed: %s", strerror(errno));
        } else if (WIFEXITED(status)) {
            LOGI("Proot exit code: %d", WEXITSTATUS(status));
        } else if (WIFSIGNALED(status)) {
            LOGE("Proot killed by signal: %d", WTERMSIG(status));
        }

    } else {
        LOGE("fork failed: %s", strerror(errno));
    }

    env->ReleaseStringUTFChars(jProotLibPath, proot);
    env->ReleaseStringUTFChars(jRootfs, rootfs);
    env->ReleaseStringUTFChars(jWinePrefix, prefix);
    env->ReleaseStringUTFChars(jBox64, box64);
    env->ReleaseStringUTFChars(jWine, wine);
    env->ReleaseStringUTFChars(jInstaller, installer);
    env->ReleaseStringUTFChars(jLogFilePath, log);
    env->ReleaseStringUTFChars(jNativeLibDir, nativeDir);
    env->ReleaseStringUTFChars(jFilesDir, filesDir);

    return 0;
}
