# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

bundle common rudder_apache {
  vars:
    any::
      "service_name"   string => "apache2";
      "group_name"     string => "www";

      "templated_conf" string => "/opt/rudder/etc/rudder-apache-relay-ssl.conf";
    redhat::
      "service_name"   string => "httpd";
      "group_name"     string => "apache";
    debian::
      "group_name"     string => "www-data";
}

bundle agent system_rudder_apache_configuration {
  vars:
      "trackingkey"     string => "&TRACKINGKEY&";
      "technique_name"  string => "Rudder apache";
      "https_port"      string => "&HTTPS_POLICY_DISTRIBUTION_PORT&";
      "reload_cond"     string => canonify("file_from_template_options_${rudder_apache.templated_conf}_repaired");
      "custom_ca"              string => "true";
      "custom_ca"              string => "false",
                                   if => strcmp("&POLICY_SERVER_CERT_CA&", "");
      "templating_data_r" data => '{ "https_only": &POLICY_SERVER_HTTPS_ONLY&, "cert_validation": &POLICY_SERVER_CERT_VALIDATION&, "custom_ca": ${custom_ca} }';
      "templating_data" string => storejson(templating_data_r);

  classes:
      "rudder_server_system_reload_apache" expression => "${reload_cond}",
                                                scope => "namespace";

  methods:
      # Force certificates and permissions
      "any" usebundle => apache_cert("${trackingkey}", "${technique_name}");

      # Force allowed networks
      "any" usebundle => system_rudder_apache_networks_configuration;

      # Force webdav user/password
      "any" usebundle => system_rudder_apache_webdav_configuration("${trackingkey}", "${technique_name}");

      # Configure log rotation
      "any" usebundle => system_apache_logrotate;

      # Check that apache is running and enabled
      "any" usebundle => _method_reporting_context_v4("Apache service", "Started", "apache_started");
      "any" usebundle => service_started("${rudder_apache.service_name}");

      "any" usebundle => _method_reporting_context_v4("Apache service", "Enabled", "apache_enabled");
      "any" usebundle => service_enabled("${rudder_apache.service_name}");

      "any" usebundle => _method_reporting_context_v4("Apache service", "Configured", "apache_configured");
      "any" usebundle => file_from_template_options("${rudder_apache.templated_conf}", "", "${templating_data}", "", "${rudder_apache.templated_conf}.j2", "");
}
