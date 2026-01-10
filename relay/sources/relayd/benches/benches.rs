// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    convert::TryFrom,
    fs::{read, read_to_string},
    io::Read,
    str::FromStr,
};

use criterion::{criterion_group, criterion_main, Criterion};
use diesel::{self, prelude::*};
use flate2::read::GzDecoder;
use openssl::x509::store::X509StoreBuilder;
use openssl::{stack::Stack, x509::X509};
use rudder_relayd::{
    configuration::main::DatabaseConfig,
    data::{node::NodesList, report::QueryableReport, RunInfo, RunLog},
    input::verify_signature,
    output::database::{
        schema::{reportsexecution::dsl::*, ruddersysevents::dsl::*},
        *,
    },
};
use std::hint::black_box;

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
        "tests/files/runlogs/2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log",
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
    let data = read("tests/files/smime/normal.signed").unwrap();

    let x509 = X509::from_pem(
        read_to_string("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert")
            .unwrap()
            .as_bytes(),
    )
    .unwrap();
    let mut certs = Stack::new().unwrap();
    certs.push(x509).unwrap();

    c.bench_function("verify runlog signature", move |b| {
        b.iter(|| {
            black_box(
                verify_signature(&data, &certs, &X509StoreBuilder::new().unwrap().build()).unwrap(),
            );
        })
    });
}

// Allows comparing gzip implementations
fn bench_uncompress_runlog(c: &mut Criterion) {
    // same as in input.rs
    let data = read("tests/files/gz/normal.log.gz").unwrap();
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
        url: "postgres://rudderreports@127.0.0.1/rudder".to_string(),
        password: Some("PASSWORD".into()),
        max_pool_size: 10,
    };
    pg_pool(&db_config).unwrap()
}

fn bench_insert_runlog(c: &mut Criterion) {
    let pool = db();
    let db = &mut *pool.get().unwrap();

    diesel::delete(reportsexecution).execute(db).unwrap();
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

    c.bench_function("insert runlog", move |b| {
        b.iter(|| {
            assert_eq!(
                insert_runlog(&pool, &runlog).unwrap(),
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
