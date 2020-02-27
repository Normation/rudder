.DEFAULT_GOAL := build
<<<<<<< HEAD
SHELL = /bin/bash

cargo:
	curl https://sh.rustup.rs -sSf | sh -s -- -y

deps:
	apt update && apt upgrade -y
	apt install -y perl
	echo | cpan
	perl -MCPAN -e 'install Config::IniFiles'

build: deps 
	perl tools/generate-lib
	cargo build --release
=======

cargo:
        curl https://sh.rustup.rs -sSf | sh -s -- -y 

deps:
        apt update && apt upgrade -y
        apt install -y python3-pip perl 
        perl -MCPAN -e 'install TOML::Parser'
        pip3 install toml

build: deps 
        perl tools/generate-lib
        cargo build --release
>>>>>>> Work in progress

test:
        cargo test

doc:
        cargo doc --help

stats:
<<<<<<< HEAD
	@ echo -n "TODOS  : " && grep -r TODO src | wc -l
	@ echo -n "Commits: " && git log --oneline | wc -l
	@ tokei
=======
        @ echo -n "TODOS  : " && grep -r TODO src | wc -l
        @ echo -n "Commits: " && git log --oneline | wc -l
        @ tokei
>>>>>>> Work in progress
#       @ echo "Lines  : " && find . -name '*.rs' | xargs wc -l
