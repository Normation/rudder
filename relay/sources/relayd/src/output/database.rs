// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

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
        ruddersysevents {
            id -> BigInt,
            executiondate -> Timestamptz,
            nodeid -> Text,
            directiveid -> Text,
            ruleid -> Text,
            serial -> Integer,
            component -> Text,
            keyvalue -> Nullable<Text>,
            executiontimestamp -> Nullable<Timestamptz>,
            eventtype -> Nullable<Text>,
            policy -> Nullable<Text>,
            msg -> Nullable<Text>,
            detail -> Nullable<Text>,
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
    use self::schema::ruddersysevents::dsl::*;

    // TODO test presence of runlog before inserting

    let connection = &*pool.get()?;
    connection.transaction::<_, Error, _>(|| {
        for report in &runlog.reports {
            insert_into(ruddersysevents)
                .values(report)
                .execute(connection)?;
        }
        Ok(())
    })
}
