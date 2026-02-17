
all: build

# Generate summary
summary:
	echo "# Rudder ADR" > SUMMARY.md
	for dir in $(shell find . -maxdepth 1 -mindepth 1 -type d ! -name "book" | sed 's|./||' | sort); do \
	    echo "# $$dir" >> SUMMARY.md; \
		for file in $$(find $$dir -maxdepth 1 -name "*.md" | sort --version-sort); do \
		    title=$$(grep "^# " $$file | head -n1 | sed "s/# //"); \
		    ticket=$$(echo $$file | cut -d'/' -f2 | cut -d'-' -f1); \
			echo "- [$$ticket - $$title]($$file)" >> SUMMARY.md; \
		done \
	done

build: summary
	mdbook build

clean:
	rm -rf SUMMARY.md book
