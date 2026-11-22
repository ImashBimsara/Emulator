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

// ෆයිල් කොපි කරගැනීමට උදව් ෆන්ක්ෂන් එකක් (Android 10+ noexec bypass)
bool copyFile(const char* src, const char* dst) {
    std::ifstream srcFile(src, std::ios::binary);
    std::ofstream dstFile(dst, std::ios::binary);
    if (!srcFile || !dstFile) return false;
    dstFile << srcFile.rdbuf();
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_startWine(JNIEnv *env, jobject /* thiz */,
                                                    jstring jRootfs, 
                                                    jstring jProotPath, 
                                                    jstring jBox64Path, 
                                                    jstring jInstallerPath) {

    const char *rootfs = env->GetStringUTFChars(jRootfs, nullptr);
    const char *proot_orig = env->GetStringUTFChars(jProotPath, nullptr);
    const char *box64_orig = env->GetStringUTFChars(jBox64Path, nullptr);
    const char *installer = env->GetStringUTFChars(jInstallerPath, nullptr);

    // 1. App එකේ Files Directory එක ලබා ගැනීම (Temp සහ Log සඳහා)
    std::string appFilesDir = "/data/data/com.your.customemulator/files";
    std::string tmpDir = appFilesDir + "/tmp";
    mkdir(appFilesDir.c_str(), 0777);
    mkdir(tmpDir.c_str(), 0777);

    // 2. Android 10+ Restriction (noexec) මගහරවා ගැනීමට, Binaries Files Dir එකට Copy කිරීම
    std::string proot = appFilesDir + "/proot";
    std::string box64 = appFilesDir + "/box64";
    
    copyFile(proot_orig, proot.c_str());
    chmod(proot.c_str(), 0755); // Execute Permission දීම
    
    copyFile(box64_orig, box64.c_str());
    chmod(box64.c_str(), 0755);

    // Box64 Path එකෙන් nativeDir එක (උදා: /data/app/.../lib/arm64) වෙන්කර ගැනීම
    char box64Copy[1024];
    strncpy(box64Copy, box64_orig, sizeof(box64Copy));
    std::string nativeDir = dirname(box64Copy);

    // 3. Shell Command එක (අලුත් Paths සමඟ)
    std::string shellCmd = "export LD_LIBRARY_PATH=" + nativeDir + 
                           ":/system/lib64:/system/lib" +
                           ":/usr/lib/x86_64-linux-gnu:/opt/wine/lib:/opt/wine/lib/wine/x86_64-unix" +
                           ":/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib && exec \"$@\"";

    // 4. PRoot Arguments (අලුත් path එකෙන් proot එක run කරයි)
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
            
            (char*)"/system/bin/sh",
            (char*)"-c",
            (char*)shellCmd.c_str(),
            (char*)"sh",      
            
            (char*)box64.c_str(),             // Copy කළ Box64
            (char*)"/opt/wine/bin/wine64",    
            (char*)installer,                 
            nullptr
    };

    LOGI("Starting PRoot process...");
    
    pid_t pid = fork();
    
    if (pid == 0) {
        // Child Process
        
        // 5. LOG REDIRECTION (මෙතනදි තමයි wine_output.log හැදෙන්නේ)
        std::string logPath = appFilesDir + "/wine_output.log";
        int logFd = open(logPath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
        if (logFd >= 0) {
            dup2(logFd, STDOUT_FILENO); // stdout log එකට යවන්න
            dup2(logFd, STDERR_FILENO); // stderr log එකට යවන්න
            close(logFd);
        } else {
            LOGE("Failed to open log file!");
        }

        // 6. PROOT_TMP_DIR නිවැරදි කිරීම (rootfs වෙනුවට appFilesDir)
        setenv("PROOT_TMP_DIR", tmpDir.c_str(), 1); 
        
        // 7. Copy කළ proot එක run කිරීම
        execv(proot.c_str(), argv);
        
        LOGE("execv failed! Error code: %d (%s)", errno, strerror(errno));
        exit(127);
        
    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
        
        env->ReleaseStringUTFChars(jRootfs, rootfs);
        env->ReleaseStringUTFChars(jProotPath, proot_orig);
        env->ReleaseStringUTFChars(jBox64Path, box64_orig);
        env->ReleaseStringUTFChars(jInstallerPath, installer);
        
        int exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : 255;
        LOGI("PRoot process exited with code: %d", exitCode);
        return exitCode;
    } else {
        LOGE("Fork failed!");
        return -1;
    }
}
