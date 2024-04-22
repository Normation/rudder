FROM rust:1.77.2-bookworm
LABEL ci=rudder/ci/typos.Dockerfile
ARG VERSION

RUN cargo install -f typos-cli --locked --version =$VERSION
