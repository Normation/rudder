all: clean test doc

test:
	cd tests/style/ && ./testall
	cd tests/acceptance/ && ./testall

doc:
	ls tree/30_generic_methods/ncf/*.cf | xargs egrep -h "^\s*bundle\s+agent\s+" | sed -r "s/\s*bundle\s+agent\s+//" | sort > doc/all_generic_methods.txt

clean:
	rm -rf tests/{style,acceptance}/.succeeded/*
	rm -f tests/{style,acceptance}/summary.log
	rm -f tests/{style,acceptance}/test.log
	rm -f tests/{style,acceptance}/test.xml
	rm -f tests/{style,acceptance}/xml.tmp

	rm -f doc/all_generic_methods.txt

.PHONY: all test doc clean
