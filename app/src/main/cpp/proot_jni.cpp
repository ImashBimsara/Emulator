#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/wait.h>
#include <android/log.h>
#include <stdlib.h>
#include <libgen.h>
#include <string.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EmulatorJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmulatorJNI", __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_startWine(JNIEnv *env, jobject /* thiz */,
                                                    jstring jRootfs, 
                                                    jstring jProotPath, 
                                                    jstring jBox64Path, 
                                                    jstring jInstallerPath) {

    // Java වලින් එන String දත්ත C++ const char* බවට හැරවීම
    const char *rootfs = env->GetStringUTFChars(jRootfs, nullptr);
    const char *proot = env->GetStringUTFChars(jProotPath, nullptr);
    const char *box64 = env->GetStringUTFChars(jBox64Path, nullptr);
    const char *installer = env->GetStringUTFChars(jInstallerPath, nullptr);

    // Box64 Path එකෙන් nativeDir එක (උදා: /data/app/.../lib/arm64) වෙන්කර ගැනීම
    char box64Copy[1024];
    strncpy(box64Copy, box64, sizeof(box64Copy));
    std::string nativeDir = dirname(box64Copy);

    // 1. පිරිසිදු Shell Command එක (LD_PRELOAD නොමැතිව)
    // මෙහිදී app එකේ nativeDir එක සහ Android/Ubuntu system paths පමණක් LD_LIBRARY_PATH වෙත ලබා දේ
    std::string shellCmd = "export LD_LIBRARY_PATH=" + nativeDir + 
                           ":/system/lib64:/system/lib" +
                           ":/usr/lib/x86_64-linux-gnu:/opt/wine/lib:/opt/wine/lib/wine/x86_64-unix" +
                           ":/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib && exec \"$@\"";

    // 2. PRoot සඳහා ලබාදෙන පිරිසිදු Arguments
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
            
            (char*)box64,                    // Box64 ධාවනය කිරීම
            (char*)"/opt/wine/bin/wine64",   // Wine64 ධාවනය කිරීම
            (char*)installer,                // Windows .exe ෆයිල් එක
            nullptr
    };

    LOGI("Starting PRoot process...");
    
    // Process එක Fork කිරීම
    pid_t pid = fork();
    
    if (pid == 0) {
        // Child Process - PRoot ධාවනය කිරීම
        setenv("PROOT_TMP_DIR", rootfs, 1); 
        
        execv(proot, argv);
        
        // execv අසමත් වුවහොත් පමණක් මෙය ධාවනය වේ
        LOGE("execv failed! Error code: %d", errno);
        exit(127);
    } else if (pid > 0) {
        // Parent Process - Child අවසන් වනතුරු රැඳී සිටීම
        int status;
        waitpid(pid, &status, 0);
        
        // මතකය නිදහස් කිරීම (Memory Cleanup)
        env->ReleaseStringUTFChars(jRootfs, rootfs);
        env->ReleaseStringUTFChars(jProotPath, proot);
        env->ReleaseStringUTFChars(jBox64Path, box64);
        env->ReleaseStringUTFChars(jInstallerPath, installer);
        
        int exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : 255;
        LOGI("PRoot process exited with code: %d", exitCode);
        return exitCode;
    } else {
        // Fork අසමත් වූ විට
        LOGE("Fork failed!");
        return -1;
    }
}
