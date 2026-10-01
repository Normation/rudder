FROM rust:bookworm
LABEL ci=ci/methods.Dockerfile

# Accept all OSes
ENV UNSUPPORTED=y
RUN <<EOF
set -xe

# Microsoft repos
wget -q https://packages.microsoft.com/config/debian/12/packages-microsoft-prod.deb
dpkg -i packages-microsoft-prod.deb
rm packages-microsoft-prod.deb

apt update && apt install -y lsb-release libacl1-dev powershell icu-devtools dotnet-sdk-8.0
apt update && apt install -y libclang-dev
wget https://repository.rudder.io/tools/rudder-setup
sed -i "s/set -e/set -xe/" rudder-setup
sed -i "s/rudder agent inventory//" rudder-setup
sed -i "s/rudder agent health/rudder agent health || true/" rudder-setup
sh ./rudder-setup setup-agent 9.0-nightly
wget https://github.com/hercules-team/augeas/releases/download/release-1.15.0/augeas-1.15.0.tar.gz
tar -xf augeas-1.15.0.tar.gz
cd augeas-1.15.0 && ./configure --prefix=/usr && make install
EOF
