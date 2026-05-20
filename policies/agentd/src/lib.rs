// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

// Q: option catchup ?
// Q: option log stderr ?

// TODO service linux
// TODO check linux command exit with signal
pub mod configuration;
pub mod scheduler;

/// Messages that can be sent by the service manager to the service itself
pub enum ServiceMessage {
    /// Service must be stopped
    Stop,
}

/// Exit values that can be returned by the service to the configuration manager
pub enum ExitType {
    /// Everything went OK
    Ok,
}
