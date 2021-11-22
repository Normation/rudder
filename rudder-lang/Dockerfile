FROM rust:1.42.0-buster

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"

RUN apt-get update && apt-get install -y python3 perl
