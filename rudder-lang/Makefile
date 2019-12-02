build:
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
