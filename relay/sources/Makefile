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

# GENERIC_GET: do not remove this line
# This is a generic get code that is common to many of our Makefiles.
# The tag is here to find them. Rudder agent has an even more generic one.
# If you change this code, change all the places where you can find this tag too.
# It is duplicated because it is the code that is used to manage dependency
# to other repositories, hence allowing deduplication of other code.
# Autodetect wget, curl or fetch usage and proxy configuration
# Usage: $(GET) <destination_file> <url> <hash>
PROXY_ENV = $(if $(PROXY), http_proxy=$(PROXY) ftp_proxy=$(PROXY))
# No accept encoding to prevent the webserver form altering the distributed file
WGET = wget -q --header="accept-encoding:" -O
CURL = curl -s --http1.1 -L -o
ifneq (,$(wildcard /usr/bin/curl))
_GET = $(PROXY_ENV) $(CURL)
else
_GET = $(PROXY_ENV) $(WGET)
endif
# Pass an empty string to skip hash check
GET=get() { $(_GET) "$$1.part" "$$2" && { openssl dgst -sha256 "$$1.part" | grep -q "$$3" || { echo "Wrong checksum, aborting"; exit 1; }; } && mv "$$1.part" "$$1"; }; get

localdepends: ./rudder-sources ./relay-api/virtualenv/virtualenv.py

./rudder-sources.tar.bz2:
	$(GET) rudder-sources.tar.bz2 https://www.rudder-project.org/archives/rudder-sources-${RUDDER_VERSION_TO_PACKAGE}.tar.bz2 ""

./rudder-sources: ./rudder-sources.tar.bz2
	tar -xjf rudder-sources.tar.bz2
	mv rudder-sources-*/ rudder-sources/

./relay-api/virtualenv.tgz: /usr/bin/wget
	# Original URL: https://pypi.python.org/packages/source/v/virtualenv/virtualenv-12.0.7.tar.gz
	$(GET) relay-api/virtualenv.tgz https://repository.rudder.io/build-dependencies/virtualenv/virtualenv-$(VIRTUALENV_RELEASE).tar.gz $(VIRTUALENV_SHA256)

./relay-api/virtualenv/virtualenv.py: ./relay-api/virtualenv.tgz
	cd relay-api && tar -xzf ./virtualenv.tgz
	cd relay-api && mv ./virtualenv-$(VIRTUALENV_RELEASE)/ virtualenv/

localclean:
	rm -rf ./rudder-sources

veryclean:
	rm -f ./rudder-sources.tar.bz2

.PHONY: localdepends localclean veryclean
