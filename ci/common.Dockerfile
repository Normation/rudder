# rust is the latest debian image with latest rust preinstalled
FROM rust
ARG TYPOS_VERSION=1.36

RUN cargo install -f typos-cli@$TYPOS_VERSION
RUN apt-get update && apt-get install -y shellcheck pylint
