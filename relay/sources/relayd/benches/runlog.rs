use criterion::{black_box, criterion_group, criterion_main, Criterion};
use diesel;
use diesel::prelude::*;
use flate2::read::GzDecoder;
use relayd::{
    configuration::DatabaseConfig,
    data::{report::QueryableReport, RunLog},
    output::database::schema::ruddersysevents::dsl::*,
    output::database::*,
};
use std::{
    fs::{read, read_to_string},
    io::Read,
    str::FromStr,
};
use xz2::read::XzDecoder;

fn bench_parse_runlog(c: &mut Criterion) {
    let runlog = read_to_string("tests/test_gz/normal.log").unwrap();
    c.bench_function("parse runlog", move |b| {
        b.iter(|| black_box(RunLog::from_str(&runlog).unwrap()))
    });
}

// Allows comparing gzip implementations
fn bench_uncompress_runlog(c: &mut Criterion) {
    // same as in input.rs
    let data = read("tests/test_gz/normal.log.gz").unwrap();
    c.bench_function("uncompress gzip runlog", move |b| {
        b.iter(|| {
            let mut gz = GzDecoder::new(data.as_slice());
            let mut s = String::new();
            gz.read_to_string(&mut s).unwrap();
            black_box(s);
        })
    });

    let data = read("tests/test_gz/normal.log.xz").unwrap();
    c.bench_function("uncompress xz runlog", move |b| {
        b.iter(|| {
            let mut xz = XzDecoder::new(data.as_slice());
            let mut s = String::new();
            xz.read_to_string(&mut s).unwrap();
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

    let runlog = RunLog::from_str(&read_to_string("tests/runlogs/normal.log").unwrap()).unwrap();

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
    bench_parse_runlog,
    bench_uncompress_runlog,
    bench_insert_runlog
);
criterion_main!(benches);
