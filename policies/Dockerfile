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

RUN <<EOF
set -e
wget https://github.com/PowerShell/PowerShell/releases/download/v7.4.0/powershell_7.4.0-1.deb_amd64.deb
apt-get install ./powershell_7.4.0-1.deb_amd64.deb
pwsh -Command Install-Module -Name PSScriptAnalyzer -RequiredVersion $PSANALYZER_VERSION -Scope AllUsers -Confirm:\$false -Force
EOF

COPY policies/setup.sh .
RUN ./setup.sh
