all: clean test

test:
	cd tests/style/ && ./testall
	cd tests/acceptance/ && ./testall

clean:
	rm -rf tests/{style,acceptance}/.succeeded/*
	rm -f tests/{style,acceptance}/summary.log
	rm -f tests/{style,acceptance}/test.log
	rm -f tests/{style,acceptance}/test.xml
	rm -f tests/{style,acceptance}/xml.tmp
