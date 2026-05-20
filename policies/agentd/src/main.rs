// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

/*! We have single main here and not one main per binary.
 *  This is because it is not possible to build 2 different architectures at the same time
 *  So there will always be a single main target.
 */

#[cfg(target_os = "windows")]
mod windows;

#[cfg(target_os = "linux")]
mod linux;

// Do no use tokio::main since the async part might be behind non-async function
// This also simplifies common code by putting everything inside the main loop
fn main() {
    #[cfg(target_os = "windows")]
    return windows::main();
    #[cfg(target_os = "linux")]
    return linux::main();
}
