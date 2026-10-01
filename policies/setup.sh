#!/bin/sh

set -e

apt-get update && apt-get install -y rsync msitools libapt-pkg-dev clang python3-jinja2 libacl1-dev
# ignore postinst failures
curl https://repository.rudder.io/tools/rudder-setup | sed 's/rudder agent \(.*\)/rudder agent \1 \| true/g' | bash -s setup-agent ci/$RUDDER_VER
# we need a patched augeas
wget https://github.com/hercules-team/augeas/releases/download/release-1.15.0/augeas-1.15.0.tar.gz
tar -xf augeas-1.15.0.tar.gz
cd augeas-1.15.0 && ./configure --prefix=/usr && make install
