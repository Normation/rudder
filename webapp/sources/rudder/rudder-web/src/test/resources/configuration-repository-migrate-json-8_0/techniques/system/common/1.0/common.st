# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

# Common variable for common system technique
bundle common system_common {
  vars:
    "directiveId"           string => "&TRACKINGKEY&";
    "sharedfiles_folder"    string => "${g.shared_files}";
    "cf_serverd_port"       string => "&COMMUNITYPORT&";
    "community_port"        string => "${server_info.policy_server_port}";
    "uuid"                  string => "&UUID&";
    "davuser"               string => "&DAVUSER&";
    "davpw"                 string => "&DAVPASSWORD&";
    "rudder_configuration_repository"
                            string => "&CONFIGURATION_REPOSITORY_FOLDER&";
    "rudder_node_config_id" string => "&RUDDER_NODE_CONFIG_ID&";
    "agent_run_interval"    string => "&AGENT_RUN_INTERVAL&";

    "cf_serverd_service_name" string => "rudder-cf-serverd";

  classes:
&if(INITIAL)&
    "initial_promises" expression => "any", scope => "namespace";
&endif&

}
