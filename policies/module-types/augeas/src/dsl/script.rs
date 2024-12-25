// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use nom::Finish;
use std::path::Path;

use crate::dsl::comparator::{Comparison, NumComparator};
use crate::dsl::{parser, AugPath, Sub, Value};
use anyhow::{anyhow, bail, Result};
use raugeas::{Augeas, Position};
use rudder_module_type::{rudder_debug, rudder_trace};

/// The mode of the interpreter.
///
/// Different from policy mode, which only affects the save operation.
/// Here we limit what the script can do based on the mode, to allow pure conditions.
///
/// The unrestricted mode is only available in the REPL.
#[derive(Debug, PartialEq, Copy, Clone)]
pub enum InterpreterPerms {
    /// Fail if running changes on the tree. Only check some assertions.
    ReadTree,
    /// Check and apply the changes to the tree, but writing to the disk is impossible in the script.
    ReadWriteTree,
    /// Anything goes. Full access to the tree and the system.
    ReadWriteSystem,
}

impl InterpreterPerms {
    fn is_authorised(&self, expr_type: ExprType) -> bool {
        match self {
            InterpreterPerms::ReadTree => expr_type == ExprType::Read,
            InterpreterPerms::ReadWriteTree => {
                expr_type == ExprType::Read || expr_type == ExprType::Write
            }
            InterpreterPerms::ReadWriteSystem => true,
        }
    }
}

pub struct InterpreterOut {
    pub outcome: InterpreterOutcome,
    pub output: String,
    pub quit: bool,
}

impl InterpreterOut {
    pub fn new(outcome: InterpreterOutcome, output: String, quit: bool) -> Self {
        Self {
            outcome,
            output,
            quit,
        }
    }

    pub fn ok() -> Result<Self> {
        Ok(Self::new(InterpreterOutcome::Ok, String::new(), false))
    }

    pub fn ok_quit() -> Result<Self> {
        Ok(Self::new(InterpreterOutcome::Ok, String::new(), true))
    }

    pub fn from_res_out(res: Result<String>) -> Result<Self> {
        match res {
            Ok(o) => Ok(Self::new(InterpreterOutcome::Ok, o, false)),
            Err(e) => Err(e),
        }
    }

    pub fn from_out(out: String) -> Result<Self> {
        Ok(Self::new(InterpreterOutcome::Ok, out, false))
    }

    pub fn from_res(res: Result<()>) -> Result<Self> {
        match res {
            Ok(()) => Ok(Self::new(InterpreterOutcome::Ok, String::new(), false)),
            Err(e) => Err(e),
        }
    }

    pub fn from_aug_res(res: raugeas::Result<()>) -> Result<Self> {
        match res {
            Ok(()) => Ok(Self::new(InterpreterOutcome::Ok, String::new(), false)),
            Err(e) => Err(e.into()),
        }
    }

    // FIXME: check res structured type
    pub fn from_check_res(res: Result<String>) -> Result<Self> {
        match res {
            Ok(o) => Ok(Self::new(InterpreterOutcome::Ok, o, false)),
            Err(e) => Ok(Self::new(
                InterpreterOutcome::CheckErrors(vec![e]),
                String::new(),
                false,
            )),
        }
    }
}

#[derive(Debug)]
pub enum InterpreterOutcome {
    /// Everything went well.
    Ok,
    /// The augeas expressions were evaluated correctly,
    /// and lead to invalidated checks.
    /// Note: change errors are treated as hard errors and stop the script.
    ///
    /// They can be accumulated or not.
    CheckErrors(Vec<anyhow::Error>),
}

impl InterpreterOutcome {
    fn from_errors(errors: Vec<anyhow::Error>) -> Self {
        if errors.is_empty() {
            Self::Ok
        } else {
            Self::CheckErrors(errors)
        }
    }
}

/// When running a script containing several expressions,
/// should the interpreter accumulate the results or fail early.
#[derive(Debug, PartialEq, Copy, Clone)]
pub enum CheckMode {
    /// Fail on first check error.
    FailEarly,
    /// Stack all check errors.
    StackErrors,
}

/// Interpreter for the extended Augeas DSL.
pub struct Interpreter<'a> {
    aug: &'a mut Augeas,
}

impl<'a> Interpreter<'a> {
    pub fn new(aug: &'a mut Augeas) -> Self {
        Self { aug }
    }

    pub fn preview(&mut self, file: &Path) -> Result<Option<String>> {
        println!("{}", self.aug.print("").unwrap());

        let path = format!("{}", file.display());

        let path = path.strip_prefix("/").unwrap();
        dbg!(path);

        self.aug.set("/augeas/context", "")?;
        self.aug.preview(path).map_err(Into::into)
    }

    pub fn run(
        &mut self,
        mode: InterpreterPerms,
        check_mode: CheckMode,
        script: &str,
    ) -> Result<InterpreterOut> {
        let script = Script::from(script)?;
        let mut check_errors = vec![];
        let mut output = String::new();

        rudder_debug!("Running {} changes", script.expressions.len());
        for expr in &script.expressions {
            let expr_type = expr.expr_type();
            if !mode.is_authorised(expr_type) {
                bail!(
                    "Expression type {:?} not allowed in {:?} mode",
                    expr_type,
                    mode
                );
            }

            let eval_r = self.eval(expr)?;
            output.push_str(&eval_r.output);
            match eval_r.outcome {
                InterpreterOutcome::CheckErrors(mut e) => match check_mode {
                    CheckMode::FailEarly => {
                        if !e.is_empty() {
                            return Ok(InterpreterOut::new(
                                InterpreterOutcome::CheckErrors(e),
                                output,
                                eval_r.quit,
                            ));
                        }
                    }
                    CheckMode::StackErrors => {
                        check_errors.append(&mut e);
                    }
                },
                InterpreterOutcome::Ok => {}
            }

            if eval_r.quit {
                return Ok(InterpreterOut::new(
                    InterpreterOutcome::from_errors(check_errors),
                    output,
                    true,
                ));
            }
        }
        Ok(InterpreterOut::new(
            InterpreterOutcome::from_errors(check_errors),
            output,
            false,
        ))
    }

    fn eval(&mut self, expr: &Expression) -> Result<InterpreterOut> {
        rudder_trace!("Running expression: {:?}", expr);
        match expr {
            Expression::Set(path, value) => InterpreterOut::from_aug_res(self.aug.set(path, value)),
            Expression::SetMultiple(path, sub, value) => {
                let n = self.aug.setm(path, sub, value)?;
                rudder_debug!("setm: modified {n} nodes");
                InterpreterOut::ok()
            }
            Expression::Remove(path) => {
                let n = self.aug.rm(path)?;
                rudder_debug!("rm: removed {n} nodes");
                InterpreterOut::ok()
            }
            Expression::Clear(path) => InterpreterOut::from_aug_res(self.aug.clear(path)),
            Expression::ClearMultiple(path, sub) => {
                let n = self.aug.clearm(path, sub)?;
                rudder_debug!("clearm: cleared {n} nodes");
                InterpreterOut::ok()
            }
            Expression::Touch(path) => InterpreterOut::from_aug_res(self.aug.touch(path)),
            Expression::Insert(label, position, path) => {
                InterpreterOut::from_aug_res(self.aug.insert(path, label, *position))
            }
            Expression::Move(path, other) => InterpreterOut::from_aug_res(self.aug.mv(path, other)),
            Expression::Copy(path, other) => InterpreterOut::from_aug_res(self.aug.cp(path, other)),
            Expression::Rename(path, label) => {
                let n = self.aug.rename(path, label)?;
                rudder_debug!("rename: renamed {n} nodes");
                InterpreterOut::ok()
            }
            Expression::DefineVar(name, path) => {
                InterpreterOut::from_aug_res(self.aug.defvar(name, path))
            }
            Expression::DefineNode(name, path, value) => {
                let created = self.aug.defnode(name, path, value)?;
                if created {
                    rudder_debug!("defnode: 1 node was created");
                } else {
                    rudder_debug!("defnode: no nodes were created");
                }
                InterpreterOut::ok()
            }
            Expression::MatchInclude(path, value) => {
                let matches = self.aug.matches(path)?;
                if !matches.iter().any(|v| v == value) {
                    todo!()
                }
                todo!()
            }
            Expression::MatchNotInclude(path, value) => {
                let matches = self.aug.matches(path)?;
                if !matches.iter().any(|v| v == value) {
                    todo!()
                }
                todo!()
            }
            Expression::MatchEqual(path, value) => {
                let matches = self.aug.matches(path)?;
                if matches == *value {
                    todo!()
                }
                todo!()
            }
            Expression::MatchNotEqual(path, value) => {
                let matches = self.aug.matches(path)?;
                if matches != *value {
                    todo!()
                }
                todo!()
            }
            Expression::GenericAugeas(cmd) => {
                let (_num, out) = self.aug.srun(cmd)?;
                InterpreterOut::from_out(out)
            }
            Expression::Save => InterpreterOut::from_aug_res(self.aug.save()),
            Expression::Load => InterpreterOut::from_aug_res(self.aug.load()),
            Expression::Quit => InterpreterOut::ok_quit(),
            _ => todo!(),
        }
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
    pub fn from(input: &'a str) -> Result<Script<'a>> {
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

*/

#[derive(Debug, PartialEq, Copy, Clone)]
enum ExprType {
    /// Only reads the tree and return data.
    Read,
    /// Changes the tree.
    Write,
    /// Changes the system (from the tree, a.k.a. save)
    Effect,
}

impl Expression<'_> {
    fn expr_type(&self) -> ExprType {
        match self {
            // We only guarantee that the generic augeas command does not modify the system.
            // There are both read and write commands there.
            Expression::GenericAugeas(..) => ExprType::Write,
            Expression::DefineVar(..)
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
            | Expression::ValuesNotInclude(..) => ExprType::Read,
            Expression::Save | Expression::Quit => ExprType::Effect,
        }
    }
}
