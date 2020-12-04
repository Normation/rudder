// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::stats::Event;
use anyhow::Error;
use std::path::PathBuf;
use tokio::{
    fs::{remove_file, rename},
    sync::mpsc,
};
use tracing::debug;

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
        if let Some(_e) = err.downcast_ref::<diesel::result::Error>() {
            return OutputError::Transient;
        }
        if let Some(_e) = err.downcast_ref::<diesel::r2d2::PoolError>() {
            return OutputError::Transient;
        }
        if let Some(_e) = err.downcast_ref::<reqwest::Error>() {
            return OutputError::Transient;
        }

        OutputError::Permanent
    }
}

async fn success(
    file: ReceivedFile,
    event: Event,
    mut stats: mpsc::Sender<Event>,
) -> Result<(), Error> {
    stats.send(event).await?;

    remove_file(file.clone())
        .await
        .map(move |_| debug!("deleted: {:#?}", file))?;
    Ok(())
}

async fn failure(
    file: ReceivedFile,
    directory: RootDirectory,
    event: Event,
    mut stats: mpsc::Sender<Event>,
) -> Result<(), Error> {
    stats.send(event).await?;

    rename(
        file.clone(),
        directory
            .join("failed")
            .join(file.file_name().expect("not a file")),
    )
    .await?;

    debug!(
        "moved: {:#?} to {:#?}",
        file,
        directory
            .join("failed")
            .join(file.file_name().expect("not a file"))
    );
    Ok(())
}
