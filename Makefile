#####################################################################################
# Copyright 2015 Normation SAS
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


DESTDIR = /usr
INSTALL := $(shell type ginstall >/dev/null 2>&1 && echo ginstall || echo install)
CP_A := cp -rp

all: install

# no dependency
depend: localdepends
localdepends:

# Install ncf in DESTDIR
install:
	mkdir -p $(DESTDIR)/share/doc/ncf
	$(CP_A) examples $(DESTDIR)/share/doc/ncf/
	$(INSTALL) -m 644 README.md $(DESTDIR)/share/doc/ncf/
	mkdir -p $(DESTDIR)/share/ncf
	$(CP_A) tree $(DESTDIR)/share/ncf/
	$(CP_A) tools $(DESTDIR)/share/ncf/
	$(CP_A) builder $(DESTDIR)/share/ncf/
	$(INSTALL) -m 755 ncf $(DESTDIR)/share/ncf/
	mkdir -p $(DESTDIR)/bin
	ln -sf ../share/ncf/ncf $(DESTDIR)/bin/ncf
	mkdir -p $(DESTDIR)/share/man/man1

test:
	cd tests/acceptance/ && ./testall --info

test-unsafe:
	cd tests/acceptance/ && ./testall --info --unsafe

clean:
	rm -rf tests/style/.succeeded
	rm -f tests/style/summary.log
	rm -f tests/style/test.log
	rm -f tests/style/test.xml
	rm -f tests/style/xml.tmp
	rm -rf tests/style/workdir/
	rm -rf tests/unit/test.log
	rm -rf tests/acceptance/.succeeded
	rm -f tests/acceptance/summary.log
	rm -f tests/acceptance/test.log
	rm -f tests/acceptance/test.xml
	rm -f tests/acceptance/xml.tmp
	rm -rf tests/acceptance/workdir/
	find $(CURDIR) -name "*.[pP][yY][cC]" -exec rm "{}" \;

distclean: clean

.PHONY: all test doc clean distclean depend localdepend install
