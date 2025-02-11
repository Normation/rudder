// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use rudder_commons::PolicyMode;
use rudderc::ir::technique::ItemKind;

/// Initial test environment to create before running a test
#[derive(Debug, Default)]
pub struct TestSetupResult {
    pub conditions: Vec<String>,
    pub method_calls: Vec<ItemKind>,
    pub policy_mode: Option<PolicyMode>,
}
impl TestSetupResult {
    pub fn push_condition(&mut self, condition: String) {
        let mut v = self.conditions.clone();
        v.push(condition);
        self.conditions = v;
    }

    pub fn push_method_call(&mut self, method: ItemKind) {
        self.method_calls.push(method);
    }

    pub fn policy_mode(&mut self, mode: PolicyMode) {
        self.policy_mode = Some(mode);
    }
}
