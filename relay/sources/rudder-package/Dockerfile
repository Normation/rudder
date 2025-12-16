FROM rust:1.92.0-trixie
LABEL ci=rudder/relay/sources/rudder-package/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

COPY ci/rust.sh .
RUN ./rust.sh

RUN apt-get update && apt-get install -y nettle-dev clang gpgv

ENV RUSTC_WRAPPER="sccache"
