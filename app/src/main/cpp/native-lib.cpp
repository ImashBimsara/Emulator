#include <jni.h>
#include <unistd.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "ProotJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_your_customemulator_MainActivity_runProot(JNIEnv* env, jobject /* this */, jstring jRootfs, jstring jBox64, jstring jWine, jstring jInstaller) {
    const char* rootfs = env->GetStringUTFChars(jRootfs, nullptr);
    const char* box64 = env->GetStringUTFChars(jBox64, nullptr);
    const char* wine = env->GetStringUTFChars(jWine, nullptr);
    const char* installer = env->GetStringUTFChars(jInstaller, nullptr);

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

    // proot executable එකේ path එක (nativeLibraryDir එකේ)
    // මේක අපිට JNI එකෙන් කෙළින්ම ගන්න බෑ, ඒ නිසා Kotlin එකෙන් pass කරන්න වෙනවා
    // (මම මේක පස්සේ හදන්නම්)

    env->ReleaseStringUTFChars(jRootfs, rootfs);
    env->ReleaseStringUTFChars(jBox64, box64);
    env->ReleaseStringUTFChars(jWine, wine);
    env->ReleaseStringUTFChars(jInstaller, installer);

    return 0;
}
