#include <jni.h>
#include <string>
#include <vector>
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

static std::string getJString(JNIEnv *env, jstring jStr) {
    if (jStr == nullptr) return "";
    const char *chars = env->GetStringUTFChars(jStr, nullptr);
    if (chars == nullptr) return "";
    std::string res(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return res;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(JNIEnv *env, jobject /* thiz */,
                                                    jstring jArg1, jstring jArg2, jstring jArg3, jstring jArg4,
                                                    jstring jArg5, jstring jArg6, jstring jArg7, jstring jArg8, jstring jArg9) {

    std::string rootfs = getJString(env, jArg1);
    std::string proot_lib = getJString(env, jArg2);
    std::string box64_lib = getJString(env, jArg3);
    std::string arg4 = getJString(env, jArg4);
    std::string arg5 = getJString(env, jArg5);
    std::string arg6 = getJString(env, jArg6);
    std::string arg7 = getJString(env, jArg7);
    std::string arg8 = getJString(env, jArg8);
    std::string arg9 = getJString(env, jArg9);

    std::string internalFilesDir = "/data/data/com.your.customemulator/files";
    std::string logPath = "/storage/emulated/0/Android/data/com.your.customemulator/files/wine_output.log";

    char box64Copy[1024];
    strncpy(box64Copy, box64_lib.c_str(), sizeof(box64Copy) - 1);
    box64Copy[sizeof(box64Copy) - 1] = '\0';
    std::string nativeDir = dirname(box64Copy);

    std::string loaderPath = nativeDir + "/libproot-loader.so";
    std::string loader32Path = nativeDir + "/libproot-loader32.so";

    // Guest (Box64 + Wine) boot වීමට අවශ්‍ය Environment Variables සහ Debug trace
    std::string shellCmd = "echo '[GUEST SHELL] Started inside PRoot!' >&2 && "
                           "export DISPLAY=:0 && "
                           "export WINEPREFIX=/root/.wine && "
                           "export WINEDEBUG=-all,err+all && "
                           "export BOX64_LOG=1 && "
                           "export BOX64_LD_LIBRARY_PATH=/opt/wine/lib/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu && "
                           "export LD_LIBRARY_PATH=" + nativeDir + ":" + internalFilesDir +
                           ":/system/lib64:/system/lib:/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib && "
                           "if [ -f " + internalFilesDir + "/libandroid-shmem.so ]; then "
                           "export LD_PRELOAD=" + internalFilesDir + "/libandroid-shmem.so; "
                           "elif [ -f /usr/lib/libandroid-shmem.so ]; then "
                           "export LD_PRELOAD=/usr/lib/libandroid-shmem.so; "
                           "fi && "
                           "echo '[GUEST SHELL] Launching Box64 + Wine...' >&2 && "
                           "exec \"$@\"";

    std::vector<const char*> proot_args = {
            proot_lib.c_str(),
            "-v", "9",
            "--link2symlink",
            "-0",
            "-r", rootfs.c_str(),
            "-b", "/system",
            "-b", "/apex",
            "-b", "/vendor",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/data",
            "-w", "/",
            "/system/bin/sh",
            "-c",
            shellCmd.c_str(),
            "sh",
            box64_lib.c_str(),
            "/opt/wine/bin/wine64"
    };

    // arg4 හිස් නොවේ නම් පමණක් argument එකක් ලෙස එකතු කිරීම
    if (!arg4.empty()) {
        proot_args.push_back(arg4.c_str());
    }
    proot_args.push_back(nullptr);

    pid_t pid = fork();

    if (pid == 0) {
        int logFd = open(logPath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
        if (logFd < 0) {
            logPath = internalFilesDir + "/wine_output.log";
            logFd = open(logPath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
        }
        if (logFd >= 0) {
            dup2(logFd, STDOUT_FILENO);
            dup2(logFd, STDERR_FILENO);
            close(logFd);
        }

        fprintf(stderr, "[JNI ARGS] 1='%s' | 2='%s' | 3='%s' | 4='%s' | 5='%s'\n",
                rootfs.c_str(), proot_lib.c_str(), box64_lib.c_str(), arg4.c_str(), arg5.c_str());

        std::string hostLdPath = nativeDir + ":" + internalFilesDir;
        setenv("LD_LIBRARY_PATH", hostLdPath.c_str(), 1);
        setenv("PROOT_TMP_DIR", internalFilesDir.c_str(), 1);
        setenv("PROOT_NO_SECCOMP", "1", 1);

        if (access(loaderPath.c_str(), F_OK) == 0) {
            setenv("PROOT_LOADER", loaderPath.c_str(), 1);
        }
        if (access(loader32Path.c_str(), F_OK) == 0) {
            setenv("PROOT_LOADER_32", loader32Path.c_str(), 1);
        }

        // /system/bin/linker64 හරහා libproot.so ධාවනය කිරීම
        std::vector<char*> linker_argv;
        linker_argv.push_back((char*)"/system/bin/linker64");
        for (size_t i = 0; proot_args[i] != nullptr; i++) {
            linker_argv.push_back((char*)proot_args[i]);
        }
        linker_argv.push_back(nullptr);

        execve("/system/bin/linker64", linker_argv.data(), environ);
        fprintf(stderr, "Linker64 execve failed: errno=%d (%s)\n", errno, strerror(errno));

        _exit(127);

    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);

        if (WIFSIGNALED(status)) {
            int sig = WTERMSIG(status);
            LOGE("Proot killed by signal: %d", sig);
            FILE *f = fopen(logPath.c_str(), "a");
            if (f) {
                fprintf(f, "\n[JNI ERROR] Proot killed by signal: %d (%s)\n", sig, strsignal(sig));
                fclose(f);
            }
        } else if (WIFEXITED(status)) {
            LOGI("Proot exited with code: %d", WEXITSTATUS(status));
        }

        return WIFEXITED(status) ? WEXITSTATUS(status) : (128 + WTERMSIG(status));
    } else {
        return -1;
    }
}
