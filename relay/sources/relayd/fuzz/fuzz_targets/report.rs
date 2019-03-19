#![no_main]
#[macro_use] extern crate libfuzzer_sys;
extern crate relayd;

use relayd::data::reporting::{Report, RunLog, RunInfo};

fuzz_target!(|data: &[u8]| {
    std::str::from_utf8(data).map(|x|x.parse::<Report>());
});
