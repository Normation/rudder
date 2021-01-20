// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#include <stdlib.h>
#include <string.h>

char* strdup(const char* s) {
    size_t size = strlen(s) + 1;
    char* p = calloc(size, 1);
    if (p) {
        memcpy(p, s, size);
    }
    return p;
}
