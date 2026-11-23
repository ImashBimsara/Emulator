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

    // MainActivity.kt වෙතින් එවන නිවැරදි Parameter පිළිවෙළ:
    std::string proot_lib  = getJString(env, jArg1); // /data/app/.../lib/arm64/libproot.so
    std::string rootfs     = getJString(env, jArg2); // /data/user/0/.../files/imagefs/rootfs
    std::string wineprefix = getJString(env, jArg3); // /data/user/0/.../files/wineprefix
    std::string box64_lib  = getJString(env, jArg4); // /data/app/.../lib/arm64/libbox64.so
    std::string wine_path  = getJString(env, jArg5); // /opt/wine/bin/wine64
    std::string arg6       = getJString(env, jArg6);
    std::string arg7       = getJString(env, jArg7);
    std::string arg8       = getJString(env, jArg8);
    std::string arg9       = getJString(env, jArg9);

    if (wineprefix.empty()) wineprefix = "/root/.wine";
    if (wine_path.empty()) wine_path = "/opt/wine/bin/wine64";

    std::string internalFilesDir = "/data/user/0/com.your.customemulator/files";
    std::string logPath = "/storage/emulated/0/Android/data/com.your.customemulator/files/wine_output.log";

    char prootCopy[1024];
    strncpy(prootCopy, proot_lib.c_str(), sizeof(prootCopy) - 1);
    prootCopy[sizeof(prootCopy) - 1] = '\0';
    std::string nativeDir = dirname(prootCopy);

    std::string loaderPath = nativeDir + "/libproot-loader.so";
    std::string loader32Path = nativeDir + "/libproot-loader32.so";

    // Guest (Box64 + Wine) boot වීමට අවශ්‍ය Environment Variables
    std::string shellCmd = "export DISPLAY=:0 && "
                           "export WINEPREFIX=\"" + wineprefix + "\" && "
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
                           "exec \"$@\"";

    std::vector<const char*> proot_args = {
            proot_lib.c_str(),
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
            wine_path.c_str()
    };

    // arg6 හි .exe හෝ wine command එකක් ඇත්නම් පමණක් එකතු කිරීම
    if (!arg6.empty() && arg6[0] != ':') {
        proot_args.push_back(arg6.c_str());
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

        fprintf(stderr, "[JNI ARGS] 1='%s' | 2='%s' | 3='%s' | 4='%s' | 5='%s' | 6='%s' | 7='%s' | 8='%s' | 9='%s'\n",
                proot_lib.c_str(), rootfs.c_str(), wineprefix.c_str(), box64_lib.c_str(),
                wine_path.c_str(), arg6.c_str(), arg7.c_str(), arg8.c_str(), arg9.c_str());

        // Host linker එකට libtalloc.so.2 සොයාගැනීමට paths ලබා දීම
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

        // 1. Direct execve (targetSdk=28 සමඟ libproot.so කෙලින්ම ක්‍රියාත්මක වේ)
        execve(proot_lib.c_str(), (char* const*)proot_args.data(), environ);
        fprintf(stderr, "1. Direct execve failed: errno=%d (%s)\n", errno, strerror(errno));

        // 2. Fallback: /system/bin/linker64 හරහා ධාවනය කිරීම
        std::vector<char*> linker_argv;
        linker_argv.push_back((char*)"/system/bin/linker64");
        for (size_t i = 0; proot_args[i] != nullptr; i++) {
            linker_argv.push_back((char*)proot_args[i]);
        }
        linker_argv.push_back(nullptr);

        execve("/system/bin/linker64", linker_argv.data(), environ);
        fprintf(stderr, "2. Linker64 execve failed: errno=%d (%s)\n", errno, strerror(errno));

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
