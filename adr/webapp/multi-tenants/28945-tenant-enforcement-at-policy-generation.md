# Enforcing the tenant boundary at policy generation

* Status: accepted
* Deciders: FAR
* Date: 2026-07-03

## Context

A tenant tag on a configuration object is a *visibility* filter. The dangerous part is *effect*: a rule
owned by a tenant must not generate policy on another tenant's nodes, nor apply another tenant's directives.
Rule targets (`AllTarget`, groups) and dynamic-group membership can otherwise cross tenant lines even when
the rule itself is only visible to its tenant. Policy generation runs as a system operation (it fetches all
objects), so the read-time tenant filtering does not apply there.

## Decision

Convert a rule's `SecurityTag` into a read-grant *scope* (`None`/`Open` → `All`; `ByTenants(ts)` → a read
grant on `ts`) and enforce, when a rule is resolved to nodes during generation:

* **node level (load-bearing):** keep only the targeted nodes whose `security` the rule's scope can see. This
  is a monotonic clamp on the fully-resolved node set, so it contains any composite target
  (union/intersection/exclusion) and any node wrongly present in a group's `serverList`.
* **target level:** drop simple targets the rule cannot see - foreign groups, and admin-only special targets
  (`AllTarget` etc., which have no tenant), so they are not usable by a tenant-scoped rule.
* **directive level:** drop directives the rule does not share a tenant with.

Dynamic-group membership is computed under the **group's own** tenant scope, so a group only ever contains
nodes its tenants can see, regardless of who triggers the update.

Untagged/admin objects map to the `All` scope, so admin rules and non-tenant deployments are unaffected.

## Consequences

* The node-level filter is the actual guarantee; the target- and directive-level filters add consistency and
  keep admin-only special targets out of tenant rules.
* Because a tenant rule drops admin-only special targets, a tenant rule whose only target is `AllTarget`
  targets nothing - a tenant must use a group to select "all my nodes".
* The `NodeFact`-derived data needed at generation (is-policy-server + tenant tag) travels as a single
  `NodeSecurityInfo` per node rather than as two parallel maps.
* Known follow-up: rule "applied" status (`RuleApplicationStatusService` / `getAppliedRuleIds`) still resolves
  nodes without the node-level tenant filter, so it can report a rule as applied on nodes generation would
  exclude. It must be made consistent with generation.
