FROM rust:1.91.0-bookworm
LABEL ci=rudder/policies/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

ARG RUDDER_VER=latest
ARG PSANALYZER_VERSION=1.20.0

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"

# we need cf-promises
# the easiest way is to install an agent
RUN <<EOF
set -e
wget https://github.com/PowerShell/PowerShell/releases/download/v7.4.0/powershell_7.4.0-1.deb_amd64.deb
apt-get install ./powershell_7.4.0-1.deb_amd64.deb
apt-get update && apt-get install -y rsync msitools libapt-pkg-dev clang python3-jinja2
# ignore postinst failures
curl https://repository.rudder.io/tools/rudder-setup | sed 's/rudder agent \(.*\)/rudder agent \1 \| true/g' | bash -s setup-agent $RUDDER_VER
pwsh -Command Install-Module -Name PSScriptAnalyzer -RequiredVersion $PSANALYZER_VERSION -Scope AllUsers -Confirm:\$false -Force
# we need a patched augeas
wget https://github.com/hercules-team/augeas/releases/download/release-1.14.1/augeas-1.14.1.tar.gz
wget https://patch-diff.githubusercontent.com/raw/hercules-team/augeas/pull/859.patch
tar -xf augeas-1.14.1.tar.gz
cd augeas-1.14.1 && patch -p1 < ../859.patch && ./configure --prefix=/usr && make install
EOF
