// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use crate::SchedulerParameters;
use crate::db::EventDatabase;
use chrono::{DateTime, Utc};
use rudder_module_type::cfengine::protocol::Class;
use rudder_module_type::rudder_info;
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EventSchedule {
    /// Run once as soon as possible when conditions are met
    Once,
    /// Run always when conditions are met
    Always,
    /// Never run
    Never,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum EventSituation {
    /// The event is in the past
    Past,
    /// The event should run now
    Now,
    /// The event is in the future
    Future,
    /// The event should never run
    Never,
}

impl Display for EventSchedule {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            EventSchedule::Once => "once",
            EventSchedule::Always => "always",
            EventSchedule::Never => "never",
        };
        f.write_str(s)
    }
}

/// Information about an event stored in the database
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct Event {
    pub(crate) id: String,
    pub(crate) schedule_id: String,
    pub(crate) name: String,
    // No need to have an enum here, as the type is only informative
    // Keep it simple and extensible
    #[serde(rename = "type")]
    pub(crate) schedule_type: String,
    pub(crate) schedule: EventSchedule,
    pub(crate) not_before: Option<DateTime<Utc>>,
    pub(crate) not_after: Option<DateTime<Utc>>,
}

impl Event {
    #[cfg(test)]
    pub fn new(
        id: String,
        schedule_id: String,
        name: String,
        e_type: String,
        schedule: EventSchedule,
        not_before: Option<DateTime<Utc>>,
        not_after: Option<DateTime<Utc>>,
    ) -> Self {
        Self {
            id,
            schedule_id,
            name,
            schedule_type: e_type,
            schedule,
            not_before,
            not_after,
        }
    }

    fn planning(&self, now: DateTime<Utc>, has_run: bool) -> EventSituation {
        if self.schedule == EventSchedule::Never {
            return EventSituation::Never;
        }
        if self.schedule == EventSchedule::Once && has_run {
            return EventSituation::Past;
        }
        if let Some(na) = self.not_after
            && now > na
        {
            return EventSituation::Past;
        }
        if let Some(nb) = self.not_before
            && now < nb
        {
            return EventSituation::Future;
        }
        EventSituation::Now
    }

    fn should_run(&self, now: DateTime<Utc>, has_run: bool) -> bool {
        self.planning(now, has_run) == EventSituation::Now
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Events {
    events: Vec<Event>,
}

impl From<SchedulerParameters> for Events {
    fn from(parameters: SchedulerParameters) -> Self {
        Self {
            events: parameters.events,
        }
    }
}

impl Events {
    pub fn update(self, db: &mut EventDatabase, now: DateTime<Utc>) -> anyhow::Result<Vec<Class>> {
        // TODO remove canceled events in db

        let mut classes = Vec::new();
        for e in self.events {
            let eid = &e.id;
            db.insert_if_new(&e, now)?;
            let has_run = db.get_run_time(eid)?.is_some();

            if e.should_run(now, has_run) {
                rudder_info!(
                    "Scheduling event '{}' (id '{}') for schedule '{}'",
                    e.name,
                    eid,
                    e.schedule_id
                );
                let class = Class::canonify(&format!("schedule_{}", e.schedule_id));
                classes.push(class);
                // TODO transaction
                db.run_event(eid, now)?;
            }
        }
        Ok(classes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;
    use std::fs::read_to_string;

    #[test]
    fn test_event_should_run() {
        let now = Utc::now();

        let event = Event::new(
            "1".to_string(),
            "sched1".to_string(),
            "Test Event".to_string(),
            "type1".to_string(),
            EventSchedule::Always,
            Some(now - Duration::minutes(5)),
            Some(now + Duration::minutes(5)),
        );
        assert_eq!(event.planning(now, true), EventSituation::Now);

        let event2 = Event::new(
            "2".to_string(),
            "sched2".to_string(),
            "Test Event 2".to_string(),
            "type2".to_string(),
            EventSchedule::Always,
            Some(now + Duration::minutes(5)),
            Some(now + Duration::minutes(10)),
        );
        assert_eq!(event2.planning(now, true), EventSituation::Future);

        let event3 = Event::new(
            "3".to_string(),
            "sched3".to_string(),
            "Test Event 3".to_string(),
            "type3".to_string(),
            EventSchedule::Always,
            Some(now - Duration::minutes(10)),
            Some(now - Duration::minutes(5)),
        );
        assert_eq!(event3.planning(now, true), EventSituation::Past);

        let event4 = Event::new(
            "4".to_string(),
            "sched4".to_string(),
            "Test Event 4".to_string(),
            "type4".to_string(),
            EventSchedule::Always,
            None,
            None,
        );
        assert_eq!(event4.planning(now, true), EventSituation::Now);
    }

    #[test]
    fn test_update() {
        let mut db = EventDatabase::new(None).unwrap();
        let now = Utc::now();

        let events: Events =
            serde_json::from_str(&read_to_string("tests/events.json").unwrap()).unwrap();
        let class_always = Class::canonify("schedule_df6ebe63_a13a_484f_9add_57836517947a");
        let class_once = Class::canonify("schedule_e27fd139_5961_4cb2_8314_1d16ddf0de92");

        let classes = events.clone().update(&mut db, now).unwrap();
        assert_eq!(classes, vec![class_once, class_always.clone()]);

        // Running update again should not run the "once" event again
        let classes2 = events.clone().update(&mut db, now).unwrap();
        assert_eq!(classes2, vec![class_always.clone()]);

        // Running update again should not run the "once" event again
        let classes3 = events.update(&mut db, now).unwrap();
        assert_eq!(classes3, vec![class_always]);
    }
}
