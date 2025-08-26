// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::{
    env::{self, VarError},
    io::{self, BufRead, Lines, Write},
    str::FromStr,
};

use anyhow::{Context, Error, Result, bail};
use log::info;
use serde::Serialize;

use crate::{
    ModuleType0, ProtocolResult, Runner0,
    cfengine::{
        header::Header,
        log::set_max_level,
        protocol::{
            EvaluateOutcome, EvaluateRequest, EvaluateResponse, ProtocolOutcome, TerminateRequest,
            TerminateResponse, ValidateOutcome, ValidateRequest, ValidateResponse,
        },
    },
};

/// Promise executor
///
/// Handles communication with the CFEngine agent using the custom promise
/// JSON protocol on stdin/stdout.
pub struct CfengineRunner;

impl Default for CfengineRunner {
    fn default() -> Self {
        Self::new()
    }
}

impl Runner0 for CfengineRunner {
    /// Runs a module type for the agent, using stdio
    fn run<T: ModuleType0>(&self, module_type: T) -> Result<(), Error> {
        let stdin = io::stdin();
        let stdout = io::stdout();
        let stderr = io::stderr();

        let input = stdin.lock();
        let output = stdout.lock();
        let error = stderr.lock();

        self.run_type(module_type, input, output, error)
    }
}

impl CfengineRunner {
    /// Create an executor
    pub fn new() -> Self {
        Self
    }

    /// Returns the output that would have been sent given provided input
    ///
    /// Used for testing
    pub fn run_with_input<T: ModuleType0>(
        &self,
        promise_type: T,
        input: &str,
    ) -> Result<String, Error> {
        let mut output = Vec::new();
        let mut error = Vec::new();

        self.run_type(promise_type, input.as_bytes(), &mut output, &mut error)?;

        let output = std::str::from_utf8(&output)?.to_string();
        Ok(output)
    }

    /// Read a line followed by two empty lines
    fn read_line<B: BufRead>(input: &mut Lines<B>) -> Result<String, Error> {
        let line = input.next().unwrap()?;

        // Read exactly two empty lines
        for _n in 0..1 {
            let empty = input.next().unwrap()?;
            if !empty.is_empty() {
                bail!("Expecting two empty lines");
            }
        }
        Ok(line)
    }

    /// Write lines followed by two empty lines
    fn write_line<W: Write>(output: &mut W, line: &str) -> Result<(), Error> {
        output.write_all(line.as_bytes())?;
        output.write_all(b"\n\n")?;
        output.flush()?;
        Ok(())
    }

    fn setup_logging() -> Result<()> {
        let logfile = match env::var("RUDDER_AGENT_MODULE_LOG") {
            Err(VarError::NotPresent) => return Ok(()), // logging is disabled
            Err(e) => bail!("{e}"),
            Ok(v) => v,
        };

        let progname = std::env::current_exe()?
            .file_name()
            .with_context(|| format!("Could not get program name"))?
            .to_string_lossy()
            .to_string();

        fern::Dispatch::new()
            .format(move |out, message, record| {
                out.finish(format_args!(
                    "{} [{}] {} {}\n",
                    chrono::Local::now().format("%Y-%m-%dT%H:%M:%S%.3f"),
                    record.level(),
                    progname,
                    message
                ))
            })
            .chain(fern::log_file(logfile)?)
            .apply()?;

        Ok(())
    }

    /// Write lines followed by two empty lines
    fn write_json<W: Write, L: Write, D: Serialize>(
        output: &mut W,
        _error: &mut L,
        data: D,
    ) -> Result<(), Error> {
        let json = serde_json::to_string(&data)?;
        Self::write_line(output, &json)?;
        info!("-> {json}");

        Ok(())
    }

    fn run_type<T: ModuleType0, R: BufRead, W: Write, L: Write>(
        &self,
        mut promise: T,
        input: R,
        mut output: W,
        mut logger: L,
    ) -> Result<(), Error> {
        Self::setup_logging()?;

        // Parse agent header
        let mut input = input.lines();
        let first_line = Self::read_line(&mut input)?;
        let header = Header::from_str(&first_line)?;
        header.compatibility()?;

        // Read generic info
        let info = promise.metadata();

        // Send my header
        let my_header = Header::new(info.name.clone(), info.version.parse().unwrap()).to_string();
        Self::write_line(&mut output, &my_header)?;

        let mut initialized = false;

        // Now we're all set up, let's run the executor main loop
        loop {
            let line = Self::read_line(&mut input)?;
            info!("<- {line}");

            //let line = dbg!(line);
            // Lazily run initializer, in case it is expensive
            if !initialized {
                match promise.init() {
                    ProtocolResult::Failure(e) => {
                        bail!("failed to initialize promise type: {}", e);
                    }
                    ProtocolResult::Error(e) => {
                        bail!("failed to initialize promise type with unexpected: {}", e);
                    }
                    ProtocolResult::Success => (),
                }
                initialized = true;
            }

            // Handle requests
            if let Ok(req) = serde_json::from_str::<ValidateRequest>(&line) {
                set_max_level(req.log_level);
                // Check parameters
                // FIXME add parameters spec check with info from the module type
                let result: ValidateOutcome = promise.validate(&req.attributes).into();
                Self::write_json(
                    &mut output,
                    &mut logger,
                    ValidateResponse::new(&req, result),
                )?
            } else if let Ok(req) = serde_json::from_str::<EvaluateRequest>(&line) {
                set_max_level(req.log_level);
                let result: EvaluateOutcome = promise
                    .check_apply(req.attributes.action_policy.into(), &req.attributes)
                    .into();
                Self::write_json(
                    &mut output,
                    &mut logger,
                    EvaluateResponse::new(&req, result, vec![]),
                )?
            } else if let Ok(_req) = serde_json::from_str::<TerminateRequest>(&line) {
                let result: ProtocolOutcome = promise.terminate().into();
                Self::write_json(&mut output, &mut logger, TerminateResponse::new(result))?;
                // Stop the runner
                return Ok(());
            } else {
                // Stop the program? Not sure if there is something better and safe to do.
                bail!("Could not parse request: {}", line);
            };
        }
    }
}
