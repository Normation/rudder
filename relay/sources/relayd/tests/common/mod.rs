// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{process::Command, thread, time};

#[allow(clippy::result_unit_err)]
pub fn start_api() -> Result<(), ()> {
    let mut retry = 10;
    while retry > 0 {
        thread::sleep(time::Duration::from_millis(200));
        retry -= 1;

        let resp = reqwest::blocking::get("http://localhost:3030/rudder/relay-api/1/system/status");

        if resp.is_ok() {
            return Ok(());
        }
    }
    Err(())
}

#[allow(dead_code)]
pub fn fake_server_start(id: String) {
    thread::spawn(|| {
        Command::new("tests/server.py")
            .arg(id)
            .spawn()
            .expect("failed to execute process")
    });
    thread::sleep(time::Duration::from_millis(400));

    let client = reqwest::blocking::Client::builder()
        .danger_accept_invalid_certs(true)
        .build()
        .unwrap();
    let response = client.get("https://localhost:4443/uuid").send().unwrap();
    assert_eq!(response.status(), hyper::StatusCode::OK);
}

#[allow(dead_code)]
pub fn fake_server_stop() {
    let client = reqwest::blocking::Client::builder()
        .danger_accept_invalid_certs(true)
        .build()
        .unwrap();
    let response = client.get("https://localhost:4443/stop").send().unwrap();
    assert_eq!(response.status(), hyper::StatusCode::OK);
}
