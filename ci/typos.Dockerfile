FROM rust:1.82.0-bookworm
LABEL ci=rudder/ci/typos.Dockerfile
ARG VERSION

RUN cargo install -f typos-cli --locked --version =$VERSION
