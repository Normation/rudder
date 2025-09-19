# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2011-2020 Normation SAS

.DEFAULT_GOAL := build

RUDDER_VERSION_TO_PACKAGE =
RUDDER_MAJOR_VERSION := $(shell echo ${RUDDER_VERSION_TO_PACKAGE} | cut -d'.' -f 1-2)
RUDDER_PLUGINS_KEY_SHA256 = 1f0268d3a33191a0cd440d055243ecc4660442ea39586c81011e1ce23b18143c

PROXY_ENV = $(if $(PROXY), http_proxy=$(PROXY))
# Usage: $(GET) <destination_file> <url> <hash>
PROXY_ENV = $(if $(PROXY), http_proxy=$(PROXY) ftp_proxy=$(PROXY))
# No accept encoding to prevent the webserver form altering the distributed file
# Pass an empty string to skip hash check
# grep 'GET=' */SOURCES/Makefile to patch everywhere
GET=get() { $(PROXY_ENV) curl -s -L -o "$$1.part" "$$2" && { openssl dgst -sha256 "$$1.part" | grep -q "$$3" || { echo "Wrong checksum, aborting"; exit 1; }; } && mv "$$1.part" "$$1"; }; get

DESTDIR = $(CURDIR)/target
APACHE_VHOSTDIR = apache2/sites-available

man-source: target/man-source/rudder-relayd.1;

target/man-source/%: man/%.adoc
	mkdir -p target/man-source
	asciidoctor -D target/man-source -b manpage $<

man: target/man/rudder-relayd.1.gz;

target/man/rudder-relayd.1.gz:
	mkdir -p target/man/
	$(GET) target/man/rudder-relayd.1 https://docs.rudder.io/man/${RUDDER_MAJOR_VERSION}/rudder-relayd.1 ""
	gzip -f target/man/rudder-relayd.1

# Don't use sequoia on SLES as we build on an SP
# having a different libnettle version from supported servers.
ifeq (, $(shell which zypper))
    CARGO_FEATURES_GPG="sequoia"
else
    CARGO_FEATURES_GPG=""
endif

CACHE=cache() { [ -x ../../../../../build-caching ] && ../../../../../build-caching "$$@"; [ -x ../../../../../../build-caching ] && ../../../../../../build-caching "$$@" ; } ; cache
build: man
	# Build rust binaries
	mkdir -p ../../../../../.cargo/ relayd/target/ rudder-package/target/
	$(CACHE) get ../../../../../.cargo/ name=cargo major=$(RUDDER_MAJOR_VERSION) || true
	### relayd
	$(CACHE) get relayd/target/ --with-env name=relayd major=$(RUDDER_MAJOR_VERSION) || true
	cd relayd && make build
	# the cache is always updated and cargo will rebuild only what is needed
	$(CACHE) put relayd/target/ --with-env name=relayd major=$(RUDDER_MAJOR_VERSION) || true
	### rudder-package
	$(CACHE) get rudder-package/target/ --with-env name=rudder-package major=$(RUDDER_MAJOR_VERSION) || true
	cd rudder-package && COMPLETION_OUT_DIR=../../../target make CARGO_FEATURES="${CARGO_FEATURES_GPG}" build
	# Modify completion to make it callable by our global completion function
	sed -i "s@_rudder() {@_rudderpkg() {@" ../../target/rudder.bash
	sed -i '/^if/,$$d' ../../target/rudder.bash 
	# the cache is always updated and cargo will rebuild only what is needed
	$(CACHE) put rudder-package/target/ --with-env name=rudder-package major=$(RUDDER_MAJOR_VERSION) || true
	### common
	$(CACHE) put ../../../../../.cargo/ name=cargo major=$(RUDDER_MAJOR_VERSION) || true

ifeq ($(SELINUX),true)
	# Build SELinux policy package
	# Compiles rudder-relay.te and rudder-relay.fc into rudder-relay.pp
	cd selinux && make -f /usr/share/selinux/devel/Makefile
endif
# TODO replicate this on previous PR
	touch $@

# Install into DESTDIR
install: build
	# Directories
	mkdir -p $(DESTDIR)/etc/$(APACHE_VHOSTDIR)/
	mkdir -p $(DESTDIR)/etc/bash_completion.d/
	mkdir -p $(DESTDIR)/opt/rudder/bin/
	mkdir -p $(DESTDIR)/opt/rudder/etc/relayd/
	mkdir -p $(DESTDIR)/opt/rudder/etc/ssl/
	mkdir -m 700 -p $(DESTDIR)/opt/rudder/etc/rudder-pkg/
	mkdir -p $(DESTDIR)/opt/rudder/share/selinux/
	mkdir -p $(DESTDIR)/opt/rudder/share/python/rudder-pkg
	mkdir -p $(DESTDIR)/opt/rudder/share/package-scripts/
	mkdir -p $(DESTDIR)/opt/rudder/share/man/man1/
	mkdir -p $(DESTDIR)/opt/rudder/share/commands/
	mkdir -p $(DESTDIR)/var/rudder/inventories/incoming
	mkdir -p $(DESTDIR)/var/rudder/inventories/failed
	mkdir -p $(DESTDIR)/var/rudder/inventories/accepted-nodes-updates
	mkdir -p $(DESTDIR)/var/rudder/lib/ssl
	mkdir -p $(DESTDIR)/var/rudder/lib/relay
	mkdir -p $(DESTDIR)/var/rudder/reports/incoming
	mkdir -p $(DESTDIR)/var/rudder/reports/failed
	mkdir -p $(DESTDIR)/var/rudder/shared-files
	mkdir -p $(DESTDIR)/var/rudder/share
	mkdir -p $(DESTDIR)/var/log/rudder/apache2/
	mkdir -p $(DESTDIR)/etc/sysconfig/
	mkdir -p $(DESTDIR)/etc/sudoers.d/
	mkdir -p $(DESTDIR)/usr/lib/systemd/system/

	touch $(DESTDIR)/var/rudder/lib/ssl/.placeholder

	# Install binary
	install -m 755 ../../target/release/rudder-relayd $(DESTDIR)/opt/rudder/bin/rudder-relayd
	# Install default configuration file
	install -m 640 relayd/tools/config/main.conf $(DESTDIR)/opt/rudder/etc/relayd/main.conf
	install -m 644 relayd/tools/config/logging.conf $(DESTDIR)/opt/rudder/etc/relayd/logging.conf
	# Install service
	install -m 644 systemd/rudder-relayd.service $(DESTDIR)/usr/lib/systemd/system/rudder-relayd.service

	# Install man pages
	install -m 644 target/man/rudder-relayd.1.gz $(DESTDIR)/opt/rudder/share/man/man1/

	# Install rudder-package
	install -m 755 ../../target/release/rudder-package $(DESTDIR)/opt/rudder/bin/rudder-package
	install -m 644 ../../target/rudder.bash $(DESTDIR)/etc/bash_completion.d/rudder-package.sh
	install -m 644 rudder-package/tools/rudder_plugins_key.asc $(DESTDIR)/opt/rudder/etc/rudder-pkg/rudder_plugins_key.asc
	# Keyring file created with:
	# gpg --no-default-keyring --homedir=. --keyring=./rudder_plugins_key.gpg --import tools/rudder_plugins_key.asc
	install -m 644 rudder-package/tools/rudder_plugins_key.gpg $(DESTDIR)/opt/rudder/etc/rudder-pkg/rudder_plugins_key.gpg
	install -m 755 rudder-package/tools/rudder-package.sh $(DESTDIR)/opt/rudder/share/commands/package
	install -m 644 rudder-package/tools/rudder-pkg.conf $(DESTDIR)/opt/rudder/etc/rudder-pkg/rudder-pkg.conf
	install -m 644 systemd/rudder-package.timer $(DESTDIR)/usr/lib/systemd/system/rudder-package.timer
	install -m 644 systemd/rudder-package.service $(DESTDIR)/usr/lib/systemd/system/rudder-package.service

	# Apache
	install -m 644 apache/rudder-vhost.conf $(DESTDIR)/etc/$(APACHE_VHOSTDIR)/rudder.conf
	install -m 644 apache/rudder-vhost.conf $(DESTDIR)/opt/rudder/share/apache.conf
	install -m 644 apache/rudder-apache-relay-common.conf $(DESTDIR)/opt/rudder/etc/rudder-apache-relay-common.conf
	install -m 644 apache/rudder-apache-relay-ssl.conf.j2 $(DESTDIR)/opt/rudder/etc/rudder-apache-relay-ssl.conf.j2
	install -m 644 apache/rudder-apache-relay-nossl.conf $(DESTDIR)/opt/rudder/etc/rudder-apache-relay-nossl.conf

	# Others
	install -m 644 openssl.cnf $(DESTDIR)/opt/rudder/etc/ssl/openssl.cnf
	install -m 644 rudder-relay-apache $(DESTDIR)/etc/sysconfig/rudder-relay-apache
	install -m 644 rudder-relay.sudo $(DESTDIR)/etc/sudoers.d/rudder-relay

	# Copy stub rudder-networks*.conf
	install -m 644 apache/rudder-networks-24.conf $(DESTDIR)/opt/rudder/etc/
	install -m 644 apache/rudder-networks-policy-server-24.conf $(DESTDIR)/opt/rudder/etc/

	install -m 755 rudder-relay-postinst $(DESTDIR)/opt/rudder/share/package-scripts/
	install -m 755 rudder-relay-prerm $(DESTDIR)/opt/rudder/share/package-scripts/

ifeq ($(SELINUX),true)
	# Install SELinux policy
	install -m 644 selinux/rudder-relay.pp $(DESTDIR)/opt/rudder/share/selinux/
endif
	touch $@

# Clean

clean:
	rm -rf target relayd/target build install
	rm -rf selinux/rudder-relay.pp
	cd relayd && cargo clean

veryclean: clean
distclean: veryclean

.PHONY: localdepends localclean veryclean
