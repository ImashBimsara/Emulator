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
#include <sys/syscall.h>
#include <sys/mman.h>
#include <errno.h>
#include <stdio.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EmulatorJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmulatorJNI", __VA_ARGS__)

extern char **environ;

// libproot.so එක RAM එකට load කරලා run කරන function එක (Android 10+ Bypass)
int execute_memfd(const char* path, char* const argv[]) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        LOGE("Failed to open proot: %s (errno=%d)", path, errno);
        return -1;
    }

    int mem_fd = syscall(279, "proot_exec", 1); // 279 = memfd_create
    if (mem_fd < 0) {
        LOGE("memfd_create failed! errno=%d", errno);
        close(fd);
        return -1;
    }

    char buf[8192];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        if (write(mem_fd, buf, n) != n) {
            LOGE("Failed to write to memfd! errno=%d", errno);
            close(fd); close(mem_fd); return -1;
        }
    }
    close(fd);

    // 🔥 මේක අත්‍යවශ්‍යයි: memfd එකට execute permission දෙනවා
    fchmod(mem_fd, 0700); 

    // 🔥 Android NDK එකේ fexecve නැති නිසා, අපි execveat syscall එක පාවිච්චි කරනවා
    // SYS_execveat = 281 (ARM64). AT_EMPTY_PATH = 0x1000
    syscall(281, mem_fd, "", argv, environ, 0x1000);
    
    // ඒකෙන් fail වුණොත් (සමහර පරණ කර්නල් වල), අපි /proc/self/fd හරහා try කරනවා
    LOGE("execveat failed! errno=%d (%s). Trying /proc/self/fd...", errno, strerror(errno));
    
    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", mem_fd);
    execve(proc_path, argv, environ);

    LOGE("execve failed entirely! errno=%d (%s)", errno, strerror(errno));
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

    std::string logPath = "/data/data/com.your.customemulator/files/wine_output.log";
    
    char box64Copy[1024];
    strncpy(box64Copy, box64_lib, sizeof(box64Copy));
    std::string nativeDir = dirname(box64Copy);

    std::string shellCmd = "export LD_LIBRARY_PATH=" + nativeDir + 
                           ":/system/lib64:/system/lib" +
                           ":/usr/lib/x86_64-linux-gnu:/opt/wine/lib:/opt/wine/lib/wine/x86_64-unix" +
                           ":/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib && exec \"$@\"";

    // 🔥 වෙනස: මෙතන argv[0] එකට දෙන්නේ proot_lib path එක (path එක හොයාගන්න "proot" කියලා දුන්නම crash වෙනවා)
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

    LOGI("Starting PRoot via Memfd...");
    
    pid_t pid = fork();
    
    if (pid == 0) {
        // Child Process
        int logFd = open(logPath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
        if (logFd >= 0) {
            dup2(logFd, STDOUT_FILENO); 
            dup2(logFd, STDERR_FILENO); 
            close(logFd);
        } else {
            LOGE("Failed to open log file! errno=%d", errno);
        }

        execute_memfd(proot_lib, argv);
        
        exit(127);
        
    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
        
        if (WIFSIGNALED(status)) {
            LOGE("Proot killed by signal: %d", WTERMSIG(status));
        } else if (WIFEXITED(status)) {
            LOGI("Proot exited with code: %d", WEXITSTATUS(status));
        }
        
        env->ReleaseStringUTFChars(jArg1, rootfs);
        env->ReleaseStringUTFChars(jArg2, proot_lib);
        env->ReleaseStringUTFChars(jArg3, box64_lib);
        env->ReleaseStringUTFChars(jArg4, installer);
        
        return WIFEXITED(status) ? WEXITSTATUS(status) : 255;
    } else {
        LOGE("Fork failed!");
        return -1;
    }
}
