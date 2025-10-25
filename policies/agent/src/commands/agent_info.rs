use crate::AgentState;
use anyhow::anyhow;

pub fn run_agent_info(state: AgentState) -> Result<(), anyhow::Error> {
    let hostname = hostname::get()?
        .into_string()
        .map_err(|_| anyhow!("Non-UTF8 hostname"))?;
    let uuid = state.uuid.unwrap_or_default();
    let policy_server = state.policy_server.unwrap_or_default();

    println!("General");
    println!("           Hostname: {}", hostname);
    println!("               UUID: {}", uuid);
    println!("      Policy server: {}", policy_server);
    println!(
        "               Role: {}",
        state.policy_params.rudder_node_kind
    );
    println!("            Version: {}", state.build_info.version);
    println!(
        "        MSI Version: {}",
        state.build_info.rudder_msi_version
    );

    println!("Policies");
    println!("             Status: {}", state.status);
    println!(
        "        Report mode: {}",
        state.policy_params.rudder_compliance_mode
    );
    println!(
        "       Run interval: {} min",
        state.policy_params.agent_run_interval
    );
    // println!("           Next run: {}", state.next_run.format("%Y-%m-%dT%H:%M:%S%:z"));
    // println!("     Inventory time: {}", state.inventory_time.format("%Y-%m-%dT%H:%M:%S%:z"));
    // println!("  Forced audit mode: {}", state.forced_audit_mode);
    println!(
        "   Configuration id: {}",
        state.policy_params.rudder_node_config_id
    );
    // println!("     Policy updated: {}", state.policy_updated.format("%Y-%m-%dT%H:%M:%S%:z"));
    // println!("     Inventory sent: {}", state.inventory_sent.format("%Y-%m-%dT%H:%M:%S%:z"));

    // println!("Key/Certificate");
    // println!("  Cert. fingerprint: {}", state.cert_fingerprint);
    // println!("     Cert. creation: {}", state.cert_creation.format("%Y-%m-%dT%H:%M:%S%:z"));
    // println!("   Cert. expiration: {}", state.cert_expiration.format("%Y-%m-%dT%H:%M:%S%:z"));
    Ok(())
}
