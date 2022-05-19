FROM rust:1.61
ARG VERSION

RUN cargo install -f typos-cli --version =$VERSION
