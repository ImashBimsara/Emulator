cmake_minimum_required(VERSION 3.22.1)
project("prootjni")

add_library(prootjni SHARED
        proot_jni.cpp)

target_link_libraries(prootjni
        android
        log)
