// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::Generator;
use crate::{error::*, ir::ir2::IR2, technique::Technique, ActionResult, Format};
use std::convert::From;
use std::path::Path;

pub struct JSON;

impl Generator for JSON {
    // TODO methods differ if this is a technique generation or not
    fn generate(
        &mut self,
        gc: &IR2,
        _source_file: &str,
        dest_file: Option<&Path>,
        _policy_metadata: bool,
    ) -> Result<Vec<ActionResult>> {
        let content = Technique::from(gc).to_json()?;
        Ok(vec![ActionResult::new(
            Format::JSON,
            match dest_file {
                Some(path) => path.to_str().map(|refstr| refstr.into()),
                None => None,
            },
            Some(content.to_string()),
        )])
    }
}
