#define _GNU_SOURCE
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>

typedef void* (*mmap_func_t)(void*, size_t, int, int, int, off_t);
typedef void* (*mmap64_func_t)(void*, size_t, int, int, int, off64_t);

extern "C" {

void* mmap(void* addr, size_t length, int prot, int flags, int fd, off_t offset) {
    static mmap_func_t real_mmap = nullptr;
    if (!real_mmap) real_mmap = (mmap_func_t)dlsym(RTLD_NEXT, "mmap");

    if (fd >= 0 && (prot & PROT_EXEC)) {
        int mem_fd = syscall(279, "wine_hook", 1); // memfd_create
        if (mem_fd >= 0) {
            char buf[8192];
            ssize_t n;
            off_t orig = lseek(fd, 0, SEEK_CUR);
            lseek(fd, 0, SEEK_SET);
            while((n = read(fd, buf, sizeof(buf))) > 0) write(mem_fd, buf, n);
            lseek(fd, orig, SEEK_SET);
            
            void* res = real_mmap(addr, length, prot, flags, mem_fd, offset);
            close(mem_fd);
            return res;
        }
    }
    return real_mmap(addr, length, prot, flags, fd, offset);
}

void* mmap64(void* addr, size_t length, int prot, int flags, int fd, off64_t offset) {
    static mmap64_func_t real_mmap64 = nullptr;
    if (!real_mmap64) real_mmap64 = (mmap64_func_t)dlsym(RTLD_NEXT, "mmap64");

    if (fd >= 0 && (prot & PROT_EXEC)) {
        int mem_fd = syscall(279, "wine_hook64", 1);
        if (mem_fd >= 0) {
            char buf[8192];
            ssize_t n;
            off64_t orig = lseek64(fd, 0, SEEK_CUR);
            lseek64(fd, 0, SEEK_SET);
            while((n = read(fd, buf, sizeof(buf))) > 0) write(mem_fd, buf, n);
            lseek64(fd, orig, SEEK_SET);
            
            void* res = real_mmap64(addr, length, prot, flags, mem_fd, offset);
            close(mem_fd);
            return res;
        }
    }
    return real_mmap64(addr, length, prot, flags, fd, offset);
}

} // extern "C"
