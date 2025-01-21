// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::error::InlineCheckReport;
use crate::dsl::parser::CheckExpr;
use crate::dsl::password::PasswordPolicy;
use crate::dsl::{
    parser::Expr,
    script::{ExprType, Script},
};
use anyhow::{bail, Result};
use raugeas::Augeas;
use rudder_module_type::{rudder_debug, rudder_trace};
use std::path::Path;

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
    fn is_authorized(&self, expr_type: ExprType) -> bool {
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

    pub fn ok() -> anyhow::Result<Self> {
        Ok(Self::new(InterpreterOutcome::Ok, String::new(), false))
    }

    pub fn ok_quit() -> anyhow::Result<Self> {
        Ok(Self::new(InterpreterOutcome::Ok, String::new(), true))
    }

    pub fn from_res_out(res: anyhow::Result<String>) -> anyhow::Result<Self> {
        match res {
            Ok(o) => Ok(Self::new(InterpreterOutcome::Ok, o, false)),
            Err(e) => Err(e),
        }
    }

    pub fn from_out(out: String) -> anyhow::Result<Self> {
        Ok(Self::new(InterpreterOutcome::Ok, out, false))
    }

    pub fn from_res(res: anyhow::Result<()>) -> anyhow::Result<Self> {
        match res {
            Ok(()) => Ok(Self::new(InterpreterOutcome::Ok, String::new(), false)),
            Err(e) => Err(e),
        }
    }

    /// Fails on error, returns the output otherwise.
    pub fn from_aug_res(res: raugeas::Result<()>) -> anyhow::Result<Self> {
        match res {
            Ok(()) => Ok(Self::new(InterpreterOutcome::Ok, String::new(), false)),
            Err(e) => Err(e.into()),
        }
    }

    /// Don't fail on error, just store it.
    // FIXME: check res structured type
    pub fn from_check_res(res: anyhow::Result<String>) -> anyhow::Result<Self> {
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
    /// Optionally, a file content to provide spanned error messages
    file_content: Option<String>,
}

impl<'a> Interpreter<'a> {
    pub fn new(aug: &'a mut Augeas, file_content: Option<String>) -> Self {
        Self { aug, file_content }
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
            if !mode.is_authorized(expr_type) {
                bail!(
                    "Expression type {:?} not allowed in {:?} mode",
                    expr_type,
                    mode
                );
            }

            let eval_r = self.eval(expr)?;

            for r in eval_r {
                output.push_str(&r.output);
                match r.outcome {
                    InterpreterOutcome::CheckErrors(mut e) => match check_mode {
                        CheckMode::FailEarly => {
                            if !e.is_empty() {
                                return Ok(InterpreterOut::new(
                                    InterpreterOutcome::CheckErrors(e),
                                    output,
                                    r.quit,
                                ));
                            }
                        }
                        CheckMode::StackErrors => {
                            check_errors.append(&mut e);
                        }
                    },
                    InterpreterOutcome::Ok => {}
                }

                if r.quit {
                    return Ok(InterpreterOut::new(
                        InterpreterOutcome::from_errors(check_errors),
                        output,
                        true,
                    ));
                }
            }
        }
        Ok(InterpreterOut::new(
            InterpreterOutcome::from_errors(check_errors),
            output,
            false,
        ))
    }

    fn eval(&mut self, expr: &Expr) -> Result<Vec<InterpreterOut>> {
        rudder_trace!("Running expression: {:?}", expr);

        let mut res = vec![];
        res.extend(match expr {
            Expr::Get(path) => vec![InterpreterOut::from_out(self.aug.get(path)?.unwrap())?],
            Expr::Set(path, value) => {
                vec![InterpreterOut::from_aug_res(self.aug.set(path, value))?]
            }
            Expr::SetMultiple(path, sub, value) => {
                let n = self.aug.setm(path, sub, value)?;
                vec![InterpreterOut::from_out(format!("modified {n} nodes"))?]
            }
            Expr::Remove(path) => {
                let n = self.aug.rm(path)?;
                vec![InterpreterOut::from_out(format!("removed {n} nodes"))?]
            }
            Expr::Clear(path) => vec![InterpreterOut::from_aug_res(self.aug.clear(path))?],
            Expr::ClearMultiple(path, sub) => {
                let n = self.aug.clearm(path, sub)?;
                vec![InterpreterOut::from_out(format!("cleared {n} nodes"))?]
            }
            Expr::Touch(path) => vec![InterpreterOut::from_aug_res(self.aug.touch(path))?],
            Expr::Insert(label, position, path) => {
                vec![InterpreterOut::from_aug_res(
                    self.aug.insert(path, label, *position),
                )?]
            }
            Expr::Move(path, other) => {
                vec![InterpreterOut::from_aug_res(self.aug.mv(path, other))?]
            }
            Expr::Copy(path, other) => {
                vec![InterpreterOut::from_aug_res(self.aug.cp(path, other))?]
            }
            Expr::Rename(path, label) => {
                let n = self.aug.rename(path, label)?;
                vec![InterpreterOut::from_out(format!("renamed {n} nodes"))?]
            }
            Expr::DefineVar(name, path) => {
                vec![InterpreterOut::from_aug_res(self.aug.defvar(name, path))?]
            }
            Expr::DefineNode(name, path, value) => {
                let created = self.aug.defnode(name, path, value)?;
                vec![InterpreterOut::from_out(if created {
                    "1 node was created".to_string()
                } else {
                    "no nodes were created".to_string()
                })?]
            }
            Expr::Check(path, check_expr) => {
                let matches = self.aug.matches(path)?;
                if matches.is_empty() {
                    bail!("No matches for path {}", path);
                }
                let mut res = vec![];

                for m in matches {
                    let value = self.aug.get(&m)?.unwrap();
                    let span = self.aug.span(&m)?;

                    res.push(match check_expr {
                        CheckExpr::HasType(value_type) => {
                            InterpreterOut::from_out(if value_type.check(&value).is_ok() {
                                format!("type of {value} is {value_type}")
                            } else if let (Some(s), Some(c)) = (span, &self.file_content) {
                                let e = InlineCheckReport::new(
                                    "Type check error".to_string(),
                                    format!("type of {value} is NOT {value_type}"),
                                    s,
                                    c.clone(),
                                    None,
                                );
                                e.report()
                            } else {
                                format!("type of {value} is NOT {value_type}")
                            })?
                        }
                        CheckExpr::PasswordScore(min_score) => {
                            let policy = PasswordPolicy::MinScore(*min_score);
                            let out = policy.check(value.into())?;
                            InterpreterOut::from_out(out)?
                        }
                        CheckExpr::PasswordLUDS(total, lower, upper, digits, special) => {
                            let policy = PasswordPolicy::CharsCriteria(
                                *total, *lower, *upper, *digits, *special,
                            );
                            let out = policy.check(value.into())?;
                            InterpreterOut::from_out(out)?
                        }
                        CheckExpr::Compare(_) => {
                            todo!()
                        }
                        CheckExpr::ValuesInclude(_) => {
                            todo!()
                        }
                        CheckExpr::ValuesNotInclude(_) => {
                            todo!()
                        }
                        CheckExpr::ValuesEqual(_) => {
                            todo!()
                        }
                        CheckExpr::ValuesNotEqual(_) => {
                            todo!()
                        }
                        CheckExpr::MatchSize(comparator, size) => {
                            let values = self.aug.matches(path)?;
                            let len = values.len();

                            InterpreterOut::from_out(if comparator.numeric_compare(&len, size) {
                                format!("number of matches {len} matches {comparator} {size}")
                            } else {
                                format!(
                                    "number of matches {len} does not match {comparator} {size}"
                                )
                            })?
                        }
                        CheckExpr::Len(comparator, size) => {
                            let len = value.len();

                            InterpreterOut::from_out(if comparator.numeric_compare(&len, size) {
                                format!("length of '{value}', {len} matches {comparator} {size}")
                            } else {
                                format!(
                                    "length of '{value}', {len} does not match {comparator} {size}"
                                )
                            })?
                        }
                    });
                }
                res
            }

            Expr::GenericAugeas(cmd) => {
                let (_num, out) = self.aug.srun(cmd)?;
                vec![InterpreterOut::from_out(out)?]
            }
            Expr::Save => vec![InterpreterOut::from_aug_res(self.aug.save())?],
            Expr::Load => vec![InterpreterOut::from_aug_res(self.aug.load())?],
            Expr::Quit => vec![InterpreterOut::ok_quit()?],
        });
        Ok(res)
    }
}
