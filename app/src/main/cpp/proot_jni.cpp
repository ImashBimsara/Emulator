#include <jni.h>
#include <unistd.h>
#include <sys/wait.h>
#include <string>
#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>

#define LOG_TAG "ProotJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProotJNI(
        JNIEnv* env,
        jobject /* this */,
        jstring jProotLibPath,
        jstring jRootfs,
        jstring jWinePrefix,
        jstring jBox64,
        jstring jWine,
        jstring jInstaller) {

    const char* prootLibPath = env->GetStringUTFChars(jProotLibPath, nullptr);
    const char* rootfs = env->GetStringUTFChars(jRootfs, nullptr);
    const char* winePrefix = env->GetStringUTFChars(jWinePrefix, nullptr);
    const char* box64 = env->GetStringUTFChars(jBox64, nullptr);
    const char* wine = env->GetStringUTFChars(jWine, nullptr);
    const char* installer = env->GetStringUTFChars(jInstaller, nullptr);

    LOGI("Starting Proot...");
    LOGI("Rootfs: %s", rootfs);
    LOGI("WinePrefix: %s", winePrefix);

    pid_t pid = fork();
    if (pid == 0) {
        // Child process - Environment Variables set කරන්න
        setenv("PROOT_NO_SECURE", "1", 1);
        setenv("WINEPREFIX", winePrefix, 1);
        setenv("WINEDEBUG", "-all", 1);
        setenv("WINEARCH", "win64", 1);
        setenv("DISPLAY", ":0", 1);

        // proot command එක හදමු
        char* const argv[] = {
                (char*)"proot",
                (char*)"-r", (char*)rootfs,
                (char*)box64,
                (char*)wine,
                (char*)installer,
                nullptr
        };

        execv(prootLibPath, argv);
        LOGE("execv failed: %s", strerror(errno));
        _exit(1);
    } else if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
        LOGI("Proot exited with status: %d", WEXITSTATUS(status));
    } else {
        LOGE("fork failed");
    }

    env->ReleaseStringUTFChars(jProotLibPath, prootLibPath);
    env->ReleaseStringUTFChars(jRootfs, rootfs);
    env->ReleaseStringUTFChars(jWinePrefix, winePrefix);
    env->ReleaseStringUTFChars(jBox64, box64);
    env->ReleaseStringUTFChars(jWine, wine);
    env->ReleaseStringUTFChars(jInstaller, installer);

    return 0;
}
