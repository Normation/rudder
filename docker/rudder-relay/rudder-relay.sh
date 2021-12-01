#!/bin/bash

set -e
#set -x

# Allow using our binaries
export PATH="/opt/rudder/bin/:$PATH"

# Persisted folder
PPKEYS="/var/rudder/cfengine-community/ppkeys"

################
# Policy server
################

# Allow safely setting trust
# If not provided, we will trust on first use and persist trust
if [ -n "$RUDDER_RELAY_SERVER_PUBKEY" ]; then
  echo "$RUDDER_RELAY_SERVER_PUBKEY" > "${PPKEYS}/server.key"
  key_hash=$(cf-key --print-digest ${PPKEYS}/server.key)
  mv "${PPKEYS}/server.key" "${PPKEYS}/root-${key_hash}.pub"
  echo "${key_hash}" > "${PPKEYS}/policy_server_hash"
fi

if [ -n "$RUDDER_RELAY_SERVER" ]; then
  echo "$RUDDER_RELAY_SERVER" > "${PPKEYS}/policy_server.dat"
elif [ ! -f "${PPKEYS}/policy_server.dat" ]; then
  echo "rudder" > "${PPKEYS}/policy_server.dat"
fi

################
# Relay node id
################

if [ -n "$RUDDER_RELAY_ID" ]; then
  echo "$RUDDER_RELAY_ID" > "${PPKEYS}/uuid.hive"
elif [ ! -f "${PPKEYS}/uuid.hive" ]; then
  rudder-uuidgen > "${PPKEYS}/uuid.hive"
fi

uuid=$(cat "${PPKEYS}/uuid.hive")

################
# Private key
################

if [ -n "$RUDDER_RELAY_PRIVKEY" ]; then
  (
    echo "-----BEGIN RSA PRIVATE KEY-----"
    echo "$RUDDER_RELAY_PRIVKEY" | fold -w 64 
    echo "-----END RSA PRIVATE KEY-----"
  ) > "${PPKEYS}/localhost.priv"
  chmod 600 "${PPKEYS}/localhost.priv"
elif [ ! -f "${PPKEYS}/localhost.priv" ]; then
  cf-key --key-type 4096 --output-file "${PPKEYS}/localhost"
fi

# Generate public key based on private key to be sure it's correct
openssl rsa -in "${PPKEYS}/localhost.priv" -RSAPublicKey_out > "${PPKEYS}/localhost.pub"

################
# Certificate
################

if [ -n "$RUDDER_RELAY_CERTIFICATE" ]; then
  (
    echo "-----BEGIN CERTIFICATE-----"
    echo "$RUDDER_RELAY_CERTIFICATE" | fold -w 64 
    echo "-----END CERTIFICATE-----"
  ) > "${PPKEYS}/agent.cert"
elif [ ! -f "${PPKEYS}/agent.cert" ]; then
  openssl req -new -sha256 -key "${PPKEYS}/localhost.priv" -out "${PPKEYS}/agent.cert" -x509 -days 3650 -extensions agent_cert -config /opt/rudder/etc/ssl/openssl-agent.cnf -subj "/UID=${uuid}"
fi

# We verify that the certificate belongs to the private key (Modulus is identical)
modulus_cert=$(openssl x509 -noout -modulus -in "${PPKEYS}/agent.cert")
modulus_key=$(openssl rsa  -noout -modulus -in "${PPKEYS}/localhost.priv")
if [ "${modulus_cert}" != "${modulus_key}" ]; then
  echo "Certificate does not match agent key" >&2
  exit 1
fi

# Copy files from persisted folder
cp "${PPKEYS}/agent.cert" "/opt/rudder/etc/ssl/agent.cert"
cp "${PPKEYS}/policy_server.dat" "/var/rudder/cfengine-community/policy_server.dat"
cp "${PPKEYS}/uuid.hive" "/opt/rudder/etc/uuid.hive"

# Make sure everything is ready
rudder agent check -f

# start services
exec systemctl --init
