FROM rust:1.92.0-trixie
LABEL ci=rudder/relay/sources/relayd/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"

RUN cargo install --locked cargo-nextest@0.9.100
RUN apt-get update && apt-get install -y pkg-config libpq-dev postgresql-client
