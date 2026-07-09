# Object tenant tag lifecycle: creation, change, and monotonic growth

* Status: accepted (monotonic-growth part: implementation pending)
* Deciders: FAR
* Date: 2026-07-03

## Context

We must define how a configuration object gets its tenant tag, who may change it, and how historical data
(the event log) stays sound when a tag changes over time. Migration requires that pre-existing (untagged)
objects can be assigned tenants, so tags cannot be made fully immutable from the start.

## Decision

**Creation.** When an object is created without an explicit tag, it inherits the creator's *writable*
tenants (`restrictToWrite.toSecurityTag`), never the read-only ones - a read-only tenant must not leak into a
created object. When the tenant feature is disabled, created objects are `None`. An explicitly provided tag
is still validated: the creator must be able to write it.

**Change.** Only an administrator (all-tenants grant) may change an object's tenant list. A non-admin's
attempt to change the list is ignored and the existing tag kept. This also makes a read-modify-write
round-trip safe: a tenant only ever reads the tenants it owns, so re-saving the object cannot accidentally
drop the tenants it could not see.

**Monotonic growth (accepted; implementation pending).** An object may only *gain* visibility, never lose it
(`None ⊂ ByTenants(S ⊆ S') ⊂ Open`). To narrow an object's tenants, one duplicates it into a fresh object and
chooses the narrower list at creation time. This law makes event-log filtering sound: record the object's
*pre-change* tag alongside each event and filter event reads by `grant.canSee(T_event)`. Because tags only
grow, `T_event ⊆ T_now`, so anyone who can see an event can currently see the object (no leak), while a
tenant added *after* a change never sees that change's pre-membership values (no historical-value leak).
Deleting a whole tenant is safe (it is also removed from every grant), and reusing a tenant id is an intended
recovery path for an accidentally deleted tenant (to be documented).

## Consequences

* Predictable, leak-free creation and admin-only re-scoping.
* The monotonic-growth law reverses the earlier "an admin can drop a tenant from an object" behavior and its
  test; those must be rewritten when the law is implemented.
* Event-log soundness additionally requires persisting the per-event tenant tag (an event-log schema change +
  migration of existing rows to admin-only). This is deferred to its own change.
