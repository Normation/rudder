// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::hash::Hasher;

use anyhow::{Result, bail};
use chrono::{DateTime, Duration, Utc};
use fnv::FnvHasher;

/// A uniform local scheduler.
///
/// Its goal is to choose a stable run time for the campaign event between two timestamps.
///
/// Choose a start DateTime that is after `start` and before `end` - (agent_schedule + 5 min).
/// The choice is based on a hash of the unique_value to make it uniformly distributed over nodes
/// in case the `unique_value` is not uniformly distributed
/// (and we want it to be deterministic, so random is not enough).
///
/// It takes its inspiration from CFEngine's `splayclass`/`splaytime`.
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
    // Require at least a full second of usable window: the splay below computes
    // `hash % window.as_secs()`, which panics on a divide-by-zero if the window
    // rounds down to 0 seconds (real_end less than 1s after start still passes a
    // plain `real_end <= start` check).
    if real_end - start < Duration::seconds(1) {
        let window = (end - start).num_minutes();
        bail!(
            "The event schedule window is too short, the minimal schedule should be superior to \
              the agent run periodicity with an extra 6 minutes of margin. \
              Current agent run frequency is {} minutes and current window is {} minutes.",
            agent_schedule.num_minutes(),
            window
        );
    }
    let splay = Duration::seconds((hash % (real_end - start).to_std()?.as_secs()) as i64);
    let real_start = start + splay;
    Ok(real_start)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{Duration, TimeZone};
    use uuid::Uuid;

    #[test]
    fn test_splayed_start() {
        let start = Utc.with_ymd_and_hms(2022, 7, 4, 18, 40, 24).unwrap();
        let end = Utc.with_ymd_and_hms(2022, 7, 4, 20, 40, 24).unwrap();

        let splay = splayed_start(start, end, Duration::minutes(5), "root").unwrap();
        assert_eq!(splay.timestamp(), 1656961861);

        for schedule in [5, 10, 15] {
            for _i in 0..100 {
                let id = Uuid::new_v4();
                let start_s =
                    splayed_start(start, end, Duration::minutes(schedule), &id.to_string())
                        .unwrap();
                assert!(start_s >= start);
                assert!(start_s < end);
            }
        }
    }

    #[test]
    fn test_splayed_start_sub_second_window_rejected() {
        // real_end lands 500ms after start: positive, but truncates to a 0-second
        // window. Must be rejected rather than panicking on `hash % 0`.
        let start = Utc.with_ymd_and_hms(2022, 7, 4, 18, 40, 24).unwrap();
        let agent_schedule = Duration::minutes(5);
        let end = start + agent_schedule + Duration::minutes(5) + Duration::milliseconds(500);

        let res = splayed_start(start, end, agent_schedule, "root");
        assert!(
            res.is_err(),
            "a window shorter than 1s must be rejected, not panic"
        );
    }
}
