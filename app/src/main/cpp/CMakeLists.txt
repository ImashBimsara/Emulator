cmake_minimum_required(VERSION 3.22.1)
project("prootjni")

# prootjni library එක
add_library(prootjni SHARED
        proot_jni.cpp)

target_link_libraries(prootjni
        android
        log)

# පරණ එකට පහළින් මේ ටික දාන්න
add_library(mmap_hook SHARED mmap_hook.cpp)
target_link_libraries(mmap_hook log dl)
