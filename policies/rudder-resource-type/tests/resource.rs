// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use rudder_resource_type::{
    parameters::Parameters, run, CheckApplyResult, Outcome, PolicyMode, ResourceType0,
    ResourceTypeMetadata,
};

struct Test {}

impl ResourceType0 for Test {
    fn metadata(&self) -> ResourceTypeMetadata {
        let raw = include_str!("../rudder_resource.yml");
        ResourceTypeMetadata::from_metadata(raw).expect("invalid metadata")
    }

    fn check_apply(&mut self, _mode: PolicyMode, _attributes: &Parameters) -> CheckApplyResult {
        Ok(Outcome::Success(None))
    }
}

fn main() -> Result<(), anyhow::Error> {
    let test = Test {};
    run(test)
}
