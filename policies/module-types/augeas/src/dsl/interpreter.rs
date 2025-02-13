// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::password::PasswordPolicy;
use crate::dsl::{
    parser::Expr,
    script::{ExprType, Script},
};
use anyhow::bail;
use raugeas::Augeas;
use rudder_module_type::{rudder_debug, rudder_trace};
use secrecy::SecretString;
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
    // enable experimental methods
    // experimental: bool,
}

impl<'a> Interpreter<'a> {
    pub fn new(aug: &'a mut Augeas) -> Self {
        Self { aug }
    }

    pub fn preview(&mut self, file: &Path) -> anyhow::Result<Option<String>> {
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
    ) -> anyhow::Result<InterpreterOut> {
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

    fn eval(&mut self, expr: &Expr) -> anyhow::Result<InterpreterOut> {
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
                let value = self.aug.get(path)?.unwrap();
                if value_type.check(&value).is_ok() {
                    rudder_debug!("type of {value} is {value_type}");
                } else {
                    rudder_debug!("type of {value} is NOT {value_type}");
                }
                InterpreterOut::ok()
            }
            Expr::PasswordScore(path, value) => {
                let policy = PasswordPolicy::MinScore(*value);
                let password = SecretString::new(self.aug.get(path)?.unwrap());
                let out = policy.check(password)?;
                InterpreterOut::from_out(out)
            }
            Expr::PasswordLUDS(path, total, lower, upper, digits, special) => {
                let policy =
                    PasswordPolicy::CharsCriteria(*total, *lower, *upper, *digits, *special);
                let password = SecretString::new(self.aug.get(path)?.unwrap());
                let out = policy.check(password)?;
                InterpreterOut::from_out(out)
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
