# Propagating the security context with QueryContext / ChangeContext

* Status: accepted
* Deciders: FAR
* Date: 2026-07-03

## Context

Every repository read and write must know the acting actor's tenant grant in order to filter results and
authorize changes. Two properties are required:

* the grant must be established only at trusted edges (REST request handler, Lift snippet, or a managed
  system service) and never re-derived deep in the call stack, where it would be easy to get wrong or spoof;
* there must be a guarantee that no code path forgets to pass it - a silent hole would be a security bug.

## Decision

Introduce two context objects carrying the actor and their `TenantAccessGrant`:

* `QueryContext` (abbreviated `qc`) for read operations;
* `ChangeContext` (abbreviated `cc`) for write operations (also carries modification id / date / message).

They are passed as Scala 3 `using`/`given` implicit parameters on the repository APIs. Because the whole
chain takes an implicit context, the compiler enforces it is threaded end-to-end: a missing context is a
**compile error, not a runtime hole**.

The context is filled only at edges:

* REST handlers build it from the authenticated user (`AuthzToken` → `qc`/`cc`);
* Lift snippets obtain it through the safe-snippet pattern (see ADR
  `28452-avoid-currentuser-for-querycontext-in-lift-snippets`);
* system operations use explicit, named, all-tenants contexts (`QueryContext.systemQC`,
  `ChangeContext.newForRudder`).

At authentication the grant is refined against the set of currently existing tenants
(`refineTenantAccessGrant`), so a grant referencing a deleted tenant collapses (`ByTenants` shrinks, and to
`None` if nothing remains).

## Consequences

* Adding a repository method forces the caller to provide a context; the "no hole" property is checked by
  the compiler rather than by review.
* The escape hatches for system/unknown contexts (`systemQC`, `todoQC`, `noneQC`, `newForRudder`) are few,
  named, and greppable, so they can be audited.
* Known follow-up: only the REST authentication path refines the grant today; the interactive UI login path
  does not yet call `refineTenantAccessGrant`, so a UI session can outlive a tenant's deletion.
