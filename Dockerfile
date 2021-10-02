FROM rust:1.54.0-bullseye

RUN cargo install --version 1.0.11 typos-cli
RUN cargo install sccache

RUN rustup component add rustfmt
