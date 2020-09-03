// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{error::Result, generator::Format, io::IOContext, output::Logs, Action};
use log::LevelFilter;
use serde::Deserialize;
use std::{cmp::PartialEq, path::PathBuf};
use structopt::StructOpt;

#[derive(Debug, StructOpt, Deserialize, PartialEq)]
#[structopt(rename_all = "kebab-case")]
/// Rudderc abilities are callable through subcommands, namely <technique read>, <technique generate>, <migrate>, <compile>,
/// allowing it to perform generation or translation from / into the following formats : [JSON, RudderLang, CFengine, DSC].
///
/// Run `rudderc <SUBCOMMAND> --help` to access its inner options and flags helper
///
/// Example:
/// rudderc technique generate -c confs/my.conf -i techniques/technique.json -f rudderlang
pub enum Opt {
    Technique(Technique),
    /// Generates a RudderLang technique from a CFEngine technique
    Migrate {
        #[structopt(flatten)]
        options: Options,

        /// Use json logs instead of human readable output
        ///
        /// This option will print a single JSON object that will contain logs, errors and generated data (or the file where it has been generated)
        ///
        /// JSON output format is always the same, whichever action is chosen.
        /// However, some fields (data and destination file) could be set to `null`, make sure to handle `null`s properly.
        ///
        /// Note that NO_COLOR specs apply by default for json output.
        ///
        /// Also note that setting NO_COLOR manually in your env will also work
        #[structopt(long, short)]
        json_logs: bool,
    },
    /// Generates either a DSC / CFEngine technique (`--format` option) from a RudderLang technique
    Compile {
        #[structopt(flatten)]
        options: Options,

        /// Use json logs instead of human readable output
        ///
        /// This option will print a single JSON object that will contain logs, errors and generated data (or the file where it has been generated)
        ///
        /// JSON output format is always the same, whichever action is chosen.
        /// However, some fields (data and destination file) could be set to `null`, make sure to handle `null`s properly.
        ///
        /// Note that NO_COLOR specs apply by default for json output.
        ///
        /// Also note that setting NO_COLOR manually in your env will also work
        #[structopt(long, short)]
        json_logs: bool,

        /// Enforce a compiler output format (overrides configuration format)
        #[structopt(long, short, possible_values = &["cf", "cfengine", "dsc"])]
        format: Option<Format>,
    },
}

/// A technique can either be used with one of the two following subcommands: `read` (from rudderlang to json) or `generate` (from json to cfengine or dsc or rudderlang)
#[derive(Debug, StructOpt, Deserialize, PartialEq)]
#[structopt(rename_all = "kebab-case")]
pub enum Technique {
    /// Generates a JSON technique from a RudderLang technique
    Read {
        #[structopt(flatten)]
        options: Options,
    },
    /// Generates a JSON object that comes with RudderLang + DSC + CFEngine technique from a JSON technique
    Generate {
        #[structopt(flatten)]
        options: Options,
    },
}

#[derive(Clone, Debug, StructOpt, Deserialize, PartialEq)]
#[structopt(rename_all = "kebab-case")]
pub struct Options {
    /// Path of the configuration file to use.
    /// A configuration file is required (containing at least stdlib and generic_methods paths)
    #[structopt(long, short, default_value = "/opt/rudder/etc/rudderc.conf")]
    pub config_file: PathBuf,

    /// Input file path.
    ///
    /// If option path does not exist, concat config input with option.
    #[structopt(long, short)]
    pub input: Option<PathBuf>,

    /// Output file path.
    ///
    /// If option path does not exist, concat config output with option.
    ///
    ///Else base output on input.
    #[structopt(long, short)]
    pub output: Option<PathBuf>,

    /// rudderc output logs verbosity.
    #[structopt(
        long,
        short,
        possible_values = &["off", "trace", "debug", "info", "warn", "error"],
        default_value = "warn"
    )]
    pub log_level: LevelFilter,

    /// Takes stdin as an input rather than using a file. Overwrites input file option
    #[structopt(long)]
    pub stdin: bool,

    /// Takes stdout as an output rather than using a file. Overwrites output file option. Dismiss logs directed to stdout.
    /// Errors are kept since they are printed to stderr
    #[structopt(long)]
    pub stdout: bool,

    /// Generates a backtrace in case an error occurs
    #[structopt(long, short)]
    pub backtrace: bool,
}

impl Opt {
    pub fn extract_logging_infos(&self) -> (Logs, LevelFilter, bool) {
        let (output, level, backtrace_enabled) = match self {
            Self::Compile {
                options, json_logs, ..
            } => {
                let output = match (json_logs, options.stdout) {
                    (_, true) => Logs::None,
                    (true, false) => Logs::JSON,
                    (false, false) => Logs::Terminal,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Migrate { options, json_logs } => {
                let output = match (json_logs, options.stdout) {
                    (_, true) => Logs::None,
                    (true, false) => Logs::JSON,
                    (false, false) => Logs::Terminal,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Technique(t) => t.extract_logging_infos(),
        };
        // remove log colors if JSON format to make logs vec readable
        if output == Logs::JSON {
            std::env::set_var("NO_COLOR", "1");
        }
        (output, level, backtrace_enabled)
    }

    pub fn extract_parameters(&self) -> Result<IOContext> {
        match self {
            Self::Compile {
                options, format, ..
            } => IOContext::new(self.as_action(), options, format.clone()),
            Self::Migrate { options, .. } => {
                IOContext::new(self.as_action(), options, Some(Format::RudderLang))
            }
            Self::Technique(t) => t.extract_parameters(),
        }
    }

    pub fn as_action(&self) -> Action {
        match self {
            Self::Technique(technique) => technique.as_action(),
            Self::Migrate { .. } => Action::Migrate,
            Self::Compile { .. } => Action::Compile,
        }
    }
}

impl Technique {
    fn extract_logging_infos(&self) -> (Logs, LevelFilter, bool) {
        match self {
            Self::Read { options } => {
                let output = match options.stdout {
                    true => Logs::None,
                    false => Logs::JSON,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Generate { options, .. } => {
                let output = match options.stdout {
                    true => Logs::None,
                    false => Logs::JSON,
                };
                (output, options.log_level, options.backtrace)
            }
        }
    }

    fn extract_parameters(&self) -> Result<IOContext> {
        match self {
            Self::Read { options } => {
                // exception: stdin + stdout are set by default
                let mut options = options.clone();
                if options.input.is_none() {
                    options.stdin = true;
                }
                if options.output.is_none() && options.stdin {
                    options.stdout = true;
                }
                IOContext::new(self.as_action(), &options, Some(Format::JSON))
            }
            Self::Generate { options } => {
                // exception: stdin + stdout are set by default
                let mut options = options.clone();
                if options.input.is_none() {
                    options.stdin = true;
                }
                if options.output.is_none() && options.stdin {
                    options.stdout = true;
                }
                IOContext::new(self.as_action(), &options, Some(Format::JSON))
            }
        }
    }

    fn as_action(&self) -> Action {
        match self {
            Technique::Read { .. } => Action::ReadTechnique,
            Technique::Generate { .. } => Action::GenerateTechnique,
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::Error;
    use pretty_assertions::assert_eq;

    // syntaxic sugar + more accessible error message when useful
    fn opt_new_r(params: &str) -> std::result::Result<Opt, String> {
        Opt::from_iter_safe(params.split_whitespace()).map_err(|e| format!("{:?}", e.kind))
    }
    // syntaxic sugar + directly returns Opt. !! If error, panic
    fn opt_new(params: &str) -> Opt {
        Opt::from_iter(params.split_whitespace())
    }

    #[test]
    // tests if cli parameters are handled properly (setup behavior, forbids unwanted ones etc)
    fn cli() {
        // TECHNIQUE READ + GENERAL
        assert_eq!(
            // full example works fine
            opt_new_r("rudderc technique read -c tools/rudderc-dev.conf -i tests/techniques/6.1.rc5/technique.rl -o tests/techniques/6.1.rc5/technique.ee.cf -l warn -b --stdin --stdout"),
            Ok(Opt::Technique(Technique::Read {
                options: Options {
                    config_file: PathBuf::from("tools/rudderc-dev.conf"),
                    input: Some(PathBuf::from("tests/techniques/6.1.rc5/technique.rl")),
                    stdin: true,
                    stdout: true,
                    output: Some(PathBuf::from("tests/techniques/6.1.rc5/technique.ee.cf")),
                    log_level: LevelFilter::Warn,
                    backtrace: true,
                },
            }))
        );
        assert_eq!(
            opt_new_r("rudderc technique read"), // cleanest setup works and default are ok
            Ok(Opt::Technique(Technique::Read {
                options: Options {
                    config_file: PathBuf::from("/opt/rudder/etc/rudderc.conf"), // default
                    input: None,
                    stdin: false,
                    stdout: false,
                    output: None,
                    log_level: LevelFilter::Warn, // default
                    backtrace: false,
                },
            }))
        );
        // technique read has no -j option
        assert!(opt_new_r("rudderc technique read --json-logs").is_err());
        // technique read has no -f option
        assert_eq!(
            opt_new_r("rudderc technique read --format"),
            Err("UnknownArgument".into())
        );
        // log-level option limited possible values
        assert!(opt_new_r("rudderc technique read --log-level doesnotexist").is_err());
        // technique subcommand must be used with a subcommand
        assert!(opt_new_r("rudderc technique --stdout").is_err());
        // technique subcommand must be used with a subcommand
        assert!(opt_new_r("rudderc technique").is_err());
        // expected subcommand
        assert!(opt_new_r("rudderc -c tools/rudderc-dev.conf -i tests/techniques/6.1.rc5/technique.rl -o tests/techniques/6.1.rc5/technique.ee.cf -l warn -b --stdin --stdout").is_err());
        // subcommand ordering matters
        assert!(opt_new_r("rudderc read technique").is_err());

        // MIGRATE
        // technique subcommand must be used with a subcommand
        assert!(opt_new_r("rudderc technique migrate").is_err());
        // migrate does not accepts format
        assert_eq!(
            opt_new_r("rudderc migrate --format"),
            Err("UnknownArgument".into())
        );
        // migrate accepts json-logs
        assert!(opt_new_r("rudderc migrate --json-logs").is_ok());

        // GENERATE
        // generate does not accepts json logs option
        assert!(opt_new_r("rudderc technique generate --json-logs").is_err());
        // generate does not accepts format option
        assert_eq!(
            opt_new_r("rudderc technique generate --format"),
            Err("UnknownArgument".into())
        );

        // COMPILE
        // compile accepts json logs option
        assert!(opt_new_r("rudderc compile --json-logs").is_ok());
        // compile accepts format option
        assert!(opt_new_r("rudderc compile --format dsc").is_ok());
        assert!(opt_new_r("rudderc compile --format cf").is_ok());
        assert!(opt_new_r("rudderc compile --format cfengine").is_ok());
        // compile format option requires a value
        assert_eq!(
            opt_new_r("rudderc compile --format"),
            Err("EmptyValue".into())
        );
        // compile format option requires dsc / cfengine as a value, nothing else
        assert_eq!(
            opt_new_r("rudderc compile --format 42"),
            Err("InvalidValue".into())
        );
        assert_eq!(
            opt_new_r("rudderc compile --format json"),
            Err("InvalidValue".into())
        );
        assert_eq!(
            opt_new_r("rudderc compile --format rl"),
            Err("InvalidValue".into())
        );
    }

    #[test]
    // tests if logging sets up as expected (backtrace, format, log levels etc)
    fn logging_infos() {
        assert_eq!(
            opt_new("rudderc technique read -b").extract_logging_infos(),
            (Logs::JSON, LevelFilter::Warn, true),
        );
        assert_eq!(
            opt_new("rudderc technique read -l info").extract_logging_infos(),
            (Logs::JSON, LevelFilter::Info, false),
        );
        assert_eq!(
            opt_new("rudderc technique read --stdout").extract_logging_infos(),
            (Logs::None, LevelFilter::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc migrate").extract_logging_infos(),
            (Logs::Terminal, LevelFilter::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc compile").extract_logging_infos(),
            (Logs::Terminal, LevelFilter::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc migrate --stdout").extract_logging_infos(),
            (Logs::None, LevelFilter::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc compile --stdout").extract_logging_infos(),
            (Logs::None, LevelFilter::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc migrate -j").extract_logging_infos(),
            (Logs::JSON, LevelFilter::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc compile -j").extract_logging_infos(),
            (Logs::JSON, LevelFilter::Warn, false),
        );
        // tricky one
        assert_eq!(
            opt_new("rudderc migrate -j --stdout").extract_logging_infos(),
            (Logs::None, LevelFilter::Warn, false),
        );
        // tricky one
        assert_eq!(
            opt_new("rudderc compile --stdout -j").extract_logging_infos(),
            (Logs::None, LevelFilter::Warn, false),
        );
    }

    fn assert_err_msg(cli: &str, expect: &str) {
        match opt_new(cli).extract_parameters() {
            Err(Error::User((msg, _))) => assert_eq!(msg, expect),
            _ => assert!(
                false,
                format!("expected error for '{}', but test result is ok", cli)
            ),
        };
    }
    fn assert_ok(cli: &str, ctx: IOContext, test_content: bool) {
        match opt_new(cli).extract_parameters() {
            Err(e) => assert!(false, format!("expected ok, got err: '{}'", e)),
            Ok(context) => {
                assert_eq!(context.stdlib, ctx.stdlib);
                assert_eq!(context.input, ctx.input);
                assert_eq!(context.output, ctx.output);
                assert_eq!(context.format, ctx.format);
                assert_eq!(context.action, ctx.action);
                if test_content {
                    // cannot test input_content properly unless we read each file, not the purpose of the test
                    assert_eq!(context.input_content, ctx.input_content);
                }
            }
        };
    }

    #[test]
    // tests if logging sets up as expected (backtrace, format, log levels etc)
    fn cli_to_context() {
        // err, config file does not exist
        assert_err_msg(
            "rudderc compile -c DOESNOTEXIST",
            "Could not read toml config file: No such file or directory (os error 2)",
        );

        // err, config dir + input file does not exist
        assert_err_msg(
            "rudderc technique read -c ./tools/rudderc-dev.conf -i DOESNOTEXIST",
            "Commands: input does not match any existing file",
        );

        // should be an error: no input file defined
        assert_err_msg(
            "rudderc compile -c ./tools/rudderc-dev.conf",
            "Commands: no input or input does not match any existing file",
        );

        // real technique path: tests/techniques/simplest/technique.rl.
        // conf input folder: tests/techniques
        // input specified so should ignore stdin default
        assert_ok(
            "rudderc technique read -c ./tools/rudderc-dev.conf -i simplest/technique.rl",
            IOContext {
                stdlib: PathBuf::from("libs/"),
                input: "technique.rl".to_owned(),
                input_content: "IGNORED FIELD".to_owned(),
                output: Some(PathBuf::from("tests/techniques/simplest/technique.json")), // based on input + updated extension
                format: Format::JSON,
                action: Action::ReadTechnique,
            },
            false,
        );

        // specify output, output dir exists, output file does not exist and wrong extension
        // input specified so should ignore stdin default
        assert_ok(
            "rudderc technique generate -c ./tools/rudderc-dev.conf -i simplest/technique.rl -o simplest/try_technique.randomext",
            IOContext {
                stdlib: PathBuf::from("libs/"),
                input: "technique.rl".to_owned(),
                input_content: "IGNORED FIELD".to_owned(),
                output: Some(PathBuf::from("tests/techniques/simplest/try_technique.json")), // based on input + updated extension
                format: Format::JSON,
                action: Action::GenerateTechnique,
            },
            false,
        );

        // compile format check
        assert_ok(
            "rudderc compile -c ./tools/rudderc-dev.conf -f cf -i simplest/technique.rl -o simplest/try_technique.randomext",
            IOContext {
                stdlib: PathBuf::from("libs/"),
                input: "technique.rl".to_owned(),
                input_content: "IGNORED FIELD".to_owned(),
                output: Some(PathBuf::from("tests/techniques/simplest/try_technique.rl.cf")), // updated extension
                format: Format::CFEngine,
                action: Action::Compile,
            },
            false,
        );

        // // compile format check
        assert_ok(
            "rudderc compile -c ./tools/rudderc-dev.conf -f cf -i simplest/technique.rl -o simplest/try_technique.randomext",
            IOContext {
                stdlib: PathBuf::from("libs/"),
                input: "technique.rl".to_owned(),
                input_content: "IGNORED FIELD".to_owned(),
                output: Some(PathBuf::from("tests/techniques/simplest/try_technique.rl.cf")), // updated extension
                format: Format::CFEngine,
                action: Action::Compile,
            },
            false,
        );

        // TODO specifically test input_content
    }
}
