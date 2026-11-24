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

static void addBindIfExists(std::vector<const char*>& args, const char* path) {
    if (access(path, F_OK) == 0) {
        args.push_back("-b");
        args.push_back(path);
    }
}

static void copyLinkerConfigToRootfs(const std::string& rootfs) {
    std::string destDir = rootfs + "/linkerconfig";
    mkdir(destDir.c_str(), 0755);
    std::string destFile = destDir + "/ld.config.txt";

    int srcFd = open("/linkerconfig/ld.config.txt", O_RDONLY);
    if (srcFd < 0) return;

    int dstFd = open(destFile.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (dstFd < 0) {
        close(srcFd);
        return;
    }

    char buf[4096];
    ssize_t n;
    while ((n = read(srcFd, buf, sizeof(buf))) > 0) {
        write(dstFd, buf, n);
    }
    close(srcFd);
    close(dstFd);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(JNIEnv *env, jobject /* thiz */,
                                                    jstring jArg1, jstring jArg2, jstring jArg3, jstring jArg4,
                                                    jstring jArg5, jstring jArg6, jstring jArg7, jstring jArg8, jstring jArg9) {

    std::string proot_lib  = getJString(env, jArg1);
    std::string rootfs     = getJString(env, jArg2);
    std::string wineprefix = getJString(env, jArg3);
    std::string box64_lib  = getJString(env, jArg4);
    std::string wine_path  = getJString(env, jArg5);
    std::string arg6       = getJString(env, jArg6);
    std::string arg7       = getJString(env, jArg7);
    std::string arg8       = getJString(env, jArg8);
    std::string arg9       = getJString(env, jArg9);

    if (wineprefix.empty()) wineprefix = "/root/.wine";
    if (wine_path.empty()) wine_path = "/opt/wine/bin/wine64";

    std::string internalFilesDir = arg9.empty() ? "/data/user/0/com.your.customemulator/files" : arg9;
    std::string logPath = arg7.empty() ? "/storage/emulated/0/Android/data/com.your.customemulator/files/wine_output.log" : arg7;

    char prootCopy[1024];
    strncpy(prootCopy, proot_lib.c_str(), sizeof(prootCopy) - 1);
    prootCopy[sizeof(prootCopy) - 1] = '\0';
    std::string nativeDir = dirname(prootCopy);

    std::string loaderPath = nativeDir + "/libproot-loader.so";
    std::string loader32Path = nativeDir + "/libproot-loader32.so";

    // /linkerconfig permission denied මඟහැරීමට ld.config.txt කෙලින්ම rootfs තුළට copy කිරීම
    copyLinkerConfigToRootfs(rootfs);

    // Bionic (libbox64.so) සඳහා Android paths පමණක් LD_LIBRARY_PATH වෙත දී, x86_64 libs සඳහා BOX64_LD_LIBRARY_PATH භාවිතය
    std::string shellCmd = "export HOME=/root && "
                           "export USER=root && "
                           "export TMPDIR=/tmp && "
                           "export DISPLAY=:0 && "
                           "export WINEPREFIX=\"" + wineprefix + "\" && "
                           "export WINESERVER=/opt/wine/bin/wineserver && "
                           "export WINELOADER=\"" + wine_path + "\" && "
                           "export WINEDEBUG=-all,err+all && "
                           "export BOX64_LOG=1 && "
                           "export BOX64_NOBANNER=0 && "
                           "export BOX64_PATH=/opt/wine/bin && "
                           "export BOX64_LD_LIBRARY_PATH=/opt/wine/lib/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu && "
                           "export LD_LIBRARY_PATH=" + nativeDir + ":" + internalFilesDir +
                           ":/apex/com.android.runtime/lib64/bionic:/system/lib64:/system/lib && "
                           "echo '[GUEST] Testing Box64 binary...' >&2 && "
                           "\"" + box64_lib + "\" --version >&2 && "
                           "if [ -f " + internalFilesDir + "/libandroid-shmem.so ]; then "
                           "export LD_PRELOAD=" + internalFilesDir + "/libandroid-shmem.so; "
                           "fi && "
                           "echo '[GUEST] Launching Wine via Box64...' >&2 && "
                           "exec \"$@\"";

    std::vector<const char*> proot_args = {
            proot_lib.c_str(),
            "--link2symlink",
            "-0",
            "-r", rootfs.c_str()
    };

    addBindIfExists(proot_args, "/system");
    addBindIfExists(proot_args, "/apex");
    addBindIfExists(proot_args, "/vendor");
    addBindIfExists(proot_args, "/product");
    addBindIfExists(proot_args, "/odm");
    addBindIfExists(proot_args, "/dev");
    addBindIfExists(proot_args, "/proc");
    addBindIfExists(proot_args, "/sys");
    addBindIfExists(proot_args, "/data");
    addBindIfExists(proot_args, "/storage");

    proot_args.push_back("-w");
    proot_args.push_back("/");
    proot_args.push_back("/system/bin/sh");
    proot_args.push_back("-c");
    proot_args.push_back(shellCmd.c_str());
    proot_args.push_back("sh");
    proot_args.push_back(box64_lib.c_str());
    proot_args.push_back(wine_path.c_str());

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

        bool hasLoader = (access(loaderPath.c_str(), F_OK) == 0);
        fprintf(stderr, "[JNI CHECK] libproot-loader.so exists: %s (%s)\n",
                hasLoader ? "YES" : "NO", loaderPath.c_str());

        std::string hostLdPath = nativeDir + ":" + internalFilesDir;
        setenv("LD_LIBRARY_PATH", hostLdPath.c_str(), 1);
        setenv("PROOT_TMP_DIR", internalFilesDir.c_str(), 1);

        if (hasLoader) {
            setenv("PROOT_LOADER", loaderPath.c_str(), 1);
        }
        if (access(loader32Path.c_str(), F_OK) == 0) {
            setenv("PROOT_LOADER_32", loader32Path.c_str(), 1);
        }

        execve(proot_lib.c_str(), (char* const*)proot_args.data(), environ);
        fprintf(stderr, "Direct execve failed: errno=%d (%s)\n", errno, strerror(errno));

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
