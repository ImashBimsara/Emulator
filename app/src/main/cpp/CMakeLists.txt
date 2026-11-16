cmake_minimum_required(VERSION 3.22.1)
project("prootjni")

# prootjni library එක
add_library(prootjni SHARED
        proot_jni.cpp)

# ✅ අලුතෙන් එකතු කරපු exechook library එක
add_library(exechook SHARED
        exec_hook.cpp)

target_link_libraries(prootjni
        android
        log)

# ✅ exechook එකට ඕන libraries
target_link_libraries(exechook
        log
        dl)
