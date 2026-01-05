use crate::{
    RebootType,
    campaign::{
        FullSchedule, RunnerParameters, do_post_update, do_schedule, do_update, fail_campaign,
    },
    db::PackageDatabase,
    package_manager::UpdateManager,
    scheduler,
    state::UpdateStatus,
    system::System,
};
use anyhow::{Result, bail};
use chrono::{DateTime, Utc};
use rudder_module_type::Outcome;

#[derive(PartialEq, Debug, Clone, Copy)]
enum Action {
    Schedule(DateTime<Utc>, bool),
    Update,
    PostUpdate,
}

#[derive(PartialEq, Debug, Clone)]
enum Continuation {
    Continue,
    Stop(Outcome),
}

pub struct Runner {
    db: PackageDatabase,
    pm: Box<dyn UpdateManager>,
    parameters: RunnerParameters,
    system: Box<dyn System>,
    pid: u32,
}

impl Runner {
    pub(crate) fn new(
        db: PackageDatabase,
        pm: Box<dyn UpdateManager>,
        parameters: RunnerParameters,
        system: Box<dyn System>,
        pid: u32,
    ) -> Self {
        Self {
            db,
            pm,
            parameters,
            system,
            pid,
        }
    }

    fn update_one_step(&mut self, state: Option<UpdateStatus>) -> Result<Continuation> {
        let maybe_action = self.decide(state)?;
        match maybe_action {
            None => Ok(Continuation::Stop(Outcome::Success(None))),
            Some(action) => {
                // TODO add method to handle dead process
                let current_pid = self.db.lock(self.pid, &self.parameters.event_id)?;
                if current_pid.is_some() {
                    // Could not acquire lock => no action
                    return Ok(Continuation::Stop(Outcome::Success(None)));
                }
                let cont = self.execute(action)?;
                self.db.unlock(&self.parameters.event_id)?;
                Ok(cont)
            }
        }
    }

    fn execute(&mut self, action: Action) -> Result<Continuation> {
        match action {
            Action::Schedule(datetime, is_started) => {
                let outcome = do_schedule(&self.parameters, &mut self.db, datetime)?;
                if is_started {
                    // If already in the window, don't send the schedule but
                    // continue instead.
                    Ok(Continuation::Continue)
                } else {
                    Ok(Continuation::Stop(outcome))
                }
            }
            Action::Update => {
                let reboot_needed =
                    do_update(&self.parameters, &mut self.db, &mut self.pm, &self.system)?;

                if self.parameters.reboot_type == RebootType::Always
                    || (self.parameters.reboot_type == RebootType::AsNeeded && reboot_needed)
                {
                    // Async reboot
                    let result = self.system.reboot();
                    match result.inner {
                        Ok(_) => Ok(Continuation::Stop(Outcome::Success(None))),
                        Err(e) => bail!("Reboot failed: {:?}", e),
                    }
                } else {
                    Ok(Continuation::Continue)
                }
            }
            Action::PostUpdate => {
                self.db.post_event(&self.parameters.event_id)?;
                let outcome = do_post_update(&self.parameters, &mut self.db)?;
                Ok(Continuation::Stop(outcome))
            }
        }
    }

    fn decide(&mut self, current_status: Option<UpdateStatus>) -> Result<Option<Action>> {
        let now = Utc::now();
        let schedule_datetime = match self.parameters.schedule {
            FullSchedule::Immediate => now,
            FullSchedule::Scheduled(ref s) => {
                scheduler::splayed_start(s.start, s.end, s.agent_frequency, s.node_id.as_str())?
            }
        };
        let is_started = now >= schedule_datetime;

        match current_status {
            None => Ok(Some(Action::Schedule(schedule_datetime, is_started))),
            Some(s) => match s {
                UpdateStatus::ScheduledUpdate => {
                    if is_started {
                        Ok(Some(Action::Update))
                    } else {
                        Ok(None)
                    }
                }
                UpdateStatus::PendingPostActions => Ok(Some(Action::PostUpdate)),
                _ => Ok(None),
            },
        }
    }

    /// Entry point
    pub fn run(&mut self) -> Result<Outcome> {
        loop {
            let status = self.db.get_status(&self.parameters.event_id)?;
            let res = self.update_one_step(status);
            match res {
                Ok(Continuation::Continue) => {}
                Ok(Continuation::Stop(o)) => {
                    return Ok(o);
                }
                Err(e) => {
                    // Send the report to server
                    fail_campaign(&format!("{e:?}"), self.parameters.report_file.as_ref())?;
                    return Err(e);
                }
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use crate::package_manager::PackageId;
    use crate::{
        RebootType, Schedule, ScheduleParameters,
        campaign::{FullCampaignType, FullSchedule, RunnerParameters},
        db::PackageDatabase,
        output::ResultOutput,
        package_manager::{PackageList, UpdateManager},
        runner::{Action, Runner},
        state::UpdateStatus,
        system::System,
    };
    use chrono::{Duration, Utc};
    use pretty_assertions::assert_eq;
    use rudder_module_type::Outcome;
    use std::collections::HashMap;

    struct MockSystem {}

    impl System for MockSystem {
        fn reboot(&self) -> ResultOutput<()> {
            ResultOutput::new(Ok(()))
        }

        fn restart_services(&self, _services: &[String]) -> ResultOutput<()> {
            ResultOutput::new(Ok(()))
        }
    }

    #[derive(Default, Clone, Copy)]
    struct MockPackageManager {
        reboot_needed: bool,
    }

    impl UpdateManager for MockPackageManager {
        fn list_installed(&mut self) -> ResultOutput<PackageList> {
            ResultOutput::new(Ok(PackageList::new(HashMap::new())))
        }

        fn upgrade(
            &mut self,
            _update_type: &FullCampaignType,
        ) -> ResultOutput<Option<HashMap<PackageId, String>>> {
            ResultOutput::new(Ok(None))
        }

        fn reboot_pending(&self) -> ResultOutput<bool> {
            ResultOutput::new(Ok(self.reboot_needed))
        }

        fn services_to_restart(&self) -> ResultOutput<Vec<String>> {
            ResultOutput::new(Ok(vec![]))
        }
    }

    fn mock_package_manager(reboot_needed: bool) -> MockPackageManager {
        MockPackageManager { reboot_needed }
    }

    fn in_memory_package_db() -> PackageDatabase {
        PackageDatabase::new(None).unwrap()
    }

    fn test_runner(should_reboot: bool, p: RunnerParameters) -> Runner {
        Runner::new(
            in_memory_package_db(),
            Box::new(mock_package_manager(should_reboot)),
            p,
            Box::new(MockSystem {}),
            0,
        )
    }

    fn default_runner_parameters() -> RunnerParameters {
        RunnerParameters {
            campaign_type: FullCampaignType::SystemUpdate,
            event_id: "".to_string(),
            campaign_name: "".to_string(),
            schedule: FullSchedule::Immediate,
            reboot_type: RebootType::Disabled,
            report_file: None,
            schedule_file: None,
        }
    }

    fn future_schedule() -> FullSchedule {
        let start = Utc::now() + Duration::days(10);
        let end = start + Duration::days(1);
        let schedule = Schedule::Scheduled(ScheduleParameters { start, end });
        FullSchedule::new(&schedule, "id".to_string(), Duration::minutes(5))
    }

    #[test]
    pub fn initial_state_with_immediate_schedule_should_return_schedule() {
        let parameters = default_runner_parameters();
        match test_runner(false, parameters).decide(None).unwrap() {
            Some(Action::Schedule(_, true)) => {}
            _ => panic!(),
        }
    }

    #[test]
    pub fn initial_state_with_future_schedule_should_return_schedule() {
        let parameters = RunnerParameters {
            schedule: future_schedule(),
            ..default_runner_parameters()
        };
        match test_runner(false, parameters).decide(None).unwrap() {
            Some(Action::Schedule(_, false)) => {}
            _ => panic!(),
        }
    }

    #[test]
    pub fn scheduled_update_state_with_immediate_schedule_should_return_update() {
        let parameters = default_runner_parameters();
        assert_eq!(
            test_runner(false, parameters)
                .decide(Some(UpdateStatus::ScheduledUpdate))
                .unwrap(),
            Some(Action::Update)
        );
    }

    #[test]
    pub fn scheduled_update_state_with_future_schedule_should_return_no_action() {
        let parameters = RunnerParameters {
            schedule: future_schedule(),
            ..default_runner_parameters()
        };

        assert_eq!(
            test_runner(false, parameters)
                .decide(Some(UpdateStatus::ScheduledUpdate))
                .unwrap(),
            None
        );
    }

    #[test]
    pub fn update_loop_with_immediate_schedule_should_complete() {
        let parameters = default_runner_parameters();
        assert_eq!(
            test_runner(false, parameters).run().unwrap(),
            Outcome::Repaired("Update has run".to_string())
        );
    }

    #[test]
    pub fn update_loop_with_immediate_schedule_and_reboot_should_stop_on_pending_post_actions() {
        let parameters = RunnerParameters {
            reboot_type: RebootType::Always,
            ..default_runner_parameters()
        };
        assert_eq!(
            test_runner(false, parameters).run().unwrap(),
            Outcome::Success(None)
        );
    }

    #[test]
    pub fn update_loop_with_immediate_schedule_and_reboot_as_needed_should_stop_on_pending_post_actions()
     {
        let parameters = RunnerParameters {
            reboot_type: RebootType::AsNeeded,
            ..default_runner_parameters()
        };
        assert_eq!(
            test_runner(true, parameters).run().unwrap(),
            Outcome::Success(None)
        );
    }

    #[test]
    pub fn update_loop_with_future_schedule_should_stay_in_scheduled_update() {
        let parameters = RunnerParameters {
            schedule: future_schedule(),
            ..default_runner_parameters()
        };
        assert_eq!(
            test_runner(false, parameters).run().unwrap(),
            Outcome::Repaired("Schedule has been sent".to_string())
        );
    }

    #[test]
    pub fn update_loop_with_immediate_schedule_and_reboot_called_twice_should_complete() {
        let parameters = RunnerParameters {
            reboot_type: RebootType::Always,
            ..default_runner_parameters()
        };
        let mut scheduler = test_runner(false, parameters);
        scheduler.run().unwrap();
        let actual = scheduler.run().unwrap();
        assert_eq!(actual, Outcome::Repaired("Update has run".to_string()));
    }

    #[test]
    pub fn update_loop_with_immediate_schedule_and_reboot_as_needed_called_twice_should_complete() {
        let parameters = RunnerParameters {
            reboot_type: RebootType::AsNeeded,
            ..default_runner_parameters()
        };
        let mut scheduler = test_runner(true, parameters);
        scheduler.run().unwrap();
        let actual = scheduler.run().unwrap();
        assert_eq!(actual, Outcome::Repaired("Update has run".to_string()));
    }
}
