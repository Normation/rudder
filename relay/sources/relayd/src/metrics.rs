// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use lazy_static::lazy_static;
use prometheus::{Histogram, HistogramOpts, IntCounterVec, IntGauge, Opts, Registry};

lazy_static! {
    pub static ref REGISTRY: Registry = Registry::new();
    /// Reports
    pub static ref REPORTS: IntCounterVec =
        IntCounterVec::new(Opts::new("reports_total", "Agent run reports")
            .namespace("rudder").subsystem("relayd"), &["status"]).unwrap();
    // Inventories
    pub static ref INVENTORIES: IntCounterVec =
        IntCounterVec::new(Opts::new("inventories_total", "Agent inventories")
            .namespace("rudder").subsystem("relayd"), &["status"]).unwrap();
    // Nodes
    pub static ref MANAGED_NODES: IntGauge =
        IntGauge::with_opts(Opts::new("managed_nodes_total", "Managed Nodes")
            .namespace("rudder").subsystem("relayd")).unwrap();
    pub static ref SUB_NODES: IntGauge =
        IntGauge::with_opts(Opts::new("sub_nodes_total", "Nodes behind this policy server")
            .namespace("rudder").subsystem("relayd")).unwrap();
    // Specific to reports processing
    pub static ref REPORTS_PROCESSING_DURATION: Histogram =
    // default buckets for now
        Histogram::with_opts(HistogramOpts::new("reports_processing_duration_seconds", "Reports processing")
            .namespace("rudder").subsystem("relayd")).unwrap();
    pub static ref REPORTS_SIZE_BYTES: Histogram =
    // FIXME: useful buckets
        Histogram::with_opts(HistogramOpts::new("reports_size_bytes", "Uncompressed reports size")
            .namespace("rudder").subsystem("relayd")).unwrap();
    // TODO add:
    //
    // * API: status & endpoint counters
    // * forwarding metrics
}

/// Registers custom metrics
pub fn register() {
    REGISTRY.register(Box::new(REPORTS.clone())).unwrap();
    // initialize with zero
    REPORTS.with_label_values(&["invalid"]);
    REPORTS.with_label_values(&["ok"]);
    REPORTS.with_label_values(&["error"]);
    REPORTS.with_label_values(&["forward_ok"]);
    REPORTS.with_label_values(&["forward_error"]);
    // initialize with zero
    REGISTRY.register(Box::new(INVENTORIES.clone())).unwrap();
    // inventories are always forwarded
    INVENTORIES.with_label_values(&["forward_ok"]);
    INVENTORIES.with_label_values(&["forward_error"]);
    //
    REGISTRY.register(Box::new(MANAGED_NODES.clone())).unwrap();
    REGISTRY.register(Box::new(SUB_NODES.clone())).unwrap();
    //
    REGISTRY
        .register(Box::new(REPORTS_PROCESSING_DURATION.clone()))
        .unwrap();
}
