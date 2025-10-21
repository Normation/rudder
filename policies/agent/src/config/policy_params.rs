use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use toml::Value;

#[derive(Deserialize, Serialize, Debug)]
#[serde(rename_all = "UPPERCASE")]
pub struct PolicyParams {
    pub agent_run_interval: String,
    pub agent_run_schedule: String,
    pub agent_run_splaytime: String,
    pub agent_run_starttime: String,
    pub agent_type: String,
    pub allowed_networks: Vec<String>,
    pub cfengine_outputs_ttl: String,
    pub communityport: String,
    pub configuration_repository_folder: String,
    pub davpassword: String,
    pub davuser: String,
    pub denybadclocks: String,
    pub https_policy_distribution_port: String,
    pub instance_id: String,
    pub modified_files_ttl: String,
    pub policy_server_key: String,
    pub policy_server_key_hash: String,
    pub relay_sync_method: String,
    pub relay_sync_promises: String,
    pub relay_sync_sharedfiles: String,
    pub reporting_protocol: String,
    pub rudder_compliance_mode: String,
    pub rudder_directives_inputs: Vec<String>,
    pub rudder_directives_sequence: String,
    pub rudder_heartbeat_interval: String,
    pub rudder_node_config_id: String,
    pub rudder_node_groups_classes: String,
    pub rudder_node_groups_vars: String,
    pub rudder_node_kind: String,
    pub rudder_report_mode: String,
    pub rudder_system_directives_inputs: String,
    pub rudder_system_directives_sequence: String,
    pub rudder_verify_certificates: String,
    //#[deprecated]
    pub send_metrics: String,
    pub server_version: String,
    pub shared_files_folder: String,
    //#[deprecated]
    pub tools_folder: String,
    #[serde(flatten)]
    pub extra: HashMap<String, Value>,
}

impl Default for PolicyParams {
    fn default() -> PolicyParams {
        PolicyParams {
            agent_run_interval: "5".to_string(),
            agent_run_schedule: "".to_string(),
            agent_run_splaytime: "".to_string(),
            agent_run_starttime: "".to_string(),
            agent_type: "dsc".to_string(),
            allowed_networks: vec![],
            cfengine_outputs_ttl: "".to_string(),
            communityport: "".to_string(),
            configuration_repository_folder: "/var/rudder/configuration-repository".to_string(),
            davpassword: "".to_string(),
            davuser: "".to_string(),
            denybadclocks: "".to_string(),
            https_policy_distribution_port: "".to_string(),
            instance_id: "".to_string(),
            modified_files_ttl: "".to_string(),
            policy_server_key: "".to_string(),
            policy_server_key_hash: "".to_string(),
            relay_sync_method: "".to_string(),
            relay_sync_promises: "".to_string(),
            relay_sync_sharedfiles: "".to_string(),
            reporting_protocol: "HTTPS".to_string(),
            rudder_compliance_mode: "enforce".to_string(),
            rudder_directives_inputs: vec![],
            rudder_directives_sequence: "".to_string(),
            rudder_heartbeat_interval: "".to_string(),
            rudder_node_config_id: "0".to_string(),
            rudder_node_groups_classes: "".to_string(),
            rudder_node_groups_vars: "".to_string(),
            rudder_node_kind: "node".to_string(),
            rudder_report_mode: "full-compliance".to_string(),
            rudder_system_directives_inputs: "".to_string(),
            rudder_system_directives_sequence: "".to_string(),
            rudder_verify_certificates: "".to_string(),
            send_metrics: "".to_string(),
            server_version: "".to_string(),
            shared_files_folder: "/var/rudder/configuration-repository/shared-files".to_string(),
            tools_folder: "".to_string(),
            extra: Default::default(),
        }
    }
}
