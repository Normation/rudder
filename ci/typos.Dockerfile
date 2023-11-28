FROM rust:1.74.0
LABEL ci=rudder/ci/typos.Dockerfile
ARG VERSION

RUN cargo install -f typos-cli --locked --version =$VERSION
