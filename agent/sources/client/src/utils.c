// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#ifdef __unix__
#    define _XOPEN_SOURCE 500
#endif

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h> // stat
#include "log.h"

char* strdup_compat(const char* s) {
#ifdef __unix__
    return strdup(s);
#elif _WIN32
    return _strdup(s);
#endif
}

bool read_file_content(const char* path, char** output) {
    FILE* fp = fopen(path, "r");
    if (fp == NULL) {
        error("cannot open %s: %s", path, strerror(errno));
        return false;
    }

    char buffer[1024];
    char* res = fgets(buffer, sizeof(buffer), fp);
    if (res == NULL) {
        error("cannot read: %s", path, strerror(errno));
        return false;
    }
    // strip \n
    res[strcspn(res, "\n")] = 0;
    *output = strdup_compat(res);
    return true;
}

bool file_exists(const char* path) {
    if (path == NULL) {
        return false;
    }
    struct stat buffer;
    return (stat(path, &buffer) == 0);
}
