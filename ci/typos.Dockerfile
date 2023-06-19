FROM rust
LABEL ci=ncf/ci/typos.Dockerfile
ARG VERSION

RUN cargo install -f typos-cli --version =$VERSION
