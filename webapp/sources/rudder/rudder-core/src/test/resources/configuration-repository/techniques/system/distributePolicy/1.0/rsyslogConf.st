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

bundle agent install_rsyslogd
{
  vars:
&if(INITIAL)&
    # This will only read the first line if several match
    "rsyslog_port_defined"     int => getfields("rudder.syslog.port\s*=\s*[0-9]+\s*","/opt/rudder/etc/rudder-web.properties","=","rsyslog_port");

    # Use standard rsyslog port number by default if not set in rudder-web.properties
    rsyslog_port_not_found::
      "rsyslog_port[2]"    string => "514";
&endif&

    root_server|role_rudder_relay_top::
      "rsyslog_source_file" string => "rudder-rsyslog-root.conf";
      "rudder_reports_db_name" string => "&RUDDER_REPORTS_DB_NAME&";
      "rudder_reports_db_user" string => "&RUDDER_REPORTS_DB_USER&";

    # Define the where to send the syslog messages
    # default is localhost if the rudder_db is unknown
    # or if the role rudder-server-root is installed

    root_server.(!role_rudder_db_server_defined|role_rudder_server_root)::
      "rudder_postgres_server" string => "localhost";

    (root_server|role_rudder_relay_top).role_rudder_db_server_defined.!role_rudder_server_root::
      "rudder_postgres_server" string => "${rudder_servers_by_role.roles[rudder-db]}";

    policy_server.!(root_server|role_rudder_relay_top)::
      "rsyslog_source_file" string => "rudder-rsyslog-relay.conf";

&if(INITIAL)&
  classes:
    # There is no "equals" function for ints in CFEngine (currently, at least) so we compare strings
      "rsyslog_port_not_found" expression => strcmp("${rsyslog_port_defined}","0");

      "rsyslog_port_too_many"  expression => isgreaterthan("${rsyslog_port_defined}","1");
&endif&

  files:

    policy_server.!reports_disabled.!role_rudder_relay_promises_only::

      "/etc/rsyslog.d/rudder.conf"
        create    => "true",
        edit_defaults => empty_size("8388608"), # the template can get pretty big with a lot of entries
        edit_line => expand_template("${this.promise_dirname}/../rsyslog.conf/${rsyslog_source_file}"),
        classes => classes_generic("rudder_rsyslog_conf"),
        comment => "Copying rsyslog conf";  

      "/etc/rsyslog.conf"
        edit_line => append_if_no_lines("$IncludeConfig /etc/rsyslog.d/*.conf"),
        edit_defaults => noempty_backup,
        comment => "Add the rsyslog.conf.d include if not already present",
        classes => classes_generic("rsyslog_inc");


      # We need to delete, if it exists, the historical rudder-agent.conf file from before the promotion of the node to relay
      "/etc/rsyslog.d/rudder-agent.conf"
        delete  => tidy,
        classes => classes_generic("rudder_rsyslog_historical_conf"),
        comment => "Deleting historical rudder-agent.conf file if it is there";

    (root_server|role_rudder_relay_top).debian.!reports_disabled.!role_rudder_relay_promises_only::
      "/etc/rsyslog.d/pgsql.conf"
        edit_line => comment_all(),
        edit_defaults => noempty_backup,
        classes => classes_generic("rudder_rsyslog_pgsql"),
        comment => "Removing the logging of all in the database";  

  commands:
    policy_server.!SuSE.(rudder_rsyslog_conf_repaired|rsyslog_inc_repaired|rudder_rsyslog_historical_conf_repaired|rudder_rsyslog_pgsql_repaired)::
      "${paths.path[service]} rsyslog"
        args => "restart",
        classes => classes_generic("rsyslog_restarted"),
        comment => "restarting rsyslog";
    policy_server.SuSE.(rudder_rsyslog_conf_repaired|rsyslog_inc_repaired|rudder_rsyslog_historical_conf_repaired|rudder_rsyslog_pgsql_repaired)::
      "${paths.path[service]} syslog"
        args => "restart",
        classes => classes_generic("rsyslog_restarted"),
        comment => "restarting rsyslog";


  reports:

    rudder_rsyslog_conf_not_ok::
      "Fatal : Can't copy the rsyslog configuration !";

    rsyslog_inc_not_ok::
      "Fatal : Can't enable the rsyslog include directory !";

    rudder_rsyslog_pgsql_not_ok::
      "Fatal : Cannot update the pgsql configuration !";

    rsyslog_restarted_repaired::
      "Info : Restarted rsyslog";

    rsyslog_restarted_not_ok::
      "Fatal : Can't restart rsyslog !";

&if(INITIAL)&
    rsyslog_port_too_many::
      "Warning: rsyslog port number has been set several times in /opt/rudder/etc/rudder-web.properties";
&endif&

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

