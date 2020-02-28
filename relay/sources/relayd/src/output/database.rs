// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    configuration::main::DatabaseConfig,
    data::{report::QueryableReport, RunLog},
    Error,
};
use diesel::{
    insert_into,
    pg::PgConnection,
    prelude::*,
    r2d2::{ConnectionManager, Pool},
};
use tracing::{debug, error, span, trace, Level};

pub mod schema {
    table! {
        use diesel::sql_types::*;

        // Needs to be kept in sync with the database schema
        ruddersysevents {
            id -> BigInt,
            executiondate -> Timestamptz,
            ruleid -> Text,
            directiveid -> Text,
            component -> Text,
            keyvalue -> Nullable<Text>,
            eventtype -> Nullable<Text>,
            msg -> Nullable<Text>,
            policy -> Nullable<Text>,
            nodeid -> Text,
            executiontimestamp -> Nullable<Timestamptz>,
            serial -> Integer,
        }
    }
}

pub type PgPool = Pool<ConnectionManager<PgConnection>>;

pub fn pg_pool(configuration: &DatabaseConfig) -> Result<PgPool, Error> {
    let manager = ConnectionManager::<PgConnection>::new(format!(
        "{}?password={}",
        configuration.url,
        configuration.password.value()
    ));
    Ok(Pool::builder()
        .max_size(configuration.max_pool_size)
        .build(manager)?)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RunlogInsertion {
    Inserted,
    AlreadyThere,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InsertionBehavior {
    SkipDuplicate,
    AllowDuplicate,
}

pub fn ping(pool: &PgPool) -> Result<(), Error> {
    use self::schema::ruddersysevents::dsl::*;
    let connection = &*pool.get()?;

    let _ = ruddersysevents
        .limit(1)
        .load::<QueryableReport>(connection)?;
    Ok(())
}

pub fn insert_runlog(
    pool: &PgPool,
    runlog: &RunLog,
    behavior: InsertionBehavior,
) -> Result<RunlogInsertion, Error> {
    use self::schema::ruddersysevents::dsl::*;
    let report_span = span!(Level::TRACE, "database");
    let _report_enter = report_span.enter();

    let connection = &*pool.get()?;

    let first_report = runlog
        .reports
        .first()
        .expect("a runlog should never be empty");

    trace!(
        "Checking if first report {} is in the database",
        first_report
    );
    connection.transaction::<_, Error, _>(|| {
        let new_runlog = ruddersysevents
            .filter(
                component
                    .eq(&first_report.component)
                    .and(nodeid.eq(&first_report.node_id))
                    .and(keyvalue.eq(&first_report.key_value))
                    .and(eventtype.eq(&first_report.event_type))
                    .and(msg.eq(&first_report.msg))
                    .and(policy.eq(&first_report.policy))
                    .and(executiontimestamp.eq(&first_report.start_datetime))
                    .and(executiondate.eq(&first_report.execution_datetime))
                    .and(serial.eq(&first_report.serial))
                    .and(ruleid.eq(&first_report.rule_id))
                    .and(directiveid.eq(&first_report.directive_id)),
            )
            .limit(1)
            .load::<QueryableReport>(connection)?
            .is_empty();

        if behavior == InsertionBehavior::AllowDuplicate || new_runlog {
            trace!("Inserting runlog {:#?}", runlog);
            insert_into(ruddersysevents)
                .values(&runlog.reports)
                .execute(connection)?;
            Ok(RunlogInsertion::Inserted)
        } else {
            error!(
                "The {} runlog was already there, skipping insertion",
                runlog.info
            );
            debug!(
                "The report that was already present in database is: {}",
                first_report
            );
            Ok(RunlogInsertion::AlreadyThere)
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        configuration::Secret, data::report::QueryableReport,
        output::database::schema::ruddersysevents::dsl::*,
    };
    use diesel;

    pub fn db() -> PgPool {
        let db_config = DatabaseConfig {
            url: "postgres://rudderreports:@127.0.0.1/rudder".to_string(),
            password: Secret::new("PASSWORD".to_string()),
            max_pool_size: 5,
        };
        pg_pool(&db_config).unwrap()
    }

    #[test]
    fn it_inserts_runlog() {
        let pool = db();
        let db = &*pool.get().unwrap();

        diesel::delete(ruddersysevents).execute(db).unwrap();
        let results = ruddersysevents
            .limit(1)
            .load::<QueryableReport>(db)
            .unwrap();
        assert_eq!(results.len(), 0);

        let runlog = RunLog::new(
            "tests/files/runlogs/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log",
        )
        .unwrap();

        // Test inserting the runlog

        assert_eq!(
            insert_runlog(&pool, &runlog, InsertionBehavior::SkipDuplicate).unwrap(),
            RunlogInsertion::Inserted
        );

        let results = ruddersysevents
            .limit(100)
            .load::<QueryableReport>(db)
            .unwrap();
        assert_eq!(results.len(), 71);

        // Test inserting twice the same runlog

        assert_eq!(
            insert_runlog(&pool, &runlog, InsertionBehavior::SkipDuplicate).unwrap(),
            RunlogInsertion::AlreadyThere
        );

        let results = ruddersysevents
            .limit(100)
            .load::<QueryableReport>(db)
            .unwrap();
        assert_eq!(results.len(), 71);
    }
}
