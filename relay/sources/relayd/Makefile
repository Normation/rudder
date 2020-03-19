# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2019-2020 Normation SAS

include ../../../rust.makefile

# Dev tools, for build see ../Makefile

yum-dependencies:
	yum install pkgconfig postgresql-devel openssl-devel zlib-devel asciidoc

apt-dependencies:
	apt update && apt install -y --no-install-recommends pkg-config libpq-dev libssl-dev zlib1g-dev asciidoc

