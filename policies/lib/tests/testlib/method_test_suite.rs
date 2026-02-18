// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::testlib::given::Given;
use crate::testlib::given::setup_state::TestSetup;
use crate::testlib::method_to_test::MethodToTest;
use crate::testlib::test_result::ExecutionResult;
use agent::{FakeAgentBuilder, Verbosity};
use anyhow::Context;
use indexmap::IndexMap;
use rudder_commons::PolicyMode;
use rudder_commons::report::Report;
#[cfg(not(feature = "test-windows"))]
use rudderc::backends::unix::Unix;
use rudderc::backends::unix::cfengine::CfAgentResult;
use rudderc::backends::{Backend, Windows};
use rudderc::generate_directive::GenerateDirective;
use rudderc::ir::Technique;
use rudderc::ir::technique::{ItemKind, TechniqueId};
use std::fs;
use std::path::{Path, PathBuf};
use std::str::FromStr;
use tracing::debug;

pub const DIRECTIVE_ID: &str = "bae000000-0000-4000-a000-000000000000";
pub const WINDOWS_LIB_FOLDER: &str = "./ncf";
#[derive(Clone)]
pub struct MethodTestSuite {
    given: Vec<Given>,
    when: Vec<MethodToTest>,
    directive_id: String,
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
            directive_id: DIRECTIVE_ID.to_string(),
        }
    }

    pub fn given(&self, g: Given) -> MethodTestSuite {
        let mut v = self.given.clone();
        v.push(g);
        MethodTestSuite {
            given: v,
            when: self.when.clone(),
            directive_id: self.directive_id.clone(),
        }
    }

    pub fn when(&self, nm: &MethodToTest) -> MethodTestSuite {
        let mut v = self.when.clone();
        v.push(nm.clone());
        MethodTestSuite {
            given: self.given.clone(),
            when: v,
            directive_id: self.directive_id.clone(),
        }
    }

    pub fn generate_test_technique(&self, extra_items: Vec<ItemKind>) -> Technique {
        let mut items = extra_items;
        items.extend(self.when.iter().map(|nm| nm.clone().to_item_kind()));
        let technique_name = "method_test_technique";
        Technique {
            format: 0,
            id: TechniqueId::from_str(technique_name).unwrap(),
            name: technique_name.to_string(),
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

    fn prepare_execution(&self, workdir: &Path) -> Technique {
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
        debug!("compiling the technique");
        #[cfg(not(feature = "test-windows"))]
        let technique_file_path = workdir.join("technique.cf");
        #[cfg(feature = "test-windows")]
        let technique_file_path = workdir.join("technique.ps1");
        debug!("converting it to a standalone policy");
        let backend = Windows::new();
        let standalone = backend
            .generate(
                test_technique.clone(),
                workdir.join("resources").as_path(),
                false,
            )
            .unwrap();

        fs::write(technique_file_path.clone(), standalone.clone()).unwrap();
        test_technique
    }

    pub fn execute(self, _library_folder: PathBuf, workdir: PathBuf) -> ExecutionResult {
        let technique = self.prepare_execution(&workdir);
        #[cfg(feature = "test-windows")]
        let policy_library_folder = PathBuf::from(WINDOWS_LIB_FOLDER)
            .canonicalize()
            .expect("The library folder does not exist");
        #[cfg(not(feature = "test-windows"))]
        let policy_library_folder = _library_folder;
        let fake_agent = FakeAgentBuilder::new(workdir.clone())
            .verbosity(Verbosity::Verbose)
            .library_folder(policy_library_folder)
            .build();
        #[cfg(feature = "test-windows")]
        let backend = Windows::new();
        #[cfg(not(feature = "test-windows"))]
        let backend = Unix::new();
        debug!(
            "generate a directive file to {}",
            fake_agent.technique_path().display()
        );
        fs::write(
            fake_agent.directive_path(),
            backend
                .generate_directive(
                    technique.clone(),
                    DIRECTIVE_ID,
                    "rule_id",
                    IndexMap::new(),
                    PolicyMode::Enforce,
                )
                .context("Could not generate the directive content")
                .unwrap(),
        )
        .context("Could not write the directive file")
        .unwrap();

        let resource_path = workdir.join("resources");
        debug!(
            "compile the Yaml technique file to {}",
            fake_agent.technique_path().display()
        );
        // Dot not generate a standalone as we build everything separately
        fs::write(
            fake_agent.technique_path().clone(),
            backend
                .generate(technique.clone(), resource_path.as_path(), false)
                .context("Could not compile the Yaml technique")
                .unwrap(),
        )
        .context("Could not write the compile technique file")
        .unwrap();

        let test_conf = fake_agent.config();
        let raw_exec = fake_agent.run().context("Running fake agent").unwrap();
        let stdout = String::from_utf8_lossy(&raw_exec.stdout);
        let stderr = String::from_utf8_lossy(&raw_exec.stderr);
        debug!("stderr:\n{}", stderr);
        let run_log = Report::parse(&stdout)
            .context("Could not parse the agent output")
            .unwrap();
        debug!("agent output: {}", stdout);
        let raw_datastate = &fs::read_to_string(&test_conf.datastate_file_path)
            .expect("Could not read the datastate file");
        let run_result = CfAgentResult {
            runlog: run_log,
            datastate: serde_json::from_str(raw_datastate).unwrap(),
            output: stdout.to_string(),
        };
        fs::write(workdir.join("output.log"), run_result.output).unwrap();
        ExecutionResult {
            directive_id: DIRECTIVE_ID.to_string(),
            conditions: run_result.datastate.classes,
            variables: run_result.datastate.vars,
            reports: vec![],
        }
    }
}
