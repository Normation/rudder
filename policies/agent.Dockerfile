FROM rust:1.93.0-trixie
LABEL ci=rudder/policies/agent.Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

ARG RUDDER_VER=latest

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"

COPY policies/setup.sh .
RUN ./setup.sh
