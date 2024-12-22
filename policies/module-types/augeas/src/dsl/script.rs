// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use nom::Finish;

use crate::dsl::comparator::{Comparison, NumComparator};
use crate::dsl::{parser, AugPath, Sub, Value};
use anyhow::{anyhow, bail, Result};
use raugeas::{Augeas, Position};
use rudder_module_type::rudder_debug;

/// The mode of the interpreter.
///
/// Different from policy mode, which only affects the save operation.
/// Here we limit what the script can do based on the mode, to allow pure conditions.
///
/// The unrestricted mode is only available in the REPL.
#[derive(Debug, PartialEq, Copy, Clone)]
pub enum InterpreterMode {
    /// Fail if running changes on the tree. Only check some assertions.
    ReadTree,
    /// Check and apply the changes to the tree, but writing to the disk is impossible in the script.
    WriteTree,
    /// Anything goes. Full access to the tree and the system.
    WriteSystem,
}

/// Interpreter for the extended Augeas DSL.
pub struct Interpreter<'a> {
    aug: &'a mut Augeas,
}

impl<'a> Interpreter<'a> {
    pub fn new(aug: &'a mut Augeas) -> Self {
        Self { aug }
    }

    pub fn run(&mut self, mode: InterpreterMode, script: &str) -> Result<bool> {
        let script = Script::from_str(script)?;

        rudder_debug!("Running {} changes", script.expressions.len());
        for expr in &script.expressions {
            if expr.expr_type() == ExprType::Write && mode == InterpreterMode::ReadTree {
                bail!("Cannot run an action ({:?}) in check mode", expr);
            }
            let quit = self.eval(expr)?;
            if quit {
                return Ok(true);
            }
        }
        // FIXME handle write/save??
        // Handle convergence/idempotency test
        Ok(false)
    }

    fn eval(&mut self, expr: &Expression) -> Result<bool> {
        match expr {
            Expression::Set(path, value) => self.aug.set(path, value)?,
            Expression::SetMultiple(path, sub, value) => {
                let n = self.aug.setm(path, sub, value)?;
                rudder_debug!("setm: modified {n} nodes");
            }
            Expression::Remove(path) => {
                let n = self.aug.rm(path)?;
                rudder_debug!("rm: removed {n} nodes");
            }
            Expression::Clear(path) => self.aug.clear(path)?,
            Expression::ClearMultiple(path, sub) => {
                let n = self.aug.clearm(path, sub)?;
                rudder_debug!("clearm: cleared {n} nodes");
            }
            Expression::Touch(path) => self.aug.touch(path)?,
            Expression::Insert(label, position, path) => self.aug.insert(path, label, *position)?,
            Expression::Move(path, other) => self.aug.mv(path, other)?,
            Expression::Copy(path, other) => self.aug.cp(path, other)?,
            Expression::Rename(path, label) => {
                let n = self.aug.rename(path, label)?;
                rudder_debug!("rename: renamed {n} nodes");
            }
            Expression::DefineVar(name, path) => self.aug.defvar(name, path)?,
            Expression::DefineNode(name, path, value) => {
                let created = self.aug.defnode(name, path, value)?;
                if created {
                    rudder_debug!("defnode: 1 node was created");
                } else {
                    rudder_debug!("defnode: no nodes were created");
                }
            }
            Expression::GenericAugeas(cmd) => {
                let (num, out) = self.aug.srun(cmd)?;
                let summary = match num {
                    raugeas::CommandsNumber::Success(n) => format!("{n} success"),
                    // FIXME: should be an error, no sense in this context.
                    raugeas::CommandsNumber::Quit => "has quit".to_string(),
                };
                rudder_debug!("{summary}, output: {out}");
            }
            Expression::Save => self.aug.save()?,
            Expression::Quit => return Ok(true),
            _ => todo!(),
        }
        Ok(false)
    }
}

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
    expressions: Vec<Expression<'a>>,
}

impl<'a> Script<'a> {
    pub fn from_str(input: &'a str) -> Result<Script<'a>> {
        let (_, changes) = parser::script(input)
            .finish()
            // We can't keep the verbose error as it contains references to the input.
            .map_err(|e| anyhow!(format!("{}", e)))?;
        Ok(Script {
            expressions: changes,
        })
    }
}

#[derive(Debug, PartialEq)]
pub enum Expression<'a> {
    /// A generic augeas command, not parsed.
    GenericAugeas(&'a str),
    /// Sets the value VALUE at location PATH
    Set(AugPath<'a>, Value<'a>),
    /// Sets multiple nodes (matching SUB relative to PATH) to VALUE
    SetMultiple(AugPath<'a>, Sub<'a>, Value<'a>),
    /// Removes the node at location PATH
    Remove(AugPath<'a>),
    /// Sets the node at PATH to NULL, creating it if needed
    Clear(AugPath<'a>),
    /// Sets multiple nodes (matching SUB relative to PATH) to NULL
    ClearMultiple(AugPath<'a>, Sub<'a>),
    /// Creates PATH with the value NULL if it does not exist
    Touch(AugPath<'a>),
    /// Inserts an empty node LABEL either before or after PATH.
    Insert(Value<'a>, Position, AugPath<'a>),
    /// Moves a node at PATH to the new location OTHER PATH
    Move(AugPath<'a>, AugPath<'a>),
    /// Copies a node at PATH to the new location OTHER PATH
    Copy(AugPath<'a>, AugPath<'a>),
    /// Rename a node at PATH to a new LABEL
    Rename(AugPath<'a>, Value<'a>),
    /// Sets Augeas variable $NAME to PATH
    DefineVar(Value<'a>, AugPath<'a>),
    /// Sets Augeas variable $NAME to PATH, creating it with VALUE if needed
    DefineNode(Value<'a>, AugPath<'a>, Value<'a>),
    // Comparison contains both the typed value and the comparator
    Compare(AugPath<'a>, Comparison),
    ValuesInclude(AugPath<'a>, &'a str),
    ValuesNotInclude(AugPath<'a>, &'a str),
    ValuesEqual(AugPath<'a>, Vec<&'a str>),
    ValuesNotEqual(AugPath<'a>, Vec<&'a str>),
    MatchSize(AugPath<'a>, NumComparator, usize),
    MatchInclude(AugPath<'a>, &'a str),
    MatchNotInclude(AugPath<'a>, &'a str),
    MatchEqual(AugPath<'a>, Vec<&'a str>),
    MatchNotEqual(AugPath<'a>, Vec<&'a str>),
    /// Save the changes to the tree.
    Save,
    /// Quit the script.
    Quit,
    /// (Re)load the tree.
    Load,
}

/*
ifonly:
    get <AUGEAS_PATH> <COMPARATOR> <STRING>
    values <MATCH_PATH> include <STRING>
    values <MATCH_PATH> not_include <STRING>
    values <MATCH_PATH> == <AN_ARRAY>
    values <MATCH_PATH> != <AN_ARRAY>
    match <MATCH_PATH> size <COMPARATOR> <INT>
    match <MATCH_PATH> include <STRING>
    match <MATCH_PATH> not_include <STRING>
    match <MATCH_PATH> == <AN_ARRAY>
    match <MATCH_PATH> != <AN_ARRAY>

where:
    AUGEAS_PATH is a valid path scoped by the context
    MATCH_PATH is a valid match syntax scoped by the context
    COMPARATOR is one of >, >=, !=, ==, <=, or <
                         ~ for regex match, !~ for regex not match
    STRING is a string
    INT is a number
    AN_ARRAY is in the form ['a string', 'another']
*/

#[derive(Debug, PartialEq)]
enum ExprType {
    /// Only reads the tree and return data.
    Read,
    /// Only reads the tree, but can fail.
    Assert,
    /// Changes the tree.
    Write,
    /// Changes the system from the tree (a.k.a. save)
    Effect,
}

impl Expression<'_> {
    fn expr_type(&self) -> ExprType {
        match self {
            Expression::GenericAugeas(..)
            | Expression::DefineVar(..)
            | Expression::DefineNode(..)
            | Expression::Set(..)
            | Expression::SetMultiple(..)
            | Expression::Remove(..)
            | Expression::Clear(..)
            | Expression::ClearMultiple(..)
            | Expression::Touch(..)
            | Expression::Insert(..)
            | Expression::Move(..)
            | Expression::Copy(..)
            | Expression::Load
            | Expression::Rename(..) => ExprType::Write,
            Expression::MatchEqual(..)
            | Expression::MatchNotEqual(..)
            | Expression::MatchInclude(..)
            | Expression::MatchNotInclude(..)
            | Expression::MatchSize(..)
            | Expression::ValuesEqual(..)
            | Expression::ValuesNotEqual(..)
            | Expression::ValuesInclude(..)
            | Expression::Compare(..)
            | Expression::ValuesNotInclude(..) => ExprType::Assert,
            Expression::Save | Expression::Quit => ExprType::Effect,
        }
    }
}
