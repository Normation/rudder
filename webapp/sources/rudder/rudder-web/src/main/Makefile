PATH  := node_modules/.bin:$(PATH)
SHELL := /bin/bash
.DEFAULT_GOAL := all
.PHONY: all clean deps help installIfNot

all: clean deps dist

dist: dist/Private.js dist/Public.js
	$(call action, "DONE")

watch:
	$(call action, "Watching")

ifeq ($(mode), dev)
cache: dist/Private.bundle.js dist/Private.elm.js dist/Public.bundle.js dist/Public.elm.js
endif

clean:
	@ rm -rf dist elm-stuff node_modules

distclean:
	@ rm -rf dist

deps:
	npm ci --no-audit

##
## Sources
##

elmSource     := src/%.elm
allElmSources := src/*.elm
jsSource      := src/%.js
allJsSources  := src/*.js
mainFile      := dist/%.js
elmOutput     := dist/%.elm.js
bundle        := dist/%.bundle.js
debug         := dist/%.html

$(mainFile): $(elmOutput) $(bundle)
	$(call stl, $*, "Concatenating")
ifeq ($(mode), prod)
	@ uglifyjs $^ -mc 'pure_funcs="F2,F3,F4,F5,F6,F7,F8,F9"' -o $@
else
	@ cat $^ > $@
endif

$(elmOutput): $(elmSource) $(allElmSources)
	$(call stl, $*, "Compiling")
ifeq ($(mode), prod)
	@ ./bin/elm make $< --optimize --output=$@
else
	@ ./bin/elm make $< --output=$@
endif

##
## Help & Formatting
##




js_out := webapp/javascript/
js_out_libs := $(js_out)/libs/
elm_out := $(js_out)/elm/
css_out := webapp/style/
css_out_libs := $(css_out)/libs/

node_modules:
	npm ci --no-audit

css:
	mkdir -p $(css_out)
	cp -r style/* $(css_out)

js:
	mkdir -p $(js_out)
	cp -r javascript/* $(js_out)

vendor_css: node_modules
	mkdir -p $(css_out_libs)
	cp node_modules/*/dist/**/*.min.css* $(css_out_libs)

vendor_js: node_modules
	mkdir -p $(js_out_libs)
	cp node_modules/*/dist/*.min.js* $(js_out_libs)
	cp node_modules/*/js/*.min.js* $(js_out_libs)
	cp node_modules/*/dist/**/*.min.js* $(js_out_libs)
	cp node_modules/datatables.net-plugins/sorting/natural.js $(js_out_libs)/dataTables.naturalSorting.js
	cp node_modules/showdown-xss-filter/showdown-xss-filter.js $(js_out_libs)




cleanf:
	rm -rf $(js_out) $(css_out)

very_cleanf: clean
	rm -rf node_modules
	rm -rf elm/*/elm-stuff

.PHONY: clean very_clean css js npm vendor_js vendor_css elm