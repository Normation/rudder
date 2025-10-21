use std::process::ExitCode;

fn main() -> ExitCode {
    match agent::run() {
        Err(e) => {
            println!("{:?}", e);
            ExitCode::FAILURE
        }
        Ok(_) => ExitCode::SUCCESS,
    }
}
