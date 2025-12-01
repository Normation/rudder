FROM rust:trixie
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
curl -LsSf https://get.nexte.st/0.9.113/linux | tar zxf - -C ${CARGO_HOME:-~/.cargo}/bin
EOF
