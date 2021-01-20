// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#ifndef CLI_H
#define CLI_H

#include <stdbool.h>
#include <stdlib.h>

static const char PROG_NAME[] = "rudder_client";
static const char PROG_VERSION[] = "0.0.0-dev";

typedef enum upload { UploadReport, UploadInventory } UploadType;

int start(int argc, char* argv[]);

#endif /* CLI_H */
