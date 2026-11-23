#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/wait.h>
#include <android/log.h>
#include <stdlib.h>
#include <libgen.h>
#include <string.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <errno.h>
#include <stdio.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EmulatorJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmulatorJNI", __VA_ARGS__)

extern char **environ;

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(JNIEnv *env, jobject /* thiz */,
                                                    jstring jArg1, jstring jArg2, jstring jArg3, jstring jArg4,
                                                    jstring jArg5, jstring jArg6, jstring jArg7, jstring jArg8, jstring jArg9) {

    const char *rootfs = env->GetStringUTFChars(jArg1, nullptr);
    const char *proot_lib = env->GetStringUTFChars(jArg2, nullptr);
    const char *box64_lib = env->GetStringUTFChars(jArg3, nullptr);
    const char *installer = env->GetStringUTFChars(jArg4, nullptr);

    std::string internalFilesDir = "/data/data/com.your.customemulator/files";
    // emulator_log එකට ගැළපෙන external log path එක
    std::string logPath = "/storage/emulated/0/Android/data/com.your.customemulator/files/wine_output.log";

    char box64Copy[1024];
    strncpy(box64Copy, box64_lib, sizeof(box64Copy) - 1);
    box64Copy[sizeof(box64Copy) - 1] = '\0';
    std::string nativeDir = dirname(box64Copy);
    std::string loaderPath = nativeDir + "/libproot-loader.so";

    std::string shellCmd = "export LD_LIBRARY_PATH=" + nativeDir +
                           ":/system/lib64:/system/lib" +
                           ":/usr/lib/x86_64-linux-gnu:/opt/wine/lib:/opt/wine/lib/wine/x86_64-unix" +
                           ":/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib && exec \"$@\"";

    char* const argv[] = {
            (char*)proot_lib,
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
            (char*)"/system/bin/sh",
            (char*)"-c",
            (char*)shellCmd.c_str(),
            (char*)"sh",
            (char*)box64_lib,
            (char*)"/opt/wine/bin/wine64",
            (char*)installer,
            nullptr
    };

    pid_t pid = fork();

    if (pid == 0) {
        int logFd = open(logPath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
        if (logFd < 0) {
            // External storage අවසර නැතිනම් internal files වෙත fallback වීම
            logPath = internalFilesDir + "/wine_output.log";
            logFd = open(logPath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
        }
        if (logFd >= 0) {
            dup2(logFd, STDOUT_FILENO);
            dup2(logFd, STDERR_FILENO);
            close(logFd);
        }

        setenv("LD_LIBRARY_PATH", nativeDir.c_str(), 1);
        setenv("PROOT_TMP_DIR", internalFilesDir.c_str(), 1);
        // Android 10+ noexec bypass කිරීමට native lib folder එකේ ඇති loader එක පෙන්වීම
        setenv("PROOT_LOADER", loaderPath.c_str(), 1);

        execve(proot_lib, argv, environ);

        fprintf(stderr, "execve failed! errno=%d (%s)\n", errno, strerror(errno));
        exit(127);

    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);

        if (WIFSIGNALED(status)) {
            int sig = WTERMSIG(status);
            LOGE("Proot killed by signal: %d", sig);
            FILE *f = fopen(logPath.c_str(), "a");
            if (f) {
                fprintf(f, "\n[JNI ERROR] Proot process killed by signal: %d (%s)\n", sig, strsignal(sig));
                fclose(f);
            }
        } else if (WIFEXITED(status)) {
            LOGI("Proot exited with code: %d", WEXITSTATUS(status));
        }

        env->ReleaseStringUTFChars(jArg1, rootfs);
        env->ReleaseStringUTFChars(jArg2, proot_lib);
        env->ReleaseStringUTFChars(jArg3, box64_lib);
        env->ReleaseStringUTFChars(jArg4, installer);

        return WIFEXITED(status) ? WEXITSTATUS(status) : (128 + WTERMSIG(status));
    } else {
        env->ReleaseStringUTFChars(jArg1, rootfs);
        env->ReleaseStringUTFChars(jArg2, proot_lib);
        env->ReleaseStringUTFChars(jArg3, box64_lib);
        env->ReleaseStringUTFChars(jArg4, installer);
        return -1;
    }
}
