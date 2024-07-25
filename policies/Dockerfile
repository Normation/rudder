FROM rust:1.74.1-bullseye
LABEL ci=rudder/policies/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

ARG RUDDER_VER=latest

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"

RUN <<EOF
set -e
wget https://github.com/PowerShell/PowerShell/releases/download/v7.4.0/powershell_7.4.0-1.deb_amd64.deb
apt-get install ./powershell_7.4.0-1.deb_amd64.deb
apt-get update && apt-get install -y rsync msitools
# ignore postinst failures
curl https://repository.rudder.io/tools/rudder-setup | sed 's/rudder agent \(.*\)/rudder agent \1 \| true/g' | bash -s setup-agent $RUDDER_VER
EOF
