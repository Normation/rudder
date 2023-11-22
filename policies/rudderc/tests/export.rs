// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::Path;

use rudderc::action;

#[test]
fn test_export_resources() {
    let technique_dir = Path::new("./tests/cases/general/ntp");
    let res = action::export(technique_dir, None);
    res.unwrap();
}

#[test]
fn test_export_without_resources() {
    let technique_dir = Path::new("./tests/cases/general/min");
    let res = action::export(technique_dir, None);
    res.unwrap();
}
