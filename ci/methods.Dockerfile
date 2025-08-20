FROM rust:bullseye
LABEL ci=ncf/ci/methods.Dockerfile

# Accept all OSes
ENV UNSUPPORTED=y
RUN <<EOF
apt update && apt install -y lsb-release libacl1-dev
wget https://repository.rudder.io/tools/rudder-setup
sed -i "s/set -e/set -xe/" rudder-setup
sed -i "s/rudder agent inventory//" rudder-setup
sed -i "s/rudder agent health/rudder agent health || true/" rudder-setup
sh ./rudder-setup setup-agent latest
curl -LsSf https://get.nexte.st/latest/linux | tar zxf - -C ${CARGO_HOME:-~/.cargo}/bin
EOF
