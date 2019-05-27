// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

#![allow(clippy::enum_glob_use)]
use self::Error::*;
use crate::data::node::NodeId;
use chrono;
use diesel;
use serde_json;
use std::{
    error::Error as StdError,
    fmt::{self, Display, Formatter},
    io, num,
    path::PathBuf,
};
use toml;

#[derive(Debug)]
pub enum Error {
    /// Unspecified parsing errors
    /// TODO add details
    InvalidRunLog,
    InvalidRunInfo(String),
    InvalidFileName,
    InvalidFile(PathBuf),
    InconsistentRunlog,
    EmptyRunlog,
    MissingIdInCertificate,
    CertificateForUnknownNode(NodeId),
    MissingCertificateForNode(NodeId),
    Unspecified(String),
    Database(diesel::result::Error),
    DatabaseConnection(diesel::ConnectionError),
    Pool(diesel::r2d2::PoolError),
    Io(io::Error),
    ConfigurationParsing(toml::de::Error),
    DateParsing(chrono::ParseError),
    JsonParsing(serde_json::Error),
    IntegerParsing(num::ParseIntError),
    Utf8(std::string::FromUtf8Error),
    Ssl(openssl::error::ErrorStack),
    InvalidCondition(String),
    ParseBoolean(std::str::ParseBoolError),
}

impl Display for Error {
    fn fmt(&self, f: &mut Formatter<'_>) -> Result<(), fmt::Error> {
        match *self {
            InvalidRunLog => write!(f, "invalid run log"),
            InvalidRunInfo(ref s) => write!(f, "invalid run info {}", s),
            InconsistentRunlog => write!(f, "inconsistent run log"),
            InvalidFileName => write!(f, "file name should be valid unicode"),
            InvalidFile(ref path) => write!(f, "received path {:#?} is not a file", path),
            EmptyRunlog => write!(f, "agent run log is empty"),
            MissingIdInCertificate => write!(f, "certificate is missing a Rudder id"),
            MissingCertificateForNode(ref node) => {
                write!(f, "no certificate known for {} node", node)
            }
            CertificateForUnknownNode(ref node) => {
                write!(f, "certificate for unknown {} node", node)
            }
            Unspecified(ref err) => write!(f, "internal error: {}", err),
            Database(ref err) => write!(f, "database error: {}", err),
            DatabaseConnection(ref err) => write!(f, "database connection error: {}", err),
            Pool(ref err) => write!(f, "database connection pool error: {}", err),
            Io(ref err) => write!(f, "I/O error: {}", err),
            ConfigurationParsing(ref err) => write!(f, "configuration parsing error: {}", err),
            DateParsing(ref err) => write!(f, "date parsing error: {}", err),
            JsonParsing(ref err) => write!(f, "json parsing error: {}", err),
            IntegerParsing(ref err) => write!(f, "integer parsing error: {}", err),
            Utf8(ref err) => write!(f, "UTF-8 decoding error: {}", err),
            Ssl(ref err) => write!(f, "Ssl error: {}", err),
            InvalidCondition(ref condition) => write!(f, "Bad agent Condition : {}", condition),
            ParseBoolean(ref err) => write!(f, "Error occurred while parsing the boolean: {}", err),
        }
    }
}

impl StdError for Error {
    fn cause(&self) -> Option<&dyn StdError> {
        match *self {
            Database(ref err) => Some(err),
            DatabaseConnection(ref err) => Some(err),
            Pool(ref err) => Some(err),
            Io(ref err) => Some(err),
            ConfigurationParsing(ref err) => Some(err),
            DateParsing(ref err) => Some(err),
            JsonParsing(ref err) => Some(err),
            IntegerParsing(ref err) => Some(err),
            Utf8(ref err) => Some(err),
            Ssl(ref err) => Some(err),
            ParseBoolean(ref err) => Some(err),
            _ => None,
        }
    }
}

impl From<diesel::result::Error> for Error {
    fn from(err: diesel::result::Error) -> Self {
        Error::Database(err)
    }
}

impl From<std::str::ParseBoolError> for Error {
    fn from(err: std::str::ParseBoolError) -> Self {
        Error::ParseBoolean(err)
    }
}

impl From<diesel::ConnectionError> for Error {
    fn from(err: diesel::ConnectionError) -> Self {
        Error::DatabaseConnection(err)
    }
}

impl From<diesel::r2d2::PoolError> for Error {
    fn from(err: diesel::r2d2::PoolError) -> Self {
        Error::Pool(err)
    }
}

impl From<String> for Error {
    fn from(string: String) -> Self {
        Error::Unspecified(string)
    }
}

impl From<io::Error> for Error {
    fn from(err: io::Error) -> Self {
        Error::Io(err)
    }
}

impl From<toml::de::Error> for Error {
    fn from(err: toml::de::Error) -> Self {
        Error::ConfigurationParsing(err)
    }
}
impl From<chrono::ParseError> for Error {
    fn from(err: chrono::ParseError) -> Self {
        Error::DateParsing(err)
    }
}

impl From<serde_json::Error> for Error {
    fn from(err: serde_json::Error) -> Self {
        Error::JsonParsing(err)
    }
}

impl From<num::ParseIntError> for Error {
    fn from(err: num::ParseIntError) -> Self {
        Error::IntegerParsing(err)
    }
}

impl From<std::string::FromUtf8Error> for Error {
    fn from(err: std::string::FromUtf8Error) -> Self {
        Error::Utf8(err)
    }
}

impl From<openssl::error::ErrorStack> for Error {
    fn from(err: openssl::error::ErrorStack) -> Self {
        Error::Ssl(err)
    }
}
