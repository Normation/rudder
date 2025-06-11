FROM rust:1.87.0-bookworm
LABEL ci=rudder/relay/sources/rudder-package/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

COPY ci/rust.sh .
RUN ./rust.sh

RUN apt-get update && apt-get install -y nettle-dev clang

ENV RUSTC_WRAPPER="sccache"
