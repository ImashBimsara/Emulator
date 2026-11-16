#define _GNU_SOURCE
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>

// execve function pointer
typedef int (*execve_func_t)(const char *filename, char *const argv[], char *const envp[]);

// අපි execve එක අල්ලා ගන්නවා
extern "C" int execve(const char *filename, char *const argv[], char *const envp[]) {
    // ඇත්තම execve එක හොයාගන්නවා
    execve_func_t original_execve = (execve_func_t)dlsym(RTLD_NEXT, "execve");

    // අපි run කරන්න හදන්නේ box64 ද කියලා බලනවා
    if (strstr(filename, "box64") != NULL) {
        int src_fd = open(filename, O_RDONLY);
        if (src_fd >= 0) {
            // RAM එකේ නිර්නාමික මතක ගොනුවක් (memfd) හදනවා
            int mem_fd = syscall(__NR_memfd_create, "box64_memfd", 0x0001); // MFD_CLOEXEC
            if (mem_fd >= 0) {
                char buf[8192];
                ssize_t n;
                // box64 ෆයිල් එකේ බයිට්ස් RAM එකට කොපි කරනවා
                while ((n = read(src_fd, buf, sizeof(buf))) > 0) {
                    write(mem_fd, buf, n);
                }
                close(src_fd);

                // /proc/self/fd/ හරහා ඒක run කරනවා
                char proc_path[128];
                snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", mem_fd);

                int ret = original_execve(proc_path, argv, envp);
                close(mem_fd);
                return ret;
            }
            close(src_fd);
        }
    }

    // box64 නෙවෙයි නම්, සාමාන්‍ය විදිහට run කරනවා
    return original_execve(filename, argv, envp);
}
