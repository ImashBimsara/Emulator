cmake_minimum_required(VERSION 3.22.1)
project("prootjni")

# prootjni library එක
add_library(prootjni SHARED
        proot_jni.cpp)

target_link_libraries(prootjni
        android
        log)

add_library(mmap_hook SHARED mmap_hook.cpp)
# කලින් තිබුණ 'log' ඉවත් කර dl පමණක් තබන්න
target_link_libraries(mmap_hook dl)
