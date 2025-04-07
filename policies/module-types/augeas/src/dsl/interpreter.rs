// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::{
    error::format_report_from_span,
    parser::{CheckExpr, Expr},
    password::PasswordPolicy,
    script::{ExprType, Script},
};
use anyhow::{Result, bail};
use raugeas::{Augeas, Span};
use rudder_module_type::{rudder_debug, rudder_trace};
use std::path::Path;

/// The mode of the interpreter.
///
/// Different from policy mode, which only affects the save operation.
/// Here we limit what the script can do based on the mode to allow pure conditions.
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
    /// Optionally, a file content to provide spanned error messages
    file_content: Option<&'a str>,
}

impl<'a> Interpreter<'a> {
    pub fn new(aug: &'a mut Augeas, file_content: Option<&'a str>) -> Self {
        Self { aug, file_content }
    }

    pub fn preview(&mut self, file: &Path) -> Result<Option<String>> {
        // FIXME should not be necessary
        self.aug.set("/augeas/context", "/files")?;

        let file = file.strip_prefix("/")?;

        self.aug.preview(file).map_err(Into::into)
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

    // FIXME Vec<Result, we need to treat all matching paths and stack errors.
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
                let values = matches
                    .iter()
                    .map(|m| (self.aug.get(m).unwrap().unwrap(), self.aug.span(m).unwrap()))
                    .collect::<Vec<(String, Option<Span>)>>();
                let values_str = values
                    .iter()
                    .map(|(s, _)| s.as_ref())
                    .collect::<Vec<&str>>();

                let values_res = match check_expr {
                    CheckExpr::ValuesInclude(expected_value) => {
                        let is_ok = values_str.contains(expected_value);
                        Some(InterpreterOut::from_out(if is_ok {
                            format!(
                                "values '{}' include the expected value {}",
                                values_str.join(", "),
                                expected_value
                            )
                        } else {
                            bail!(
                                "values '{}' does not include the expected value {}",
                                values_str.join(", "),
                                expected_value
                            )
                        })?)
                    }
                    CheckExpr::ValuesNotInclude(expected_value) => {
                        let is_ok = !values_str.contains(expected_value);
                        Some(InterpreterOut::from_out(if is_ok {
                            format!(
                                "values '{}' does not include the forbidden value {}",
                                values_str.join(", "),
                                expected_value
                            )
                        } else {
                            bail!(
                                "values '{}' includes the forbidden value {}",
                                values_str.join(", "),
                                expected_value
                            )
                        })?)
                    }
                    CheckExpr::ValuesEqual(expected_values) => {
                        let expected_sorted = {
                            let mut v = expected_values.clone();
                            v.sort();
                            v
                        };
                        let values_sorted = {
                            let mut v = values_str.clone();
                            v.sort();
                            v
                        };

                        let is_ok = expected_sorted == values_sorted;
                        Some(InterpreterOut::from_out(if is_ok {
                            format!(
                                "values '{}' match the expected values in any order",
                                values_str.join(", ")
                            )
                        } else {
                            bail!(
                                "values '{}' do not match the expected values in any order '{}'",
                                values_str.join(", "),
                                expected_values.join(", ")
                            )
                        })?)
                    }
                    CheckExpr::ValuesEqualOrdered(expected_values) => {
                        let is_ok = *expected_values == values_str;
                        Some(InterpreterOut::from_out(if is_ok {
                            format!(
                                "values '{}' match the expected values in given order",
                                values_str.join(", ")
                            )
                        } else {
                            bail!(
                                "values '{}' do not match the expected values in given order '{}'",
                                values_str.join(", "),
                                expected_values.join(", ")
                            )
                        })?)
                    }
                    CheckExpr::ValuesLen(comparator, size) => {
                        let len = values.len();
                        Some(InterpreterOut::from_out(
                            if comparator.numeric_compare(&len, size) {
                                format!("number of matches {len} matches {comparator} {size}")
                            } else {
                                bail!("number of matches {len} does not match {comparator} {size}")
                            },
                        )?)
                    }
                    _ => None,
                };
                if let Some(v) = values_res {
                    return Ok(vec![v]);
                }

                let mut res = vec![];
                for (value, span) in values {
                    res.push(match check_expr {
                        CheckExpr::HasType(value_type) => {
                            InterpreterOut::from_out(if value_type.check(&value).is_ok() {
                                format!("type of {value} is {value_type}")
                            } else if let (Some(s), Some(c)) = (span, &self.file_content) {
                                bail!(format_report_from_span(
                                    "Type check error",
                                    &format!("type of {value} is NOT {value_type}"),
                                    s,
                                    c,
                                    None,
                                ))
                            } else {
                                bail!(format!("type of {value} is NOT {value_type}"))
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
                        _ => {
                            unreachable!()
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

#[cfg(test)]
mod tests {
    use crate::dsl::interpreter::Interpreter;
    use raugeas::{Augeas, Flags};
    use std::fs;
    use std::path::Path;

    #[test]
    #[ignore]
    fn preview_interpreter() {
        let mut flags = Flags::NONE;

        // BREAKS preview ?!
        flags.insert(Flags::ENABLE_SPAN);

        let mut a = Augeas::init(None::<&str>, "", flags).unwrap();
        let mut i = Interpreter::new(&mut a, None);

        // First: system file
        let r = i.preview(Path::new("/etc/hosts")).unwrap();
        assert_eq!(r, Some(fs::read_to_string("/etc/hosts").unwrap()));
    }
}
