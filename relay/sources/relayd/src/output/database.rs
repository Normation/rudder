// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use diesel::{
    insert_into,
    pg::PgConnection,
    prelude::*,
    r2d2::{ConnectionManager, Pool},
};
use percent_encoding::NON_ALPHANUMERIC;
use secrecy::ExposeSecret;
use tracing::{debug, error, instrument, trace};

use crate::{
    configuration::main::DatabaseConfig,
    data::{
        report::QueryableReport,
        runlog::{InsertedRunlog, RunLogType},
        RunLog,
    },
    Error,
};

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
            reportid -> Text,
        }
    }

    table! {
        // (nodeid, date) is the primary key
        reportsexecution(nodeid, date) {
            nodeid -> Text,
            date -> Timestamptz,
            nodeconfigid -> Nullable<Text>,
            insertionid -> Nullable<BigInt>,
            insertiondate -> Nullable<Timestamptz>,
            compliancecomputationdate -> Nullable<Timestamptz>,
        }
    }
}

pub type PgPool = Pool<ConnectionManager<PgConnection>>;

pub fn pg_pool(configuration: &DatabaseConfig) -> Result<PgPool, Error> {
    let manager = ConnectionManager::<PgConnection>::new(format!(
        "{}{}password={}",
        configuration.url,
        // Allow options in the provided URL
        if configuration.url.contains('?') {
            "&"
        } else {
            "?"
        },
        percent_encoding::percent_encode(
            // database is selected at this point, so validation must have detected None password
            configuration
                .password
                .as_ref()
                .unwrap()
                .expose_secret()
                .as_bytes(),
            NON_ALPHANUMERIC
        )
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

pub fn ping(pool: &PgPool) -> Result<(), Error> {
    use self::schema::ruddersysevents::dsl::*;
    let connection = &mut *pool.get()?;

    let _ = ruddersysevents
        .limit(1)
        .load::<QueryableReport>(connection)?;
    Ok(())
}

#[instrument(name = "database", level = "debug", skip(pool))]
pub fn insert_runlog(pool: &PgPool, runlog: &RunLog) -> Result<RunlogInsertion, Error> {
    use self::schema::{
        reportsexecution::dsl::*,
        ruddersysevents::dsl::{nodeid, *},
    };

    let connection = &mut *pool.get()?;

    let first_report = runlog
        .reports
        .first()
        .expect("a runlog should never be empty");

    trace!(
        "Checking if first report {} is in the database",
        first_report
    );
    connection.transaction::<_, Error, _>(|connection| {
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
                    .and(reportid.eq(&first_report.report_id))
                    .and(ruleid.eq(&first_report.rule_id))
                    .and(directiveid.eq(&first_report.directive_id)),
            )
            .first::<QueryableReport>(connection)
            .optional()?
            .is_none();

        if new_runlog {
            trace!("Inserting runlog {:#?}", runlog);
            let report_id = insert_into(ruddersysevents)
                .values(&runlog.reports)
                .get_results::<QueryableReport>(connection)?
                .first()
                .expect("inserted runlog cannot be empty")
                .id;

            // Only insert full run logs into `reportsexecution`
            if runlog.log_type() == RunLogType::Complete {
                let runlog_info = InsertedRunlog::new(runlog, report_id);
                insert_into(reportsexecution)
                    .values(runlog_info)
                    .execute(connection)?;
            } else {
                debug!(
                    "The {} runlog was not inserted into 'reportsexecution' as it was not complete",
                    runlog.info
                );
            }

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
    use diesel::dsl::count;

    use super::*;
    use crate::{
        data::report::QueryableReport,
        output::database::schema::{reportsexecution::dsl::*, ruddersysevents::dsl::*},
    };

    pub fn db() -> PgPool {
        let db_config = DatabaseConfig {
            url: "postgres://rudderreports:@postgres/rudder".to_string(),
            password: Some("PASSWORD".into()),
            max_pool_size: 5,
        };
        pg_pool(&db_config).unwrap()
    }

    #[test]
    fn it_inserts_runlog() {
        let pool = db();
        let db = &mut *pool.get().unwrap();

        diesel::delete(ruddersysevents).execute(db).unwrap();
        diesel::delete(reportsexecution).execute(db).unwrap();

        let results = ruddersysevents
            .limit(1)
            .load::<QueryableReport>(db)
            .unwrap();
        assert_eq!(results.len(), 0);

        let runlog = RunLog::new(
            "tests/files/runlogs/2018-08-24T15_55_01+00_00@e745a140-40bc-4b86-b6dc-084488fc906b.log",
        )
        .unwrap();

        // Test inserting the runlog

        assert_eq!(
            insert_runlog(&pool, &runlog).unwrap(),
            RunlogInsertion::Inserted
        );

        let results = ruddersysevents
            .limit(100)
            .load::<QueryableReport>(db)
            .unwrap();
        assert_eq!(results.len(), 72);

        let results: i64 = reportsexecution
            .select(count(insertionid))
            .first(db)
            .unwrap();
        assert_eq!(results, 1);

        // Test inserting twice the same runlog

        assert_eq!(
            insert_runlog(&pool, &runlog).unwrap(),
            RunlogInsertion::AlreadyThere
        );

        let results = ruddersysevents
            .limit(100)
            .load::<QueryableReport>(db)
            .unwrap();
        assert_eq!(results.len(), 72);

        let results: i64 = reportsexecution
            .select(count(insertionid))
            .first(db)
            .unwrap();
        assert_eq!(results, 1);
    }
}
