# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2019-2020 Normation SAS

include ../../../cargo.mk

# relayd tools, for general relay build see ../Makefile

check: lint
	$(MAKE) cargo-test PACKAGE=rudder-relayd
