use tokio::io::{AsyncReadExt, AsyncWriteExt, BufReader, AsyncBufReadExt, AsyncRead, AsyncBufRead, AsyncWrite};
use tokio::net::{TcpListener, TcpStream};
use tokio::process::{Child, ChildStderr, ChildStdin, ChildStdout, Command};
use tokio::sync::Mutex;
use tokio::select;
use tokio_util::sync::CancellationToken;
use tokio_openssl::SslStream;

use std::process::Stdio;
use std::sync::Arc;
use std::fmt::Debug;
use std::path::PathBuf;
use std::pin::Pin;

use tracing::{Level, event};
use serde::Deserialize;
use serde_inline_default::serde_inline_default;
use agent::{BUFFER_SIZE, TL_SIZE, get_config, init_tracing, LogLevel, ReadStreamPoller};
use anyhow::{Context, Result};
use openssl::ssl::{Ssl, SslAcceptor, SslContext, SslFiletype, SslMethod, SslVerifyMode, SslVersion};

const CONFIG_PATH: &str = "/tmp/original.toml";
const DEBUG_CONFIG_PATH: &str = "/tmp/test.toml";

// TODO review defaults
#[serde_inline_default]
#[derive(Deserialize, Debug)]
struct Config {
    // where to listen on
    #[serde_inline_default("localhost:873".into())]
    listen: String,

    // Allow no authentication
    #[serde_inline_default(true)]
    authentication: bool,

    // Specify authentication CA
    #[serde_inline_default(Some(PathBuf::from("/root/client-cert.pem")))]
    authentication_ca: Option<PathBuf>,

    // Specify authentication cert
    #[serde_inline_default(PathBuf::from("/root/server-cert.pem"))]
    authentication_certificate: PathBuf,

    // Specify authentication cert key
    #[serde_inline_default(PathBuf::from("/root/server-key.pem"))]
    authentication_key: PathBuf,

    // Log level (default debug in debug build)
    #[cfg(debug_assertions)]
    #[serde_inline_default(LogLevel(Level::DEBUG))]
    log_level: LogLevel,

    // Log level (default info in release build)
    #[cfg(not(debug_assertions))]
    #[serde_inline_default(LogLevel(Level::INFO))]
    log_level: LogLevel,
}

/// main with auto error printing
#[tokio::main]
async fn main() -> Result<()> {
    // startup
    let config: Config = get_config(CONFIG_PATH, DEBUG_CONFIG_PATH)?;
    init_tracing(&config.log_level);
    event!(Level::TRACE, "Configuration {:?}", config);

    // tcp for tests, we'll switch to openssl easily
    let listener = TcpListener::bind(&config.listen).await?;
    event!(Level::DEBUG, "Listening on: {}", config.listen);

    // Prepare ssl context only once
    let ssl_context = Arc::new(create_ssl_context(&config)?);

    loop {
        // Asynchronously wait for an inbound socket.
        let (socket, _) = listener.accept().await?;

        // Handle connection in dedicated task
        let ssl = ssl_context.clone();
        tokio::spawn(async move {
            match handle_connection(socket, ssl).await {
                Ok(()) => event!(Level::INFO, "socket closed"),
                Err(e) => event!(Level::DEBUG, "Socket handling error {:?}", e),
            }
        });
    }
}

fn create_ssl_context(config: &Config) -> Result<SslContext> {
    // mozilla TLS https://wiki.mozilla.org/Security/Server_Side_TLS
    let mut acceptor = SslAcceptor::mozilla_intermediate_v5(SslMethod::tls_server())?;
    acceptor.set_private_key_file(&config.authentication_key, SslFiletype::PEM)?;
    acceptor.set_certificate_file(&config.authentication_certificate, SslFiletype::PEM)?;
    if config.authentication {
        acceptor.set_verify(SslVerifyMode::PEER);
    } else {
        acceptor.set_verify(SslVerifyMode::NONE);
    }
    // no need for default trust store since we are communicating with our agents
    if let Some(file) = &config.authentication_ca {
        acceptor.set_ca_file(file)?;
    }
    // TODO should we set_client_ca_list() ?
    // TODO shoud we set_session_id_context() ?
    let context_builder =  acceptor.build();
    Ok(context_builder.into_context())
}

/// Handle a single incoming connection
#[tracing::instrument]
async fn handle_connection(socket: TcpStream, ssl_context: Arc<SslContext>) -> Result<()>{
    event!(Level::INFO, "Servicing one connection!");
    let socket = accept_ssl(socket, ssl_context).await?;
    // Line based, synchronous part of the protocol
    let mut stream = BufReader::new(socket);
    // 1/ Client -> One line containing the rsync command
    let command = read_command(&mut stream).await.context("Missing rsync command")?;
    // 2/ Server -> One line starting with "OK: " or "ERR: " depending on the command run
    let mut process = run_process(command, &mut stream).await;

    let stdout = process.stdout.take().context("Missing stdout access")?;
    let stdin = process.stdin.take().context("Missing stdin access")?;
    let stderr = process.stderr.take().context("Missing stderr access")?;

    // Asynchronous part of the protocol
    // - Client -> any data that go to stdin of the rsync command
    // - Server -> TLV with either:
    //        T=stdout -> data that go to stdout of the client
    //        T=stderr -> data that go to stderr of the client
    //        T=exit -> exit code just before closing the connexion
    let stream_ref = Arc::new(Mutex::new(stream));
    let stream_ref1 = stream_ref.clone();
    let stream_ref2 = stream_ref.clone();
    let stream_ref3 = stream_ref.clone();
    let token1 = CancellationToken::new();
    let token2 = token1.clone();
    let token3 = token1.clone();
    let mut handles = Vec::new();
    // can't user io::copy because of above split stream mutex
    handles.push(tokio::spawn(
        async move { select! {
            res = proxy_stdin(stream_ref1, stdin) => { token1.cancel(); res }
            _ = token1.cancelled() => Ok(())
        }}
    ));
    handles.push(tokio::spawn(
        async move { select! {
            res = proxy_stderr(stderr, stream_ref2) => { token2.cancel(); res }
            _ = token2.cancelled() => Ok(())
        }}
    ));
    handles.push(tokio::spawn(
        async move { select! {
            res = proxy_stdout(stdout, stream_ref3) => { token3.cancel(); res }
            _ = token3.cancelled() => Ok(())
        }}
    ));
    // Only process exit code after all proxy have finished
    for h in handles {
        match h.await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => event!(Level::ERROR, "Service Error {:?}", e),
            Err(e) => event!(Level::ERROR, "Join Error {:?}", e),
        }
    }
    event!(Level::INFO, "Ended serving connection!");

    let exit = process.wait().await.expect("failed try wait");
    // 16 bit exit code
    let code = exit.code().unwrap_or(0) & 0xFFFF;
    let mut buf = vec![0; 4];
    store_u24(&mut buf[1..4], code as usize);
    stream_ref.lock()
        .await
        .write_all(&buf[0..4])
        .await?;
    event!(Level::TRACE, "Stdout closed");    Ok(())
}

#[tracing::instrument]
async fn accept_ssl(socket: TcpStream, ssl_context: Arc<SslContext>) -> Result<SslStream<TcpStream>> {
    let ssl = Ssl::new(&ssl_context)?;
    let mut ssl_stream = SslStream::new(ssl, socket)?;
    let pinned = Pin::new(&mut ssl_stream);
    // server side handshake
    pinned.accept().await?;
    Ok(ssl_stream)
}

#[tracing::instrument]
/// Read one line of command from a buffered stream
async fn read_command<ABR: AsyncBufRead + Unpin + Debug>(stream: &mut ABR) -> Option<String>  {
    let mut command: String = String::new();
    let n = stream
        .read_line(&mut command)
        .await
        .expect("failed to read data from socket");
    event!(Level::TRACE, "Got data from socket ({})='{}'", n, command);
    if n == 0 {
        return None;
    }
    if command.ends_with('\n') {
        command.pop();
        if command.ends_with('\r') {
            command.pop();
        }
    }
    Some(command)
}

#[tracing::instrument]
async fn run_process<AW: AsyncWrite + Unpin + Debug>(command: String, stream: &mut AW) -> Child {
    let mut split = command.split(' ');
    let bin = split.next().unwrap();
    let args = split.collect::<Vec<&str>>();
    let process =
        Command::new(bin)
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap_or_else(|_| panic!("Could not spawn process :{}:\n", command));

    stream
        .write_all("OK: running\n".as_bytes())
        .await
        .expect("failed to write data to socket");
    event!(Level::TRACE, "Process started '{}'", command);
    process
}

// copy length to 3 next bytes in the buffer
fn store_u24(buf: &mut [u8], value: usize) {
    // Big endian is the usual network encoding
    let len = value.to_be_bytes();
    // that's why size is limited to 2^24
    buf[0] = len[5];
    buf[1] = len[6];
    buf[2] = len[7];
}
#[tracing::instrument]
// rsync command's stdout on the server -> client socket
// also handle process termination
async fn proxy_stdout<AW: AsyncWrite + Unpin + Debug>(mut stdout: ChildStdout, stream_ref: Arc<Mutex<AW>>) -> Result<()> {
    let mut buf = vec![0; BUFFER_SIZE];
    buf[0] = 1; // 1 = stdout
    loop {
        // read from rsync process and write to connexion
        let n = stdout
            .read(&mut buf[TL_SIZE..])
            .await?;
        event!(Level::TRACE, "Data from stdout ({})='{:?}'", n, &buf[TL_SIZE..n+TL_SIZE]);
        if n == 0 {
            return Ok(());
        } else {
            store_u24(&mut buf[1..4], n);
            stream_ref.lock()
                .await
                .write_all(&buf[0..n+TL_SIZE]) //
                .await?;
        }
    }
}

#[tracing::instrument]
// rsync command's stderr on the server -> client socket
async fn proxy_stderr<AW: AsyncWrite + Unpin+ Debug>(mut stderr: ChildStderr, stream_ref: Arc<Mutex<AW>>) -> Result<()> {
    let mut buf = vec![0; BUFFER_SIZE];
    buf[0] = 2; // 2 = stderr
    loop {
        // read from rsync process and write to connexion
        let n = stderr
            .read(&mut buf[TL_SIZE..])
            .await?;
        event!(Level::TRACE, "Data from stderr ({})='{:?}'", n, &buf[TL_SIZE..n+TL_SIZE]);
        if n == 0 {
            // no need for specific process, stdout will be closed too
            event!(Level::TRACE, "Stderr closed");
            return Ok(());
        } else {
            store_u24(&mut buf[1..4], n);
            stream_ref.lock()
                .await
                .write_all(&buf[0..n+TL_SIZE])
                .await?;
        }
    }
}

#[tracing::instrument]
// client socket -> rsync command's stdin on the server
async fn proxy_stdin<AR: AsyncRead + Unpin + Debug>(stream_ref: Arc<Mutex<AR>>, mut stdin: ChildStdin) -> Result<()> {
    let mut buf = vec![0; BUFFER_SIZE];
    // read from connexion and write to rsync process
    loop {
        let poller = ReadStreamPoller::new(stream_ref.clone(), &mut buf);
        let n = poller.await?;
        event!(Level::TRACE, "Data from socket ({})='{:?}'", n, &buf[0..n]);
        if n == 0 {
            event!(Level::TRACE, "Socket closed");
        } else {
            stdin.write_all(&buf[0..n])
                .await?;
        }
    }

}
