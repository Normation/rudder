use crate::configuration::DEFAULT_CONFIGURATION_FILE;
use clap::{crate_version, App, Arg};
use slog::Level;
use std::path::PathBuf;

#[derive(Debug)]
pub struct CliConfiguration {
    pub configuration_file: PathBuf,
    pub verbosity: Level,
}

pub fn parse() -> CliConfiguration {
    let matches = App::new("relayd")
        .version(crate_version!())
        .author("Rudder team")
        .about("Rudder relay server")
        .arg(
            Arg::with_name("config")
                .short("c")
                .long("config")
                .default_value(DEFAULT_CONFIGURATION_FILE)
                .value_name("FILE")
                .help("Sets a custom config file")
                .takes_value(true),
        )
        .arg(
            Arg::with_name("verbosity")
                .short("v")
                .long("verbosity")
                .default_value("info")
                .possible_values(&["error", "warn", "info", "debug", "trace"])
                .value_name("VERBOSITY")
                .help("Sets the level of verbosity")
                .takes_value(true),
        )
        .get_matches();

    CliConfiguration {
        configuration_file: matches
            .value_of("config")
            .expect("No configuration file specified")
            .into(),
        verbosity: matches
            .value_of("verbosity")
            .expect("Missing verbosity level")
            .parse::<Level>()
            .expect("Unknown verbosity level"),
    }
}
