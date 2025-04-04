///! Here we put things that serve both the daemon and the client

use std::cell::RefCell;
use std::fmt::{Debug, Formatter};
use std::{fs, io, task};
use std::future::Future;
use std::path::Path;
use std::pin::{Pin, pin};
use std::str::FromStr;
use std::sync::Arc;
use std::task::Poll;
use std::ops::DerefMut;
use serde::{Deserialize, Deserializer};
use serde::de::Visitor;
use tokio::io::{AsyncRead, ReadBuf};
use tokio::sync::Mutex;
use tracing::Level;
use tracing_subscriber::fmt::format::FmtSpan;
use anyhow::{Context, Result};

/// Size of the send/receive buffer
// must be < 2^24
// > classic ip packet size, but maybe tcp window (65k or more) would be better
pub const BUFFER_SIZE: usize = 2048;
/// Size of TL in TLV frames
pub const TL_SIZE: usize = 4;

/// Get configuration from config file
pub fn get_config<C: for<'a> Deserialize<'a> + Debug>(normal_path: &str, debug_path: &str) -> Result<C> {
    let mut config_path = normal_path;
    // Allow for an alternate config path in debug builds
    #[cfg(debug_assertions)]
    if Path::new(debug_path).exists() {
        config_path = debug_path;
    }

    // look for configuration
    let config: C =
        if let Ok(data) = fs::read_to_string(config_path) {
            toml::from_str(&data)
                .with_context(|| format!("Unable to parse configuration file {}", config_path))?
        } else {
            // Config implement default for all fields so "" is always valid
            toml::from_str("").unwrap()
        };
    Ok(config)
}

/// Log level newtype to allow deserializing it from configuration
#[derive(Debug)]
pub struct LogLevel(pub Level);
impl<'de> Deserialize<'de> for LogLevel {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error> where D: Deserializer<'de> {
        deserializer.deserialize_str(LogLevelVisitor)
    }
}
struct LogLevelVisitor;
impl<'de> Visitor<'de> for LogLevelVisitor {
    type Value = LogLevel;

    fn expecting(&self, formatter: &mut Formatter) -> std::fmt::Result {
        formatter.write_str("one of : ERROR, WARN, INFO, DEBUG, TRACE")
    }
    fn visit_str<E: serde::de::Error>(self, v: &str) -> Result<Self::Value, E> {
        match Level::from_str(v) {
            Ok(l) => Ok(LogLevel(l)),
            Err(e) => Err(E::custom(format!("Invalid log_level {}: {}", v, e))),
        }
    }
}

/// Initialize tokio tracing subscriber
pub fn init_tracing(log_level: &LogLevel) {
    // initiate logger
    let mut subscriber_builder = tracing_subscriber::fmt()
        .compact()
        .with_writer(io::stderr)
        .with_file(true)
        .with_line_number(true)
        .with_thread_ids(false)
        .with_max_level(log_level.0);
    if log_level.0 == Level::TRACE {
        // add function tracing if trace is enabled
        subscriber_builder = subscriber_builder.with_span_events(FmtSpan::ENTER);
    }
    subscriber_builder.init();
}

/// Implement future over a stream held behind a mutex
/// This is needed because in a proxy the stream can be both waiting for read and being written to
/// So we need
/// 1/ a mutex to allow both reading and writing by separate tasks
/// 2/ something that waits for reading while holding the mutex
/// 3/ something that releases the mutex when there is nothing to read to allow for writing when necessary
///
/// The current solution releases the mutex each time the poll in pending, which is enough currently
/// - we may need to add a read_timeout to the socket to make is more reactive
/// - we may want to add a signal mechanism to wake up the task exactly  when a write is required
pub struct ReadStreamPoller<'a, AR: AsyncRead> {
    // We have to use a tokio mutex because the writing task tasks awaits with the mutex on hold
    inner: Arc<Mutex<AR>>,
    // we need to be able to mutably access to the buffer while pinned and inner in locked, so RefCell
    buffer: RefCell<ReadBuf<'a>>,
}

impl<'a, AR: AsyncRead> ReadStreamPoller<'a, AR> {
    // Buffer is better to be handled by the caller
    pub fn new(inner: Arc<Mutex<AR>>, buf: &'a mut [u8]) -> Self {
        ReadStreamPoller {
            inner,
            buffer: RefCell::new(ReadBuf::new(buf)),
        }
    }
}
impl<'a, AR: AsyncRead + Unpin> Future for ReadStreamPoller<'a, AR> {
    type Output = io::Result<usize>;

    fn poll(self: Pin<&mut Self>, cx: &mut task::Context<'_>) -> Poll<Self::Output> {
        match self.inner.try_lock() {
            Ok(mut guard) => {
                let pinned = pin!(guard.deref_mut());
                let mut buf = self.buffer.borrow_mut();
                match pinned.poll_read(cx, &mut buf) {
                    Poll::Ready(Ok(())) => {
                        Poll::Ready(Ok(buf.filled().len()))
                    },
                    Poll::Ready(Err(e)) => {
                        match e.kind() {
                            io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut => Poll::Pending,
                            _ => Poll::Ready(Err(e)),
                        }
                    },
                    Poll::Pending => Poll::Pending,
                }
            }
            Err(_) => Poll::Pending,
        }
    }
}
