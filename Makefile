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

build: doc doc/ncf.1

# Install ncf in DESTDIR
install: build
	mkdir -p $(DESTDIR)
	mkdir -p $(DESTDIR)/share/doc/ncf
	$(CP_A) doc $(DESTDIR)/share/doc/ncf/
	$(CP_A) examples $(DESTDIR)/share/doc/ncf/
	$(INSTALL) -m 644 README.md $(DESTDIR)/share/doc/ncf/
	mkdir -p $(DESTDIR)/share/ncf
	$(CP_A) tree $(DESTDIR)/share/ncf/
	$(CP_A) tools $(DESTDIR)/share/ncf/
	$(CP_A) builder $(DESTDIR)/share/ncf/
	$(CP_A) api $(DESTDIR)/share/ncf/
	$(INSTALL) -m 755 ncf $(DESTDIR)/share/ncf/
	mkdir -p $(DESTDIR)/bin
	ln -s ../share/ncf/ncf $(DESTDIR)/bin/ncf
	mkdir -p $(DESTDIR)/share/man/man1
	$(INSTALL) -m 644 doc/ncf.1 $(DESTDIR)/share/man/man1/

test:
	type fakeroot 2>/dev/null || { echo "fakeroot is required but not found." ; exit 1 ; }
	cd tests/style/ && ./testall
	cd tests/unit/ && ./testall
	cd tests/acceptance/ && ./testall --no-network
	cd tests/acceptance/ && ./testall

doc: 
	ls tree/30_generic_methods/*.cf | xargs egrep -h "^\s*bundle\s+agent\s+" | sed -r "s/\s*bundle\s+agent\s+//" | sort > doc/all_generic_methods.txt
	tools/ncf_doc.py
	rm -f tools/ncf_doc.pyc
	rm -f tools/ncf.pyc

doc/ncf.1:
	cd doc && a2x --doctype manpage --format manpage ncf.asciidoc

html: doc
	# To use this, run pip install pelican Markdown
	
	# Copy README.md and prefix it with metadata to make the site's index file
	echo "Title: A powerful and structured CFEngine framework" > site/content/index.md
	echo "URL: " >> site/content/index.md
	echo "save_as: index.html" >> site/content/index.md
	# Skip the first line (title that will be re-added by pelican)
	tail -n+2 README.md >> site/content/index.md

	# Copy reference data
	cp doc/generic_methods.md site/content/
	cp doc/generic_methods.html site/pelican-bootstrap3/templates/includes/
	cd site; make html

testsite: html
	cd site; make serve

clean:
	rm -rf tests/style/.succeeded
	rm -f tests/style/summary.log
	rm -f tests/style/test.log
	rm -f tests/style/test.xml
	rm -f tests/style/xml.tmp
	rm -rf tests/style/workdir/
	rm -rf tests/acceptance/.succeeded
	rm -f tests/acceptance/summary.log
	rm -f tests/acceptance/test.log
	rm -f tests/acceptance/test.xml
	rm -f tests/acceptance/xml.tmp
	rm -rf tests/acceptance/workdir/
	rm -f doc/all_generic_methods.txt
	rm -f doc/generic_methods.md
	rm -f doc/ncf.1
	find $(CURDIR) -iname "*.pyc" -delete

distclean: clean

.PHONY: all test doc clean distclean depend localdepend build install
