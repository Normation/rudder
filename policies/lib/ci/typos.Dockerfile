FROM rust:1.82
LABEL ci=ncf/ci/typos.Dockerfile
ARG VERSION

RUN cargo install --locked -f typos-cli --version =$VERSION
