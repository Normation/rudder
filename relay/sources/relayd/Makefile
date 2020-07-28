# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2019-2020 Normation SAS

include ../../../rust.makefile

# relayd tools, for general relay build see ../Makefile

yum-dependencies:
	$(YUM) pkgconfig postgresql-devel openssl-devel zlib-devel

apt-dependencies:
	$(APT) pkg-config libpq-dev libssl-dev zlib1g-dev

# Test

test:
	cargo test
