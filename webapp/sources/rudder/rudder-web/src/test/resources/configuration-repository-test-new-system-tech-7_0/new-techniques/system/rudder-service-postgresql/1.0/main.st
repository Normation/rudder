# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

bundle common rudder_postgresql {
  vars:
    # sles 12, sp1 and sp2 don't have by default a systemd postgresql
    agent.!(sles_12_0|sles_12_1|sles_12_2)::
      "service_name"    string => execresult("${paths.systemctl} --no-ask-password list-unit-files --type service | ${paths.awk} -F'.' '{print $1}' | ${paths.grep} -E \"^postgresql-?[0-9]*$\" | tail -n 1", "useshell");
    agent.(sles_12_0|sles_12_1|sles_12_2)::
      "service_name"    string => execresult("chkconfig 2>/dev/null | ${paths.awk} '{ print $1 }' | ${paths.grep} 'postgresql' | tail -n 1", "useshell");

    agent::
      "no"                 int => getfields("RUDDER_PSQL_PASSWORD:.*","/opt/rudder/etc/rudder-passwords.conf",":","password_getter");
      "postgres_passwd" string => "${password_getter[2]}";
      
      "db_url"          string => "&RUDDER_REPORTS_DB_URL&";
      "db_name"         string => "&RUDDER_REPORTS_DB_NAME&";
      "db_admin_name"   string => "postgres";
    # Define the where to send the reports messages
    # default is localhost if the rudder_db is unknown
    # or if the role rudder-server-root is installed
      "host"            string => "localhost";
    role_rudder_db_server_defined.!role_rudder_server_root::
      "host"            string => "${rudder_servers_by_role.roles[rudder-db]}";
}

bundle agent system_rudder_postgresql_configuration {
  vars:
    any::
      "trackingkey"     string => "&TRACKINGKEY&";
      "postgres_passwd" string => "${rudder_postgresql.postgres_passwd}";
      "technique_name"  string => "Rudder Postgresql";

  methods:

      # Check that apache is running and enabled
      "any" usebundle => _method_reporting_context("Postgresql service", "Started");
      "any" usebundle => service_started("${rudder_postgresql.service_name}");

      "any" usebundle => _method_reporting_context("Postgresql service", "Enabled");
      "any" usebundle => service_enabled("${rudder_postgresql.service_name}");

      # Check pgpass and db credentials
      "any" usebundle => system_rudder_postgresql("${trackingkey}", "${technique_name}", "${postgres_passwd}");

}
