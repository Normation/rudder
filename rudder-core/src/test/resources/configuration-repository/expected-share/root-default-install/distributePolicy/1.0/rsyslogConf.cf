#####################################################################################
# Copyright 2011 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

# Configure rsyslog on the root/relay servers

bundle agent install_rsyslogd {

  vars:

    root_server|role_rudder_relay_top::
      "rsyslog_source_file" string => "rudder-rsyslog-root.conf";

    # Define the where to send the syslog messages
    # default is localhost if the rudder_db is unknown
    # or if the role rudder-server-root is installed

    root_server.(!role_rudder_db_server_defined|role_rudder_server_root)::
      "rudder_postgres_server" string => "localhost";

    (root_server|role_rudder_relay_top).role_rudder_db_server_defined.!role_rudder_server_root::
      "rudder_postgres_server" string => "${rudder_servers_by_role.roles[rudder-db]}";

    policy_server.!(root_server|role_rudder_relay_top)::
      "rsyslog_source_file" string => "rudder-rsyslog-relay.conf";

  files:

    policy_server.!reports_disabled.!role_rudder_relay_promises_only::

      "/etc/rsyslog.d/rudder.conf"
        create    => "true",
        edit_defaults => empty_size("8388608"), # the template can get pretty big with a lot of entries
        edit_line => expand_template("${sys.workdir}/inputs/distributePolicy/rsyslog.conf/${rsyslog_source_file}"),
              classes => cf2_if_else("rudder_rsyslog_conf_copied", "cannot_copy_rudder_rsyslog_conf"),
              comment => "Copying rsyslog conf";  

      "/etc/rsyslog.conf"
        edit_line => append_if_no_lines("$IncludeConfig /etc/rsyslog.d/*.conf"),
        edit_defaults => noempty_backup,
        comment => "Add the rsyslog.conf.d include if not already present",
        classes => cf2_if_else("rsyslog_inc_ok" , "rsyslog_inc_failed");


      # We need to delete, if it exists, the historical rudder-agent.conf file from before the promotion of the node to relay
      "/etc/rsyslog.d/rudder-agent.conf"
        delete  => tidy,
        classes => classes_generic("rudder_rsyslog_historical_conf_purged"),
        comment => "Deleting historical rudder-agent.conf file if it is there";

    policy_server.debian.!reports_disabled.!role_rudder_relay_promises_only::
      "/etc/rsyslog.d/pgsql.conf"
        edit_line => comment_all(),
        edit_defaults => noempty_backup,
        classes => cf2_if_else("rudder_rsyslog_pgsql", "cannot_update_rudder_rsyslog_pgsql"),
              comment => "Removing the logging of all in the database";  

  packages:

    policy_server.!SuSE.!redhat.!reports_disabled.!role_rudder_relay_promises_only::
      "rsyslog"
        package_policy  => "add",
        package_method  => generic,
        classes => cf2_if_else("rsyslog_installed", "cant_install_rsyslog"),
        comment => "Installing rsyslog using apt backports";

      "rsyslog-pgsql"
        package_policy  => "add",
        package_method  => generic,
        classes => cf2_if_else("rsyslog_pgsql_installed", "cant_install_rsyslog_pgsql"),
        comment => "Installing rsyslog_pgsql using apt backports";

    policy_server.!SuSE.redhat.!reports_disabled.!role_rudder_relay_promises_only::
      "rsyslog"
        package_policy  => "add",
        package_method  => rudder_yum,
        classes => cf2_if_else("rsyslog_installed", "cant_install_rsyslog"),
        comment => "Installing rsyslog using apt backports";

      "rsyslog-pgsql"
        package_policy  => "add",
        package_method  => rudder_yum,
        classes => cf2_if_else("rsyslog_pgsql_installed", "cant_install_rsyslog_pgsql"),
        comment => "Installing rsyslog_pgsql using apt backports";

  reports:

    cant_install_rsyslog|cant_install_rsyslog_pgsql::
      "Fatal : Can't install rsyslog or rsyslog_pgsql on the Rudder root server !";

    cannot_copy_rudder_rsyslog_conf::
      "Fatal : Can't copy the rsyslog configuration !";

    rsyslog_inc_failed::
      "Fatal : Can't enable the rsyslog include directory !";

    cannot_update_rudder_rsyslog_pgsql::
      "Fatal : Cannot update the pgsql configuration !";

    service_restart_rsyslog_repaired|service_restart_syslog_repaired::
      "Info : Restarted rsyslog";

    service_restart_rsyslog_not_ok|service_restart_syslog_not_ok::
      "Fatal : Can't restart rsyslog !";

}

bundle edit_line comment_all()
{
  replace_patterns:
  # comment all lines
      "^[^#](.*)"
        replace_with => comments;
}

body replace_with comments
{
        replace_value => "#${match.1}"; # backreference 0
        occurrences => "all";  # first, last all
}

