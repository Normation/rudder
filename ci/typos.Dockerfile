FROM rust
ARG VERSION

RUN cargo install -f typos-cli --version =$VERSION
