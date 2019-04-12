// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

pub mod watch;

use crate::configuration::LogComponent;
use crate::error::Error;
use crate::processing::ReceivedFile;
use flate2::read::GzDecoder;
use slog::slog_debug;
use slog_scope::debug;
use std::ffi::OsStr;
use std::io::Read;
use xz2::read::XzDecoder;

pub fn read_file_content(path: &ReceivedFile) -> Result<String, Error> {
    debug!("Reading {:#?} content", path);
    let data = std::fs::read(path)?;

    Ok(match path.extension().map(OsStr::to_str) {
        Some(Some("gz")) => {
            debug!("{:?} has .gz extension, extracting", path; "component" => LogComponent::Watcher);
            let mut gz = GzDecoder::new(data.as_slice());
            let mut s = String::new();
            gz.read_to_string(&mut s)?;
            s
        }
        Some(Some("xz")) => {
            debug!("{:?} has .xz extension, extracting", path; "component" => LogComponent::Watcher);
            let mut xz = XzDecoder::new(data.as_slice());
            let mut s = String::new();
            xz.read_to_string(&mut s)?;
            s
        }
        // Let's assume everything else in this directory is a text file
        _ => {
            debug!("{:?} has no gz/xz extension, no extraction needed", path; "component" => LogComponent::Watcher);
            String::from_utf8(data)?
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::{fs::read_to_string, str::FromStr};

    #[test]
    fn it_reads_gzipped_files() {
        let reference = read_to_string("tests/test_gz/normal.log").unwrap();
        assert_eq!(
            read_file_content(&PathBuf::from_str("tests/test_gz/normal.log.gz").unwrap()).unwrap(),
            reference
        );
    }

    #[test]
    fn it_reads_xzipped_files() {
        let reference = read_to_string("tests/test_gz/normal.log").unwrap();
        assert_eq!(
            read_file_content(&PathBuf::from_str("tests/test_gz/normal.log.xz").unwrap()).unwrap(),
            reference
        );
    }

    #[test]
    fn it_reads_plain_files() {
        let reference = read_to_string("tests/test_gz/normal.log").unwrap();
        assert_eq!(
            read_file_content(&PathBuf::from_str("tests/test_gz/normal.log").unwrap()).unwrap(),
            reference
        );
    }
}
