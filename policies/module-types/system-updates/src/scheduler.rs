// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{bail, Result};
use chrono::{DateTime, Duration, Utc};
use fnv::FnvHasher;
use std::hash::Hasher;

/// Simple local scheduler
///
/// Its goal is to choose a stable run time for the campaign event between two timestamps.
///
/// Choose a start DateTime that is after `start` and before `end` - (agent_schedule + 5min).
/// The choice is based on a hash of the unique_value to make it uniformly distributed over nodes.
pub fn splayed_start(
    start: DateTime<Utc>,
    end: DateTime<Utc>,
    agent_schedule: Duration,
    unique_value: &str,
) -> Result<DateTime<Utc>> {
    let mut hasher = FnvHasher::default();
    hasher.write(unique_value.as_bytes());
    let hash = hasher.finish();

    let real_end = end - (agent_schedule + Duration::minutes(5));
    if real_end <= start {
        let campaign_window = (end - start).num_minutes();
        bail!("Campaign execution schedule is too short, the minimal schedule should be superior to \
              the agent run periodicity with an extra 6 minutes of margin.
              Current agent run frequency is {} minutes and current campaign schedule is {} minutes.",
        agent_schedule, campaign_window);
    }
    let splay = Duration::seconds((hash % (real_end - start).to_std()?.as_secs()) as i64);
    let real_start = start + splay;
    Ok(real_start)
}
