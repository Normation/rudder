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

use crate::{error::Error, stats::Event};
use futures::{future::Future, sync::mpsc};
use std::path::PathBuf;
use tokio::{
    fs::{remove_file, rename},
    prelude::*,
};
use tracing::{debug, error};

pub mod inventory;
pub mod reporting;

pub type ReceivedFile = PathBuf;
pub type RootDirectory = PathBuf;

#[derive(Debug, Copy, Clone)]
enum OutputError {
    Transient,
    Permanent,
}

impl From<Error> for OutputError {
    fn from(err: Error) -> Self {
        match err {
            Error::Database(_) | Error::DatabaseConnection(_) => OutputError::Transient,
            _ => OutputError::Permanent,
        }
    }
}

fn success(
    file: ReceivedFile,
    event: Event,
    stats: mpsc::Sender<Event>,
) -> Box<dyn Future<Item = (), Error = ()> + Send> {
    Box::new(
        stats
            .send(event)
            .map_err(|e| error!("send error: {}", e))
            .then(|_| {
                remove_file(file.clone())
                    .map(move |_| debug!("deleted: {:#?}", file))
                    .map_err(|e| error!("error: {}", e))
            }),
    )
}

fn failure(
    file: ReceivedFile,
    directory: RootDirectory,
    event: Event,
    stats: mpsc::Sender<Event>,
) -> Box<dyn Future<Item = (), Error = ()> + Send> {
    Box::new(
        stats
            .send(event)
            .map_err(|e| error!("send error: {}", e))
            .then(move |_| {
                rename(
                    file.clone(),
                    directory
                        .join("failed")
                        .join(file.file_name().expect("not a file")),
                )
                .map(move |_| {
                    debug!(
                        "moved: {:#?} to {:#?}",
                        file,
                        directory
                            .join("failed")
                            .join(file.file_name().expect("not a file"))
                    )
                })
                .map_err(|e| error!("error: {}", e))
            })
            // Hack for easier chaining
            .and_then(|_| Box::new(futures::future::err::<(), ()>(()))),
    )
}
