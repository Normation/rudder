// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};
use tokio::sync::mpsc;
use tracing::trace;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct Stats {
    pub report_received: u64,
    pub report_refused: u64,
    pub report_sent: u64,
    pub report_inserted: u64,
    pub inventory_received: u64,
    pub inventory_refused: u64,
    pub inventory_sent: u64,
}

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Event {
    ReportReceived,
    ReportSent,
    ReportInserted,
    ReportRefused,
    InventoryReceived,
    InventorySent,
    InventoryRefused,
}

impl Stats {
    pub fn event(&mut self, event: Event) {
        match event {
            Event::ReportReceived => self.report_received += 1,
            Event::ReportSent => self.report_sent += 1,
            Event::ReportInserted => self.report_inserted += 1,
            Event::ReportRefused => self.report_refused += 1,
            Event::InventoryReceived => self.inventory_received += 1,
            Event::InventorySent => self.inventory_sent += 1,
            Event::InventoryRefused => self.inventory_refused += 1,
        }
    }

    pub async fn receiver(stats: Arc<RwLock<Self>>, mut rx: mpsc::Receiver<Event>) {
        while let Some(event) = rx.recv().await {
            stats
                .write()
                .expect("could not write lock stats")
                .event(event);
            trace!("Received stat event: {:?}", event);
        }
    }
}
