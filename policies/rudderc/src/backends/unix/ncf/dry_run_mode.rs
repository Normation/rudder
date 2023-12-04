use rudder_commons::PolicyMode;

use crate::backends::unix::cfengine::{promise::Promise, quoted};

pub fn push_policy_mode(op: Option<PolicyMode>, promiser: String) -> Option<Promise> {
    op.map(|p| {
        Promise::usebundle(
            "push_dry_run_mode",
            None,
            Some(&promiser),
            vec![match p {
                PolicyMode::Enforce => quoted("false").to_string(),
                PolicyMode::Audit => quoted("true").to_string(),
            }],
        )
    })
}
pub fn pop_policy_mode(op: Option<PolicyMode>, promiser: String) -> Option<Promise> {
    if op.is_some() {
        Some(Promise::usebundle(
            "pop_dry_run_mode",
            None,
            Some(&promiser),
            vec![],
        ))
    } else {
        None
    }
}
