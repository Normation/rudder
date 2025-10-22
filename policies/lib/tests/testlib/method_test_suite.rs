// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::testlib::given::Given;
use crate::testlib::given::setup_state::TestSetup;
use crate::testlib::method_to_test::MethodToTest;
use crate::testlib::test_result::ExecutionResult;
use log::debug;
use rudder_commons::PolicyMode;
use rudderc::backends::Backend;
use rudderc::backends::unix::Unix;
use rudderc::backends::unix::cfengine::cf_agent;
use rudderc::ir::Technique;
use rudderc::ir::technique::{ItemKind, TechniqueId};
use std::fs;
use std::path::PathBuf;
use std::str::FromStr;

#[derive(Clone)]
pub struct MethodTestSuite {
    given: Vec<Given>,
    when: Vec<MethodToTest>,
}
impl Default for MethodTestSuite {
    fn default() -> Self {
        Self::new()
    }
}

impl MethodTestSuite {
    pub fn new() -> MethodTestSuite {
        MethodTestSuite {
            given: Vec::new(),
            when: Vec::new(),
        }
    }

    pub fn given(&self, g: Given) -> MethodTestSuite {
        let mut v = self.given.clone();
        v.push(g);
        MethodTestSuite {
            given: v,
            when: self.when.clone(),
        }
    }

    pub fn when(&self, nm: &MethodToTest) -> MethodTestSuite {
        let mut v = self.when.clone();
        v.push(nm.clone());
        MethodTestSuite {
            given: self.given.clone(),
            when: v,
        }
    }

    pub fn generate_test_technique(&self, extra_items: Vec<ItemKind>) -> Technique {
        let mut items = extra_items;
        items.extend(self.when.iter().map(|nm| nm.clone().to_item_kind()));
        Technique {
            format: 0,
            id: TechniqueId::from_str("method_test_technique").unwrap(),
            name: "".to_string(),
            version: "".to_string(),
            tags: None,
            category: None,
            description: None,
            documentation: None,
            policy_types: Vec::new(),
            items,
            params: vec![],
        }
    }

    pub fn execute(self, library_path: PathBuf, workdir: PathBuf) -> ExecutionResult {
        debug!("[Starting a new method test]");
        let mut conditions = vec![];
        let mut policy_mode: PolicyMode = Default::default();
        let mut extra_items: Vec<ItemKind> = vec![];
        debug!("resolving the given");
        self.given.iter().for_each(|g| match g {
            Given::Setup(setup) => {
                let setup_result = setup.resolve().unwrap();
                if let Some(p) = setup_result.policy_mode {
                    policy_mode = p;
                }
                conditions.extend(setup_result.conditions);
            }
            Given::MethodCall(method) => {
                extra_items.push(method.clone().to_item_kind());
            }
        });
        debug!("generating a YAML technique");
        let test_technique = self.clone().generate_test_technique(extra_items);
        fs::write(
            workdir.join("technique.yml"),
            serde_yaml::to_string(&test_technique.clone()).unwrap(),
        )
        .unwrap();
        let resource_path = workdir.join("resources");
        debug!("compiling the technique");
        let compiled_technique_path = workdir.join("technique.cf");

        debug!("converting it to a standalone policy");
        let backend = Unix::new();
        let standalone = backend
            .generate(test_technique.clone(), resource_path.as_path(), true)
            .unwrap();

        fs::write(compiled_technique_path.clone(), standalone.clone()).unwrap();

        debug!("executing the standalone technique");
        let run_result = cf_agent(
            &compiled_technique_path,
            &compiled_technique_path,
            &library_path,
            &PathBuf::from("/opt/rudder/bin/"),
            true,
        )
        .unwrap();
        let cfengine_log_path = workdir.join("output.log");
        fs::write(cfengine_log_path.clone(), run_result.output).unwrap();
        fs::write(
            workdir.join("datastate.json"),
            serde_json::to_string(&run_result.datastate.clone()).unwrap(),
        )
        .unwrap();
        ExecutionResult {
            conditions: run_result.datastate.classes,
            variables: run_result.datastate.vars,
            reports: vec![],
        }
    }
}
