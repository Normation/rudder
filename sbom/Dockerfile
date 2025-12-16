FROM rust:1.92.0-trixie
LABEL ci=rudder/policies/Dockerfile

ARG USER_ID=1000
COPY ci/user.sh .
RUN ./user.sh $USER_ID

COPY ci/rust.sh .
RUN ./rust.sh

RUN wget https://packages.microsoft.com/config/debian/12/packages-microsoft-prod.deb -O packages-microsoft-prod.deb
RUN dpkg -i packages-microsoft-prod.deb

RUN apt-get update && apt-get install -y rsync maven npm dotnet-sdk-8.0

# cyclonedx-cli
RUN wget https://github.com/CycloneDX/cyclonedx-cli/releases/download/v0.25.0/cyclonedx-linux-x64
RUN mv cyclonedx-linux-x64 /usr/bin/cyclonedx && chmod +x /usr/bin/cyclonedx
