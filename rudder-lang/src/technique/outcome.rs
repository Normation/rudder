// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::error::*;
use std::{fmt, str::FromStr};
use strum::{EnumIter, IntoEnumIterator};

pub enum ReturnConditionOutcome {
    Kept,
    Repaired,
    Error,
}
#[derive(EnumIter, PartialEq, Debug)]
pub enum ConditionOutcome {
    NotRepaired,
    Repaired,
    False,
    True,
    NotOk,
    Ok,
    Reached,
    Error,
    Failed,
    Denied,
    Timeout,
    Success,
    NotKept,
    Kept,
}
impl fmt::Display for ConditionOutcome {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Self::NotRepaired => "not_repaired",
                Self::Repaired => "repaired",
                Self::False => "false",
                Self::True => "true",
                Self::NotOk => "not_ok",
                Self::Ok => "ok",
                Self::Reached => "reached",
                Self::Error => "error",
                Self::Failed => "failed",
                Self::Denied => "denied",
                Self::Timeout => "timeout",
                Self::Success => "success",
                Self::NotKept => "not_kept",
                Self::Kept => "kept",
            }
        )
    }
}
// Since format can only be defined by users as string, Rudder-lang does not appear to be a supported format
impl FromStr for ConditionOutcome {
    type Err = Error;

    fn from_str(outcome: &str) -> Result<Self> {
        match outcome {
            "not_repaired" => Ok(Self::NotRepaired),
            "repaired" => Ok(Self::Repaired),
            "false" => Ok(Self::False),
            "true" => Ok(Self::True),
            "not_ok" => Ok(Self::NotOk),
            "ok" => Ok(Self::Ok),
            "reached" => Ok(Self::Reached),
            "error" => Ok(Self::Error),
            "failed" => Ok(Self::Failed),
            "denied" => Ok(Self::Denied),
            "timeout" => Ok(Self::Timeout),
            "success" => Ok(Self::Success),
            "not_kept" => Ok(Self::NotKept),
            "kept" => Ok(Self::Kept),
            _ => Err(Error::new(format!(
                "Could not parse cfengine outcome {}",
                outcome
            ))),
        }
    }
}
