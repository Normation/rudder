FROM rust:1.72.0-bullseye
LABEL ci=rudder/policies/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

ARG RUDDER_VER=latest

COPY ci/rust.sh .
RUN ./rust.sh

ENV RUSTC_WRAPPER="sccache"

RUN apt-get update && apt-get install -y rsync msitools

# we need cf-promises
# the easiest way is to install an agent
RUN wget https://repository.rudder.io/tools/rudder-setup
# ignore postinst failures
RUN sh rudder-setup setup-agent $RUDDER_VER || true

# and PowerShell!
RUN wget https://github.com/PowerShell/PowerShell/releases/download/v7.4.0/powershell_7.4.0-1.deb_amd64.deb
RUN apt-get install ./powershell_7.4.0-1.deb_amd64.deb
# our lib expects this name for some reason, only used in tests anyway
RUN ln -s /usr/bin/pwsh /usr/bin/pwsh.exe
