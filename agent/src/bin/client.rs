///! This binary will :
///! - have its stdin/stdout/stderr used by rsync client
///! - open a http/connect socket to the daemon
///! - eventually use a TLS authentication with the daemon
///! - transmit data with bidirectional connection

use std::io;
use std::io::prelude::*;
use std::net::TcpStream;
use std::io::BufReader;
use std::fmt::Debug;
use std::thread;
use std::env;
use std::ffi::OsString;
use std::fs;
use std::path::PathBuf;
use std::process::exit;
use std::sync::mpsc;
use std::sync::mpsc::{Receiver, TryRecvError};

use openssl::ssl::{Ssl, SslAcceptor, SslConnector, SslFiletype, SslMethod, SslStream, SslVerifyMode};
use openssl::x509::store::X509StoreBuilder;
use openssl::x509::X509;
use tracing::{Level, event};
use serde::Deserialize;
use serde_inline_default::serde_inline_default;
use anyhow::{anyhow, bail, Context, Result};

use agent::{BUFFER_SIZE, get_config, init_tracing, LogLevel, TL_SIZE};

const CONFIG_PATH: &str = "/tmp/original.toml";
const DEBUG_CONFIG_PATH: &str = "/tmp/test.toml";

// TODO review defaults
#[serde_inline_default]
#[derive(Deserialize, Debug)]
struct Config {
    // port to connect to remote server in HTTPS
    #[serde_inline_default("443".into())]
    https_port: String,

    // TLS verify cert
    #[serde_inline_default(false)]
    verify_https_cert: bool,

    // TLS certificate trust (None = system truststore)
    #[serde_inline_default(None)]
    https_ca_path: Option<PathBuf>,

    // local port on the remote server (should not be changed)
    #[serde_inline_default("873".into())]
    target_port: String,

    // Check authentication server certificate
    #[serde_inline_default(false)]
    authentication_verify: bool,

    // Specify authentication CA (used to trust server cert)
    #[serde_inline_default(Some(PathBuf::from("/root/ca.pem")))]
    authentication_ca: Option<PathBuf>,

    // Specify authentication cert
    #[serde_inline_default(Some(PathBuf::from("/root/client-cert.pem")))]
    authentication_certificate: Option<PathBuf>,

    // Specify authentication cert key
    #[serde_inline_default(Some(PathBuf::from("/root/client-key.pem")))]
    authentication_key: Option<PathBuf>,

    // Log level (default debug in debug build)
    #[cfg(debug_assertions)]
    #[serde_inline_default(LogLevel(Level::TRACE))]
    log_level: LogLevel,

    // Log level (default info in release build)
    #[cfg(not(debug_assertions))]
    #[serde_inline_default(LogLevel(Level::INFO))]
    log_level: LogLevel,
}

/// main with auto error printing
#[tracing::instrument]
fn main() -> Result<()> {
    // startup
    let config: Config = get_config(CONFIG_PATH, DEBUG_CONFIG_PATH)?;
    init_tracing(&config.log_level);
    event!(Level::TRACE, "Configuration {:?}", config);

    // initiate connection
    let (host, command) = get_command()?;
    let stream = connect(&config, host)?;

    // Line based, HTTP part of the protocol (proxy through apache to daemon)
    let mut read_stream = BufReader::new(stream);
    let target = String::from("localhost:") + &config.target_port;
    write_proxy_header(read_stream.get_mut(), &target)?;
    read_proxy_response(&mut read_stream)?;

    // Connect with ssl to daemon
    let stream = read_stream.into_inner();
    let mut read_stream = BufReader::new(ssl_connect(stream, &config)?);

    // Line based, daemon part of the protocol (run more rsync)
    read_stream.get_mut().write_all(command.as_encoded_bytes())?;
    read_command_response(&mut read_stream)
        .with_context(|| format!("Remote rsync run failed '{}'", command.to_string_lossy()))?;

    // Asynchronous part of the protocol (once rsync can start communicating)
    read_stream.get_mut().get_mut().get_mut().set_nonblocking(true)?;

    // Since stdin cannot be put into non blocking mode, we must read it in a separate thread
    let stdin = spawn_stdin_channel();

    // loop over communication channel to to transmit bytes when needed
    let mut stderr = io::stderr();
    let mut stdout = io::stdout();
    io::stderr().write_all("Starting\n".as_bytes())?;
    loop {
        let mut processed_data = false;
        processed_data |= proxy_stdin(&stdin, read_stream.get_mut())?;
        processed_data |= proxy_stderrout(&mut read_stream, &mut stdout, &mut stderr)?;
        if !processed_data {
            // if we didn't process data, we let the CPU do other things before coming back
            thread::yield_now();
        }
    }
    // exit will be called from inside the loop when needed, so this is expected
}

/// Get command line arguments as passed by rsync
#[tracing::instrument]
fn get_command() -> Result<(OsString, OsString)> {
    let mut args = env::args_os();
    args.next(); // skip self path
    let host = args.next().ok_or_else(|| anyhow!("Must be run by rsync (host argument missing)"))?;
    let command = args.next().ok_or_else(|| anyhow!("Must be run by rsync (command arguments missing)"))?;
    let mut command = args.fold(command, |mut a1,a2| { a1.push(" "); a1.push(a2); a1 });
    command.push("\n");
    Ok((host, command))
}

/// Connect with TLS to HTTPS endpoint
#[tracing::instrument]
fn connect(config: &Config, host: OsString) -> Result<SslStream<TcpStream>> {
    // use a string to allow for evolution (like ipv6 ...)
    let connect_string = String::from(host.to_string_lossy()) + ":" + &config.https_port;
    let stream = TcpStream::connect(&connect_string)
        .with_context(|| format!("Unable to connect to {}", connect_string))?;

    // Configure TLS connection
    let mut builder = SslConnector::builder(SslMethod::tls_client())?;
    builder.set_verify(if config.verify_https_cert { SslVerifyMode::PEER }
                      else { SslVerifyMode::NONE });
    if let Some(cert_path) = &config.https_ca_path {
        let pem = fs::read(cert_path)?;
        let cert_list = X509::stack_from_pem(&pem)?;
        let mut store = X509StoreBuilder::new()?;
        for cert in cert_list {
            store.add_cert(cert)?;
        }
        builder.set_verify_cert_store(store.build())?;
    }
    let connector = builder.build();

    // TLS part of the protocol
    connector.connect(&host.to_string_lossy(), stream)
        .with_context(|| format!("Unable open TLS connection to {}", connect_string))
}

/// Initiate HTTP CONNECT over an existing connection
#[tracing::instrument]
fn write_proxy_header<W:Write + Debug>(stream: &mut W, dest: &str) -> Result<()> {
    let connect_string = format!(
        "CONNECT {0} HTTP/1.1\r\n\
         Host: {0}\r\n\
         \r\n",
        dest
    );
    stream.write_all(connect_string.as_bytes())
        .with_context(|| "Cannot write to HTTP socket")
}

/// Read HTTP response after a CONNECT
#[tracing::instrument]
fn read_proxy_response<T:BufRead + Debug>(stream: &mut T) -> Result<()> {
    let mut line = String::new();
    stream.read_line(&mut line)?;
    if line.starts_with("HTTP/1.0 200 ") {
        while line != "\r\n" {
            line = String::new();
            stream.read_line(&mut line)?;
        }
        return Ok(())
    }
    Err(anyhow!("Proxy CONNECT to rudder server failed: {}", line))
}

/// Initiate TLS connexion over an the HTTP tunnel
#[tracing::instrument]
fn ssl_connect(stream: SslStream<TcpStream>, config: &Config) -> Result<SslStream<SslStream<TcpStream>>> {
    // TThis is called acceptor but it works for clients too
    let mut builder = SslAcceptor::mozilla_intermediate_v5(SslMethod::tls_client())?;
    if config.authentication_verify {
        builder.set_verify(SslVerifyMode::PEER);
    } else {
        builder.set_verify(SslVerifyMode::NONE);
    }
    if let Some(file) = &config.authentication_ca {
        builder.set_ca_file(file)?;
    }
    if let Some(file) = &config.authentication_certificate {
        builder.set_certificate_file(file, SslFiletype::PEM)?;
    }
    if let Some(file) = &config.authentication_key {
        builder.set_private_key_file(file, SslFiletype::PEM)?;
    }
    let context = builder.build().into_context();
    let ssl = Ssl::new(&context)?;
    let stream = ssl.connect(stream)?;
    Ok(stream)
}

/// Read daemon response after sending remote rsync command
#[tracing::instrument]
fn read_command_response<T:BufRead + Debug>(stream: &mut T) -> Result<()> {
    let mut line = String::new();
    stream.read_line(&mut line)?;
    if line.starts_with("OK:") {
        return Ok(())
    }
    Err(anyhow!(line))
}

/// Loop to wait for stdin and write any data to the channel
/// The only goal of this is to avoid blocking on stdin (a thread can afford to block)
/// We redirect to a channel and not tow the target socket directly because this would lock the socket for read AND write
#[tracing::instrument]
fn spawn_stdin_channel() -> Receiver<Vec<u8>> {
    let (tx, rx) = mpsc::channel();
    event!(Level::DEBUG, "Starting stdin channel");
    thread::spawn(move || {
        let mut stdin = io::stdin();
        let mut buffer = [0_u8; BUFFER_SIZE];
        loop {
            let n = match stdin.read(&mut buffer) {
                Ok(n) => n,
                // any error here is fatal
                Err(e) => {
                    let stderr = io::stderr();
                    let mut handle = stderr.lock();
                    // handling error in error is too hard, just unwrap here
                    write!(handle, "Error reading stdin: {}.\nTerminating!\n", e).unwrap();
                    exit(2);
                }
            };
            let vec = buffer[0..n].to_vec();
            if let Err(e) = tx.send(vec) {
                // any error here is fatal
                let stderr = io::stderr();
                let mut handle = stderr.lock();
                // handling error in error is too hard, just unwrap here
                write!(handle, "Error writing stdin channel: {}.\nTerminating!\n", e).unwrap();
                exit(2);
            }
        }
    });
    rx
}

/// Proxy stdin (from channel) into the socket
fn proxy_stdin<W: Write + Debug>(stdin: &Receiver<Vec<u8>>, stream: &mut W) -> Result<bool> {
    match stdin.try_recv() {
        Ok(data) => {
            event!(Level::DEBUG, "Read from stdin ({})='{:?}'", data.len(), data);
            stream.write_all(&data)?;
            Ok(true)
        },
        Err(TryRecvError::Empty) => Ok(false),
        Err(e) => Err(e).with_context(|| "Unable read from stdin thread"),
    }
}

/// Utility method to extract int from TLV
fn load_u24(value: &[u8]) -> usize {
    let mut buf = [0_u8; 8];
    buf[5] = value[0];
    buf[6] = value[1];
    buf[7] = value[2];
    usize::from_be_bytes(buf)
}


/// Redirect data from socket to proper target (stdout or stderr)
/// Also handles exit code from remore rsync
fn proxy_stderrout<R: Read + Debug, W1: Write + Debug, W2: Write + Debug>(stream: &mut R, stdout: &mut W1, stderr: &mut W2) -> Result<bool> {
    let mut buffer: [u8; 2048] = [0; BUFFER_SIZE];
    match stream.read_exact(&mut buffer[0..TL_SIZE]) {
        Ok(()) => {},
        Err(e) => return match e.kind() {
            io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => Ok(false),
            _ => Err(e).with_context(|| "Protocol error, no TLV header"),
        },
    }
    let len = load_u24(&buffer[1..TL_SIZE]);
    event!(Level::TRACE, "Receiving packet of {:?}/{:?}", buffer[0], len);
    if buffer[0] == 0 {
        // remote termination with exit code, no need to terminate thread, everything is handled by exit
        event!(Level::INFO, "Exit with value {}", len);
        exit(len as i32);
    } else {
        let code = buffer[0];
        loop {
            match stream.read_exact(&mut buffer[0..len]) {
                Ok(()) => break, // done
                Err(e) => match e.kind() {
                    io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => { }, // keep looping because we know there are more data in flight
                    _ => return Err(e).context("Protocol error, length does not match TLV"),
                },
            }
        }
        event!(Level::TRACE, "Write to stdout ({})='{:?}'", len, &buffer[..len]);
        if code == 1 {
            stdout.write_all(&buffer[..len])?;
            stdout.flush()?; // may be useful if there is a buffer
        } else if code== 2 {
            stderr.write_all(&buffer[..len])?;
            stderr.flush()?; // may be useful if there is a buffer
        } else {
            bail!("Protocol error, unknown type in TLV! {}", code);
        }
    }
    Ok(true)
}


