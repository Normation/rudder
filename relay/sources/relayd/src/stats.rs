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

use crate::configuration::LogComponent;
use futures::{stream::Stream, sync::mpsc, Future};
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};
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

    pub fn receiver(
        stats: Arc<RwLock<Self>>,
        rx: mpsc::Receiver<Event>,
    ) -> impl Future<Item = (), Error = ()> {
        rx.for_each(move |event| {
            stats
                .write()
                .expect("could not write lock stats")
                .event(event);
            trace!("Received stat event: {:?}", event);
            Ok(())
        })
    }
}
