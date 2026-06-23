TYPST  ?= typst
NAME   ?= rudder-cheatsheet
OUT    ?= target
SRC    := main.typ
ASSETS := rudder-logo.svg

SCREEN := $(OUT)/$(NAME).pdf
PRINT  := $(OUT)/$(NAME)-print.pdf

.PHONY: all screen print clean

# Build both versions
all: screen print

# Screen version: with clickable "docs ↗" buttons
screen: $(SCREEN)
$(SCREEN): $(SRC) $(ASSETS) | $(OUT)
	$(TYPST) compile $(SRC) $@

# Print version: doc buttons hidden
print: $(PRINT)
$(PRINT): $(SRC) $(ASSETS) | $(OUT)
	$(TYPST) compile $(SRC) $@ --input print=true

$(OUT):
	mkdir -p $(OUT)

clean:
	rm -rf $(OUT)
