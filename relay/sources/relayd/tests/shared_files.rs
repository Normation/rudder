// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    fs::{read_to_string, remove_file},
    str::FromStr,
    thread,
};

use crate::common::random_ports;
use rudder_relayd::{
    configuration::cli::CliConfiguration, data::shared_file::Metadata, init_logger, start,
};

mod common;

#[test]
fn it_shares_files() {
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);
    let (api_port, https_port) = random_ports();

    thread::spawn(move || {
        start(
            cli_cfg,
            init_logger().unwrap(),
            Some((api_port, https_port)),
        )
        .unwrap();
    });
    assert!(common::start_api(api_port).is_ok());

    let client = reqwest::blocking::Client::new();

    // .sign created with:
    // tools/rudder-sign tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.priv tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.pub "node1.rudder.local"

    // curl --head http://127.0.0.1:3030/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=dda78e9b97a69aca3cff21de266246bde0d91bc4b61df72bfb0387564ac0c7bd64dd4caca39ce1ef400f32aa711ec4909789705beec93314eb65fabd5183bbfe

    let hashes_are_equal = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=dda78e9b97a69aca3cff21de266246bde0d91bc4b61df72bfb0387564ac0c7bd64dd4caca39ce1ef400f32aa711ec4909789705beec93314eb65fabd5183bbfe"))
            .send()
            .unwrap();

    assert_eq!(200, hashes_are_equal.status());

    let hashes_are_not_equal = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=dda78e9b97a69aca3cff21de266246bde0d91bc4b61df72bfb0387564ac0c7bd64dd4caca39ce1ef400f32aa711ec4909789705beec93314eb65fabd5183bbf3"))
            .send()
            .unwrap();

    assert_eq!(404, hashes_are_not_equal.status());

    let hashes_are_invalid = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash=0xcafecafecafe"))
            .send()
            .unwrap();

    assert_eq!(404, hashes_are_invalid.status());

    let no_hash_sent = client
            .head(format!(
                "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file?hash="))
            .send()
            .unwrap();

    assert_eq!(404, no_hash_sent.status());

    // prepare body content
    // .sign created with:
    // tools/rudder-sign tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2 tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.priv tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.pub "node1.rudder.local"

    let file = "tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/files/e745a140-40bc-4b86-b6dc-084488fc906b/file2";
    let signature = read_to_string(format!("{file}.sign")).unwrap();
    let content = read_to_string(format!("{file}.source")).unwrap();

    // Wrong digest

    let wrong_signature = read_to_string(format!("{file}.wrongsign")).unwrap();
    let upload = client.put(format!(
        "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2?ttl=1d")).body(format!("{wrong_signature}\n{content}"))
        .send().unwrap();
    assert_eq!(500, upload.status());

    let upload = client.put(format!(
        "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2?ttl=1d")).body(format!("{}\n{}", signature, "test"))
        .send().unwrap();
    assert_eq!(500, upload.status());

    // Correct upload

    let upload = client.put(format!(
        "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2?ttl=1d")).body(format!("{signature}\n{content}"))
        .send().unwrap();
    assert_eq!(200, upload.status());

    let mut written_metadata =
        Metadata::from_str(&read_to_string(format!("{file}.metadata")).unwrap()).unwrap();
    let expiration = (chrono::Utc::now() + chrono::Duration::days(1)).timestamp();
    // Check expiration is correct
    assert!((expiration - written_metadata.expires.unwrap()).abs() < 500);
    written_metadata.expires = None;

    let source_metadata =
        Metadata::from_str(&read_to_string(format!("{file}.sign")).unwrap()).unwrap();

    // Check uploaded file
    assert_eq!(
        read_to_string(file).unwrap(),
        read_to_string(format!("{file}.source")).unwrap()
    );
    // Check metadata file
    assert_eq!(source_metadata, written_metadata);

    // Remove leftover
    remove_file(file).unwrap();
    remove_file(format!("{file}.metadata")).unwrap();
}

/// The metadata of a shared file is stored as `<file_id>.metadata`, so an upload using
/// such a file id would overwrite the metadata of another file of the same source.
#[test]
fn it_refuses_a_file_id_colliding_with_a_metadata_file() {
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);
    let (api_port, https_port) = random_ports();

    thread::spawn(move || {
        start(
            cli_cfg,
            init_logger().unwrap(),
            Some((api_port, https_port)),
        )
        .unwrap();
    });
    assert!(common::start_api(api_port).is_ok());

    let client = reqwest::blocking::Client::new();

    let dir = "tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/files/e745a140-40bc-4b86-b6dc-084488fc906b";
    // Targets the metadata of the `file` shared file
    let file_id = "file.metadata";

    let signature = read_to_string(format!("{dir}/file2.sign")).unwrap();
    let content = read_to_string(format!("{dir}/file2.source")).unwrap();
    let victim = read_to_string(format!("{dir}/file.metadata")).unwrap();

    let upload = client.put(format!(
        "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/{file_id}?ttl=1d"))
        .body(format!("{signature}\n{content}"))
        .send().unwrap();
    // An invalid id is a client error
    assert_eq!(400, upload.status());

    // The metadata of the `file` shared file is left untouched
    assert_eq!(
        victim,
        read_to_string(format!("{dir}/file.metadata")).unwrap()
    );
}

/// The uploaded metadata may already contain an `expires` header. It must not end up
/// duplicated in the stored metadata, which would make it unparsable, and hence prevent
/// the file from ever expiring.
#[test]
fn it_overrides_client_provided_expiration() {
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);
    let (api_port, https_port) = random_ports();

    thread::spawn(move || {
        start(
            cli_cfg,
            init_logger().unwrap(),
            Some((api_port, https_port)),
        )
        .unwrap();
    });
    assert!(common::start_api(api_port).is_ok());

    let client = reqwest::blocking::Client::new();

    let dir = "tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/files/e745a140-40bc-4b86-b6dc-084488fc906b";
    // A file id of its own, to avoid interfering with the other tests, containing a dot
    // as it is allowed and common in practice.
    let file_id = "file3.properties";

    let signature = read_to_string(format!("{dir}/file2.sign")).unwrap();
    let content = read_to_string(format!("{dir}/file2.source")).unwrap();
    // The signature only covers the file content, so adding a header does not invalidate it.
    let body = format!("{signature}expires=1580941341\n\n{content}");

    let upload = client.put(format!(
        "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/{file_id}?ttl=1d"))
        .body(body)
        .send().unwrap();
    assert_eq!(200, upload.status());

    let raw = read_to_string(format!("{dir}/{file_id}.metadata")).unwrap();

    // Exactly one expiration date, the one computed by the server from the ttl
    assert_eq!(1, raw.lines().filter(|l| l.starts_with("expires=")).count());
    let metadata = Metadata::from_str(&raw).unwrap();
    let expiration = (chrono::Utc::now() + chrono::Duration::days(1)).timestamp();
    assert!((expiration - metadata.expires.unwrap()).abs() < 500);

    // The stored metadata is still usable to answer HEAD requests
    let head = client.head(format!(
        "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/{file_id}?hash={}",
        metadata.hash.hex()))
        .send().unwrap();
    assert_eq!(200, head.status());

    // Remove leftover
    remove_file(format!("{dir}/{file_id}")).unwrap();
    remove_file(format!("{dir}/{file_id}.metadata")).unwrap();
}

#[test]
fn it_rejects_invalid_ttl_values() {
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);
    let (api_port, https_port) = random_ports();

    thread::spawn(move || {
        start(
            cli_cfg,
            init_logger().unwrap(),
            Some((api_port, https_port)),
        )
        .unwrap();
    });
    assert!(common::start_api(api_port).is_ok());

    let client = reqwest::blocking::Client::new();

    let file = "tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/files/e745a140-40bc-4b86-b6dc-084488fc906b/file2";
    let signature = read_to_string(format!("{file}.sign")).unwrap();
    let content = read_to_string(format!("{file}.source")).unwrap();

    let too_large_ttl = client.put(format!(
        "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2?ttl={}", u64::MAX
    )).body(format!("{signature}\n{content}"))
        .send().unwrap();
    assert_eq!(400, too_large_ttl.status());

    let invalid_ttl = client
        .put(format!(
            "http://localhost:{api_port}/rudder/relay-api/1/shared-files/37817c4d-fbf7-4850-a985-50021f4e8f41/e745a140-40bc-4b86-b6dc-084488fc906b/file2?ttl=not-a-duration"
        ))
        .body(format!("{signature}\n{content}"))
        .send()
        .unwrap();
    assert_eq!(400, invalid_ttl.status());
}
