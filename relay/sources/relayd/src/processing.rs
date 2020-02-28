// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

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
            Error::Database(_) | Error::DatabaseConnection(_) | Error::HttpClient(_) => {
                OutputError::Transient
            }
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
