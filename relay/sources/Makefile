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
VIRTUALENV_RELEASE = 16.5.0
VIRTUALENV_SHA256 = 15ee248d13e4001a691d9583948ad3947bcb8a289775102e4c4aa98a8b7a6d73
PYTHON = python3

PROXY_ENV = $(if $(PROXY), http_proxy=$(PROXY))
# Usage: $(GET) <destination_file> <url> <hash>
PROXY_ENV = $(if $(PROXY), http_proxy=$(PROXY) ftp_proxy=$(PROXY))
# No accept encoding to prevent the webserver form altering the distributed file
# Pass an empty string to skip hash check
# grep 'GET=' */SOURCES/Makefile to patch everywhere
GET=get() { $(PROXY_ENV) curl -s -L -o "$$1.part" "$$2" && { openssl dgst -sha256 "$$1.part" | grep -q "$$3" || { echo "Wrong checksum, aborting"; exit 1; }; } && mv "$$1.part" "$$1"; }; get

DESTDIR = $(CURDIR)/target
APACHE_VHOSTDIR = apache2/sites-available

relay-api/virtualenv.tgz:
	# Original URL: https://pypi.python.org/packages/source/v/virtualenv/virtualenv-12.0.7.tar.gz
	$(GET) relay-api/virtualenv.tgz https://repository.rudder.io/build-dependencies/virtualenv/virtualenv-$(VIRTUALENV_RELEASE).tar.gz $(VIRTUALENV_SHA256)

relay-api/virtualenv/virtualenv.py: relay-api/virtualenv.tgz
	cd relay-api && tar -xzf ./virtualenv.tgz
	cd relay-api && mv ./virtualenv-$(VIRTUALENV_RELEASE)/ virtualenv/

man-source: target/man-source/rudder-relayd.1;

target/man-source/%: man/%.adoc
	mkdir -p target/man-source
	asciidoctor -D target/man-source -b manpage $<

man: target/man/rudder-relayd.1.gz;

target/man/rudder-relayd.1.gz:
	mkdir -p target/man/
	$(GET) target/man/rudder-relayd.1 https://docs.rudder.io/man/${RUDDER_MAJOR_VERSION}/rudder-relayd.1 ""
	gzip -f target/man/rudder-relayd.1

build: man relay-api/virtualenv/virtualenv.py
	# Build virtualenv
	cd relay-api && $(PYTHON) virtualenv/virtualenv.py flask
	# Get all requirements via pip
	cd relay-api && flask/bin/pip install -r requirements.txt
	# Clean up unwanted binaries
	cd relay-api && for i in easy_install python python3 pip; do rm -f flask/bin/$${i}*; done
	# Build relayd
	cd relayd && cargo build --release --bin rudder-relayd

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
	mkdir -p $(DESTDIR)/opt/rudder/bin/
	mkdir -p $(DESTDIR)/opt/rudder/etc/
	mkdir -p $(DESTDIR)/opt/rudder/etc/ssl/
	mkdir -p $(DESTDIR)/opt/rudder/share/selinux/
	mkdir -p $(DESTDIR)/opt/rudder/share/relay-api/
	mkdir -p $(DESTDIR)/opt/rudder/share/python/
	mkdir -p $(DESTDIR)/opt/rudder/share/package-scripts/
	mkdir -p $(DESTDIR)/opt/rudder/share/man/man1/
	mkdir -p $(DESTDIR)/var/rudder/inventories/incoming
	mkdir -p $(DESTDIR)/var/rudder/inventories/accepted-nodes-updates
	mkdir -p $(DESTDIR)/var/rudder/reports/incoming
	mkdir -p $(DESTDIR)/var/rudder/shared-files
	mkdir -p $(DESTDIR)/var/rudder/share
	mkdir -p $(DESTDIR)/var/log/rudder/apache2/
	mkdir -p $(DESTDIR)/etc/sysconfig/
	mkdir -p $(DESTDIR)/etc/cron.d/
	mkdir -p $(DESTDIR)/etc/sudoers.d/
	mkdir -p $(DESTDIR)/usr/lib/systemd/system/

	# Install binary
	install -m 755 relayd/target/release/rudder-relayd $(DESTDIR)/opt/rudder/bin/rudder-relayd
	# Install default configuration file
	install -m 644 relayd/tools/rudder-relayd.conf $(DESTDIR)/opt/rudder/etc/rudder-relayd.conf
	# Install service
	install -m 644 systemd/rudder-relayd.service $(DESTDIR)/usr/lib/systemd/system/rudder-relayd.service
	# Install man pages
	install -m 644 target/man/rudder-relayd.1.gz $(DESTDIR)/opt/rudder/share/man/man1/
	
	# relay api
	cp -r relay-api/flask $(DESTDIR)/opt/rudder/share/relay-api/
	cp -r relay-api/relay_api $(DESTDIR)/opt/rudder/share/relay-api/
	install -m 644 relay-api/apache/relay-api.wsgi $(DESTDIR)/opt/rudder/share/relay-api/
	install -m 755 relay-api/cleanup.sh $(DESTDIR)/opt/rudder/share/relay-api/
	
	# rudder packaging
	install -m 755 rudder-pkg $(DESTDIR)/opt/rudder/bin/
	install -m 644 docopt.py $(DESTDIR)/opt/rudder/share/python/
	
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
	
	# Copy stub rudder-networks*.conf
	install -m 644 apache/rudder-networks-24.conf $(DESTDIR)/opt/rudder/etc/
	install -m 644 apache/rudder-networks-policy-server-24.conf $(DESTDIR)/opt/rudder/etc/
	
	install -m 755 rudder-server-relay-postinst $(DESTDIR)/opt/rudder/share/package-scripts/
	
ifeq ($(SELINUX),true)
	# Install SELinux policy
	install -m 644 selinux/rudder-relay.pp $(DESTDIR)/opt/rudder/share/selinux/
endif
	touch $@

# Quality

lint:
	cd relayd && RUSTFLAGS="-D warnings" cargo clippy --all-targets --examples --tests -- -D clippy::all

check: lint
	cd relayd && cargo test
	cd relayd && cargo audit
	cd relayd && cargo bench

# Clean

clean:
	rm -rf target relayd/target build install
	rm -rf selinux/rudder-relay.pp
	cd relayd && cargo clean

veryclean: clean
distclean: veryclean

.PHONY: localdepends localclean veryclean
