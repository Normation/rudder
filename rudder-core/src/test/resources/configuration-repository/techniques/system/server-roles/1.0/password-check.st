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

# This file contains bundles to manage password between all components of a
# Rudder server (OpenLDAP, PostgreSQL, Apache WebDAV, endpoint and web interface)

# It is currently only used on root servers where all components are installed
# on one host. It may be extended in the future to support changing passwords
# across multiple hosts.

bundle common p
{

  vars:

    root_server::

      "no" int => getfields("RUDDER_PSQL_PASSWORD:.*","/opt/rudder/etc/rudder-passwords.conf",":","psql_password");
      "no2" int => getfields("RUDDER_OPENLDAP_BIND_PASSWORD:.*","/opt/rudder/etc/rudder-passwords.conf",":","ldap_password");

      "properties_files" slist => { "${g.rudder_base}/etc/rudder-web.properties", "${g.rudder_base}/etc/inventory-web.properties" };
      "managed_files" slist => { "@{properties_files}", "/root/.pgpass", "${g.rudder_base}/etc/openldap/slapd.conf" };

}

bundle agent root_password_check_disclaimer
{

  vars:

      "disclaim" slist => { "@{p.managed_files}" };

  files:

    root_server::

      "${disclaim}"
        edit_line => insert_rudder_disclaimer,
        comment => "Insert a disclaimer into Rudder";

}

bundle agent root_password_security
{
  vars:
    "secure_files" slist => { "@{p.managed_files}", "/opt/rudder/etc/rudder-passwords.conf", "/opt/rudder/etc/rudder-users.xml", "/opt/rudder/etc/rudder-slapd.conf" };

  files:
    # ensure password access is forbidden
    root_server::
      "${secure_files}"
        perms => mog("600", "root", "0");
}

bundle agent root_password_check_file
{

  vars:

      "pgpass[1]" string => "${install_rsyslogd.rudder_postgres_server}";
      "pgpass[2]" string => "5432";
      "pgpass[3]" string => "${install_rsyslogd.rudder_reports_db_user}";
      "pgpass[4]" string => "${install_rsyslogd.rudder_reports_db_name}";
      "pgpass[5]" string => "${p.psql_password[2]}";

  files:

    root_server::

      "${g.rudder_base}/etc/rudder-passwords.conf"
        perms => mog("600", "root", "0"),
        classes => kept_if_else("file_ok", "file_repaired", "file_error");

      "/root/.pgpass"
        create    => "true",
        edit_line => root_password_check_file_pgpass("root_password_check_file.pgpass"),
        classes   => rudder_common_classes("rudder_postgres_pgpass");

  methods:

    root_server.file_ok.rudder_postgres_pgpass_kept.!(file_repaired|file_error|rudder_postgres_pgpass_repaired|rudder_postgres_pgpass_failed)::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check rudder-passwords.conf and pgpass files", "None", "Rudder passwords file is present and secure");

    root_server.file_repaired.!file_error::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check rudder-passwords.conf and pgpass files", "None", "Rudder passwords file permissions were fixed");

    root_server.file_error::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check rudder-passwords.conf and pgpass files", "None", "Rudder passwords file (${g.rudder_base}/etc/rudder-passwords.conf) is absent, server will not operate properly");

    root_server.rudder_postgres_pgpass_repaired.!rudder_postgres_pgpass_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check rudder-passwords.conf and pgpass files", "None", "PostgreSQL .pgpass file was repaired");

    root_server.rudder_postgres_pgpass_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check rudder-passwords.conf and pgpass files", "None", "Could not repair the PostgreSQL .pgpass file");



    policy_server.!root_server::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check rudder-passwords.conf and pgpass files", "None", "Rudder passwords file does not need to be checked on relay servers. Skipping.");

}

bundle agent root_password_check_ldap
{

  vars:

      # Build an array using ldap configuration values

      "rudder[ldap.authpw]" string => "${p.ldap_password[2]}";

      "slapd[rootpw]" string => "${p.ldap_password[2]}"; # Looks like a bug, I can not use execresult("/opt/rudder/sbin/slappasswd -s ${p.ldap_password[2]}","noshell");

      "prop_edit" slist => { "@{p.properties_files}" };

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:

    root_server::

      "${prop_edit}"
        edit_line => set_variable_values("root_password_check_ldap.rudder"),
        classes => kept_if_else("rudder_web_password_integrity_ok", "rudder_web_password_integrity_changed", "rudder_web_password_integrity_failed"),
        comment => "Verifying the Rudder web interface properties file passwords and users";

      "${g.rudder_base}/etc/openldap/slapd.conf"
        edit_line => update_slapd("root_password_check_ldap.slapd"),
        classes => kept_if_else("rudder_ldap_password_integrity_ok", "rudder_ldap_password_integrity_changed", "rudder_ldap_password_integrity_failed"),
        comment => "Verifying the Rudder LDAP file password and user";

  methods:

    pass2.rudder_ldap_password_integrity_changed::

      "restart_slapd" usebundle => service_restart("rudder-slapd");

    pass3.root_server.rudder_web_password_integrity_ok.!rudder_web_password_integrity_changed.!rudder_web_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check LDAP in rudder-webapp.properties", "None", "Web interface configuration files are correct (checked LDAP password)");

    pass3.root_server.rudder_web_password_integrity_changed.!rudder_web_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check LDAP in rudder-webapp.properties", "None", "Web interface configuration files were updated with a new LDAP password");

    pass3.root_server.rudder_web_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check LDAP in rudder-webapp.properties", "None", "Web interface configuration files could not be updated to set the LDAP password");

    pass3.root_server.rudder_ldap_password_integrity_ok.!rudder_ldap_password_integrity_changed.!rudder_ldap_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check LDAP credentials", "None", "OpenLDAP configuration file is correct (checked rootdn password)");

    pass3.root_server.rudder_ldap_password_integrity_changed.!rudder_ldap_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check LDAP credentials", "None", "OpenLDAP configuration file was updated with a new rootdn password");

    pass3.root_server.rudder_ldap_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check LDAP credentials", "None", "OpenLDAP configuration file could not be updated to set the rootdn password");

    pass3.root_server.service_restart_rudder_slapd_repaired::
      "any" usebundle => rudder_common_report("server-roles", "log_info", "&TRACKINGKEY&", "Check rudder-passwords.conf file", "None", "rudder-slapd has been restarted");

    pass3.(policy_server.!root_server)::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check LDAP in rudder-webapp.properties", "None", "Web interface configuration files do not need to be checked on relay servers. Skipping...");
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check LDAP credentials", "None", "OpenLDAP configuration file do not need to be checked on relay servers. Skipping.");

}

bundle agent root_password_check_psql
{

  vars:

    root_server::

      "no" int => getfields("RUDDER_PSQL_PASSWORD:.*","/opt/rudder/etc/rudder-passwords.conf",":","psql_pass");

      # Build an array using PSQL configuration values
      "rudder[rudder.jdbc.password]" string => "${p.psql_password[2]}";

  classes:

    root_server::

      "psql_cant_connect" not => returnszero("/usr/bin/psql --host localhost --username rudder --dbname rudder --quiet --output /dev/null --command 'select 1' 2> /dev/null","useshell");

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:

    root_server::

      "${g.rudder_base}/etc/rudder-web.properties"
        edit_line => set_variable_values("root_password_check_psql.rudder"),
        classes => kept_if_else("rudder_web_password_integrity_ok", "rudder_web_password_integrity_changed", "rudder_web_password_integrity_failed"),
        comment => "Verifying the Rudder web interface properties file passwords and users";

  methods:

    pass3.root_server.rudder_web_password_integrity_ok.!rudder_web_password_integrity_changed.!rudder_web_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check SQL in rudder-webapp.properties", "None", "Web interface configuration files are OK (checked SQL password)");

    pass3.root_server.rudder_web_password_integrity_changed.!rudder_web_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check SQL in rudder-webapp.properties", "None", "Web interface configuration files were updated with a new SQL password");

    pass3.root_server.rudder_web_password_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check SQL in rudder-webapp.properties", "None", "Web interface configuration files could not be updated to set the SQL password");

    pass3.root_server.!psql_cant_connect::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check SQL credentials", "None", "PostgreSQL user account's password is correct and works");

    pass3.root_server.postgres_updated::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check SQL credentials", "None", "PostgreSQL user account's password has been changed");

    pass3.root_server.postgres_update_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check SQL credentials", "None", "PostgreSQL user account's password could not be changed!");

    pass3.(policy_server.!root_server)::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check SQL in rudder-webapp.properties", "None", "Web interface configuration files do not need to be checked on relay servers. Skipping.");
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check SQL credentials", "None", "PostgreSQL user account's do not need to be checked on relay servers. Skipping.");

  commands:

    psql_cant_connect|rudder_postgres_pgpass_repaired::

      "/usr/bin/psql -q -c \"ALTER USER rudder WITH PASSWORD '${p.psql_password[2]}'\""
        contain => setuid_sh("postgres"),
        classes => if_else("postgres_updated", "postgres_update_failed");

}

bundle agent root_password_restart_jetty
{

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:

    pass2.rudder_web_password_integrity_changed::

      "restart_jetty" usebundle => service_restart("rudder-jetty");

    pass3.service_restart_rudder_jetty_repaired::
      "any" usebundle => rudder_common_report("server-roles", "log_info", "&TRACKINGKEY&", "Check rudder-webapp.properties", "None", "Jetty has been restarted");

}

bundle agent root_password_check_dav
{

  vars:

    debian::
      "webdav_check_wwwgroup" string => "www-data";

    redhat::
      "webdav_check_wwwgroup" string => "apache";

    !debian.!redhat::
      "webdav_check_wwwgroup" string => "www";

    SuSE::
      "htpasswd_bin" string => "/usr/bin/htpasswd2";

    !SuSE::
      "htpasswd_bin" string => "/usr/bin/htpasswd";

  classes:

      "dav_cant_connect" not => returnszero("/usr/bin/curl --proxy '' -k -s -f -o /dev/null -u ${g.davuser}:${g.davpw} -T /opt/rudder/etc/uuid.hive ${g.inventory_upload_protocol}://localhost/inventory-updates/uuid.hive","noshell");

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:

      "${g.rudder_base}/etc/htpasswd-webdav"
        create => "true",
        perms => mog("640", "root", "${webdav_check_wwwgroup}");

  methods:

    pass3.!dav_cant_connect::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check WebDAV credentials", "None", "Apache WebDAV user and password are OK");

    pass3.rudder_apache_davpassword_changed.!rudder_apache_davpassword_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check WebDAV credentials", "None", "Apache WebDAV user and password were updated");

    pass3.rudder_apache_davpassword_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check WebDAV credentials", "None", "Apache WebDAV user and password could not be updated");

  commands:

    dav_cant_connect::

      # Apache reads this file when it's changed, no need to restart or reload it after it's changed
      "${htpasswd_bin}"
        args => "-b ${g.rudder_base}/etc/htpasswd-webdav ${g.davuser} ${g.davpw}",
        classes => kept_if_else("rudder_apache_davpassword_ok", "rudder_apache_davpassword_changed", "rudder_apache_davpassword_failed"),
        comment => "Repairing the Rudder WebDAV user and password";

}

bundle edit_line update_slapd(tab)
{

  vars:

      "index" slist => getindices("${tab}");

  delete_lines:

      "${index}.*";

  insert_lines:

      "${index}		${${tab}[${index}]}" location => after("^rootdn.*");

}

bundle edit_line root_password_check_file_pgpass(parameter)
{
  vars:

      "indices" slist => getindices(${parameter});

  field_edits:

      "localhost:5432:rudder:.*"

    # Set field of the file to parameter
        edit_field => col(":","${indices}","${${parameter}[${indices}]}","set");

  insert_lines:

      "localhost:5432:rudder:${${parameter}[4]}:${${parameter}[5]}";

}
