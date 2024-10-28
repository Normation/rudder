// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::{
    fmt::{Display, Formatter},
    str::FromStr,
};

/// State machine for the update process. Each step should be more or less atomic,
/// and the state should be saved in the database to be able to resume the process
/// in case of interruption.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum UpdateStatus {
    /// Wait until schedule
    ScheduledUpdate,
    /// Schedule is reached, start update and reboot if necessary
    RunningUpdate,
    /// Update is over, waiting for next agent run for post-actions in case of a reboot
    PendingPostActions,
    /// Running post-actions and report
    RunningPostActions,
    /// Nothing to do
    Completed,
}

impl Display for UpdateStatus {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::ScheduledUpdate => "scheduled",
            Self::RunningUpdate => "running",
            Self::Completed => "completed",
            Self::PendingPostActions => "pending-post-actions",
            Self::RunningPostActions => "running-post-actions",
        })
    }
}

impl FromStr for UpdateStatus {
    type Err = std::io::Error;

    fn from_str(s: &str) -> anyhow::Result<Self, Self::Err> {
        match s {
            "running" => Ok(Self::RunningUpdate),
            "scheduled" => Ok(Self::ScheduledUpdate),
            "completed" => Ok(Self::Completed),
            "pending-post-actions" => Ok(Self::PendingPostActions),
            "running-post-actions" => Ok(Self::RunningPostActions),
            _ => Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "Invalid input",
            )),
        }
    }
}
