#include <jni.h>
#include <unistd.h>
#include <sys/wait.h>
#include <string>
#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>

#define LOG_TAG "ProotJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(
        JNIEnv* env,
        jobject /* this */,
        jstring jProotLibPath,
        jstring jRootfs,
        jstring jWinePrefix,
        jstring jBox64,
        jstring jWine,
        jstring jInstaller,
        jstring jLogFilePath,
        jstring jNativeLibDir,
        jstring jFilesDir) {

    const char* prootLibPath = env->GetStringUTFChars(jProotLibPath, nullptr);
    const char* rootfs = env->GetStringUTFChars(jRootfs, nullptr);
    const char* winePrefix = env->GetStringUTFChars(jWinePrefix, nullptr);
    const char* box64 = env->GetStringUTFChars(jBox64, nullptr);
    const char* wine = env->GetStringUTFChars(jWine, nullptr);
    const char* installer = env->GetStringUTFChars(jInstaller, nullptr);
    const char* logFilePath = env->GetStringUTFChars(jLogFilePath, nullptr);
    const char* nativeLibDir = env->GetStringUTFChars(jNativeLibDir, nullptr);
    const char* filesDir = env->GetStringUTFChars(jFilesDir, nullptr);

    pid_t pid = fork();
    if (pid == 0) {
        // Child process
        int fd = open(logFilePath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (fd != -1) {
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            close(fd);
        }

        // Environment variables set කරන්න
        setenv("PROOT_TMP_DIR", filesDir, 1);
        setenv("PROOT_NO_SECURE", "1", 1);
        setenv("WINEPREFIX", winePrefix, 1);
        setenv("WINEDEBUG", "-all", 1);
        setenv("WINEARCH", "win64", 1);
        setenv("DISPLAY", ":0", 1);
        
        std::string ldPath = std::string(filesDir) + ":" + std::string(nativeLibDir);
        setenv("LD_LIBRARY_PATH", ldPath.c_str(), 1);

        // ✅ වෙනස් කරපු තැන්: -w /, -b /data, /usr/bin/box64
        char* const argv[] = {
                (char*)"proot",
                (char*)"--link2symlink",
                (char*)"-0",
                (char*)"-r", (char*)rootfs,
                (char*)"-b", (char*)"/system",
                (char*)"-b", (char*)"/vendor",
                (char*)"-b", (char*)"/dev",
                (char*)"-b", (char*)"/proc",
                (char*)"-b", (char*)"/sys",
                (char*)"-b", (char*)"/data",
                (char*)"-w", (char*)"/",
                (char*)box64, 
                (char*)wine,
                (char*)installer,
                nullptr
        };

        execv(prootLibPath, argv);
        dprintf(STDERR_FILENO, "execv failed: %s\n", strerror(errno));
        _exit(1);
    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
        LOGI("Proot exited with status: %d", WEXITSTATUS(status));
    } else {
        LOGE("fork failed");
    }

    env->ReleaseStringUTFChars(jProotLibPath, prootLibPath);
    env->ReleaseStringUTFChars(jRootfs, rootfs);
    env->ReleaseStringUTFChars(jWinePrefix, winePrefix);
    env->ReleaseStringUTFChars(jBox64, box64);
    env->ReleaseStringUTFChars(jWine, wine);
    env->ReleaseStringUTFChars(jInstaller, installer);
    env->ReleaseStringUTFChars(jLogFilePath, logFilePath);
    env->ReleaseStringUTFChars(jNativeLibDir, nativeLibDir);
    env->ReleaseStringUTFChars(jFilesDir, filesDir);

    return 0;
}
