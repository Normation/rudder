# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../cargo.mk

check: lint
	cd rudderc && make check
	$(MAKE) cargo-test PACKAGE=rudder_cli
	$(MAKE) cargo-test PACKAGE=rudder_commons
	$(MAKE) cargo-test PACKAGE=rudder_report
	$(MAKE) cargo-test PACKAGE=rudder_system_updates
	$(MAKE) cargo-test PACKAGE=rudder_system_updates FEATURES=apt
	$(MAKE) cargo-test PACKAGE=rudder_system_updates FEATURES=apt-compat
	$(MAKE) cargo-test PACKAGE=augeas
	$(MAKE) cargo-test PACKAGE=template
	$(MAKE) cargo-test PACKAGE=commands
	$(MAKE) cargo-test PACKAGE=inventory
