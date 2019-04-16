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

.DEFAULT_GOAL := localdepends

RUDDER_VERSION_TO_PACKAGE =
VIRTUALENV_RELEASE = 16.0.0
VIRTUALENV_SHA256 = ca07b4c0b54e14a91af9f34d0919790b016923d157afda5efdde55c96718f752

PROXY_ENV = $(if $(PROXY), http_proxy=$(PROXY))
# Usage: $(GET) <destination_file> <url> <hash>
PROXY_ENV = $(if $(PROXY), http_proxy=$(PROXY) ftp_proxy=$(PROXY))
# No accept encoding to prevent the webserver form altering the distributed file
# Pass an empty string to skip hash check
# grep 'GET=' */SOURCES/Makefile to patch everywhere
GET=get() { $(PROXY_ENV) curl -s --http1.1 -L -o "$$1.part" "$$2" && { openssl dgst -sha256 "$$1.part" | grep -q "$$3" || { echo "Wrong checksum, aborting"; exit 1; }; } && mv "$$1.part" "$$1"; }; get

DESTDIR = $(CURDIR)/target
APACHE_VHOSTDIR = apache2/sites-available

localdepends: rudder-sources relay-api/virtualenv/virtualenv.py

rudder-sources.tar.bz2:
	$(GET) rudder-sources.tar.bz2 https://www.rudder-project.org/archives/rudder-sources-${RUDDER_VERSION_TO_PACKAGE}.tar.bz2 ""

rudder-sources: rudder-sources.tar.bz2
	tar -xjf rudder-sources.tar.bz2
	mv rudder-sources-*/ rudder-sources/

relay-api/virtualenv.tgz:
	# Original URL: https://pypi.python.org/packages/source/v/virtualenv/virtualenv-12.0.7.tar.gz
	$(GET) relay-api/virtualenv.tgz https://repository.rudder.io/build-dependencies/virtualenv/virtualenv-$(VIRTUALENV_RELEASE).tar.gz $(VIRTUALENV_SHA256)

relay-api/virtualenv/virtualenv.py: relay-api/virtualenv.tgz
	cd relay-api && tar -xzf ./virtualenv.tgz
	cd relay-api && mv ./virtualenv-$(VIRTUALENV_RELEASE)/ virtualenv/

build:
	# Build virtualenv
	cd relay-api && python virtualenv/virtualenv.py flask
	# Get all requirements via pip
	cd relay-api && flask/bin/pip install -r requirements.txt
	# Clean up unwanted binaries
	cd relay-api && for i in easy_install python pip; do rm -f flask/bin/$${i}*; done

ifeq ($(SELINUX),true)
	# Build SELinux policy package
	# Compiles rudder-relay.te and rudder-relay.fc into rudder-relay.pp
	make -f /usr/share/selinux/devel/Makefile
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
	mkdir -p $(DESTDIR)/var/rudder/inventories/incoming
	mkdir -p $(DESTDIR)/var/rudder/inventories/accepted-nodes-updates
	mkdir -p $(DESTDIR)/var/rudder/shared-files
	mkdir -p $(DESTDIR)/var/rudder/share
	mkdir -p $(DESTDIR)/var/log/rudder/apache2/
	mkdir -p $(DESTDIR)/etc/sysconfig/
	mkdir -p $(DESTDIR)/etc/cron.d/
	mkdir -p $(DESTDIR)/etc/sudoers.d/
	mkdir -p $(DESTDIR)/opt/rudder/share/package-scripts/
	
	# relay api
	cp -r relay-api/flask $(DESTDIR)/opt/rudder/share/relay-api/
	cp -r relay-api/relay_api $(DESTDIR)/opt/rudder/share/relay-api/
	install -m 644 relay-api/apache/relay-api.wsgi $(DESTDIR)/opt/rudder/share/relay-api/
	install -m 755 relay-api/cleanup.sh $(DESTDIR)/opt/rudder/share/relay-api/
	
	# rudder packaging
	install -m 755 rudder-pkg $(DESTDIR)/opt/rudder/bin/
	install -m 644 docopt.py $(DESTDIR)/opt/rudder/share/python/
	
	# Others
	install -m 644 rudder-apache-relay-common.conf $(DESTDIR)/opt/rudder/etc/rudder-apache-relay-common.conf
	install -m 644 rudder-apache-relay-ssl.conf $(DESTDIR)/opt/rudder/etc/rudder-apache-relay-ssl.conf
	install -m 644 openssl.cnf $(DESTDIR)/opt/rudder/etc/ssl/openssl.cnf
	install -m 644 rudder-relay-apache $(DESTDIR)/etc/sysconfig/rudder-relay-apache
	install -m 644 rudder-relay.cron $(DESTDIR)/etc/cron.d/rudder-relay
	install -m 644 rudder-relay.sudo $(DESTDIR)/etc/sudoers.d/rudder-relay
	install -m 644 rudder-vhost.conf $(DESTDIR)/etc/$(APACHE_VHOSTDIR)/rudder.conf
	
	# Copy stub rudder-networks*.conf
	install -m 644 rudder-networks-24.conf $(DESTDIR)/opt/rudder/etc/
	install -m 644 rudder-networks-policy-server-24.conf $(DESTDIR)/opt/rudder/etc/
	
	install -m 755 rudder-server-relay-postinst $(DESTDIR)/opt/rudder/share/package-scripts/
	
ifeq ($(SELINUX),true)
	# Install SELinux policy
	install -m 644 rudder-relay.pp $(DESTDIR)/opt/rudder/share/selinux/
endif
	touch $@


clean: localclean
localclean:
	rm -rf ./rudder-sources
# TODO replicate this on previous PR
	rm -f build install

distclean: veryclean
veryclean: clean
	rm -f ./rudder-sources.tar.bz2

.PHONY: localdepends localclean veryclean
