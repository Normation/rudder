// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

#![deny(clippy::unwrap_used)]
/*! Linux specific code for the service
*  Include service startup, log initialization, ...
*  The communication with the service manager is translated into channel::<ServiceMessage>()
*  to make it generic
 */

/// We target systemd as much as possible
/// Non systemd linux service cans be made in a second phase if necessary
/// See https://www.freedesktop.org/software/systemd/man/latest/daemon.html?__goaway_challenge=meta-refresh&__goaway_id=e2fc004ac6d021e49c84da570aea9c8d#New-Style%20Daemons
pub fn main() {
    // init log system
    // setup signal handler
    // read configuration
    // jump to scheduler loop
}
