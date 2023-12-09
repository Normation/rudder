// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::fs::read_to_string;

use rudder_relayd::configuration::{logging::LogConfig, main::Configuration};

#[test]
fn default_configuration_is_valid() {
    // We don't use Configuration::new as it also validates the configuration
    // but we don't have a proper node id on test systems. So we parse it directly
    // instead.
    let cfg = read_to_string("tools/config/main.conf")
        .unwrap()
        .parse::<Configuration>();
    assert!(dbg!(cfg).is_ok());

    let log_cfg = LogConfig::new("tools/config");
    assert!(log_cfg.is_ok());
}
