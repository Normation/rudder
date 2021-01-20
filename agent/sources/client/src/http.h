// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#ifndef HTTP_H
#define HTTP_H

#include "cli.h"
#include "config.h"

int upload_file(Config config, bool verbose, const char* file, UploadType type, bool new);
int get_id(Config config, bool verbose);

#endif /* HTTP_H */
