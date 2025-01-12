// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::comparator::{Comparison, NumComparator};
use crate::dsl::value_type::ValueType;
use crate::dsl::{parser, AugPath, Sub, Value};
use anyhow::{anyhow, bail, Result};
use miette::{miette, LabeledSpan, NamedSource, Severity};
use nom::Finish;
use raugeas::{Augeas, Position};
use rudder_module_type::{rudder_debug, rudder_trace};
use std::path::Path;
use zxcvbn::Score;

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

    /// Fails on error, returns the output otherwise.
    pub fn from_aug_res(res: raugeas::Result<()>) -> Result<Self> {
        match res {
            Ok(()) => Ok(Self::new(InterpreterOutcome::Ok, String::new(), false)),
            Err(e) => Err(e.into()),
        }
    }

    /// Don't fail on error, just store it.
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
    // enable experimental methods
    // experimental: bool,
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

    fn eval(&mut self, expr: &Expr) -> Result<InterpreterOut> {
        rudder_trace!("Running expression: {:?}", expr);
        match expr {
            Expr::Set(path, value) => InterpreterOut::from_aug_res(self.aug.set(path, value)),
            Expr::SetMultiple(path, sub, value) => {
                let n = self.aug.setm(path, sub, value)?;
                rudder_debug!("setm: modified {n} nodes");
                InterpreterOut::ok()
            }
            Expr::Remove(path) => {
                let n = self.aug.rm(path)?;
                rudder_debug!("rm: removed {n} nodes");
                InterpreterOut::ok()
            }
            Expr::Clear(path) => InterpreterOut::from_aug_res(self.aug.clear(path)),
            Expr::ClearMultiple(path, sub) => {
                let n = self.aug.clearm(path, sub)?;
                rudder_debug!("clearm: cleared {n} nodes");
                InterpreterOut::ok()
            }
            Expr::Touch(path) => InterpreterOut::from_aug_res(self.aug.touch(path)),
            Expr::Insert(label, position, path) => {
                InterpreterOut::from_aug_res(self.aug.insert(path, label, *position))
            }
            Expr::Move(path, other) => InterpreterOut::from_aug_res(self.aug.mv(path, other)),
            Expr::Copy(path, other) => InterpreterOut::from_aug_res(self.aug.cp(path, other)),
            Expr::Rename(path, label) => {
                let n = self.aug.rename(path, label)?;
                rudder_debug!("rename: renamed {n} nodes");
                InterpreterOut::ok()
            }
            Expr::DefineVar(name, path) => {
                InterpreterOut::from_aug_res(self.aug.defvar(name, path))
            }
            Expr::DefineNode(name, path, value) => {
                let created = self.aug.defnode(name, path, value)?;
                if created {
                    rudder_debug!("defnode: 1 node was created");
                } else {
                    rudder_debug!("defnode: no nodes were created");
                }
                InterpreterOut::ok()
            }
            Expr::MatchInclude(path, value) => {
                let matches = self.aug.matches(path)?;
                if !matches.iter().any(|v| v == value) {
                    todo!()
                }
                todo!()
            }
            Expr::MatchNotInclude(path, value) => {
                let matches = self.aug.matches(path)?;
                if !matches.iter().any(|v| v == value) {
                    todo!()
                }
                todo!()
            }
            Expr::MatchEqual(path, value) => {
                let matches = self.aug.matches(path)?;
                if matches == *value {
                    todo!()
                }
                todo!()
            }
            Expr::MatchNotEqual(path, value) => {
                let matches = self.aug.matches(path)?;
                if matches != *value {
                    todo!()
                }
                todo!()
            }
            Expr::HasType(path, value_type) => {
                let value = self.aug.get(path).unwrap().unwrap();
                if value_type.check(&value).is_ok() {
                    rudder_debug!("type of {value} is {value_type}");
                } else {
                    let span = self.aug.span(path).unwrap().unwrap();

                    rudder_debug!("type of {value} is NOT {value_type}");
                }
                InterpreterOut::ok()
            }
            Expr::GenericAugeas(cmd) => {
                let (_num, out) = self.aug.srun(cmd)?;
                InterpreterOut::from_out(out)
            }
            Expr::Save => InterpreterOut::from_aug_res(self.aug.save()),
            Expr::Load => InterpreterOut::from_aug_res(self.aug.load()),
            Expr::Quit => InterpreterOut::ok_quit(),
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
    expressions: Vec<Expr<'a>>,
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
pub enum Expr<'src> {
    /// A generic augeas command, not parsed.
    GenericAugeas(&'src str),
    /// Sets the value VALUE at location PATH
    Set(AugPath<'src>, Value<'src>),
    /// Sets multiple nodes (matching SUB relative to PATH) to VALUE
    SetMultiple(AugPath<'src>, Sub<'src>, Value<'src>),
    /// Removes the node at location PATH
    Remove(AugPath<'src>),
    /// Sets the node at PATH to NULL, creating it if needed
    Clear(AugPath<'src>),
    /// Sets multiple nodes (matching SUB relative to PATH) to NULL
    ClearMultiple(AugPath<'src>, Sub<'src>),
    /// Creates PATH with the value NULL if it does not exist
    Touch(AugPath<'src>),
    /// Inserts an empty node LABEL either before or after PATH.
    Insert(Value<'src>, Position, AugPath<'src>),
    /// Moves a node at PATH to the new location OTHER PATH
    Move(AugPath<'src>, AugPath<'src>),
    /// Copies a node at PATH to the new location OTHER PATH
    Copy(AugPath<'src>, AugPath<'src>),
    /// Rename a node at PATH to a new LABEL
    Rename(AugPath<'src>, Value<'src>),
    /// Sets Augeas variable $NAME to PATH
    DefineVar(Value<'src>, AugPath<'src>),
    /// Sets Augeas variable $NAME to PATH, creating it with VALUE if needed
    DefineNode(Value<'src>, AugPath<'src>, Value<'src>),
    // Comparison contains both the typed value and the comparator
    Compare(AugPath<'src>, Comparison),
    ValuesInclude(AugPath<'src>, &'src str),
    ValuesNotInclude(AugPath<'src>, &'src str),
    ValuesEqual(AugPath<'src>, Vec<&'src str>),
    ValuesNotEqual(AugPath<'src>, Vec<&'src str>),
    MatchSize(AugPath<'src>, NumComparator, usize),
    MatchInclude(AugPath<'src>, &'src str),
    MatchNotInclude(AugPath<'src>, &'src str),
    MatchEqual(AugPath<'src>, Vec<&'src str>),
    MatchNotEqual(AugPath<'src>, Vec<&'src str>),
    /// Check the value at the path has a given type
    ///
    /// Uses the "is" keyword.
    HasType(AugPath<'src>, ValueType),
    /// String length
    ///
    /// Warning: do no use for passwords as the value will be displayed.
    StrLen(AugPath<'src>, NumComparator, usize),
    /// Minimal score
    PasswordScore(AugPath<'src>, Score),
    /// Minimal LUDS values
    PasswordLUDS(AugPath<'src>, u8, u8, u8, u8, u8),
    /// Save the changes to the tree.
    Save,
    /// Quit the script.
    Quit,
    /// (Re)load the tree.
    Load,
}

#[derive(Debug, PartialEq, Copy, Clone)]
enum ExprType {
    /// Only reads the tree and return data.
    Read,
    /// Changes the tree.
    Write,
    /// Changes the system (from the tree, a.k.a. save)
    Effect,
}

impl Expr<'_> {
    fn expr_type(&self) -> ExprType {
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
            | Expr::StrLen(..) => ExprType::Read,
            Expr::Save | Expr::Quit => ExprType::Effect,
            _ => todo!(),
        }
    }
}
