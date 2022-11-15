FROM rust:1.64
ARG VERSION

RUN cargo install -f typos-cli --version =$VERSION
