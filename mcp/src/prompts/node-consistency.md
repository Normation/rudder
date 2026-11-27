# Consistency of a node's policies

Every directive applied to a node can be correct on its own and still conflict with the others.
Look at them together:

- **Conflicting states**: two directives managing the same object differently. A file templated
  or copied by one directive and edited by another, a package present in one and absent in
  another (or in different versions), a service started in one and stopped in another, enabled
  and disabled at boot, a user or group with different settings, a key set to different values in
  the same file. Such directives fight at every agent run: the object is repaired back and forth,
  and compliance flaps.
- **Duplicates**: the same thing managed twice, even with the same values, makes reports
  ambiguous and changes risky. Skipped directives (`skippedDetails`) are the same unique technique
  assigned by several rules: only one applies, check that it is the intended one.
- **Dependencies between directives**: a technique using a condition or variable defined by
  another directive (a `condition` naming another technique's result condition, a `${...}`
  variable it does not define). It breaks when that directive is removed, disabled, reordered, or
  in another policy mode.
- **Properties**: directive parameters reading node properties (`${node.properties[...]}`) that
  the node does not have, or that are in error (`error`, `propertiesError`). A property error
  blocks the node's policy generation. A property defined in several places is resolved to the
  closest one (`definedIn`): check that it is the expected value.
- **Platform**: methods or techniques that do not apply to the node's OS (Windows methods on a
  Linux node, package managers of another distribution), or conditions that can never be true on
  it.
- **Compliance as evidence**: errors and non-compliance in the node's compliance often point at
  the conflicts above. Use them to confirm a finding, not as the finding itself.
