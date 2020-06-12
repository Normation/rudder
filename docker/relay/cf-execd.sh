#!/bin/sh

set -e
set -x

# Needed because standard paths are symlinks

if [ ! -f /data/uuid.hive ]; then
  /opt/rudder/bin/rudder-uuidgen > /data/uuid.hive
fi

uuid=$(cat /data/uuid.hive)

if [ ! -f /data/ppkeys/localhost.pub ]; then
  mkdir -p /data/ppkeys
  /opt/rudder/bin/cf-key -T 4096 -f /data/ppkeys/localhost
fi

if [ ! -f /opt/rudder/etc/ssl/agent.cert ]; then
  mkdir -p /data/ssl
  openssl req -new -sha256 -key /data/ppkeys/localhost.priv -out /data/ssl/agent.cert -passin "pass:Cfengine passphrase" -x509 -days 3650 -extensions agent_cert -config /opt/rudder/etc/ssl/openssl-agent.cnf -subj "/UID=${uuid}"
fi

echo "server" > /var/rudder/cfengine-community/policy_server.dat 

rudder agent check

rudder agent inventory

/opt/rudder/bin/cf-execd --no-fork --inform
