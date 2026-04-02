// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

/*!
 * The main scheduler code
 */

use crate::configuration::{Configuration, ScheduleConfiguration};
use crate::{ExitType, ServiceMessage, configuration};
use anyhow::{Result, bail};
use chrono::{DateTime, Local, NaiveTime, SubsecRound, TimeDelta};
use log::{debug, error, info, warn};
use std::cmp::{max, min};
use std::hash::{DefaultHasher, Hash, Hasher};
use std::path::Path;
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tokio::process::{Child, Command};
use tokio::runtime::Builder;
use tokio::sync::mpsc::Receiver;
use tokio::time;
use tokio::time::timeout;
use uuid::Uuid;

/// One schedule, this decides when an item must be scheduled.
///
/// Having an enum here allows creating other scheduler types.
#[derive(Debug)]
enum Schedule {
    /// An interval schedule,  used for scheduling using periods starting at 00:00:00 every day
    /// Each period has an allowed run interval within it. For example:
    /// - every 5mn, between minute 2 and minute 3
    ///
    /// Everything is in seconds for easier handling
    Interval {
        /// How many seconds between 2 runs
        period: u64,
        /// How many seconds after the period starts we can run
        interval_begin: u64,
        /// How many second after interval_begin we cannot run anymore
        interval_duration: u64,
    },
}

impl Schedule {
    fn from_schedule_configuration(
        name: &str,
        schedule: &ScheduleConfiguration,
    ) -> anyhow::Result<Schedule> {
        // Currently assume that only Schedule::Interval exists
        let period = schedule.period.as_secs();
        if !(60..=24 * 60 * 60).contains(&period) {
            bail!(
                "{} configuration: schedule period is not between 1mn and 24h",
                name
            );
        }
        let interval_begin = schedule.interval_begin.as_secs();
        let interval_end = match schedule.interval_end {
            Some(e) => e.as_secs(),
            None => period,
        };
        if interval_end > period {
            bail!(
                "{} configuration: interval end must be less than period",
                name
            );
        }
        if interval_begin > interval_end {
            bail!(
                "{} configuration: interval end must be after interval begin",
                name
            );
        }
        Ok(Schedule::Interval {
            period,
            interval_begin,
            interval_duration: interval_end - interval_begin,
        })
    }

    /// return None if next run is not calculable for the moment
    /// passing now simplifies testing and make calculation more precise
    fn next_run(&self, now: DateTime<Local>, seed: u64) -> Option<DateTime<Local>> {
        match self {
            Schedule::Interval {
                period,
                interval_begin,
                interval_duration,
            } => {
                let seconds_from_midnight = (now.time()
                    - NaiveTime::from_hms_opt(0, 0, 0).expect("00:00:00 invalid"))
                .num_seconds();
                // subtracting 0 should have produced a positive number anyway
                let period_id = max(seconds_from_midnight, 0) as u64 / period;
                let period_start = period_id * period;
                let interval_start = period_start + interval_begin;
                let current_start = interval_start + seed % interval_duration;
                let next_start = if current_start as i64 > seconds_from_midnight {
                    current_start
                } else {
                    // we already passed the start of the current interval
                    current_start + period
                };
                info!("next_start {}", next_start);
                // if there is a calculation error (or if it falls tomorrow) return nothing
                let final_time =
                    NaiveTime::from_num_seconds_from_midnight_opt(next_start as u32, 0)?;
                // if the result is ambiguous in the timezone, take the first one,
                // if it is impossible, return nothing
                now.with_time(final_time).earliest()
            }
        }
    }
}

/// One command to be scheduled
/// It contains the command parameters, and the schedule itself
#[derive(Debug)]
struct ScheduleItem {
    /// Name used for information (from the configuration section name)
    name: String,
    /// The command to run
    command: String,
    /// When to schedule this command
    schedule: Schedule,
    /// Maximum duration of the command before being killed
    max_execution_duration: Duration,
    /// Maximum concurrent execution of this command by the service
    max_concurrent_executions: usize,
}

/// One event that has been planned within a short term
/// This is necessary because we schedule in wall time (see scheduling algorithm for more information)
#[derive(Debug)]
struct Event {
    /// When it should happen in wall time
    when: DateTime<Local>,
    /// Position in the schedules vector
    // we use id because it is easier to use than Arc
    // and the service configuration is static after startup
    id: usize,
}

/// Describe how to run a scheduled command on the target OS
#[derive(Debug)]
pub struct CommandBuilder {
    /// The shell to use if any
    shell: Option<&'static str>,
    /// Arguments to the shell
    args: Vec<&'static str>,
    /// false if the shell expect the command to be a single string (arg to bash -c)
    split_cmd: bool,
}

impl CommandBuilder {
    pub fn new(
        shell: Option<&'static str>,
        args: Vec<&'static str>,
        split_cmd: bool,
    ) -> CommandBuilder {
        CommandBuilder {
            shell,
            args,
            split_cmd,
        }
    }

    fn spawn(&self, command: &str, reference: &str) -> Result<Child> {
        let command = command.trim();
        if command.is_empty() {
            bail!("Command for {} is empty", reference);
        }
        let mut cmd = match self.shell {
            Some(shell) => {
                let mut cmd = Command::new(shell);
                cmd.args(self.args.clone());
                if self.split_cmd {
                    // cannot be empty
                    let it = command.split_whitespace();
                    for arg in it {
                        cmd.arg(arg);
                    }
                } else {
                    cmd.arg(command);
                }
                cmd
            }
            None => {
                let mut it = command.split_whitespace();
                // unwrap allowed because command cannot be empty
                let first = it.next().expect("BUG: command is empty");
                let mut cmd = Command::new(first);
                for arg in it {
                    cmd.arg(arg);
                }
                cmd
            }
        };

        let child = cmd
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()?;
        Ok(child)
    }
}

/// A command builder that should work for anything that do not need a shell
impl Default for CommandBuilder {
    fn default() -> Self {
        CommandBuilder {
            shell: None,
            args: vec![],
            split_cmd: true,
        }
    }
}

/// The main scheduler object that waits for the proper time and runs commands
#[derive(Debug)]
pub struct Scheduler {
    /// Stable hash serving as the schedule seed (based on agent uuid)
    uuid_hash: u64,
    /// Command builder for target OS
    command_builder: CommandBuilder,
    /// List of commands to be scheduled
    schedules: Vec<ScheduleItem>,
    /// Count of children process currently running
    children: Vec<AtomicUsize>,
}

impl Scheduler {
    /// Result::Err should be a fatal error
    pub fn from_configuration_file(
        path: &Path,
        uuid_file: &Path,
        command_builder: CommandBuilder,
    ) -> anyhow::Result<Scheduler> {
        let configuration = configuration::Configuration::from_file(path)?;
        let uuid = configuration::read_uuid(uuid_file)?;
        Self::from_configuration(configuration, uuid, command_builder)
    }

    pub fn from_configuration(
        configuration: Configuration,
        uuid: Uuid,
        command_builder: CommandBuilder,
    ) -> anyhow::Result<Scheduler> {
        let mut hasher = DefaultHasher::new();
        uuid.hash(&mut hasher);
        let uuid_hash = hasher.finish();
        let mut scheduler = Scheduler {
            uuid_hash,
            command_builder,
            schedules: Vec::new(),
            children: Vec::new(),
        };
        for (name, schedule_conf) in configuration.schedules {
            // there's only one schedulable type, assume IntervalSchedulable for now
            let schedule = Schedule::from_schedule_configuration(&name, &schedule_conf)?;
            let ScheduleConfiguration {
                command,
                period: _,
                interval_begin: _,
                interval_end: _,
                max_execution_duration,
                max_concurrent_executions,
            } = schedule_conf;
            let item = ScheduleItem {
                name,
                command,
                schedule,
                max_execution_duration,
                max_concurrent_executions,
            };
            scheduler.schedules.push(item);
            scheduler.children.push(AtomicUsize::new(0));
        }
        Ok(scheduler)
    }

    /// A command rudder task (one per command)
    // the command is an id, see `struct Event` for explanation
    async fn run_command(self: Arc<Self>, id: usize) {
        let item = &self.schedules[id];

        // detect if we reached maximum runs
        let count = self.children[id].load(Ordering::Relaxed);
        if count >= item.max_concurrent_executions {
            return;
        }

        // prepare and run the command
        let mut child = match self.command_builder.spawn(&item.command, &item.name) {
            Ok(child) => child,
            Err(e) => {
                error!("Could not run command '{}' because {}", item.command, e);
                return;
            }
        };

        // Store child info (nothing more is needed ATM since everything is handled inside this function
        self.children[id].fetch_add(1, Ordering::Relaxed);

        // wait for command termination
        match timeout(item.max_execution_duration, child.wait()).await {
            Ok(Ok(status)) => {
                // wait properly finished
                if !status.success() {
                    info!("Command {} exited with status {}", item.command, status);
                } else {
                    // TODO, on linux, if the process received a signal, this might not be the proper status
                    error!("Command {} exited with status {}", item.command, status);
                }
            }
            Ok(Err(_e)) => {
                // wait timeouted
                match child.kill().await {
                    // TODO should we timeout the kill
                    Ok(()) => {
                        warn!(
                            "Command {} killed by timeout after {}s",
                            item.command,
                            item.max_execution_duration.as_secs_f64()
                        );
                    }
                    Err(e) => {
                        error!("Failed to kill command {}: {}", item.command, e);
                    }
                }
            }
            Err(e) => {
                // wait couldn't even start
                error!(
                    "Could detect end of command '{}' because {}",
                    item.command, e
                );
            }
        }
        self.children[id].fetch_sub(1, Ordering::Relaxed);
    }

    /// The main schedule task
    async fn schedule(self: Arc<Self>) {
        /// How long do we sleep when we know there is nothing to do
        const WAKE_UP_DELAY_S: i64 = 60;
        /// How many seconds do we allow time drift per sleep
        const ACCEPTABLE_DRIFT_S: i64 = 10; // > 1s is mandatory to avoid sleep rounding error
        /// How long do we allow the system to oversleep or suspend when preparing to run a command
        const ACCEPTABLE_OVERSLEEP_S: i64 = 30;

        // a vector is largely enough for small amount of schedules
        let mut events: Vec<Event> = Vec::new();
        loop {
            // Wall time scheduling algorithm
            // ------------------------------
            // We cannot sleep in wall time duration.
            // So, we wake up regularly to make sure wall time has not drifted.
            // If next run is in less than the wake-up delay we queue it and wait for the precise duration.
            // If we oversleep for an acceptable delay, we run the command.
            // Otherwise, we skip the event.
            //
            // As a consequence:
            // * If there is nothing to do, we are mostly asleep
            // * We "try" to be precise to the second but might not succeed
            // * If there is an NTP drift we won't miss the run (maximum negative drift = min(ACCEPTABLE_DRIFT_S,ACCEPTABLE_OVERSLEEP_S))
            // * If we oversleep because of rounding effect (or NTP drift) we won't miss the run (ACCEPTABLE_OVERSLEEP_S maximum)
            // * If there is a big time change (datectl) or a machine suspend we skip the runs (ACCEPTABLE_OVERSLEEP_S minimum)
            // * The run frequency cannot be more than 1/WAKE_UP_DELAY_S

            // use a common now() for calculation to have a precise count
            let now = Local::now();
            let next_second = now.trunc_subsecs(0) + TimeDelta::seconds(1);
            let mut next_sleep = TimeDelta::seconds(WAKE_UP_DELAY_S);

            // check event queue
            events.retain(|evt| {
                // event will not trigger before next second
                if evt.when >= next_second {
                    next_sleep = min(next_sleep, evt.when - next_second);
                    true

                // event must trigger within a second or has trigger not too long ago
                } else if evt.when >= now - TimeDelta::seconds(ACCEPTABLE_OVERSLEEP_S) {
                    // spawn a task to run the command
                    // yield so we don't need to take into account the time needed to run the command
                    // and the task can wait by itself for the end of the command
                    info!("Running command {:?}", self.schedules[evt.id]);
                    // TODO yield
                    tokio::spawn(self.clone().run_command(evt.id));
                    false

                // event has trigger too long ago
                } else {
                    // just drop the event
                    warn!("TODO WARN event dropped {:?}", self.schedules[evt.id]);
                    false
                }
            });

            // check schedules
            for i in 0..self.schedules.len() {
                // ignore events that are already scheduled
                if events.iter().any(|evt| evt.id == i) {
                    continue;
                }

                let schedule = &self.schedules[i].schedule;
                // date has to be in the future
                let next_run = match schedule.next_run(now, self.uuid_hash) {
                    Some(t) => t,
                    None => continue, // no event
                };
                info!("Found event at {}", next_run);
                if next_run - now < TimeDelta::zero() {
                    error!(
                        "Next run calculated event in the past : {} for {:?}",
                        next_run, schedule
                    );
                } else if next_run - now < TimeDelta::seconds(WAKE_UP_DELAY_S + ACCEPTABLE_DRIFT_S)
                {
                    let event = Event {
                        when: next_run,
                        id: i,
                    };
                    events.push(event);
                    next_sleep = min(next_sleep, next_run - now);
                }
            }

            // probably not useful as long as we have only one or 2 schedules
            let time_lost = Local::now() - now;

            debug!(
                "Current time is {}, waiting for {}, time lost {}",
                Local::now(),
                next_sleep,
                time_lost
            );
            time::sleep((next_sleep - time_lost).to_std().unwrap_or(Duration::ZERO)).await
        }
    }

    /// The main loop run by the service, handles tokio an all its tasks
    pub fn main_loop(self, mut receiver: Receiver<ServiceMessage>) -> ExitType {
        // Initialize tokio. We do it here because:
        // - it is a common place between linux and windows
        // - it cannot be done before because system service main cannot be async
        Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("Cannot build tokio runtime")
            .block_on(async {
                // one task/interval for running commands (wait with wall time)
                tokio::spawn(Arc::new(self).schedule());

                // one task/timer for terminating commands after timeout (wait with duration)
                // one task per command waiting for command termination
                // (optional) one task  per command waiting and logging output

                // main task is waiting for instructions from the task manager
                loop {
                    match receiver.recv().await {
                        Some(ServiceMessage::Stop) => {
                            info!("Shutdown signal receivedX");
                            // TODO kill tasks
                            return ExitType::Ok;
                        }
                        None => {
                            // wait ended for unknown reason, try again
                        }
                    }
                }
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn time(h: u32, m: u32, s: u32) -> DateTime<Local> {
        Local::now()
            .with_time(NaiveTime::from_hms_opt(h, m, s).unwrap())
            .single()
            .unwrap()
    }

    #[test]
    fn test_schedule_interval() {
        let schedule = Schedule::Interval {
            period: 120,
            interval_begin: 50,
            interval_duration: 50,
        };
        let midnight = time(0, 0, 0);
        let before_interval = time(1, 0, 10);
        let in_interval = time(1, 0, 55);
        let after_interval = time(1, 1, 55);
        let before_midnight = time(23, 59, 55);

        let seed = 0;
        assert_eq!(
            schedule.next_run(midnight, seed),
            Some(time(0, 0, 50)),
            "midnight run, seed {}",
            seed
        );
        assert_eq!(
            schedule.next_run(before_interval, seed),
            Some(time(1, 0, 50)),
            "before_interval, seed {}",
            seed
        );
        assert_eq!(
            schedule.next_run(in_interval, seed),
            Some(time(1, 2, 50)),
            "in_interval, seed {}",
            seed
        );
        assert_eq!(
            schedule.next_run(after_interval, seed),
            Some(time(1, 2, 50)),
            "after_interval, seed {}",
            seed
        );
        assert_eq!(
            schedule.next_run(before_midnight, seed),
            None,
            "before_midnight, seed {}",
            seed
        );

        let seed = 75;
        assert_eq!(
            schedule.next_run(midnight, seed),
            Some(time(0, 1, 15)),
            "midnight run, seed {}",
            seed
        );
        assert_eq!(
            schedule.next_run(before_interval, seed),
            Some(time(1, 1, 15)),
            "before_interval, seed {}",
            seed
        );
        assert_eq!(
            schedule.next_run(in_interval, seed),
            Some(time(1, 1, 15)),
            "in_interval, seed {}",
            seed
        );
        assert_eq!(
            schedule.next_run(after_interval, seed),
            Some(time(1, 3, 15)),
            "after_interval, seed {}",
            seed
        );
        assert_eq!(
            schedule.next_run(before_midnight, seed),
            None,
            "before_midnight, seed {}",
            seed
        );
    }
}
