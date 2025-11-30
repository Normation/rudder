# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../cargo.mk

check: lint
	cd rudderc && make check
	$(MAKE) cargo-test PACKAGE=rudder-cli
	$(MAKE) cargo-test PACKAGE=rudder-commons
	$(MAKE) cargo-test PACKAGE=rudder-report
	$(MAKE) cargo-test PACKAGE=rudder-system-updates
	$(MAKE) cargo-test PACKAGE=rudder-system-updates FEATURES=apt
	$(MAKE) cargo-test PACKAGE=rudder-system-updates FEATURES=apt-compat
	$(MAKE) cargo-test PACKAGE=augeas
	$(MAKE) cargo-test PACKAGE=template
	$(MAKE) cargo-test PACKAGE=commands
	$(MAKE) cargo-test PACKAGE=inventory
