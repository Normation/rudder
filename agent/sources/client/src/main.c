// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#include "cli.h"
#include "log.h"

int main(int argc, const char* argv[]) {
    output_set_enabled(true);
    return start(argc, argv);
    output_free();
}
