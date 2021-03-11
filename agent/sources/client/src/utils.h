// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#include <stdbool.h>

#define MIN(a, b) (((a) < (b)) ? (a) : (b))

#ifdef _WIN32
#    define PATH_SEPARATOR "\\"
#else
#    define PATH_SEPARATOR "/"
#endif

char* strdup_compat(const char* s);
bool read_file_content(const char* path, char** output);
bool file_exists(const char* path);
