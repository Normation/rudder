# Tenant filtering as repository proxies, authorization centralized in TenantCheckLogic

* Status: accepted
* Deciders: FAR
* Date: 2026-07-03

## Context

Tenant filtering must be added to persistence without entangling it with the existing `LDAPRepositories`,
which are large and must stay reviewable. We also want a single, small, unit-testable home for every
tenant/authorization decision, so that a new repository method cannot accidentally bypass a check.

## Decision

**Separate persistence from tenant logic with proxies.** The `LDAPRepositories` stay tenant-agnostic (pure
persistence). Each is wrapped by a proxy (`RoTenant*` / `WoTenant*`) that:

* on reads, post-filters results with the tenant scoping;
* on writes, authorizes the operation, then delegates the actual fetch/store to the underlying repo.

Only the proxies are wired into the application. The raw repositories are used solely for internal plumbing
(archive import/export).

**Abstract taggable objects with a typeclass.** `HasSecurityTag[A]` exposes `security`, `isSystem`,
`debugId`, `updateSecurityContext`. Every taggable type provides an instance.

**Centralize all authorization in `TenantCheckLogic`** (`checkTenant`). Proxies never read `accessGrant.*`
directly; they only call the service, which is the single authorization entry point:

* read filtering: `check` / `filter` / `collect` / `filterStream` / `getMapView`;
* `checkModify(obj)` - may change/move this object: tenant write-visibility **and** system-admin-only;
* `checkWriteInto(container)` - may create/move children under this container: tenant write-visibility only,
  deliberately **not** system-gated (tenant objects live under the shared/system root categories);
* `checkDelete(obj)` - delete authorization (a delete is a write, so same rules as `checkModify`);
* `checkAdmin` - admin-only operations that have no object to check (e.g. policy server targets);
* `manageCreate` / `manageUpdate` - the create/update tag logic, which also enforce the system-object rule.

The lattice primitives (`canSee`, `canModify`, `restrictToWrite`, `plus`) remain on `TenantAccessGrant` and
are used only *inside* the service.

## Consequences

* The authorization surface is small and testable in isolation; the persistence layer is reviewed unchanged.
* A new write method cannot forget a tenant/system check - it must go through `checkTenant`.
* The YAML-based REST test framework was extended to drive API logic under several user profiles
  (admin / single-tenant / multi-tenant / read-only tenant / no-tenant), which pins these laws end to end.
* Slight indirection cost (a proxy per repository) in exchange for a clean, auditable separation.
