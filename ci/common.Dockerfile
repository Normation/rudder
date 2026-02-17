# rust is the latest debian image with latest rust preinstalled
FROM rust
ARG TYPOS_VERSION=1.36.3
ARG MDBOOK_VERSION=0.5.2

RUN cargo install -f typos-cli@$TYPOS_VERSION mdbook@$MDBOOK_VERSION
RUN apt-get update && apt-get install -y shellcheck pylint rsync
