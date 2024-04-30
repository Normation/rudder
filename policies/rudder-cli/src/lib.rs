// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

pub mod logs;

use std::panic;
use std::process::exit;

// taken from https://github.com/yamafaktory/jql/commit/12f5110b3443c33c09cf60d03fe638c2c266de98
// under MIT/Apache 2 license

/// Use a custom hook to manage broken pipe errors.
///
/// See https://github.com/rust-lang/rust/issues/46016 for cause.
pub fn custom_panic_hook_ignore_sigpipe() {
    // Take the hook.
    let hook = panic::take_hook();

    // Register a custom panic hook.
    panic::set_hook(Box::new(move |panic_info| {
        let panic_message = panic_info.to_string();

        // Exit on a broken pipe message.
        if panic_message.contains("Broken pipe") || panic_message.contains("os error 32") {
            exit(0);
        }

        // Hook back to default.
        (hook)(panic_info)
    }));
}
