# This file contains the necessary logrotate entries relative for every installed
# Rudder component.
#
# It will automatically be updated by Rudder itself if a new component to be managed
# is added to the machine.

[%CFEngine role_rudder_server_root|(policy_server.!role_rudder_relay_promises_only):: %]
/var/log/rudder/apache2/*.log {
        daily
        missingok
        rotate 30
        compress
        notifempty
        create ${root_logrotate_check.syslog_file_mode} ${root_logrotate_check.syslog_user} ${root_logrotate_check.syslog_group}
        delaycompress
        sharedscripts
        postrotate
          ${root_logrotate_check.service_restart_utility} ${root_logrotate_check.apache_service_name} reload > /dev/null || true
        endscript
}

[%CFEngine !redhat.(role_rudder_server_root|(policy_server.!role_rudder_relay_promises_only)):: %]
/var/log/rudder/reports/*.log {
        daily
        missingok
        rotate 30
        compress
        notifempty
        create ${root_logrotate_check.syslog_file_mode} ${root_logrotate_check.syslog_user} ${root_logrotate_check.syslog_group}
        delaycompress
        sharedscripts
        postrotate
          ${root_logrotate_check.service_restart_utility} ${root_logrotate_check.syslog_service_name} ${root_logrotate_check.syslog_rotation_method} > /dev/null || true
        endscript
}

[%CFEngine redhat.(role_rudder_server_root|(policy_server.!role_rudder_relay_promises_only)):: %]
/var/log/rudder/reports/*.log {
        daily
        missingok
        rotate 30
        compress
        notifempty
        create ${root_logrotate_check.syslog_file_mode} ${root_logrotate_check.syslog_user} ${root_logrotate_check.syslog_group}
        delaycompress
        sharedscripts
        postrotate
          /bin/kill -HUP `cat /var/run/syslogd.pid 2> /dev/null` 2> /dev/null || true
        endscript
}

[%CFEngine !redhat.role_rudder_inventory_ldap:: %]
/var/log/rudder/ldap/slapd.log {
        daily
        missingok
        rotate 30
        compress
        notifempty
        create ${root_logrotate_check.syslog_file_mode} ${root_logrotate_check.syslog_user} ${root_logrotate_check.syslog_group}
        delaycompress
        postrotate
          ${root_logrotate_check.service_restart_utility} ${root_logrotate_check.syslog_service_name} ${root_logrotate_check.syslog_rotation_method} > /dev/null || true
        endscript
}

[%CFEngine redhat.role_rudder_inventory_ldap:: %]
/var/log/rudder/ldap/slapd.log {
        daily
        missingok
        rotate 30
        compress
        notifempty
        create ${root_logrotate_check.syslog_file_mode} ${root_logrotate_check.syslog_user} ${root_logrotate_check.syslog_group}
        delaycompress
        postrotate
          /bin/kill -HUP `cat /var/run/syslogd.pid 2> /dev/null` 2> /dev/null || true
        endscript
}
