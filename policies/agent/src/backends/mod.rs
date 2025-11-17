mod agent_builder;
mod fake_agent;
mod fake_unix_agent;
mod fake_windows_agent;
mod verbosity;

pub use agent_builder::FakeAgentBuilder;
pub use fake_agent::*;
pub use fake_unix_agent::FakeUnixAgent;
pub use fake_windows_agent::FakeWindowsAgent;
pub use verbosity::Verbosity;

//todo implement condition definition in the standalone
