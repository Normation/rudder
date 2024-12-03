FROM rust:1.83.0-bookworm
LABEL ci=rudder/relay/sources/rudder-package/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"
