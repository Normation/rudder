# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2025 Normation SAS

# Entry point for building the release artifacts and some CI steps

include cargo.mk

webapp-version:
	mvn --version
	npm --version

webapp-release: webapp-version
	cd webapp/sources && mvn --batch-mode clean package -DskipTests
	find webapp/sources -name "rudder-webapp.cdx.json"
	# TODO sbom for webapp: maven & npm in the build process

clean:
	find . -name "*.cdx.*" -exec rm {} \;

