# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

# Common variable for distribute policy system technique
bundle common rudder_relayd {
  vars:
      "config_dir"          string => "${g.rudder_base}/etc/relayd";
      "config_file"         string => "${config_dir}/main.conf";
      "config_log_file"     string => "${config_dir}/logging.conf";
      "service_name"        string => "rudder-relayd";
}

bundle agent system_rudder_relay_configuration {
  vars:
      "technique_name"         string => "Rudder relay";
      "trackingkey"            string => "&TRACKINGKEY&";
      "relay_sync_method"      string => "&RELAY_SYNC_METHOD&";
      "relay_sync_promises"    string => "&RELAY_SYNC_PROMISES&";
      "relay_sync_sharedfiles" string => "&RELAY_SYNC_SHAREDFILES&";
      "https_port"             string => "&HTTPS_POLICY_DISTRIBUTION_PORT&";

    root_server::
      "db_url"                 string => "&RUDDER_REPORTS_DB_URL&";
      # The value is empty on non-root servers
      "db_password"            string => "&RUDDER_REPORTS_DB_PASSWORD&";

  methods:
    # Only run this on relays (non-root)
    root_server::
      "any" usebundle => rudder_relay_root_na("${trackingkey}", "${technique_name}");
    policy_server.!root_server::
      "any" usebundle => propagate_promises("${trackingkey}", "${technique_name}", "${relay_sync_method}", "${relayd_sync_promises}", "${relay_sync_sharedfiles}");
    any::
      "any" usebundle => system_rudder_relayd_configuration;
}
