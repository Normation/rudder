use relayd::{
    configuration::CliConfiguration, data::report::QueryableReport, init,
    output::database::schema::ruddersysevents::dsl::*, stats::Stats,
};
use std::{
    fs::{copy, create_dir_all, remove_dir_all},
    path::Path,
    thread, time,
};

use reqwest;

fn start_api() -> Result<(), ()> {
    let mut retry = 10;
    while retry > 0 {
        thread::sleep(time::Duration::from_millis(200));
        retry -= 1;

        let resp = reqwest::get("http://localhost:3030/status");

        if resp.is_ok() {
            return Ok(());
        }
    }
    Err(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_handles_errors() {
        let cli_cfg = CliConfiguration::new("tests/test_simple/relayd.conf", false);

        thread::spawn(move || {
            init(cli_cfg).unwrap();
        });

        assert!(start_api().is_ok());

        let client = reqwest::Client::new();

        // // curl -X POST http://127.0.0.1:3030/rudder/relay-api/remote-run/nodes -d "asynchronous=false&keep_output=true&conditions=class1,class2,class3&nodes=node2.rudder.local,server.rudder.local"

        let params = [
            ("asynchronous", "false"),
            ("keep_output", "true"),
            ("conditions", "clas~1,class2,class3"),
            ("nodes", "node2.rudder.local,server.rudder.local"),
        ];

        let res = client
            .post("http://localhost:3030/rudder/relay-api/remote-run/nodes")
            .form(&params)
            .send();

        assert_eq!(res.unwrap().text().unwrap(), "Unhandled rejection: Invalid agent Condition : Invalid agent Condition : Wrong condition: {} Your condition should match this regex : ^[a-zA-Z0-9][a-zA-Z0-9_]*$".to_string());
    }
}
