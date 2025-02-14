// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::{parser, parser::Expr};
use anyhow::Result;

/// The ordered list of changes to apply.
///
/// It is compatible with the augeas command line and srun syntax.
/// The goal is to restrict the DSL to a subset of the augeas command line syntax
/// to prevent the user from shooting themselves in the foot.
///
/// TODO: make it as close as possible to the augeas command line & srun syntax to ease
///       development and debugging.
#[derive(Debug, PartialEq)]
pub struct Script<'a> {
    pub(crate) expressions: Vec<Expr<'a>>,
}

impl<'a> Script<'a> {
    pub fn from(input: &'a str) -> Result<Script<'a>> {
        parser::parse_script(input)
    }
}

#[derive(Debug, PartialEq, Copy, Clone)]
pub(crate) enum ExprType {
    /// Only reads the tree and return data.
    Read,
    /// Changes the tree.
    Write,
    /// Changes the system (from the tree, a.k.a. save)
    Effect,
}

impl Expr<'_> {
    pub(crate) fn expr_type(&self) -> ExprType {
        match self {
            // We only guarantee that the generic augeas command does not modify the system.
            // There are both read and write commands there.
            Expr::GenericAugeas(..) => ExprType::Write,
            Expr::DefineVar(..)
            | Expr::DefineNode(..)
            | Expr::Set(..)
            | Expr::SetMultiple(..)
            | Expr::Remove(..)
            | Expr::Clear(..)
            | Expr::ClearMultiple(..)
            | Expr::Touch(..)
            | Expr::Insert(..)
            | Expr::Move(..)
            | Expr::Copy(..)
            | Expr::Load
            | Expr::Rename(..) => ExprType::Write,
            Expr::MatchEqual(..)
            | Expr::MatchNotEqual(..)
            | Expr::MatchInclude(..)
            | Expr::MatchNotInclude(..)
            | Expr::MatchSize(..)
            | Expr::ValuesEqual(..)
            | Expr::ValuesNotEqual(..)
            | Expr::ValuesInclude(..)
            | Expr::Compare(..)
            | Expr::ValuesNotInclude(..)
            | Expr::HasType(..)
            | Expr::PasswordScore(..)
            | Expr::PasswordLUDS(..)
            | Expr::StrLen(..) => ExprType::Read,
            Expr::Save | Expr::Quit => ExprType::Effect,
        }
    }
}
