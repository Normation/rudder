// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use std::{
    fs,
    io::{BufRead, BufReader, Write},
    net::TcpListener,
    process::Command,
    time::Duration,
};

use openssl::ssl::{SslAcceptor, SslFiletype, SslMethod};
use rudder_relayd::http_client::HttpClient;

#[tokio::test]
async fn custom_ca_replaces_system_roots() {
    // Set OpenSSL's system trust store in a child so other tests never see it.
    if let Ok(url) = std::env::var("RUDDER_TEST_CA_URL") {
        let builder = || HttpClient::builder(Duration::from_secs(5));
        let other = fs::read("tests/files/keys/37817c4d-fbf7-4850-a985-50021f4e8f41.cert").unwrap();
        let server = fs::read(std::env::var_os("SSL_CERT_FILE").unwrap()).unwrap();

        for (client, expected) in [
            (builder().system().unwrap(), true),
            (builder().custom_ca(&other).unwrap(), false),
            (
                builder().custom_ca(&[other, server].concat()).unwrap(),
                true,
            ),
        ] {
            let result = client
                .inner()
                .get(&url)
                .timeout(Duration::from_secs(5))
                .send()
                .await;
            assert_eq!(result.is_ok(), expected, "{result:?}");
        }
        return;
    }

    let dir = tempfile::tempdir().unwrap();
    let key = dir.path().join("key.pem");
    let cert = dir.path().join("cert.pem");
    let generated = Command::new("openssl")
        .args([
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-days",
            "1",
            "-subj",
            "/CN=localhost",
            "-addext",
            "subjectAltName=IP:127.0.0.1",
            "-keyout",
        ])
        .arg(&key)
        .arg("-out")
        .arg(&cert)
        .output()
        .unwrap();
    assert!(
        generated.status.success(),
        "{}",
        String::from_utf8_lossy(&generated.stderr)
    );

    let mut tls = SslAcceptor::mozilla_intermediate(SslMethod::tls()).unwrap();
    tls.set_private_key_file(key, SslFiletype::PEM).unwrap();
    tls.set_certificate_file(&cert, SslFiletype::PEM).unwrap();
    let tls = tls.build();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for socket in listener.incoming().take(3) {
            if let Ok(mut stream) = tls.accept(socket.unwrap()) {
                let mut line = String::new();
                BufReader::new(&mut stream).read_line(&mut line).unwrap();
                stream
                    .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .unwrap();
            }
        }
    });
    let status = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "custom_ca_replaces_system_roots", "--nocapture"])
        .env("RUDDER_TEST_CA_URL", format!("https://{addr}/"))
        .env("SSL_CERT_FILE", cert)
        .env("NO_PROXY", "127.0.0.1")
        .status()
        .unwrap();
    assert!(status.success());
}
