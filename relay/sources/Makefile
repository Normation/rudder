#####################################################################################
# Copyright 2011 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

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

rudder-pkg/rudder_plugins_key.pub:
	$(GET) rudder-pkg/rudder_plugins_key.pub https://repository.rudder.io/apt/rudder_apt_key.pub $(RUDDER_PLUGINS_KEY_SHA256)

man-source: target/man-source/rudder-relayd.1;

target/man-source/%: man/%.adoc
	mkdir -p target/man-source
	asciidoctor -D target/man-source -b manpage $<

autocomplete/rudder-pkg.sh:
	if type pip3 >/dev/null 2>&1; then pip3 install infi.docopt-completion; else easy_install infi.docopt-completion; fi
	mkdir -p autocomplete
	export PYTHONPATH=$(CURDIR)/rudder-pkg/lib/rudder-pkg:$$PYTHONPATH; cd autocomplete && docopt-completion $(CURDIR)/rudder-pkg/rudder-pkg --manual-bash && cd -

man: target/man/rudder-relayd.1.gz;

target/man/rudder-relayd.1.gz:
	mkdir -p target/man/
	$(GET) target/man/rudder-relayd.1 https://docs.rudder.io/man/${RUDDER_MAJOR_VERSION}/rudder-relayd.1 ""
	gzip -f target/man/rudder-relayd.1

CACHE=cache() { [ -x ../../../../../build-caching ] && ../../../../../build-caching "$$@"; [ -x ../../../../../../build-caching ] && ../../../../../../build-caching "$$@" ; } ; cache
build: man rudder-pkg/rudder_plugins_key.pub autocomplete/rudder-pkg.sh
	# Build relayd 
	mkdir -p ../../../../../.cargo/ relayd/target/
	$(CACHE) get ../../../../../.cargo/ name=cargo major=$(RUDDER_MAJOR_VERSION) || true
	$(CACHE) get relayd/target/ --with-env name=relayd major=$(RUDDER_MAJOR_VERSION) || true
	cd relayd && cargo build --release --bin rudder-relayd
	# the cache is always updated and cargo will rebuild only what is needed
	$(CACHE) put ../../../../../.cargo/ name=cargo major=$(RUDDER_MAJOR_VERSION) || true
	$(CACHE) put relayd/target/ --with-env name=relayd major=$(RUDDER_MAJOR_VERSION) || true

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
	mkdir -p $(DESTDIR)/opt/rudder/etc/rudder-pkg/
	mkdir -p $(DESTDIR)/opt/rudder/share/selinux/
	mkdir -p $(DESTDIR)/opt/rudder/share/python/
	mkdir -p $(DESTDIR)/opt/rudder/share/package-scripts/
	mkdir -p $(DESTDIR)/opt/rudder/share/man/man1/
	mkdir -p $(DESTDIR)/opt/rudder/share/commands/
	mkdir -p $(DESTDIR)/var/rudder/inventories/incoming
	mkdir -p $(DESTDIR)/var/rudder/inventories/accepted-nodes-updates
	mkdir -p $(DESTDIR)/var/rudder/lib/ssl
	mkdir -p $(DESTDIR)/var/rudder/lib/relay
	mkdir -p $(DESTDIR)/var/rudder/reports/incoming
	mkdir -p $(DESTDIR)/var/rudder/reports/failed
	mkdir -p $(DESTDIR)/var/rudder/shared-files
	mkdir -p $(DESTDIR)/var/rudder/share
	mkdir -p $(DESTDIR)/var/log/rudder/apache2/
	mkdir -p $(DESTDIR)/etc/sysconfig/
	mkdir -p $(DESTDIR)/etc/cron.d/
	mkdir -p $(DESTDIR)/etc/sudoers.d/
	mkdir -p $(DESTDIR)/usr/lib/systemd/system/

	touch $(DESTDIR)/var/rudder/lib/ssl/.placeholder

	# Install binary
	install -m 755 relayd/target/release/rudder-relayd $(DESTDIR)/opt/rudder/bin/rudder-relayd
	# Install default configuration file
	install -m 640 relayd/tools/config/main.conf $(DESTDIR)/opt/rudder/etc/relayd/main.conf
	install -m 644 relayd/tools/config/logging.conf $(DESTDIR)/opt/rudder/etc/relayd/logging.conf
	# Install service
	install -m 644 systemd/rudder-relayd.service $(DESTDIR)/usr/lib/systemd/system/rudder-relayd.service
	# Sandboxing if compatible
	[ $$(systemctl --version | head -1 | cut -d' ' -f2) -gt 232 ] && sed -i '/\[Service\]/ r systemd/rudder-relayd-hardening' $(DESTDIR)/usr/lib/systemd/system/rudder-relayd.service || true

	# Install man pages
	install -m 644 target/man/rudder-relayd.1.gz $(DESTDIR)/opt/rudder/share/man/man1/
	
	# rudder packaging
	install -m 755 rudder-pkg/rudder-pkg $(DESTDIR)/opt/rudder/share/commands/package
	ln -ns ../share/commands/package $(DESTDIR)/opt/rudder/bin/rudder-pkg
	install -m 755 rudder-pkg/rudder-pkg.conf $(DESTDIR)/opt/rudder/etc/rudder-pkg/rudder-pkg.conf
	install -m 755 rudder-pkg/rudder_plugins_key.pub $(DESTDIR)/opt/rudder/etc/rudder-pkg/rudder_plugins_key.pub
	install -m 644 autocomplete/rudder-pkg.sh $(DESTDIR)/etc/bash_completion.d/
	cp -r rudder-pkg/lib/* $(DESTDIR)/opt/rudder/share/python/
	
	# Apache
	install -m 644 apache/rudder-vhost.conf $(DESTDIR)/etc/$(APACHE_VHOSTDIR)/rudder.conf
	install -m 644 apache/rudder-apache-relay-common.conf $(DESTDIR)/opt/rudder/etc/rudder-apache-relay-common.conf
	install -m 644 apache/rudder-apache-relay-ssl.conf $(DESTDIR)/opt/rudder/etc/rudder-apache-relay-ssl.conf
	install -m 644 apache/rudder-apache-relay-nossl.conf $(DESTDIR)/opt/rudder/etc/rudder-apache-relay-nossl.conf

	# Others
	install -m 644 openssl.cnf $(DESTDIR)/opt/rudder/etc/ssl/openssl.cnf
	install -m 644 rudder-relay-apache $(DESTDIR)/etc/sysconfig/rudder-relay-apache
	install -m 644 rudder-relay.cron $(DESTDIR)/etc/cron.d/rudder-relay
	install -m 644 rudder-relay.sudo $(DESTDIR)/etc/sudoers.d/rudder-relay
	install -m 755 relay-cleanup $(DESTDIR)/opt/rudder/bin/relay-cleanup
	
	# Copy stub rudder-networks*.conf
	install -m 644 apache/rudder-networks-24.conf $(DESTDIR)/opt/rudder/etc/
	install -m 644 apache/rudder-networks-policy-server-24.conf $(DESTDIR)/opt/rudder/etc/
	
	install -m 755 rudder-server-relay-postinst $(DESTDIR)/opt/rudder/share/package-scripts/
	install -m 755 rudder-server-relay-prerm $(DESTDIR)/opt/rudder/share/package-scripts/
	
ifeq ($(SELINUX),true)
	# Install SELinux policy
	install -m 644 selinux/rudder-relay.pp $(DESTDIR)/opt/rudder/share/selinux/
endif
	touch $@

# Quality

lint:
	cd relayd && RUSTFLAGS="-D warnings" cargo check --all-targets --examples --tests
	cd relayd && cargo clippy --all-targets --examples --tests

check: lint
	cd relayd && cargo test
	cd relayd && cargo audit

# Clean

clean:
	rm -rf target relayd/target build install
	rm -rf selinux/rudder-relay.pp
	rm -rf rudder-pkg/lib/rudder-pkg/__pycache__/
	rm -rf rudder-pkg/lib/rudder-pkg/*.pyc
	rm -rf rudder-pkg/lib/rudder-pkg/tests/.hypothesis/
	rm -rf rudder-pkg/rudder_plugins_key.pub
	rm -rf autocomplete
	cd relayd && cargo clean

veryclean: clean
distclean: veryclean

.PHONY: localdepends localclean veryclean
