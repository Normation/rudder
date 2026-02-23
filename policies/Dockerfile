# Debian trixie has a broken PowerShell dependency
FROM rust:1.93.0-bookworm
LABEL ci=rudder/policies/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

ARG RUDDER_VER=latest
ARG PSANALYZER_VERSION=1.20.0

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"
ENV INSTALL_PWSH=1

COPY policies/setup.sh
RUN ./setup.sh
