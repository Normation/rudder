API:=relay webapp

all: deps $(API)

deps:
	npm_config_loglevel=error npm ci --no-audit --no-fund

$(API): clean
	# Prepare all sources in build/ dir
	mkdir -p build target/$@
	cp -r ../$@/sources/api-doc build/$@
	mkdir -p build/$@/assets
	cp -r assets/. build/$@/assets
	cp node_modules/rapidoc/dist/rapidoc-min.js build/$@/assets/
	cp -r rapidoc.html build/$@/
	./build.py $@ build target

clean:
	rm -rf build target
