use std::path::PathBuf;

#[derive(Debug)]
pub struct Paths {
    pub node_id: PathBuf,
    pub policy_server: PathBuf,
    pub agent_conf: PathBuf,
    pub rudder_json: PathBuf,
    pub disable_agent: PathBuf,
    pub version: PathBuf,
    pub force_audit: PathBuf,
    pub policy_server_hash: PathBuf,
}

impl Paths {
    pub fn unix() -> Self {
        let var = PathBuf::from("/var/rudder/");
        let opt = PathBuf::from("/opt/rudder/");
        Self {
            node_id: opt.join("etc/uuid.hive"),
            force_audit: opt.join("etc/force-audit-agent"),
            policy_server_hash: opt.join("lib/ssl/policy_server_hash"),
            disable_agent: opt.join("etc/disable-agent"),
            agent_conf: opt.join("etc/agent.conf"),
            policy_server: var.join("cf-engine-community/policy_server.dat"),
            rudder_json: var.join("cfengine-community/inputs/rudder.json"),
            version: var.join("share/versions/rudder-agent-version"),
        }
    }
    
    pub fn windows() -> Self {
       todo!()
    }
}
