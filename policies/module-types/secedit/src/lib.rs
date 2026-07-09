use crate::cli::Cli;
use crate::secedit::Secedit;
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::run_module;

mod cli;
mod secedit;

pub fn entry() -> Result<(), anyhow::Error> {
    let promise_type = Secedit::new();
    if called_from_agent() {
        run_module(promise_type)
    } else {
        Cli::run()
    }
}
