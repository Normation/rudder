FROM rust:trixie
LABEL ci=ci/methods.Dockerfile

# Accept all OSes
ENV UNSUPPORTED=y
# Microsoft repos
RUN set -ex \
    && apt-get update && apt-get install -y wget \
    && wget https://packages.microsoft.com/config/debian/13/packages-microsoft-prod.deb -O packages-microsoft-prod.deb \
    && dpkg -i packages-microsoft-prod.deb \
    && rm packages-microsoft-prod.deb \
    && apt-get update \
    && apt-get install -yq dotnet-sdk-10.0 \
    && wget https://github.com/PowerShell/PowerShell/releases/download/v7.6.1/powershell-lts_7.6.1-1.deb_amd64.deb -O /tmp/powershell.deb \
    && echo "045a9d7c80c1b89fba71113d1d99d4038c7486470dd492f881ebefe5b4a35240 /tmp/powershell.deb" | sha256sum --check \
    && dpkg -i /tmp/powershell.deb \
    && rm /tmp/powershell.deb

RUN <<EOF
set -xe
apt update && apt install -y lsb-release libacl1-dev icu-devtools libclang-dev
wget https://repository.rudder.io/tools/rudder-setup
sed -i "s/set -e/set -xe/" rudder-setup
sed -i "s/rudder agent inventory//" rudder-setup
sed -i "s/rudder agent health/rudder agent health || true/" rudder-setup
<<<<<<< HEAD
sh ./rudder-setup setup-agent 9.2-nightly
wget https://github.com/hercules-team/augeas/releases/download/release-1.15.0/augeas-1.15.0.tar.gz
tar -xf augeas-1.15.0.tar.gz
cd augeas-1.15.0 && ./configure --prefix=/usr && make install
EOF
