# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../cargo.mk

check-methods-unix:
	$(MAKE) cargo-test PACKAGE=lib FEATURES=test-unix

check: lint
	cd rudderc && make check
	$(MAKE) cargo-test PACKAGE=agent
	$(MAKE) cargo-test PACKAGE=rudder_cli
	$(MAKE) cargo-test PACKAGE=rudder_commons
	$(MAKE) cargo-test PACKAGE=rudder-report
	$(MAKE) cargo-test PACKAGE=rudder-module-system-updates
	$(MAKE) cargo-test PACKAGE=rudder-module-system-updates FEATURES=apt
	$(MAKE) cargo-test PACKAGE=rudder-module-system-updates FEATURES=apt-compat
	$(MAKE) cargo-test PACKAGE=rudder-module-augeas
	$(MAKE) cargo-test PACKAGE=rudder-module-template
	$(MAKE) cargo-test PACKAGE=rudder-module-commands
	$(MAKE) cargo-test PACKAGE=rudder-module-inventory
