#####################################################################################
# Copyright 2014 Normation SAS
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

bundle agent root_postgres_check
{

  vars:

    SuSE::

      "configuration_statements" slist => { "host    all             rudder             ::1/128              md5", "host    all             rudder          127.0.0.1/32            md5" };

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:

    SuSE::

      "/var/lib/pgsql/data/pg_hba.conf"
        edit_line => prepend("${configuration_statements}"),
        edit_defaults => noempty_backup,
        classes => kept_if_else("psql_conf_ok", "psql_conf_updated", "psql_conf_update_error"),
        comment => "Edit the SuSE postgresql configuration to enable account-less logins";

  methods:

    pass2.SuSE.psql_conf_updated::
      "restart_postgresql" usebundle => service_restart("postgresql");

    # Report about the configuration file editing
    pass3.SuSE.psql_conf_ok.!psql_conf_updated.!psql_conf_update_error::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check PostgreSQL configuration", "None", "The SuSE specific PostgreSQL configuration is present");

    pass3.SuSE.psql_conf_updated.!psql_conf_update_error::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check PostgreSQL configuration", "None", "The SuSE specific PostgreSQL configuration has been added");

    pass3.SuSE.psql_conf_update_error::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check PostgreSQL configuration", "None", "The SuSE specific PostgreSQL configuration could not be added");

    # Reports about Postgres restart status
    pass3.SuSE.service_restart_postgresql_repaired::
      "any" usebundle => rudder_common_report("server-roles", "log_info", "&TRACKINGKEY&", "Check PostgreSQL configuration", "None", "PostgreSQL has been restarted");

    pass3.SuSE.service_restart_postgresql_not_ok::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check PostgreSQL configuration", "None", "PostgreSQL restart has failed! Rudder is most certainly broken...");

    pass3.!SuSE::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check PostgreSQL configuration", "None", "There is no need of specific PostgreSQL configuration on this system");

}
