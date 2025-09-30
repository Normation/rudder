FROM rust:bookworm
LABEL ci=ci/methods.Dockerfile

# Accept all OSes
ENV UNSUPPORTED=y
RUN <<EOF
apt update && apt install -y lsb-release libacl1-dev libclang-dev
wget https://repository.rudder.io/tools/rudder-setup
sed -i "s/set -e/set -xe/" rudder-setup
sed -i "s/rudder agent inventory//" rudder-setup
sed -i "s/rudder agent health/rudder agent health || true/" rudder-setup
sh ./rudder-setup setup-agent 9.0-nightly
curl -LsSf https://get.nexte.st/latest/linux | tar zxf - -C ${CARGO_HOME:-~/.cargo}/bin
# we need a patched augeas
wget https://github.com/hercules-team/augeas/releases/download/release-1.14.1/augeas-1.14.1.tar.gz
wget https://patch-diff.githubusercontent.com/raw/hercules-team/augeas/pull/859.patch
tar -xf augeas-1.14.1.tar.gz
cd augeas-1.14.1 && patch -p1 < ../859.patch && ./configure --prefix=/usr && make install
EOF
