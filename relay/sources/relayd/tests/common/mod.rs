// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use rand::Rng;
use std::{process::Command, thread, time};

/// WARNING: Sensitive to collisions (birthday problem).
/// For now we retry the tests.
pub fn random_ports() -> (u16, u16) {
    let mut rng = rand::rng();
    let r = rng.random_range(2000..65000);
    (r, r + 1)
}

#[allow(dead_code)]
#[allow(clippy::result_unit_err)]
pub fn start_api(port: u16) -> Result<(), ()> {
    let mut retry = 10;
    while retry > 0 {
        thread::sleep(time::Duration::from_millis(200));
        retry -= 1;

        let resp = reqwest::blocking::get(format!(
            "http://localhost:{port}/rudder/relay-api/1/system/status"
        ));

        if dbg!(resp).is_ok() {
            return Ok(());
        }
    }
    Err(())
}

#[allow(dead_code)]
pub fn fake_server_start(id: String, port: u16) {
    thread::spawn(move || {
        Command::new("tests/server.py")
            .arg(id)
            .arg(port.to_string())
            .spawn()
            .expect("failed to execute process")
    });
    thread::sleep(time::Duration::from_millis(400));

    let client = reqwest::blocking::Client::builder()
        .danger_accept_invalid_certs(true)
        .build()
        .unwrap();
    let response = client
        .get(format!("https://localhost:{port}/uuid"))
        .send()
        .unwrap();
    assert_eq!(response.status(), hyper::StatusCode::OK);
}

#[allow(dead_code)]
pub fn fake_server_stop(port: u16) {
    let client = reqwest::blocking::Client::builder()
        .danger_accept_invalid_certs(true)
        .build()
        .unwrap();
    let response = client
        .get(format!("https://localhost:{port}/stop"))
        .send()
        .unwrap();
    assert_eq!(response.status(), hyper::StatusCode::OK);
}
