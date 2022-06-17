# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../../rust.makefile

test:
	cargo test --locked
	# Run custom resource
	@rm -rf /tmp/test-directory
	@mkdir -p ${HOME}/.cfagent/bin && cp /opt/rudder/bin/cf-* ${HOME}/.cfagent/bin/ || true
	/opt/rudder/bin/cf-agent -KIf ./examples/resource_directory.cf && [ -e /tmp/test-directory ]

check: lint test
