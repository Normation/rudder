# System configuration objects are admin-only, uniformly

* Status: accepted
* Deciders: FAR
* Date: 2026-07-03

## Context

With tenants, an `Open` object is (by decision) visible - and writable/deletable - by anyone: the protection
of shared/library objects is the pre-existing `system` flag, not the security tag. But a system object now
carries a cross-tenant *scope*: it is shared/global, so a change to one - even one that looks tenant-local -
can have effects on several tenants' nodes. Deciding what a system object is and how it behaves is therefore
not something a tenant-scoped actor should be able to do.

Note also that `None`-tagged objects (the default) are already admin-only through the normal
visibility/`canModify` rules; the gap is a system object tagged `Open` or `ByTenants`, which `canSee`/
`canModify` would otherwise let a tenant actor change.

## Decision

Managing (create / modify / move / delete) a **system** object is allowed only to an actor with an
all-tenants (admin) grant. This is enforced uniformly, on every write of every tenant-scoped repository,
inside `TenantCheckLogic`:

* folded into `checkModify`, `checkDelete` and `manageCreate`/`manageUpdate` (which read `obj.isSystem`);
* `checkAdmin` covers system operations that have no `HasSecurityTag` object (e.g. policy server targets).

`HasSecurityTag.isSystem` has **no default**: each instance states its own system notion explicitly (a rule/
directive/group/category exposes its `isSystem`; an active technique is system when it carries the system
policy type; properties, parameters and nodes are never system).

Container checks (`checkWriteInto`) are intentionally **not** system-gated: creating a tenant object under a
system root category is normal and must stay allowed.

## Consequences

* No-op for legitimate flows: system objects are `None`-tagged by default (already admin-restricted) and all
  internal system maintenance runs with an all-tenants context, so the guard never blocks it. Its value is
  closing the `Open`/`ByTenants` system-object case.
* `system` becomes a load-bearing, cross-tenant protection: an object's "system" meaning and behavior are an
  administrator responsibility.
* The distinction between "modify this object" (`checkModify`, system-gated) and "create under this
  container" (`checkWriteInto`, not system-gated) is essential - conflating them would forbid tenants from
  creating anything under the system root categories.
