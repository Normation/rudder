//! Rudder server information and health, from the Rudder API.

use serde::Deserialize;
use serde_json::Value;

/// `GET /system/info` data without the JVM command line: most of the response, only noise for the
/// model, and it exposes the server's internal paths and settings
pub fn info(body: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct Response {
        data: Value,
    }

    let mut data = serde_json::from_str::<Response>(body)
        .map_err(|e| format!("unexpected Rudder API response: {e}"))?
        .data;
    if let Some(jvm) = data
        .pointer_mut("/system/jvm")
        .and_then(Value::as_object_mut)
    {
        jvm.remove("cmd");
    }
    serde_json::to_string_pretty(&data).map_err(|e| e.to_string())
}

/// Summary of `GET /system/status` (webapp state) and `GET /system/healthcheck` (checks)
pub fn health(status_body: &str, checks_body: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct Status {
        data: StatusData,
    }
    #[derive(Deserialize)]
    struct StatusData {
        global: String,
    }
    #[derive(Deserialize)]
    struct Checks {
        data: Vec<Check>,
    }
    #[derive(Deserialize)]
    struct Check {
        name: String,
        msg: String,
        status: String,
    }

    let unexpected = |e: serde_json::Error| format!("unexpected Rudder API response: {e}");
    let status = serde_json::from_str::<Status>(status_body)
        .map_err(unexpected)?
        .data
        .global;
    let checks = serde_json::from_str::<Checks>(checks_body)
        .map_err(unexpected)?
        .data;

    let mut summary = format!("Status: {status}\nChecks:");
    for check in checks {
        // Messages can span several lines (e.g. one per file system)
        let msg = check.msg.replace('\n', "\n    ");
        summary.push_str(&format!("\n- [{}] {}: {msg}", check.status, check.name));
    }
    Ok(summary)
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use serde_json::json;

    use super::*;

    #[test]
    fn info_without_jvm_command_line() {
        // Shape of a real 9.2 response, command line shortened
        let body = json!({"action": "getSystemInfo", "result": "success", "data": {
            "rudder": {"version": "9.2.0", "buildTime": "2026-09-23T09:49:42Z",
                       "instanceId": "0523bc78-4529-4d99-a375-15f8c75c0ec1", "relays": []},
            "system": {"os": {"name": "Debian GNU/Linux 13 (trixie)", "version": "13"},
                       "jvm": {"version": "21.0.12.1", "cmd": "/usr/lib/jvm/java-21/bin/java -Xmx1024m ..."}},
            "nodes": {"total": 1, "audit": 0, "enforce": 0, "mixed": 0, "enabled": 1, "disabled": 0},
            "plugins": []
        }})
        .to_string();
        let info: Value = serde_json::from_str(&info(&body).unwrap()).unwrap();
        assert_eq!(info["system"]["jvm"], json!({"version": "21.0.12.1"}));
        assert_eq!(info["rudder"]["version"], "9.2.0");
        assert_eq!(info["nodes"]["total"], 1);
    }

    #[test]
    fn health_summary() {
        // Real 9.2 responses
        let status = r#"{"action":"getStatus","result":"success","data":{"global":"OK"}}"#;
        let checks = r#"{"action":"getHealthcheckResult","result":"success","data":[
            {"name":"CPU cores","msg":"Only one core, recommended value is at least 2","status":"Warning"},
            {"name":"Free disk space","msg":"Enough available free space:\n/var/log has 90% free space\n/var/rudder has 90% free space","status":"Ok"}
        ]}"#;
        assert_eq!(
            health(status, checks).unwrap(),
            "Status: OK\n\
             Checks:\n\
             - [Warning] CPU cores: Only one core, recommended value is at least 2\n\
             - [Ok] Free disk space: Enough available free space:\n    \
             /var/log has 90% free space\n    \
             /var/rudder has 90% free space"
        );
    }

    #[test]
    fn unexpected_responses() {
        assert!(
            info("{}")
                .unwrap_err()
                .contains("unexpected Rudder API response")
        );
        assert!(
            health(r#"{"data":{"global":"OK"}}"#, r#"{"data":{}}"#)
                .unwrap_err()
                .contains("unexpected Rudder API response")
        );
    }
}
