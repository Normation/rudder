use reqwest;
use std::{thread, time};

pub fn start_api() -> Result<(), ()> {
    let mut retry = 10;
    while retry > 0 {
        thread::sleep(time::Duration::from_millis(200));
        retry -= 1;

        let resp = reqwest::get("http://localhost:3030/rudder/relay-api/1/system/status");

        if resp.is_ok() {
            return Ok(());
        }
    }
    Err(())
}
