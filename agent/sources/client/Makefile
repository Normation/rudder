# cargo-style Makefile

BUILD_DIR = ./target
SRC_DIR = ./src
TEST_DIR = ./tests

EXEC_OBJ := main.o
EXEC := rudder_client

TEST_OBJ := run.o
TEST_EXEC := test

OUR_SRCS := $(shell find $(SRC_DIR) -name "*.[ch]" ! -path "*argtable3*")

# main is handled separately
SRCS := $(shell find $(SRC_DIR) ! -path '*main.c' -name '*.c')
OBJS := $(patsubst $(SRC_DIR)/%,$(BUILD_DIR)/%,$(SRCS:.c=.o))

DESTDIR := $(BUILD_DIR)/dest
PREFIX :=

BUILD_CPPFLAGS=$(CPPFLAGS)
# Strict flags, only compatible with modern compilers.
# Release flags come from packaging.
# C99 like CFEngine, for old platforms
# inspired from https://github.com/mcinglis/c-style
CFLAGS ?= -std=c99 -Wall -Wextra -Wpedantic \
          -Wformat=2 -Wno-unused-parameter -Wshadow \
          -Wwrite-strings -Wstrict-prototypes -Wold-style-definition \
          -Wredundant-decls -Wnested-externs -Wmissing-include-dirs \
          -D_FORTIFY_SOURCE=2 -fstack-protector-strong -fPIE
BUILD_CFLAGS=$(CFLAGS)
LDFLAGS ?= -Wl,-z,now -Wl,-z,relro -pie
BUILD_LDLAGS=$(LDFLAGS)
# Debug flags
CFLAGS_DEBUG := -O -g3 -DDEBUG -fsanitize=address,undefined
LDFLAGS_DEBUG := -fsanitize=address,undefined
ifeq ($(CC),gcc)
CFLAGS_DEBUG += -fanalyzer
endif

CFLAGS_RELEASE := -O2
BUILD_LDFLAGS += -lcurl -lm

# to use static tomlc use: make STATIC_TOML=1
# in this case you need to put its sources in ./tomlc99/
ifdef STATIC_TOML
BUILD_CFLAGS += -I./tomlc99
BUILD_LDFLAGS += ./tomlc99/libtoml.a
else
BUILD_LDFLAGS += -ltoml
endif

# build is debug
b : build
build: BUILD_CFLAGS += $(CFLAGS_DEBUG)
build: BUILD_LDFLAGS += $(LDFLAGS_DEBUG)
build: bin-target
build-release: BUILD_CFLAGS += $(CFLAGS_RELEASE)
build-release: version bin-target

$(BUILD_DIR):
	install -d $(BUILD_DIR)

# No separate dirs for debug and release, we clean at every run
bin-target: clean $(BUILD_DIR) toml $(BUILD_DIR)/$(EXEC)

toml:
ifdef STATIC_TOML
	cd tomlc99 && make "CFLAGS=$(BUILD_CFLAGS) -std=c99 -fPIC"
endif

$(BUILD_DIR)/%.o: $(SRC_DIR)/%.c
	$(CC) $(BUILD_CFLAGS) $(BUILD_CPPFLAGS) -c -o $@ $<

$(BUILD_DIR)/$(EXEC): $(OBJS) $(BUILD_DIR)/$(EXEC_OBJ)
	$(CC) $^ -o $@ $(BUILD_LDFLAGS)

install: $(BUILD_DIR)/$(EXEC)
	install -d $(DESTDIR)$(PREFIX)/bin/
	install -m 755 $^ $(DESTDIR)$(PREFIX)/bin/
	install -d $(DESTDIR)$(PREFIX)/etc/
	install -m 640 config/agent.conf $(DESTDIR)$(PREFIX)/etc/agent.conf

# tests

$(BUILD_DIR)/%.o: $(TEST_DIR)/%.c
	$(CC) -Isrc $(BUILD_CFLAGS) $(BUILD_CPPFLAGS) -c -o $@ $<

$(BUILD_DIR)/$(TEST_EXEC): $(OBJS) $(BUILD_DIR)/$(TEST_OBJ)
	$(CC) $^ -o $@ $(BUILD_LDFLAGS)

test-target: bin-target $(BUILD_DIR)/$(TEST_EXEC)

t: test
test: BUILD_CFLAGS += $(CFLAGS_DEBUG)
test: BUILD_LDFLAGS += $(LDFLAGS_DEBUG)
test: test-target
	# run test server
	tests/server.py &
	sleep 1
	# allow memory leaks with "ASAN_OPTIONS=detect_leaks=0"
	$(BUILD_DIR)/$(TEST_EXEC)
	# stop test server
	curl -s -k https://127.0.0.1:8443/stop

# tests but refuse warnings, for usage in CI
check: BUILD_CFLAGS += -Werror
check: test

# tools

version:
	$(CC) --version

fmt:
	clang-format -i $(OUR_SRCS)

clippy:
	clang-tidy $(OUR_SRCS) -- $(BUILD_CFLAGS)

clean:
	rm -rf $(BUILD_DIR)

.PHONY: clean
