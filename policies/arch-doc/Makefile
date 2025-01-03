# Run commands on all documents.

TOP_TARGETS := all release clean html pdf

SUB_DIRS := $(wildcard */.)

$(TOP_TARGETS): $(SUB_DIRS)
$(SUB_DIRS):
	$(MAKE) -C $@ $(MAKECMDGOALS)

.PHONY: $(TOP_TARGETS) $(SUB_DIRS)
