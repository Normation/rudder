API:=webapp relay

all: $(API)

$(API): clean
	# Prepare all sources in build/ dir
	mkdir -p build target/$@
	cp -r ../$@/sources/api-doc build/$@
	mkdir -p build/$@/assets
	cp -r assets/. build/$@/assets
	./build.py $@ build target

clean:
	rm -rf build target
