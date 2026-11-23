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
#include <sys/syscall.h>
#include <sys/mman.h>
#include <errno.h>
#include <stdio.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EmulatorJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmulatorJNI", __VA_ARGS__)

extern char **environ;

// Fallback: memfd හරහා execute කිරීමට උත්සාහ කිරීම
int execute_memfd(const char* path, char* const argv[]) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "Failed to open proot: %s (errno=%d: %s)\n", path, errno, strerror(errno));
        return -1;
    }

    // 279 = SYS_memfd_create (ARM64), 1 = MFD_CLOEXEC
    int mem_fd = syscall(279, "proot_exec", 1);
    if (mem_fd < 0) {
        fprintf(stderr, "memfd_create failed! errno=%d (%s)\n", errno, strerror(errno));
        close(fd);
        return -1;
    }

    char buf[8192];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        if (write(mem_fd, buf, n) != n) {
            fprintf(stderr, "Failed to write to memfd! errno=%d (%s)\n", errno, strerror(errno));
            close(fd);
            close(mem_fd);
            return -1;
        }
    }
    close(fd);

    fchmod(mem_fd, 0700);

    // 281 = SYS_execveat (ARM64), 0x1000 = AT_EMPTY_PATH
    syscall(281, mem_fd, "", argv, environ, 0x1000);

    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", mem_fd);
    execve(proc_path, argv, environ);

    close(mem_fd);
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(JNIEnv *env, jobject /* thiz */,
                                                    jstring jArg1, jstring jArg2, jstring jArg3, jstring jArg4,
                                                    jstring jArg5, jstring jArg6, jstring jArg7, jstring jArg8, jstring jArg9) {

    const char *rootfs = env->GetStringUTFChars(jArg1, nullptr);
    const char *proot_lib = env->GetStringUTFChars(jArg2, nullptr);
    const char *box64_lib = env->GetStringUTFChars(jArg3, nullptr);
    const char *installer = env->GetStringUTFChars(jArg4, nullptr);

    std::string internalFilesDir = "/data/data/com.your.customemulator/files";
    std::string logPath = "/storage/emulated/0/Android/data/com.your.customemulator/files/wine_output.log";

    char box64Copy[1024];
    strncpy(box64Copy, box64_lib, sizeof(box64Copy) - 1);
    box64Copy[sizeof(box64Copy) - 1] = '\0';
    std::string nativeDir = dirname(box64Copy);

    std::string loaderPath = nativeDir + "/libproot-loader.so";
    std::string loader32Path = nativeDir + "/libproot-loader32.so";

    // Guest (Box64 + Wine) boot වීමට අවශ්‍ය Environment Variables
    std::string shellCmd = "export DISPLAY=:0 && "
                           "export WINEPREFIX=/root/.wine && "
                           "export WINEDEBUG=-all,err+all && "
                           "export BOX64_LD_LIBRARY_PATH=/opt/wine/lib/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu && "
                           "export LD_LIBRARY_PATH=" + nativeDir + ":" + internalFilesDir +
                           ":/system/lib64:/system/lib:/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib && "
                           "if [ -f " + internalFilesDir + "/libandroid-shmem.so ]; then "
                           "export LD_PRELOAD=" + internalFilesDir + "/libandroid-shmem.so; "
                           "elif [ -f /usr/lib/libandroid-shmem.so ]; then "
                           "export LD_PRELOAD=/usr/lib/libandroid-shmem.so; "
                           "fi && "
                           "exec \"$@\"";

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

    LOGI("Starting PRoot (Direct execve primary)...");

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

        // libtalloc.so.2 සොයාගැනීමට host LD_LIBRARY_PATH සැකසීම
        std::string hostLdPath = nativeDir + ":" + internalFilesDir;
        setenv("LD_LIBRARY_PATH", hostLdPath.c_str(), 1);
        setenv("PROOT_TMP_DIR", internalFilesDir.c_str(), 1);
        setenv("PROOT_NO_SECCOMP", "1", 1);

        // Native lib folder එකේ ඇති PRoot loaders යොමු කිරීම
        if (access(loaderPath.c_str(), F_OK) == 0) {
            setenv("PROOT_LOADER", loaderPath.c_str(), 1);
        }
        if (access(loader32Path.c_str(), F_OK) == 0) {
            setenv("PROOT_LOADER_32", loader32Path.c_str(), 1);
        }

        // 1. Direct execve (targetSdk 28 සමඟ කෙලින්ම ක්‍රියාත්මක වේ)
        execve(proot_lib, argv, environ);
        fprintf(stderr, "1. Direct execve failed: errno=%d (%s)\n", errno, strerror(errno));

        // 2. Linker64 fallback
        std::vector<char*> linker_argv;
        linker_argv.push_back((char*)"/system/bin/linker64");
        for (int i = 0; argv[i] != nullptr; i++) {
            linker_argv.push_back(argv[i]);
        }
        linker_argv.push_back(nullptr);

        execve("/system/bin/linker64", linker_argv.data(), environ);
        fprintf(stderr, "2. Linker64 execve failed: errno=%d (%s)\n", errno, strerror(errno));

        // 3. Memfd fallback
        execute_memfd(proot_lib, argv);
        fprintf(stderr, "3. Memfd execve failed: errno=%d (%s)\n", errno, strerror(errno));

        // Multithreaded fork එකකදී exit() වෙනුවට _exit() භාවිතා කිරීමෙන් SIGSEGV වළක්වයි
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
