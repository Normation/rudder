// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use crate::cli::Cli;
use crate::module::ChocoModule;
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::run_module;

pub mod choco;
pub mod cli;
pub mod module;
pub mod parameters;

pub fn entry() -> anyhow::Result<()> {
    let promise_type = ChocoModule::default();

    if called_from_agent() {
        run_module(promise_type)
    } else {
        Cli::run()
    }
}
