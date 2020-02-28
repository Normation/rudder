.DEFAULT_GOAL := build
SHELL = /bin/bash

cargo:
        curl https://sh.rustup.rs -sSf | sh -s -- -y
        source $(HOME)/.cargo/env

deps:
        apt update && apt upgrade -y
        apt install -y python3-pip perl
        echo | cpan
        perl -MCPAN -e 'install TOML::Parser'
        pip3 install toml

build: deps 
        perl tools/generate-lib
        cargo build --release

test:
        cargo test

doc:
        cargo doc --help

stats:
        @ echo -n "TODOS  : " && grep -r TODO src | wc -l
        @ echo -n "Commits: " && git log --oneline | wc -l
        @ tokei
#       @ echo "Lines  : " && find . -name '*.rs' | xargs wc -l
