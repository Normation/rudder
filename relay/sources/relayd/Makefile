# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2019-2020 Normation SAS

include ../../../rust.makefile

# relayd tools, for general relay build see ../Makefile

check: lint
	# -j1 for now to avoid test interference on remote run
	cargo nextest run --locked --retries 3 -j1
