#!/bin/sh

set -e

apt-get update && apt-get install -y rsync msitools libapt-pkg-dev clang python3-jinja2 libacl1-dev
# download first: piping curl into bash silently installs nothing on a failed download
curl -fsSL https://repository.rudder.io/tools/rudder-setup -o rudder-setup
# ignore postinst failures
sed -i 's/rudder agent \(.*\)/rudder agent \1 \| true/g' rudder-setup
bash ./rudder-setup setup-agent ci/$RUDDER_VER
# module type tests run the module through CFEngine
test -x /opt/rudder/bin/cf-promises
# we need a patched augeas
wget https://github.com/hercules-team/augeas/releases/download/release-1.15.0/augeas-1.15.0.tar.gz
tar -xf augeas-1.15.0.tar.gz
cd augeas-1.15.0 && ./configure --prefix=/usr && make install
