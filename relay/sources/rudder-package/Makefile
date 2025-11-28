# SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
# SPDX-FileCopyrightText: 2019-2020 Normation SAS

include ../../../cargo.mk

# rudder-package tools, for general relay build see ../Makefile

check: lint
	$(MAKE) cargo-test PACKAGE=rudder-package FEATURES=sequoia
	$(MAKE) cargo-test PACKAGE=rudder-package FEATURES=
