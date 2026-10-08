# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 Normation SAS

# Before the include: cargo.mk derives PACKAGE_OPT from it when included
export PACKAGE := rudder-mcp

include ../cargo.mk

check: lint
	$(MAKE) cargo-test
