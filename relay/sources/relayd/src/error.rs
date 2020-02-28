// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::data::node::NodeId;
use chrono;
use diesel;
use serde_json;
use std::{io, num, path::PathBuf};
use thiserror::Error;
use toml;

#[derive(Debug, Error)]
pub enum Error {
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
    #[error("database error: {0}")]
    Database(#[from] diesel::result::Error),
    #[error("database connection error: {0}")]
    DatabaseConnection(#[from] diesel::ConnectionError),
    #[error("database pool error: {0}")]
    Pool(#[from] diesel::r2d2::PoolError),
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("configuration parsing error: {0}")]
    ConfigurationParsing(#[from] toml::de::Error),
    #[error("date parsing error: {0}")]
    DateParsing(#[from] chrono::ParseError),
    #[error("json parsing error: {0}")]
    JsonParsing(#[from] serde_json::Error),
    #[error("integer parsing error: {0}")]
    IntegerParsing(#[from] num::ParseIntError),
    #[error("UTF-8 decoding error: {0}")]
    Utf8(#[from] std::string::FromUtf8Error),
    #[error("UTF-8 decoding error: {0}")]
    StrUtf8(#[from] std::str::Utf8Error),
    #[error("SSL error: {0}")]
    Ssl(#[from] openssl::error::ErrorStack),
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
    #[error("boolean parsing error: {0}")]
    ParseBoolean(#[from] std::str::ParseBoolError),
    #[error("log format error: {0}")]
    LogFormat(#[from] tracing_subscriber::reload::Error),
    #[error("global logger setting error: {0}")]
    GlobalLogger(#[from] tracing::dispatcher::SetGlobalDefaultError),
    #[error("logger setting error: {0}")]
    SetLogLogger(#[from] log::SetLoggerError),
    #[error("missing target nodes")]
    MissingTargetNodes,
    #[error("invalid hash type provided {invalid:} (available hash types: {valid:})")]
    InvalidHashType {
        invalid: String,
        valid: &'static str,
    },
    #[error("invalid hash {0}")]
    InvalidHash(String),
    #[error("invalid log filter: {0}")]
    InvalidLogFilter(#[from] tracing_subscriber::filter::ParseError),
    #[error("invalid header: {0}")]
    InvalidHeader(String),
    #[error("duplicate header: {0}")]
    DuplicateHeader(String),
    #[error("missing header: {0}")]
    MissingHeader(String),
    #[error("HTTP error: {0}")]
    HttpClient(#[from] reqwest::Error),
    #[error("Invalid duration: {0}")]
    InvalidDuration(#[from] humantime::DurationError),
    #[error("Invalid hexadecimal: {0}")]
    InvalidHexadecimalValue(#[from] hex::FromHexError),
    #[error("invalid shared file: {0}")]
    InvalidSharedFile(String),
    #[error("could not extract zip file: {0}")]
    Zip(#[from] zip::result::ZipError),
}
