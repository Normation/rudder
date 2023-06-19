FROM rust:1.70.0
ARG VERSION

RUN cargo install -f typos-cli --locked --version =$VERSION
