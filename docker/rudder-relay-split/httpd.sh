#!/bin/bash

set -e
set -x
set -m

update_conf() {
  sed "s@127.0.0.1@relayd@" /httpd_conf/rudder-apache-relay-ssl.conf > /httpd_conf/rudder-apache-relay-ssl.conf.mod
}

for dir in /var/rudder/inventories/incoming /var/rudder/inventories/failed \
           /var/rudder/inventories/accepted-nodes-updates \
           /var/rudder/reports/incoming /var/rudder/reports/failed
do
  chmod 770 ${dir}
  chown apache:rudder ${dir}
done

update_conf
/usr/sbin/httpd -DFOREGROUND &

# watch for changes in /apache_config_file and update http if there is one on /backup
while true; do
    inotifywait -e move,create,modify /httpd_conf/
    update_conf
    /usr/sbin/httpd -k graceful
done
