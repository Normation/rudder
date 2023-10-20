FROM rust:1.72.0-bullseye
LABEL ci=rudder/relay/sources/rudder-package/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"
