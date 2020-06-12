#!/bin/sh

set -e
set -x

cp /opt/rudder/etc/rudder-networks-24.conf /apache_conf_file/rudder-networks-24.conf
cp /opt/rudder/etc/rudder-networks-policy-server-24.conf /apache_conf_file/rudder-networks-policy-server-24.conf
cp /opt/rudder/etc/rudder-apache-relay-ssl.conf  /apache_conf_file/rudder-apache-relay-ssl.conf
cp /opt/rudder/etc/rudder-apache-relay-common.conf /apache_conf_file/rudder-apache-relay-common.conf
cp /opt/rudder/etc/rudder-apache-relay-nossl.conf /apache_conf_file/rudder-apache-relay-nossl.conf
