# SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
# SPDX-FileCopyrightText: 2019-2020 Normation SAS

include ../../../rust.makefile

# rudder-package tools, for general relay build see ../Makefile

check: lint
	cargo test --locked --features sequoia
