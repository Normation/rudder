#!/bin/bash

set -e
set -x
set -m

update_conf() {
  sed "s@127.0.0.1@relayd@" /relayd_conf/main.conf > /relayd_conf/main.conf.mod
}

update_conf
/opt/rudder/bin/rudder-relayd &

# watch for changes in data files and reload
while true; do
    inotifywait -e move,create,modify /relayd_conf/ /var/rudder/lib/relay/ /var/rudder/lib/ssl/
    # Reload only reloads data files and logging config for now
    # To be safe in case of config change we need to restart
    #rudder relay reload
    pkill rudder-relayd

    update_conf
    /opt/rudder/bin/rudder-relayd &
done
