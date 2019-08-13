# Common variable for common system technique
bundle common system_common {
  vars:
    "directiveId"           string => "&TRACKINGKEY&";
    "sharedfiles_folder"    string => "&SHARED_FILES_FOLDER&";
    "community_port"        string => "&COMMUNITYPORT&";
    "uuid"                  string => "&UUID&";
    "davuser"               string => "&DAVUSER&";
    "davpw"                 string => "&DAVPASSWORD&";
    "rudder_configuration_repository"
                            string => "&CONFIGURATION_REPOSITORY_FOLDER&";
    "heartbeat_interval"    string => "&RUDDER_HEARTBEAT_INTERVAL&";
    "rudder_node_config_id" string => "&RUDDER_NODE_CONFIG_ID&";
    "agent_run_interval"    string => "&AGENT_RUN_INTERVAL&";

  classes:
&if(INITIAL)&
    "initial_promises" expression => "any", scope => "namespace";
&endif&

}
