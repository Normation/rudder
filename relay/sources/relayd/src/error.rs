// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::data::node::NodeId;
use std::path::PathBuf;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum RudderError {
    #[error("invalid run log: {0}")]
    InvalidRunLog(String),
    #[error("invalid run info: {0}")]
    InvalidRunInfo(String),
    #[error("file name should be valid unicode")]
    InvalidFileName,
    #[error("received path {0:?} is not a file")]
    InvalidFile(PathBuf),
    #[error("inconsistent run log")]
    InconsistentRunlog,
    #[error("empty run log")]
    EmptyRunlog,
    #[error("missing id in certificate")]
    MissingIdInCertificate,
    #[error("certificate for unknown node: {0}")]
    CertificateForUnknownNode(NodeId),
    #[error("missing certificate for node: {0}")]
    MissingCertificateForNode(NodeId),
    #[error("unknown node: {0}")]
    UnknownNode(NodeId),
    #[error("invalid condition: {condition:}, should match {condition_regex:}")]
    InvalidCondition {
        condition: String,
        condition_regex: &'static str,
    },
    #[error("invalid condition: {condition:}, should have less then {max_length:} chars")]
    MaxLengthCondition {
        condition: String,
        max_length: usize,
    },
    #[error("missing target nodes")]
    MissingTargetNodes,
    #[error("invalid hash type provided {invalid:} (available hash types: {valid:})")]
    InvalidHashType {
        invalid: String,
        valid: &'static str,
    },
    #[error("invalid hash {0}")]
    InvalidHash(String),
    #[error("invalid header: {0}")]
    InvalidHeader(String),
    #[error("duplicate header: {0}")]
    DuplicateHeader(String),
    #[error("missing header: {0}")]
    MissingHeader(String),
    #[error("invalid shared file: {0}")]
    InvalidSharedFile(String),
}
