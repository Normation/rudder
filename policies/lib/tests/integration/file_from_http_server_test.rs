use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

use std::io::Write;
use std::net::TcpListener;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

/// Start a minimal HTTP server that serves a given file content
fn start_test_http_server(
    content: &'static str,
) -> (String, mpsc::Sender<()>, thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap(); // Important!

    let port = listener.local_addr().unwrap().port();
    let url = format!("http://127.0.0.1:{port}");

    let (shutdown_tx, shutdown_rx) = mpsc::channel();

    let handle = thread::spawn(move || {
        loop {
            if shutdown_rx.try_recv().is_ok() {
                break;
            }

            match listener.accept() {
                Ok((mut stream, _)) => {
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n{}",
                        content.len(),
                        content
                    );
                    let _ = stream.write_all(response.as_bytes());
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(50));
                    continue;
                }
                Err(_) => break,
            }
        }
    });

    (url, shutdown_tx, handle)
}

#[test]
fn it_should_download_file_if_not_exists() {
    let workdir = init_test();
    let file_path = workdir.path().join("downloaded.txt");
    let file_str = file_path.to_str().unwrap();

    let expected_content = "Hello from server!";
    let (url, shutdown_tx, server_thread) = start_test_http_server(expected_content);

    let tested_method = &method(
        "file_from_http_server",
        &[&format!("{url}/some.txt"), file_str],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    let _ = shutdown_tx.send(());
    let _ = server_thread.join();

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert_eq!(
        std::fs::read_to_string(file_str).unwrap(),
        expected_content,
        "The file content should match what the HTTP server sent"
    );

    end_test(workdir);
}

#[test]
fn it_should_not_download_if_file_already_exists() {
    let workdir = init_test();
    let file_path = workdir.path().join("existing.txt");
    let file_str = file_path.to_str().unwrap();

    std::fs::write(&file_path, "Already here").unwrap();

    let (url, shutdown_tx, server_thread) = start_test_http_server("This should not be downloaded");

    let tested_method = &method(
        "file_from_http_server",
        &[&format!("{url}/ignored.txt"), file_str],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, "Already here"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    let _ = shutdown_tx.send(());
    let _ = server_thread.join();

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    assert_eq!(
        std::fs::read_to_string(file_str).unwrap(),
        "Already here",
        "The file should not be replaced if it already exists"
    );

    end_test(workdir);
}

#[test]
fn it_should_fail_in_audit_if_file_is_missing() {
    let workdir = init_test();
    let file_path = workdir.path().join("missing.txt");
    let file_str = file_path.to_str().unwrap();

    let (url, shutdown_tx, server_thread) =
        start_test_http_server("This would be downloaded in enforce");

    let tested_method = &method(
        "file_from_http_server",
        &[&format!("{url}/missing.txt"), file_str],
    )
    .audit();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    let _ = shutdown_tx.send(());
    let _ = server_thread.join();
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    assert!(
        !file_path.exists(),
        "In audit mode, file should not be downloaded or created"
    );

    end_test(workdir);
}
