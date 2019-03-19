extern crate relayd;

use clap::{crate_version, App, Arg};
use relayd::fake::reporting::runlog;

#[derive(Debug)]
pub struct FakeNodeConfiguration {
    pub node_id: Option<String>,
}

pub fn parse() -> FakeNodeConfiguration {
    let matches = App::new("agent")
        .version(crate_version!())
        .author("Rudder team")
        .about("Rudder fake agent")
        .arg(
            Arg::with_name("node_id")
                .short("i")
                .long("node_id")
                .value_name("ID")
                .help("Set a specific node id for reporting")
                .takes_value(true),
        )
        .get_matches();

    FakeNodeConfiguration {
        node_id: matches.value_of("node_id").map(|x| x.to_string()),
    }
}

fn main() {
    println!("{:}", runlog(None));
}
