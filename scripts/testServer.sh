#!/bin/sh

set -xe

. /tmp/libtests.sh

if [ $# -ne 1 ]
then
  echo "usage: $0 Node1IP"
  exit
else
    NODE1_INSTANCE_IP=$1 #ServerIP
fi

serverInventory
