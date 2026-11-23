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
#include <fstream>
#include <iostream>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EmulatorJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmulatorJNI", __VA_ARGS__)

bool copyFile(const char* src, const char* dst) {
    std::ifstream srcFile(src, std::ios::binary);
    std::ofstream dstFile(dst, std::ios::binary);
    if (!srcFile || !dstFile) return false;
    dstFile << srcFile.rdbuf();
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(JNIEnv *env, jobject /* thiz */,
                                                    jstring jArg1, jstring jArg2, jstring jArg3, jstring jArg4,
                                                    jstring jArg5, jstring jArg6, jstring jArg7, jstring jArg8, jstring jArg9) {

    const char *rootfs = env->GetStringUTFChars(jArg1, nullptr);
    const char *proot_orig = env->GetStringUTFChars(jArg2, nullptr);
    const char *box64_orig = env->GetStringUTFChars(jArg3, nullptr);
    const char *installer = env->GetStringUTFChars(jArg4, nullptr);

    // අපි දැන් ලොග් එක ලියන්නේ Download ෆෝල්ඩරයට. එතකොට අපිට ලේසියෙන්ම ගන්න පුළුවන්.
    std::string logPath = "/storage/emulated/0/Download/wine_output.log";
    
    // 1. Fork එකට කලින්ම log file එක හදමු
    int logFd = open(logPath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (logFd >= 0) {
        LOGI("Log file created at: %s", logPath.c_str());
    } else {
        LOGE("Failed to create log file! errno=%d", errno);
    }

    std::string appFilesDir = "/data/data/com.your.customemulator/files";
    std::string tmpDir = appFilesDir + "/tmp";
    mkdir(appFilesDir.c_str(), 0777);
    mkdir(tmpDir.c_str(), 0777);

    std::string proot = appFilesDir + "/proot";
    std::string box64 = appFilesDir + "/box64";
    
    copyFile(proot_orig, proot.c_str());
    chmod(proot.c_str(), 0755);
    copyFile(box64_orig, box64.c_str());
    chmod(box64.c_str(), 0755);

    char box64Copy[1024];
    strncpy(box64Copy, box64_orig, sizeof(box64Copy));
    std::string nativeDir = dirname(box64Copy);

    std::string shellCmd = "export LD_LIBRARY_PATH=" + nativeDir + 
                           ":/system/lib64:/system/lib" +
                           ":/usr/lib/x86_64-linux-gnu:/opt/wine/lib:/opt/wine/lib/wine/x86_64-unix" +
                           ":/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib && exec \"$@\"";

    char* const argv[] = {
            (char*)"proot", (char*)"--link2symlink", (char*)"-0",
            (char*)"-r", (char*)rootfs, (char*)"-b", (char*)"/system", (char*)"-b", (char*)"/apex",
            (char*)"-b", (char*)"/vendor", (char*)"-b", (char*)"/dev", (char*)"-b", (char*)"/proc",
            (char*)"-b", (char*)"/sys", (char*)"-b", (char*)"/data", (char*)"-w", (char*)"/",
            (char*)"/system/bin/sh", (char*)"-c", (char*)shellCmd.c_str(), (char*)"sh",      
            (char*)box64.c_str(), (char*)"/opt/wine/bin/wine64", (char*)installer, nullptr
    };

    LOGI("Starting PRoot process...");
    
    pid_t pid = fork();
    
    if (pid == 0) {
        // Child Process
        if (logFd >= 0) {
            dup2(logFd, STDOUT_FILENO); 
            dup2(logFd, STDERR_FILENO); 
            close(logFd);
        }
        setenv("PROOT_TMP_DIR", tmpDir.c_str(), 1); 
        
        LOGI("Executing proot...");
        execv(proot.c_str(), argv);
        
        LOGE("execv failed! Error code: %d (%s)", errno, strerror(errno));
        exit(127);
        
    } else if (pid > 0) {
        if (logFd >= 0) close(logFd); // Parent එකේ fd එක close කරන්න
        
        int status;
        waitpid(pid, &status, 0);
        
        if (WIFSIGNALED(status)) {
            LOGE("Child was killed by signal: %d", WTERMSIG(status));
        } else if (WIFEXITED(status)) {
            LOGI("Child exited normally with code: %d", WEXITSTATUS(status));
        }
        
        env->ReleaseStringUTFChars(jArg1, rootfs);
        env->ReleaseStringUTFChars(jArg2, proot_orig);
        env->ReleaseStringUTFChars(jArg3, box64_orig);
        env->ReleaseStringUTFChars(jArg4, installer);
        
        return 255; // සාමාන්‍යයෙන් 255 වෙනුවට exit code එක return කරන්න
    } else {
        LOGE("Fork failed!");
        return -1;
    }
}
