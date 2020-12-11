#!/bin/sh

set -e
set -x

# Needed because standard paths are symlinks

if [ ! -f /node_id/uuid.hive ]; then
  /opt/rudder/bin/rudder-uuidgen > /node_id/uuid.hive
fi

uuid=$(cat /node_id/uuid.hive)

if [ ! -f /agent_certs/ppkeys/localhost.pub ]; then
  mkdir -p /agent_certs/ppkeys
  /opt/rudder/bin/cf-key -T 4096 -f /agent_certs/ppkeys/localhost
fi

if [ ! -f /opt/rudder/etc/ssl/agent.cert ]; then
  mkdir -p /agent_certs/ssl
  openssl req -new -sha256 -key /agent_certs/ppkeys/localhost.priv -out /agent_certs/ssl/agent.cert -passin "pass:Cfengine passphrase" -x509 -days 3650 -extensions agent_cert -config /opt/rudder/etc/ssl/openssl-agent.cnf -subj "/UID=${uuid}"
fi

rudder agent check -f

/opt/rudder/bin/cf-execd --no-fork --inform
