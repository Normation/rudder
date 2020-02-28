// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

#![no_main]
#[macro_use]
extern crate libfuzzer_sys;
extern crate relayd;

use relayd::data::RunInfo;

fuzz_target!(|data: &[u8]| {
    let _ = std::str::from_utf8(data).map(|x| x.parse::<RunInfo>());
});
