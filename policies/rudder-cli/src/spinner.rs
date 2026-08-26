// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! A progress spinner for long-running commands.
//!
//! Replaces the `spinners` crate, of which we used a single style. Like it, we
//! write the frames to stderr, and replace them with a symbol when done:
//!
//! ```text
//! ⠹ Restarting the Web application to apply changes
//! 🗸 Restarting the Web application to apply changes
//! ```
//!
//! The spinner hides itself when the output is not a terminal, or when `INFO`
//! logs are disabled, and logs the message once instead. That keeps
//! non-interactive output (packaging, CI, `--quiet`) readable.

use std::{
    io::{IsTerminal, Write, stderr, stdout},
    sync::mpsc::{Sender, channel},
    thread::{self, JoinHandle},
    time::Duration,
};
use tracing::{Level, enabled, info};

/// Braille dots, the `Dots` style of the `spinners` crate.
const FRAMES: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

/// Delay between two frames.
const INTERVAL: Duration = Duration::from_millis(80);

/// Erases the current line, to replace the spinner with the final message.
const CLEAR_LINE: &str = "\x1b[2K\r";

/// A progress spinner, shown only when it makes sense to.
pub enum Spinner {
    Shown(Animation),
    /// Non-interactive output: the message was logged instead.
    Hidden,
}

impl Spinner {
    /// Displays `message` with a spinner until [`Self::stop_with_success`] is
    /// called, or logs it once when the output is not interactive.
    pub fn start(message: String) -> Self {
        if stdout().is_terminal() && enabled!(Level::INFO) {
            Self::Shown(Animation::start(message))
        } else {
            info!(message);
            Self::Hidden
        }
    }

    /// Replaces the spinner with a success mark, keeping the message.
    pub fn stop_with_success(self) {
        match self {
            Self::Shown(animation) => animation.stop_with_symbol("🗸"),
            Self::Hidden => {}
        }
    }
}

/// The animation itself, driven by a background thread until it is stopped or
/// dropped.
///
/// Nothing here is allowed to fail the command it decorates, so all output
/// errors are ignored.
pub struct Animation {
    stop: Sender<()>,
    thread: Option<JoinHandle<()>>,
    message: String,
}

impl Animation {
    pub fn start(message: String) -> Self {
        let (stop, stopped) = channel();
        let thread = {
            let message = message.clone();
            thread::spawn(move || {
                for frame in FRAMES.iter().cycle() {
                    let mut err = stderr();
                    let _ = write!(err, "\r{frame} {message}");
                    let _ = err.flush();
                    // Either we are asked to stop, or the delay elapses and we
                    // display the next frame. A disconnected channel also stops us.
                    if stopped.recv_timeout(INTERVAL).is_ok() {
                        break;
                    }
                }
            })
        };

        Self {
            stop,
            thread: Some(thread),
            message,
        }
    }

    /// Stops the spinner, replacing it with `symbol` in front of the message.
    pub fn stop_with_symbol(mut self, symbol: &str) {
        self.stop_thread();
        let mut err = stderr();
        let _ = writeln!(err, "{CLEAR_LINE}{symbol} {}", self.message);
        let _ = err.flush();
    }

    fn stop_thread(&mut self) {
        // The thread also stops on a disconnected channel, so a failure to send
        // means it is already gone.
        let _ = self.stop.send(());
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

impl Drop for Animation {
    /// Leaves the terminal clean when the spinner is dropped without being
    /// stopped, which happens when the decorated command fails.
    fn drop(&mut self) {
        if self.thread.is_some() {
            self.stop_thread();
            let mut err = stderr();
            let _ = write!(err, "{CLEAR_LINE}");
            let _ = err.flush();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_stops_without_leaking_the_thread() {
        Animation::start("testing".into()).stop_with_symbol("🗸");
    }

    #[test]
    fn it_stops_when_dropped() {
        drop(Animation::start("testing".into()));
    }

    #[test]
    fn it_stops_before_the_first_frame_delay() {
        let start = std::time::Instant::now();
        Animation::start("testing".into()).stop_with_symbol("🗸");
        // We must not wait for the frame delay to elapse
        assert!(start.elapsed() < INTERVAL, "{:?}", start.elapsed());
    }

    #[test]
    fn it_hides_itself_when_the_output_is_not_a_terminal() {
        // Tests never run with a terminal on stdout
        assert!(matches!(Spinner::start("testing".into()), Spinner::Hidden));
        Spinner::start("testing".into()).stop_with_success();
    }
}
