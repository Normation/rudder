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

use criterion::{black_box, criterion_group, criterion_main, Criterion};
use diesel::{self, prelude::*};
use flate2::read::GzDecoder;
use openssl::{stack::Stack, x509::X509};
use relayd::data::node::NodesList;
use relayd::{
    configuration::main::DatabaseConfig,
    data::{report::QueryableReport, RunInfo, RunLog},
    input::signature,
    output::database::{schema::ruddersysevents::dsl::*, *},
};
use std::{
    convert::TryFrom,
    fs::{read, read_to_string},
    io::Read,
    str::FromStr,
};

fn bench_nodeslist(c: &mut Criterion) {
    c.bench_function("parse nodes list", move |b| {
        b.iter(|| {
            assert!(black_box(NodesList::new(
                "root".to_string(),
                "benches/files/nodeslist.json",
                None
            ))
            .is_ok())
        })
    });
}

fn bench_nodeslist_certs(c: &mut Criterion) {
    c.bench_function("parse nodes list and certificates", move |b| {
        b.iter(|| {
            assert!(black_box(NodesList::new(
                "root".to_string(),
                "benches/files/nodeslist.json",
                Some("benches/files/allnodescerts.pem")
            ))
            .is_ok())
        })
    });
}

fn bench_parse_runlog(c: &mut Criterion) {
    let runlog = read_to_string(
        "tests/runlogs/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log",
    )
    .unwrap();
    let info =
        RunInfo::from_str("2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log")
            .unwrap();
    c.bench_function("parse runlog", move |b| {
        b.iter(|| black_box(RunLog::try_from((info.clone(), runlog.as_ref())).unwrap()))
    });
}

fn bench_signature_runlog(c: &mut Criterion) {
    let data = read("tests/test_smime/normal.signed").unwrap();

    let x509 = X509::from_pem(
        read_to_string("tests/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert")
            .unwrap()
            .as_bytes(),
    )
    .unwrap();
    let mut certs = Stack::new().unwrap();
    certs.push(x509).unwrap();

    c.bench_function("verify runlog signature", move |b| {
        b.iter(|| {
            black_box(signature(&data, &certs).unwrap());
        })
    });
}

// Allows comparing gzip implementations
fn bench_uncompress_runlog(c: &mut Criterion) {
    // same as in input.rs
    let data = read("tests/test_gz/normal.log.gz").unwrap();
    c.bench_function("uncompress runlog", move |b| {
        b.iter(|| {
            let mut gz = GzDecoder::new(data.as_slice());
            let mut s = String::new();
            gz.read_to_string(&mut s).unwrap();
            black_box(s);
        })
    });
}

pub fn db() -> PgPool {
    let db_config = DatabaseConfig {
        url: "postgres://rudderreports:PASSWORD@127.0.0.1/rudder".to_string(),
        max_pool_size: 10,
    };
    pg_pool(&db_config).unwrap()
}

fn bench_insert_runlog(c: &mut Criterion) {
    let pool = db();
    let db = &*pool.get().unwrap();

    diesel::delete(ruddersysevents).execute(db).unwrap();
    let results = ruddersysevents
        .limit(1)
        .load::<QueryableReport>(db)
        .unwrap();
    assert_eq!(results.len(), 0);

    let runlog = RunLog::new(
        "tests/runlogs/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log",
    )
    .unwrap();

    // Test inserting the runlog

    c.bench_function("insert runlog", move |b| {
        b.iter(|| {
            assert_eq!(
                insert_runlog(&pool, &runlog, InsertionBehavior::AllowDuplicate).unwrap(),
                RunlogInsertion::Inserted
            );
        })
    });

    let results = ruddersysevents
        .limit(1)
        .load::<QueryableReport>(db)
        .unwrap();
    assert_eq!(results.len(), 1);
}

criterion_group!(
    benches,
    bench_nodeslist,
    bench_nodeslist_certs,
    bench_uncompress_runlog,
    bench_signature_runlog,
    bench_parse_runlog,
    bench_insert_runlog
);
criterion_main!(benches);
