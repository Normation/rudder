// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

#[cfg(feature = "apt-compat")]
use rust_apt_compat as rust_apt;

use memfile::MemFile;
use rust_apt::{
    error::pending_error,
    progress::DynAcquireProgress,
    raw::{AcqTextStatus, ItemDesc, ItemState, PkgAcquire},
    util::{NumSys, time_str, unit_str},
};
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};

/// Reimplement `rust_apt`'s progress without interactivity.
///
/// Try to stay consistent with APT's output, but allow some divergence.

/// This struct mimics the output of `apt update`.
#[derive(Debug)]
pub struct RudderAptAcquireProgress {
    /// Enforce MemFile as we won't properly handle IO errors
    writer: BufWriter<MemFile>,
}

impl RudderAptAcquireProgress {
    /// Returns a new default progress instance.
    pub fn new(out_file: MemFile) -> Self {
        let writer = BufWriter::new(out_file);
        Self { writer }
    }

    pub fn read_mem_file(mut file: MemFile) -> String {
        let mut acquire_out = String::new();
        file.flush().unwrap();
        file.seek(SeekFrom::Start(0)).unwrap();
        file.read_to_string(&mut acquire_out).unwrap();
        acquire_out
    }

    #[cfg(test)]
    pub fn simulate(&mut self, s: &str) {
        write!(self.writer, "{}", s).unwrap();
        self.writer.flush().unwrap();
    }
}

impl DynAcquireProgress for RudderAptAcquireProgress {
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
        write!(
            self.writer,
            "\rHit:{} {}",
            item.owner().id(),
            item.description()
        )
        .unwrap();
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

        write!(self.writer, "{string}").unwrap();
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
                write!(self.writer, "\rIgn: {desc}").unwrap();
                if error_text.is_empty() {
                    show_error = false;
                }
            }
            _ => {
                write!(self.writer, "\rErr: {desc}").unwrap();
            }
        }

        if show_error {
            write!(self.writer, "\r{error_text}").unwrap();
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
            write!(
                self.writer,
                "Fetched {} in {} ({}/s)",
                unit_str(owner.fetched_bytes(), NumSys::Decimal),
                time_str(owner.elapsed_time()),
                unit_str(owner.current_cps(), NumSys::Decimal)
            )
            .unwrap();
        } else {
            write!(self.writer, "Nothing to fetch.").unwrap();
        }
        self.writer.flush().unwrap()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use memfile::MemFile;
    use std::ffi::c_void;
    use std::os::fd::AsRawFd;

    #[test]
    fn test_apt_progress() {
        let file = MemFile::create_default("update-acquire").unwrap();
        let mut progress = RudderAptAcquireProgress::new(file.try_clone().unwrap());
        progress.simulate("toto");
        let acquire_out = RudderAptAcquireProgress::read_mem_file(file);
        assert_eq!(acquire_out, "toto".to_string());
    }

    #[test]
    fn test_apt_progress_raw() {
        let mem_file_install = MemFile::create_default("upgrade-install").unwrap();
        let install_progress = mem_file_install.as_raw_fd();
        unsafe {
            let r = libc::write(
                install_progress,
                "Hello World!".as_ptr() as *const c_void,
                12,
            );
            assert_eq!(r, 12);
        }
        let install_out = RudderAptAcquireProgress::read_mem_file(mem_file_install);
        assert_eq!(install_out, "Hello World!".to_string());
    }
}
