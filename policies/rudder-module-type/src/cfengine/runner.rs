// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::{
    io,
    io::{BufRead, Lines, Write},
    str::FromStr,
};

use anyhow::{Error, bail};
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
use crate::cfengine::protocol::Request;

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

    /// Write lines followed by two empty lines
    fn write_json<W: Write, L: Write, D: Serialize>(
        output: &mut W,
        _error: &mut L,
        data: D,
    ) -> Result<(), Error> {
        let json = serde_json::to_string(&data)?;
        Self::write_line(output, &json)
    }

    fn try_parse_request(line: &str) -> Result<Request, Vec<String>> {
        let mut errors = Vec::new();

        match serde_json::from_str::<ValidateRequest>(line) {
            Ok(req) => return Ok(Request::Validate(req)),
            Err(e) => errors.push(format!("ValidateRequest: {}", e)),
        }

        match serde_json::from_str::<EvaluateRequest>(line) {
            Ok(req) => return Ok(Request::Evaluate(req)),
            Err(e) => errors.push(format!("EvaluateRequest: {}", e)),
        }

        match serde_json::from_str::<TerminateRequest>(line) {
            Ok(req) => return Ok(Request::Terminate(req)),
            Err(e) => errors.push(format!("TerminateRequest: {}", e)),
        }

        Err(errors)
    }
    fn run_type<T: ModuleType0, R: BufRead, W: Write, L: Write>(
        &self,
        mut promise: T,
        input: R,
        mut output: W,
        mut logger: L,
    ) -> Result<(), Error> {
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
            match Self::try_parse_request(&line) {
                Ok(Request::Validate(req)) => {
                    set_max_level(req.log_level);
                    // Check parameters
                    // FIXME add parameters spec check with info from the module type
                    let result: ValidateOutcome = promise.validate(&req.attributes).into();
                    Self::write_json(
                        &mut output,
                        &mut logger,
                        ValidateResponse::new(&req, result),
                    )?
                }
                Ok(Request::Evaluate(req)) => {
                    set_max_level(req.log_level);
                    let result: EvaluateOutcome = promise
                        .check_apply(req.attributes.action_policy.into(), &req.attributes)
                        .into();
                    Self::write_json(
                        &mut output,
                        &mut logger,
                        EvaluateResponse::new(&req, result, vec![]),
                    )?
                }
                Ok(Request::Terminate(_req)) => {
                    let result: ProtocolOutcome = promise.terminate().into();
                    Self::write_json(&mut output, &mut logger, TerminateResponse::new(result))?;
                    // Stop the runner
                    return Ok(());
                }
                Err(errors) => {
                   let error_msg = format!(
                       "Failed to parse JSON as any known request type:\n{}\nOriginal input: {}",
                       errors.iter()
                           .map(|e| format!("  - {}", e))
                           .collect::<Vec<_>>()
                           .join("\n"),
                       line
                   );
                   bail!(error_msg);
               }
            }
        }
    }
}
