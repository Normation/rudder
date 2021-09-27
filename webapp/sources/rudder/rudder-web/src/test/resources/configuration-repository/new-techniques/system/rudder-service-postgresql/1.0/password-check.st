# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

bundle agent system_rudder_postgresql(trackingkey, technique_name, db_pass) {
  vars:
      "root_home_path" string => execresult("/usr/bin/getent passwd root | cut -d: -f6", "useshell");

      "component" string => "Postgresql configuration";


    any::
    # Array used to force content of the .pgpass file
      #"pgpass[1]" string => "${rudder_postgresql.host}";
      "pgpass[2]" string => "5432";
      "pgpass[3]" string => "${rudder_postgresql.db_name}";
      "pgpass[4]" string => "rudder"; # this is always rudder, we don't have anything else to configure it
      "pgpass[5]" string => "${db_pass}";

  classes:
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

      "psql_cant_connect" not => returnszero("/usr/bin/psql --no-password --host localhost --username ${rudder_postgresql.db_user} --dbname ${rudder_postgresql.db_name} --quiet --output /dev/null --command 'select 1' 2> /dev/null","useshell");

  files:
    # ensure password access is forbidden
      "${root_home_path}/.pgpass"
        create    => "true",
        edit_line => rudder_system_pgpass_password("${this.bundle}.pgpass"),
        perms     => mog("600", "root", "0"),
        classes   => classes_generic("rudder_postgres_pgpass");

    # Insert Rudder disclaimer in the pgpass file
    # Do not report it
      "${root_home_path}/.pgpass"
        edit_line => rudder_common_disclaimer,
        classes   => classes_generic("rudder_postgres_disclaimer");

  methods:
    pass3::
      "any" usebundle => rudder_common_reports_generic("${technique_name}", "rudder_postgres_pgpass", "${trackingkey}", "${component}", "Check pgpass file", "Presence and permissions of pgsql password file");
    pass3.psql_cant_connect::
      "any" usebundle => rudder_common_reports_generic("${technique_name}", "rudder_psql_sql_password", "${trackingkey}", "${component}", "Postgresql credentials", "PostgreSQL user account's password");
    pass3.!psql_cant_connect::
      "any" usebundle => rudder_common_report("${technique_name}", "result_success", "${trackingkey}", "${component}", "Postgresql credentials", "PostgreSQL user account's password");

  commands:
    psql_cant_connect|rudder_postgres_pgpass_repaired::
      "/usr/bin/psql --no-password -q -c \"ALTER USER rudder WITH PASSWORD '${db_pass}'\""
        contain => setuid_sh("${rudder_postgresql.db_admin_name}"),
        classes => classes_generic("rudder_psql_sql_password");

}


bundle edit_line rudder_system_pgpass_password(parameter) {
  vars:
      "indices" slist => getindices(${parameter});

  field_edits:
      "localhost:5432:${${parameter}[3]}:.*"

    # Set field of the file to parameter
        edit_field => col(":","${indices}","${${parameter}[${indices}]}","set");

  insert_lines:
      "localhost:5432:${${parameter}[3]}:${${parameter}[4]}:${${parameter}[5]}";
}
