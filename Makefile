# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2025 Normation SAS

# Entry point for building the release artifacts and some CI steps

include cargo.mk

mvn-version:
	mvn --version

webapp: mvn-version
	cd webapp/sources && mvn --batch-mode clean package -DskipTests

