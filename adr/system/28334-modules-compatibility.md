# Modules compatibility layer

* Status: accepted
* Deciders: AMO, FDA
* Date: 2026-02-13

## Context

We came across the need to introduce a paramter in the `system-updates` module, for expluding specific packages.
It raises a problem of compatibility with the previous version of the module, as an older module would simply ignore the added parameter 
and potentially do something different from what the user expected.
This is something we must prevent.

## Decision

We need to add some information in the module. We considered a version number, or a list of supported features, and decided to go with the latter, as it is more flexible and allows us to add new features in older versions of the module.

## Consequences

* Remove the unused parts of the modules metadata. We will add it back when we have a real use for it, but for now it is just a source of confusion and maintenance burden.
* Add the features list to the module trait interface:

```rust
pub const MODULE_NAME: &str = env!("CARGO_PKG_NAME");
pub const MODULE_FEATURES: [&str; 1] = ["excludes"];

impl ModuleType0 for SystemUpdateModule {
    fn metadata(&self) -> ModuleTypeMetadata {
        ModuleTypeMetadata::new(MODULE_NAME, Vec::from(MODULE_FEATURES))
    }
  // ...
}
```

* Modify the `--info` option of generic CFEngine modules CLI interface (behind `--cfengine`) to return a JSON with the required information:

  * The `name` is the crate name.
  * The `features` is a list of supported features, which can be used by the method to determine if the module is compatible with the parameters it wants to use.
  * The `agent_version` is the (Rudder) version of the agent that the module was compiled as.

```json
{
  "name": "rudder-module-system-updates",
  "features": [
    "excludes"
  ],
  "agent_version": "9.0.4"
}
```

* The general principle stays that we ignore unknown parameters and strive to use default values that allow this.
* Implement compatibility mechanisms in the methods themselves (in CFEngine and PowerShell), as they are distributed by the server and can hence handle the new parameter.
  * In this precise case, the behavior is to ensure the `excludes` feature is present only if excludes are passed. If not, the method call fails and nothing is done.

