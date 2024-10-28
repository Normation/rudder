// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use rust_apt::error::pending_error;
use rust_apt::progress::DynAcquireProgress;
use rust_apt::raw::{AcqTextStatus, ItemDesc, ItemState, PkgAcquire};
use rust_apt::util::{time_str, unit_str, NumSys};

/// Reimplement `rust_apt`'s progress without interactivity.
///
/// Try to stay consistent with APT's output, but allow some divergence.

/// This struct mimics the output of `apt update`.
#[derive(Default, Debug)]
pub struct AptAcquireProgress {}

impl AptAcquireProgress {
    /// Returns a new default progress instance.
    pub fn new() -> Self {
        Self::default()
    }
}

impl DynAcquireProgress for AptAcquireProgress {
    /// Used to send the pulse interval to the apt progress class.
    ///
    /// Pulse Interval is in microseconds.
    ///
    /// Example: 1 second = 1000000 microseconds.
    ///
    /// Apt default is 500000 microseconds or 0.5 seconds.
    ///
    /// The higher the number, the less frequent pulse updates will be.
    ///
    /// Pulse Interval set to 0 assumes the apt defaults.
    fn pulse_interval(&self) -> usize {
        0
    }

    /// Called when an item is confirmed to be up-to-date.
    ///
    /// Prints out the short description and the expected size.
    fn hit(&mut self, item: &ItemDesc) {
        println!("\rHit:{} {}", item.owner().id(), item.description());
    }

    /// Called when an Item has started to download
    ///
    /// Prints out the short description and the expected size.
    fn fetch(&mut self, item: &ItemDesc) {
        let mut string = format!("\rGet:{} {}", item.owner().id(), item.description());

        let file_size = item.owner().file_size();
        if file_size != 0 {
            string.push_str(&format!(" [{}]", unit_str(file_size, NumSys::Decimal)));
        }

        println!("{string}");
    }

    /// Called when an Item fails to download.
    ///
    /// Print out the ErrorText for the Item.
    fn fail(&mut self, item: &ItemDesc) {
        let mut show_error = true;
        let error_text = item.owner().error_text();
        let desc = format!("{} {}", item.owner().id(), item.description());

        match item.owner().status() {
            ItemState::StatIdle | ItemState::StatDone => {
                println!("\rIgn: {desc}");
                if error_text.is_empty() {
                    show_error = false;
                }
            }
            _ => {
                println!("\rErr: {desc}");
            }
        }

        if show_error {
            println!("\r{error_text}");
        }
    }

    /// Called periodically to provide the overall progress information
    ///
    /// Draws the current progress.
    fn pulse(&mut self, _status: &AcqTextStatus, _owner: &PkgAcquire) {
        // no pulse
    }

    /// Called when an item is successfully and completely fetched.
    ///
    /// We don't print anything here to remain consistent with apt.
    fn done(&mut self, _item: &ItemDesc) {
        // self.clear_last_line(terminal_width() - 1);

        // println!("This is done!");
    }

    /// Called when progress has started.
    ///
    /// Start does not pass information into the method.
    ///
    /// We do not print anything here to remain consistent with apt.
    fn start(&mut self) {}

    /// Called when progress has finished.
    ///
    /// Stop does not pass information into the method.
    ///
    /// prints out the bytes downloaded and the overall average line speed.
    fn stop(&mut self, owner: &AcqTextStatus) {
        if pending_error() {
            return;
        }

        if owner.fetched_bytes() != 0 {
            println!(
                "Fetched {} in {} ({}/s)",
                unit_str(owner.fetched_bytes(), NumSys::Decimal),
                time_str(owner.elapsed_time()),
                unit_str(owner.current_cps(), NumSys::Decimal)
            );
        } else {
            println!("Nothing to fetch.");
        }
    }
}
