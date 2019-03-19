use crate::{configuration::DatabaseConfig, data::reporting::RunLog, error::Error};
use diesel::{
    insert_into,
    pg::PgConnection,
    prelude::*,
    r2d2::{ConnectionManager, Pool},
};

pub mod schema {
    table! {
        use diesel::sql_types::*;

        // Needs to be kept in sync with the database schema
        rudder_sys_events {
            id -> BigInt,
            executionDate -> Timestamptz,
            nodeId -> Text,
            directiveId -> Text,
            ruleId -> Text,
            serial -> Integer,
            component -> Text,
            keyValue -> Nullable<Text>,
            executionTimeStamp -> Nullable<Timestamptz>,
            eventType -> Nullable<Text>,
            policy -> Nullable<Text>,
            msg -> Nullable<Text>,
        }
    }
}

pub type PgPool = Pool<ConnectionManager<PgConnection>>;

pub fn pg_pool(configuration: &DatabaseConfig) -> Result<PgPool, Error> {
    let manager = ConnectionManager::<PgConnection>::new(configuration.url.as_ref());
    Ok(Pool::builder()
        .max_size(configuration.max_pool_size)
        .build(manager)?)
}

pub fn insert_runlog(pool: &PgPool, runlog: &RunLog) -> Result<(), Error> {
    use self::schema::rudder_sys_events::dsl::*;

    let connection = &*pool.get()?;
    connection.transaction::<_, Error, _>(|| {
        for report in &runlog.reports {
            insert_into(rudder_sys_events)
                .values(report)
                .execute(connection)?;
        }
        Ok(())
    })
}
