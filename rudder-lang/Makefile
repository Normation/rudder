build:
	# check for cargo, perl, cpan, pip, pip3 install
	# sudo cpan TOML
	# pip3 install toml
	cargo build

test:
	cargo test

doc:
	cargo doc --help

stats:
	@ echo -n "TODOS  : " && grep -r TODO src | wc -l
	@ echo -n "Commits: " && git log --oneline | wc -l
	@ tokei
#	@ echo "Lines  : " && find . -name '*.rs' | xargs wc -l
