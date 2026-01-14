// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::{path::PathBuf, str::FromStr};

use anyhow::{Error, bail};
use serde::{Deserialize, Serialize};

use crate::{
    CheckApplyResult, Outcome, PolicyMode, ProtocolResult, ValidateResult,
    cfengine::log::LevelFilter, parameters::Parameters, rudder_debug, rudder_error, rudder_info,
};

const ALLOWED_CHAR_CLASS: &str = "_0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
#[serde(transparent)]
pub struct Class {
    inner: String,
}

impl FromStr for Class {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        for c in s.chars() {
            if !ALLOWED_CHAR_CLASS.contains(c) {
                bail!("Unexpected char in class name: '{}' in {}", c, s);
            }
        }
        Ok(Class {
            inner: s.to_string(),
        })
    }
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone, Copy, Default)]
#[serde(rename_all = "lowercase")]
pub(crate) enum ActionPolicy {
    #[serde(alias = "nop")]
    Warn,
    #[default]
    Fix,
}

impl From<ActionPolicy> for PolicyMode {
    fn from(p: ActionPolicy) -> Self {
        match p {
            ActionPolicy::Fix => PolicyMode::Enforce,
            ActionPolicy::Warn => PolicyMode::Audit,
        }
    }
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
/// Promise validation outcomes
pub(crate) enum ValidateOutcome {
    /// Validation successful
    Valid,
    /// Validation failed, error in cfengine policy
    Invalid,
    /// Unexpected error
    Error,
}

impl From<ValidateResult> for ValidateOutcome {
    fn from(item: ValidateResult) -> Self {
        match item {
            Ok(()) => ValidateOutcome::Valid,
            Err(e) => {
                // Use Debug to show full anyhow error stack.
                rudder_error!("{:#}", e);
                ValidateOutcome::Invalid
            }
        }
    }
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
/// Promise evaluation outcomes
pub(crate) enum EvaluateOutcome {
    /// Satisfied already, no change
    Kept,
    /// Not satisfied before, but fixed
    Repaired,
    /// Not satisfied before, not fixed
    NotKept,
    /// Unexpected error
    Error,
}

impl From<CheckApplyResult> for EvaluateOutcome {
    fn from(item: CheckApplyResult) -> Self {
        match item {
            Ok(Outcome::Success(m)) => {
                if let Some(i) = m {
                    rudder_info!("{}", i);
                }
                EvaluateOutcome::Kept
            }
            Ok(Outcome::Repaired(m)) => {
                rudder_info!("{}", m);
                EvaluateOutcome::Repaired
            }
            Err(e) => {
                // Use Debug to show full anyhow error stack.
                // Only log in debug level as there is already a report written.
                rudder_debug!("{:#}", e);
                EvaluateOutcome::NotKept
            }
        }
    }
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
/// Result for init/terminate
pub(crate) enum ProtocolOutcome {
    /// Success
    Success,
    /// Error
    Failure,
    /// Unexpected error
    Error,
}

impl From<ProtocolResult> for ProtocolOutcome {
    fn from(item: ProtocolResult) -> Self {
        match item {
            ProtocolResult::Success => ProtocolOutcome::Success,
            ProtocolResult::Failure(e) => {
                // Debug as we use anyhow's Error
                rudder_error!("{:?}", e);
                ProtocolOutcome::Failure
            }
            ProtocolResult::Error(e) => {
                // Debug as we use anyhow's Error
                rudder_error!("{:?}", e);
                ProtocolOutcome::Error
            }
        }
    }
}

// Little hack for constant tags in serialized JSON
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
#[serde(rename_all = "snake_case")]
enum ValidateOperation {
    ValidatePromise,
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
#[serde(rename_all = "snake_case")]
enum EvaluateOperation {
    EvaluatePromise,
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
#[serde(rename_all = "snake_case")]
enum TerminateOperation {
    Terminate,
}

// {"operation": "validate_promise", "log_level": "info", "promise_type": "git", "promiser": "/opt/cfengine/masterfiles", "attributes": {"repo": "https://github.com/cfengine/masterfiles"}}
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub(crate) struct ValidateRequest {
    operation: ValidateOperation,
    pub(crate) log_level: LevelFilter,
    pub(crate) promiser: String,
    pub(crate) attributes: Parameters,
    pub(crate) promise_type: String,
    pub(crate) filename: PathBuf,
    pub(crate) line_number: u16,
}

// {"operation": "evaluate_promise", "log_level": "info", "promise_type": "git", "promiser": "/opt/cfengine/masterfiles", "attributes": {"repo": "https://github.com/cfengine/masterfiles"}}
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub(crate) struct EvaluateRequest {
    operation: EvaluateOperation,
    pub(crate) log_level: LevelFilter,
    pub(crate) promiser: String,
    pub(crate) attributes: Parameters,
    pub(crate) promise_type: String,
    pub(crate) filename: PathBuf,
    pub(crate) line_number: u16,
}

// {"operation": "terminate", "log_level": "info"}
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub(crate) struct TerminateRequest {
    operation: TerminateOperation,
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub(crate) enum Request {
    Validate(ValidateRequest),
    Evaluate(EvaluateRequest),
    Terminate(TerminateRequest),
}
////////////////////////////////////

// {"operation": "validate_promise", "promiser": "/opt/cfengine/masterfiles", "attributes": {"repo": "https://github.com/cfengine/masterfiles"}, "result": "valid"}
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub(crate) struct ValidateResponse {
    operation: ValidateOperation,
    promiser: String,
    attributes: Parameters,
    result: ValidateOutcome,
}

impl ValidateResponse {
    pub(crate) fn new(request: &ValidateRequest, result: ValidateOutcome) -> Self {
        Self {
            operation: ValidateOperation::ValidatePromise,
            promiser: request.promiser.clone(),
            result,
            attributes: request.attributes.clone(),
        }
    }
}

// {"operation": "evaluate_promise", "promiser": "/opt/cfengine/masterfiles", "attributes": {"repo": "https://github.com/cfengine/masterfiles"}, "result": "kept"}
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub(crate) struct EvaluateResponse {
    operation: EvaluateOperation,
    promiser: String,
    attributes: Parameters,
    result: EvaluateOutcome,
    result_classes: Vec<Class>,
}

impl EvaluateResponse {
    pub(crate) fn new(
        request: &EvaluateRequest,
        result: EvaluateOutcome,
        classes: Vec<Class>,
    ) -> Self {
        Self {
            operation: EvaluateOperation::EvaluatePromise,
            promiser: request.promiser.clone(),
            result,
            attributes: request.attributes.clone(),
            result_classes: classes,
        }
    }
}

// {"operation": "terminate", "result": "success"}
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub(crate) struct TerminateResponse {
    operation: TerminateOperation,
    result: ProtocolOutcome,
}

impl TerminateResponse {
    pub(crate) fn new(result: ProtocolOutcome) -> Self {
        Self {
            operation: TerminateOperation::Terminate,
            result,
        }
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use serde_json::{Map, Value};

    use super::*;

    #[test]
    fn it_rejects_wrong_classes() {
        assert!(Class::from_str("my class").is_err());
        assert!(Class::from_str("my_class").is_ok());
    }

    #[test]
    fn it_parses_validate_requests() {
        let val = r#"{"filename":"/tmp/test.cf","line_number": 42,"promise_type":"git","attributes":{"rudder_module_protocol": "0","temporary_dir": "","node_id": "","backup_dir": "/backup", "data": { "repo":"https://github.com/cfengine/masterfiles"} },"log_level":"info","operation":"validate_promise","promiser":"/tmp/masterfiles"}"#;
        let mut data = Map::new();
        data.insert(
            "repo".to_string(),
            Value::String("https://github.com/cfengine/masterfiles".to_string()),
        );
        let params = Parameters {
            data,
            node_id: "".to_string(),
            temporary_dir: "".into(),
            backup_dir: "/backup".into(),
            rudder_module_protocol: 0,
            action_policy: ActionPolicy::Fix,
            agent_frequency_minutes: 5,
            state_dir: "/var/rudder/cfengine-community/state/".into(),
            report_id: None,
        };
        let ref_val = ValidateRequest {
            operation: ValidateOperation::ValidatePromise,
            log_level: LevelFilter::Info,
            promiser: "/tmp/masterfiles".to_string(),
            attributes: params,
            promise_type: "git".to_string(),
            filename: PathBuf::from("/tmp/test.cf"),
            line_number: 42,
        };
        assert_eq!(
            serde_json::from_str::<ValidateRequest>(val).unwrap(),
            ref_val
        );

        let val = "{\"attributes\":{\"data\":{\"path\":\"/tmp/.tmpEX81Uv/test\",\"state\":\"present\"},\"rudder_module_protocol\":\"0\",\"node_id\":\"\"},\"filename\":\"./tests/test.cf\",\"line_number\":15,\"log_level\":\"notice\",\"operation\":\"validate_promise\",\"promise_type\":\"directory\",\"promiser\":\"test\"}";
        serde_json::from_str::<ValidateRequest>(val).unwrap();
    }

    #[test]
    fn it_parses_evaluate_requests() {
        let val = r#"{"filename":"/tmp/test.cf","line_number": 42,"promise_type":"git","attributes":{"rudder_module_protocol": "0","temporary_dir": "","backup_dir": "/backup", "node_id": "test", "data": { "repo":"https://github.com/cfengine/masterfiles"} },"log_level":"info","operation":"evaluate_promise","promiser":"/tmp/masterfiles"}"#;
        let mut data = Map::new();
        data.insert(
            "repo".to_string(),
            Value::String("https://github.com/cfengine/masterfiles".to_string()),
        );
        let params = Parameters {
            data,
            node_id: "test".to_string(),
            temporary_dir: "".into(),
            backup_dir: "/backup".into(),
            rudder_module_protocol: 0,
            action_policy: ActionPolicy::Fix,
            agent_frequency_minutes: 5,
            state_dir: "/var/rudder/cfengine-community/state/".into(),
            report_id: None,
        };
        let ref_val = EvaluateRequest {
            operation: EvaluateOperation::EvaluatePromise,
            log_level: LevelFilter::Info,
            promiser: "/tmp/masterfiles".to_string(),
            attributes: params,
            promise_type: "git".to_string(),
            filename: PathBuf::from("/tmp/test.cf"),
            line_number: 42,
        };
        assert_eq!(
            serde_json::from_str::<EvaluateRequest>(val).unwrap(),
            ref_val
        );
    }
}
