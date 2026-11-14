#include <jni.h>
#include <unistd.h>
#include <sys/wait.h>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "ProotJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(
        JNIEnv* env,
        jobject /* this */,
        jstring jProotLibPath,
        jstring jRootfs,
        jstring jBox64,
        jstring jWine,
        jstring jInstaller) {

    const char* prootLibPath = env->GetStringUTFChars(jProotLibPath, nullptr);
    const char* rootfs = env->GetStringUTFChars(jRootfs, nullptr);
    const char* box64 = env->GetStringUTFChars(jBox64, nullptr);
    const char* wine = env->GetStringUTFChars(jWine, nullptr);
    const char* installer = env->GetStringUTFChars(jInstaller, nullptr);

    LOGI("Proot Lib Path: %s", prootLibPath);
    LOGI("Rootfs: %s", rootfs);
    LOGI("Box64: %s", box64);
    LOGI("Wine: %s", wine);
    LOGI("Installer: %s", installer);

    // proot command එක හදමු
    // proot -r rootfs box64 wine installer
    char* const argv[] = {
            (char*)"proot",
            (char*)"-r", (char*)rootfs,
            (char*)box64,
            (char*)wine,
            (char*)installer,
            nullptr
    };

    // fork() කරලා child process එකක් හදමු
    pid_t pid = fork();
    if (pid == 0) {
        // Child process
        // proot library එක execve() කරන්න
        execv(prootLibPath, argv);
        LOGE("execv failed: %s", strerror(errno));
        _exit(1);
    } else if (pid > 0) {
        // Parent process
        int status;
        waitpid(pid, &status, 0);
        LOGI("Proot process exited with status: %d", WEXITSTATUS(status));
    } else {
        LOGE("fork failed");
    }

    env->ReleaseStringUTFChars(jProotLibPath, prootLibPath);
    env->ReleaseStringUTFChars(jRootfs, rootfs);
    env->ReleaseStringUTFChars(jBox64, box64);
    env->ReleaseStringUTFChars(jWine, wine);
    env->ReleaseStringUTFChars(jInstaller, installer);

    return 0;
}
