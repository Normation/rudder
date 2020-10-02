#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::Error;
    use pretty_assertions::assert_eq;

    // syntaxic sugar + more accessible error message when useful
    fn opt_new_r(params: &str) -> std::result::Result<CLI, String> {
        CLI::from_iter_safe(params.split_whitespace()).map_err(|e| format!("{:?}", e.kind))
    }
    // syntaxic sugar + directly returns CLI. !! If error, panic
    fn opt_new(params: &str) -> CLI {
        CLI::from_iter(params.split_whitespace())
    }

    #[test]
    // tests if cli parameters are handled properly (setup behavior, forbids unwanted ones etc)
    fn cli() {
        // TECHNIQUE READ + GENERAL
        assert_eq!(
            // full example works fine
            opt_new_r("rudderc technique read -c tools/rudderc-dev.conf -i tests/techniques/6.1.rc5/technique.rl -o tests/techniques/6.1.rc5/technique.ee.cf -l warn -b --stdin --stdout"),
            Ok(CLI::Technique(Technique::Read {
                options: Options {
                    config_file: PathBuf::from("tools/rudderc-dev.conf"),
                    input: Some(PathBuf::from("tests/techniques/6.1.rc5/technique.rl")),
                    stdin: true,
                    stdout: true,
                    output: Some(PathBuf::from("tests/techniques/6.1.rc5/technique.ee.cf")),
                    log_level: LogLevel::Warn,
                    backtrace: true,
                },
            }))
        );
        assert_eq!(
            opt_new_r("rudderc technique read"), // cleanest setup works and default are ok
            Ok(CLI::Technique(Technique::Read {
                options: Options {
                    config_file: PathBuf::from("/opt/rudder/etc/rudderc.conf"), // default
                    input: None,
                    stdin: false,
                    stdout: false,
                    output: None,
                    log_level: LogLevel::Warn, // default
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
        assert!(opt_new_r("rudderc technique save").is_err());
        // save does not accepts format
        assert_eq!(
            opt_new_r("rudderc save --format"),
            Err("UnknownArgument".into())
        );
        // save accepts json-logs
        assert!(opt_new_r("rudderc save --json-logs").is_ok());

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
            (LogOutput::JSON, LogLevel::Warn, true),
        );
        assert_eq!(
            opt_new("rudderc technique read -l info").extract_logging_infos(),
            (LogOutput::JSON, LogLevel::Info, false),
        );
        assert_eq!(
            opt_new("rudderc technique read --stdout").extract_logging_infos(),
            (LogOutput::None, LogLevel::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc save").extract_logging_infos(),
            (LogOutput::Raw, LogLevel::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc compile").extract_logging_infos(),
            (LogOutput::Raw, LogLevel::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc save --stdout").extract_logging_infos(),
            (LogOutput::None, LogLevel::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc compile --stdout").extract_logging_infos(),
            (LogOutput::None, LogLevel::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc save -j").extract_logging_infos(),
            (LogOutput::JSON, LogLevel::Warn, false),
        );
        assert_eq!(
            opt_new("rudderc compile -j").extract_logging_infos(),
            (LogOutput::JSON, LogLevel::Warn, false),
        );
        // tricky one
        assert_eq!(
            opt_new("rudderc save -j --stdout").extract_logging_infos(),
            (LogOutput::None, LogLevel::Warn, false),
        );
        // tricky one
        assert_eq!(
            opt_new("rudderc compile --stdout -j").extract_logging_infos(),
            (LogOutput::None, LogLevel::Warn, false),
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
                assert_eq!(context.command, ctx.command);
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
                command: Command::ReadTechnique,
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
                command: Command::GenerateTechnique,
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
                command: Command::Compile,
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
                command: Command::Compile,
            },
            false,
        );

        // TODO specifically test input_content
    }
}
