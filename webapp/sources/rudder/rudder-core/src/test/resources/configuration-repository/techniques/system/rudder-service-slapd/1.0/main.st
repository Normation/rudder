# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

bundle common rudder_slapd {
  vars:
      "service_name" string => "rudder-slapd";
      "user_name"    string => "rudder-slapd";
      "group_name"   string => "rudder-slapd";

      "config_file"  string => "${g.rudder_base}/etc/openldap/slapd.conf";
}

bundle agent system_rudder_slapd_configuration {
  vars:

      "no"                  int => getfields("RUDDER_OPENLDAP_BIND_PASSWORD:.*","/opt/rudder/etc/rudder-passwords.conf",":","password_getter");
      "slapd_password"   string => "${password_getter[2]}";
      "technique_name"   string => "Rudder slapd";
      "trackingkey"      string => "&TRACKINGKEY&";

      "args"              slist => { "${rudder_slapd.config_file}", "640", "root", "${rudder_slapd.group_name}" };
      "report_param"     string => join("_", args);
      "permission_class" string => canonify("permissions_${report_param}");

  classes:
      "rudder_server_system_restart_slapd" expression => "rudder_slapd_ldap_password_repaired|${permission_class}_repaired",
                                                scope => "namespace";
      "rudder_server_system_restart_jetty" expression => "rudder_slapd_ldap_password_repaired|${permission_class}_repaired",
                                                scope => "namespace";

  files:
      "${rudder_slapd.config_file}"
        edit_line => set_rudder_slapd_password("${slapd_password}"),
        classes   => classes_generic("rudder_slapd_ldap_password"),
        comment   => "Verifying the Rudder LDAP file password and user";

  methods:
    # Log rotation
      "any" usebundle => system_slapd_logrotate;
    # Service configuration
      "any" usebundle => _method_reporting_context("Rudder slapd configuration", "Check Rudder ldap permissions");
      "any" usebundle => permissions("${rudder_slapd.config_file}", "640", "root", "${rudder_slapd.group_name}");
    !is_rudder_bootstrap::
      "any" usebundle => _method_reporting_context_v4("Rudder slapd service", "Started", "slapd_started");
      "any" usebundle => service_started("${rudder_slapd.service_name}");
    any::
      "any" usebundle => _method_reporting_context_v4("Rudder slapd service", "Enabled", "slapd_enabled");
      "any" usebundle => service_enabled("${rudder_slapd.service_name}");

      "any" usebundle => rudder_common_reports_generic("${technique_name}", "rudder_slapd_ldap_password", "${trackingkey}", "Rudder slapd configuration", "Check Rudder ldap password", "OpenLDAP configuration file (rootdn password)");
}


bundle edit_line set_rudder_slapd_password(password) {
  delete_lines:
      "rootpw.*";

  insert_lines:
      "rootpw         ${password}" location => after("^rootdn.*");
}
