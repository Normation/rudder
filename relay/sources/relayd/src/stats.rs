use futures::{stream::Stream, sync::mpsc, Future};
use serde::Serialize;
use slog::slog_trace;
use slog_scope::trace;
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone, Serialize, PartialEq, Eq, Default)]
pub struct Stats {
    pub report_received: u32,
    pub report_refused: u32,
    pub report_sent: u32,
    pub report_inserted: u32,
    pub inventory_received: u32,
    pub inventory_refused: u32,
    pub inventory_sent: u32,
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
}

pub fn stats_job(
    stats: Arc<RwLock<Stats>>,
    rx: mpsc::Receiver<Event>,
) -> impl Future<Item = (), Error = ()> {
    rx.for_each(move |event| {
        stats
            .write()
            .expect("could not write lock stats")
            .event(event);
        trace!("Received stat event: {:?}", event; "component" => "statistics");
        Ok(())
    })
}
