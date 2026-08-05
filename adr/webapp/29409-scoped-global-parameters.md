# Scoped global parameters: a property level between global and group

* Status: accepted
* Deciders: FAR
* Date: 2026-07-28

## Context

Rudder resolves a node's properties over three levels: global parameters, group properties, node
properties. Only the two lower levels can be restricted to a subset of the fleet; a global parameter
is, by construction, distributed to **every** node - it is merged into every node's properties
(`NodeContextBuilder`, "now we set defaults global parameters to all nodes"), written into every
node's `properties.json`, and written again into every node's `rudder-parameters.json`.

That is a problem for any producer of configuration that concerns only part of the fleet. The
security-benchmarks plugin is the case that forced the issue: a benchmark stores its per-item
enable/audit/enforce modes and its parameters as a global parameter named after the benchmark, then
relies on group and node properties for per-group / per-node overrides. The benchmark applies to a
`RuleTarget`, but its configuration lands on every node of the installation.

Three distinct consequences:

* **Tenant leak.** ADR
  [`28945-tenant-enforcement-at-policy-generation`](multi-tenants/28945-tenant-enforcement-at-policy-generation.md)
  clamps a *rule* to the nodes its `SecurityTag` can see. There is no equivalent clamp for
  parameters, so a tenant-tagged global parameter is written verbatim into the policies of every
  node of every tenant.
* **Scale.** A CIS benchmark model has ~400 items; the derived default value is tens of KB of JSON,
  replicated to every node, twice on disk, and held per node in the in-memory property-hierarchy
  cache. It also makes *any* global parameter change regenerate *all* nodes
  (`NodeConfigurationCacheRepository`, `parameterHash`).
* **Model.** A plugin's internal storage occupies the user-facing "global parameter" namespace.

Two options were rejected before this one:

* **Reuse `Visibility.Hidden` to mean "not distributed".** `Visibility` is today a pure API/UI
  filter, and in production only the benchmark plugin sets `Hidden`, so its semantics were ours to
  change. But the benchmark's mode keys are consumed **at agent runtime**: `common/1.0/properties.cf`
  reads `node.properties[<benchmarkId>]` and defines a class `<benchmarkId>_<itemId>_<mode>` per
  item (the `rudder_auto_conditions` mechanism), and the generated `technique.cf` guards every
  control on that class. Not distributing the property would silently define no class, run no
  control, and raise no error. More fundamentally, `Visibility` is a *unary* predicate on the
  property while the requirement is *relational* - "this value belongs to these nodes" - so no
  re-reading of a boolean can express it.
* **Give targets a stable identity (a first-class `Target` entity) and hang properties off it.**
  Attractive for other reasons (targets in rules, compliance per target), but not needed here: the
  stable identity is already the **property name**. Group and node overrides key off the name and
  survive any change of target, so only the *distribution scope* has to move.

## Decision

**Add an optional `scope` to a global parameter.** A parameter with a scope is distributed only to
the nodes matched by that scope; a parameter without one keeps today's behaviour.

* The scope is a field on `GlobalParameter`, not a new entity: same shape and storage pattern as
  `security` and `visibility` (a key in the property's HOCON config plus an optional LDAP
  attribute), one repository, one API, one event log.
* The scope is typed **`RuleTarget`** and persisted with the existing `RuleTarget` string form. This
  is deliberate: if named targets ever become entities, `NamedTarget(id)` is just another
  `RuleTarget` value and the field needs no migration.
* The accessor is a total `Option[RuleTarget]`, because the target is **parsed where the parameter
  crosses a boundary** - `LDAPEntityMapper.entry2Parameter` and the git-archive unserialisation both
  reject an unparsable target and fail the read. Reading a broken scope as "no scope" would mean
  "distribute everywhere", exactly the leak this ADR removes, so it must not be a case the domain
  has to handle: it is rejected before a `GlobalParameter` exists. Every other builder takes an
  already-typed `RuleTarget`.
* The accessor lives on `GlobalParameter` only. A `NodeProperty.scope` would be meaningless, and a
  signature must not lie.

**Resolution order becomes global < scoped < group < node**, materialized by a new
`ParentProperty.Target` case in the hierarchy ADT (kind `target`). A scoped parameter overrides the
unscoped parameter of the same name and is in turn overridden by group and node properties, exactly
like any other level.

**Scope resolution happens once per property, not once per node.** Targets are resolved through
`RoNodeGroupRepository`/`FullNodeGroupCategory.getNodeIds` with a `NodeAndServerIds` obtained from
`NodeFactRepository.getNodeAndServerIds()` - never by hand.

**A scope belongs to a property *name*, not merely to a value, and it defines that name's domain of
definition.** On a node outside the scope, the name does not exist at all: not as an inherited
default, and not through a group or node override of the same name either. This is the load-bearing
part of the decision. Restricting only the parameter's own value would leave the leak wide open -
a node excluded from a benchmark target but member of a group that overrides the benchmark's
property would still receive that configuration, which is exactly what the scope is for.

This is unambiguous because **a global parameter's name is unique in storage** (its LDAP RDN *is*
`parameterName`), so a name carries at most one parameter and therefore at most one scope. There is
no "which scope wins" question, and no need for a combining or conflict rule between scopes.

Concretely, resolution is unchanged - the group DAG, the override order, everything - and the scope
is applied as a filter on names once the merge is done. Errors about an out-of-scope name are
dropped too: a node must not fail its property resolution, and therefore its policy generation,
because of a property it does not have.

**`rudder_auto_conditions` therefore stays a single unscoped parameter.** Beyond the storage
constraint, what it discloses is cheap: benchmark ids are random UUIDs unless a caller supplies its
own, so a node learns how many benchmarks exist and their opaque ids - not their names, and not
their configuration. It is also functionally safe: on a node outside a benchmark target the id is
listed but `node.properties[<benchmarkId>]` is absent, which yields no class and no error.

**Scoped parameters are shown at group level like unscoped ones**, so that a group overriding one
displays what it overrides. This view is *indicative*: a group is not necessarily a subset of the
scope, so the displayed parent is what a member node inside the scope inherits, not a promise about
every member. That matches the existing meaning of the group property view, which is already a
potential hierarchy rather than a per-node truth (group membership is dynamic). The authoritative
resolution is the per-node one, where the domain-of-definition rule above applies.

**Referencing a scoped parameter from a node outside its scope is a generation error.** The property
is genuinely absent for that node, and `${rudder.parameters[X]}` fails as it does for any unknown
parameter. Failing closed here is preferable to silently substituting the unscoped value.

## Consequences

* The tenant leak and the fleet-wide bloat are fixed for any producer of partial configuration, not
  only for security benchmarks.
* Combined with the removal of the parameter distribution channel (`rudder-parameters.json` and the
  `rudder_parameters` bundle, planned for the next major), the `parameterHash` term of the
  node-configuration hash disappears and a scoped-parameter change only regenerates the nodes in its
  scope. Scoping alone does not achieve that: the two changes are worth sequencing together.
* The property JSON contract gains a `target` kind in the inheritance hierarchy. This is additive,
  but the Elm decoder rejects unknown kinds, so the UI must be updated in the same change.
* Migrating an existing producer to a scope removes the property from out-of-scope nodes, which
  stops whatever it was driving there. That is the intent, but it is a visible behaviour change and
  must be release-noted, not shipped silently.
* Because the scope governs the whole name, a group or node override of a scoped name silently does
  not apply outside the scope. That would be surprising for a user-authored parameter - but `scope`
  is deliberately not settable through the API, so every scoped name is owned by the plugin that
  created it, and overriding it outside its scope has no meaning.
* Should a "several producers feed one shared name" use case ever appear, it needs both a composite
  `(name, scope)` identity in the parameter repository and a combination rule in the merge engine.
  Neither is built here, on purpose: nothing can produce that state today.
* `Visibility` keeps its current meaning - API/UI listings and archives - and stays useful next to
  `scope`: a benchmark's blob should be scoped *and* hidden. The one distribution rule attached to
  it is that a hidden parameter is not published in the agent's parameter namespace, since "hidden"
  means "not a user-facing parameter".
