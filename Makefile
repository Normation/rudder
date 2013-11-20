all: clean test

test:
	cd tests/acceptance/ && ./testall

clean:
	rm -rf tests/acceptance/.succeeded/*
	rm -f tests/acceptance/summary.log
	rm -f tests/acceptance/test.log
	rm -f tests/acceptance/test.xml
	rm -f tests/acceptance/xml.tmp
