// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

#![deny(clippy::unwrap_used)]
/*! Windows specific code for the service
 *  Include service startup, log initialization, ...
 *  The communication with the service manager is translated into channel::<ServiceMessage>()
 *  to make it generic
 */

use agentd::scheduler::{CommandBuilder, Scheduler};
use agentd::{ExitType, ServiceMessage};
use log::{error, info};
use std::ffi::OsString;
use std::path::Path;
use std::process::exit;
use std::time::Duration;
use tokio::sync::mpsc::channel;
use windows_service::{
    Result, define_windows_service,
    service::{
        ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus,
        ServiceType,
    },
    service_control_handler::{self, ServiceControlHandlerResult},
    service_dispatcher,
};

const SERVICE_NAME: &str = "RudderAgentD";
const LOG_SOURCE: &str = "AgentD";
const CONFIGURATION_FILE: &str = "C:\\Program Files\\Rudder\\etc\\agentd.conf";
const UUID_FILE: &str = "C:\\Program Files\\Rudder\\etc\\uuid.hive";

/// Log error as fatal, then terminate the process
/// Should only be used when the service is not properly initialized
/// Otherwise you should log an error and terminate the service properly
macro_rules! fatal(
    ( $x:expr, $( $more:expr ),* ) => {
        {
            error!($x, $( $more ),* );
            error!("last error was fatal: {} terminating", SERVICE_NAME);
            // mandatory otherwise windows doesn't know we have stopped
            exit(1);
        }
    }
);

/// The service itself
fn run_service() -> Result<()> {
    // PowerShell command builder
    let command_builder = CommandBuilder::new(Some("powershell.exe"), vec![], true);
    // Read configuration before starting the service to fail early in case of error
    let scheduler = match Scheduler::from_configuration_file(
        Path::new(CONFIGURATION_FILE),
        Path::new(UUID_FILE),
        command_builder,
    ) {
        Err(e) => fatal!("{}\n", e),
        Ok(c) => c,
    };

    info!("Starting {} service ...", SERVICE_NAME);
    let (sender, receiver) = channel(10);

    // Define a handler that manages messages from service manager
    let status_handle =
        service_control_handler::register(
            SERVICE_NAME,
            move |control_event| match control_event {
                // Retransmit stop to the main loop via a channel
                ServiceControl::Stop => {
                    if let Err(e) = sender.blocking_send(ServiceMessage::Stop) {
                        fatal!("Cannot handle manager stop because of {}", e);
                    }
                    ServiceControlHandlerResult::NoError
                }
                // It is mandatory to handle this message even with noop otherwise the service can fail
                ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
                // Other messages are disabled, they should not happen
                _ => ServiceControlHandlerResult::NotImplemented,
            },
        )?;

    // Announce service started status to service manager
    status_handle.set_service_status(ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: ServiceState::Running,
        controls_accepted: ServiceControlAccept::STOP,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::ZERO,
        process_id: None,
    })?;

    // Main service loop (go to common scheduler code)
    info!("{} service started", SERVICE_NAME);
    match scheduler.main_loop(receiver) {
        ExitType::Ok => {}
    }

    // Announce service stopped status to service manager
    status_handle.set_service_status(ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: ServiceState::Stopped,
        controls_accepted: ServiceControlAccept::empty(),
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::ZERO,
        process_id: None,
    })?;

    info!("{} service stopped", SERVICE_NAME);
    Ok(())
}

// Define the C service main function and redirect it to rust `service_main`
define_windows_service!(ffi_service_main, service_main);
fn service_main(_arguments: Vec<OsString>) {
    if let Err(e) = run_service() {
        error!("Service {} failed! Stopping now: {:?}", SERVICE_NAME, e);
        // mandatory otherwise windows doesn't know we have stopped
        exit(1);
    }
}

/// Classical main redirected from main.rs
pub fn main() {
    // logger register is done by postinst with `New-EventLog`
    //winlog2::register(LOG_SOURCE);

    // This should be the only expect, any other fatal error should go through fatal!()
    winlog2::init(LOG_SOURCE)
        .expect("Failed to initialize logger, Rudder agent is not properly installed");

    // Register generated `ffi_service_main` with the system and start the service, blocking
    // this thread until the service is stopped.
    if let Err(e) = service_dispatcher::start(SERVICE_NAME, ffi_service_main) {
        fatal!(
            "Failed to start {}, Rudder agent is not properly installed: {:?}",
            SERVICE_NAME,
            e
        );
    }
}
