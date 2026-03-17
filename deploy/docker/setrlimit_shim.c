#define _GNU_SOURCE
#include <sys/resource.h>
#include <dlfcn.h>
#include <stdio.h>

/* Intercept setrlimit */
typedef int (*orig_setrlimit_t)(__rlimit_resource_t resource, const struct rlimit *rlim);

int setrlimit(__rlimit_resource_t resource, const struct rlimit *rlim) {
    orig_setrlimit_t orig = (orig_setrlimit_t)dlsym(RTLD_NEXT, "setrlimit");
    int ret = orig(resource, rlim);
    if (ret != 0 && resource == RLIMIT_CORE) {
        fprintf(stderr, "WARN: setrlimit(CORE) failed, continuing without core dumps\n");
        return 0;
    }
    return ret;
}

/* Intercept setrlimit64 — Rust libc uses this on glibc systems */
typedef int (*orig_setrlimit64_t)(__rlimit_resource_t resource, const struct rlimit64 *rlim);

int setrlimit64(__rlimit_resource_t resource, const struct rlimit64 *rlim) {
    orig_setrlimit64_t orig = (orig_setrlimit64_t)dlsym(RTLD_NEXT, "setrlimit64");
    int ret = orig(resource, rlim);
    if (ret != 0 && resource == RLIMIT_CORE) {
        fprintf(stderr, "WARN: setrlimit64(CORE) failed, continuing without core dumps\n");
        return 0;
    }
    return ret;
}
