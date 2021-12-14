// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use rudder_relayd::configuration::{logging::LogConfig, main::Configuration};

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::read_to_string;

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
}
