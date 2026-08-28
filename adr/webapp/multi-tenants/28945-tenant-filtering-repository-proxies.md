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

Only the proxies are wired into the application *for the business services*. Two caveats:

* the raw repositories are used in archive import/export and are also injected into a handful of
  system services that legitimately run with an all-tenants context (`RefuseGroups`,
  `DynGroupUpdaterServiceImpl`, `ItemRollbackServiceImpl`, `ItemArchiveManagerImpl`,
  `PolicyGenerationServiceImpl`, `DependencyAndDeletionServiceImpl`, bootstrap checks).  
  Nothing forbids a new caller from taking a raw repository, so "a write can not bypass the check" is a review property;
* the read repository a write proxy delegates to is the **raw** one, not the filtering one. This does not
  weaken anything because the write law is enforced by the service, and the object lookup is done by it.

**Abstract taggable objects with a typeclass.** `HasSecurityTag[A]` exposes `security`, `isSystem`,
`debugId`, `updateSecurityContext`. Every taggable type provides an instance.

**Centralize all authorization in `TenantCheckLogic`** (`checkTenant`). Proxies never read `accessGrant.*`
directly; they only call the service, which is the single authorization entry point:

* read filtering: `check` / `filter` / `collect` / `filterStream` / `getMapView`;
* writes: there is **no standalone check** to remember to call - a check the caller must remember is a check
  the caller can forget. Each write goes through the one operation that names it, which *wraps* the
  persistence action, so the action only ever runs on an authorized and correctly tagged object:
  `manageCreate`, `manageUpdate`, `manageSave` (upsert), `manageModify` (change an object without submitting
  a new version of it), `manageUpdateAndMove`, `manageMove`, `manageDelete`, and `manageDeletePure` for the
  in-memory caches that decide under a `Ref.modify`;
* `checkAdmin` - admin-only operations that have no `HasSecurityTag` object at all (e.g. policy server
  targets).

**What the repository states, and what the service decides.** The repository states *where* to find things
(`existing`, `into`) and what an absent object means *for that operation* (`IfAbsent.Noop` / `IfAbsent.Fail`,
which genuinely differs: deleting an absent directive is a no-op, deleting an absent rule is an error);
`Container.none` states that an object has no container at all (global parameters, node facts) rather than
leaving the check out silently. Everything else belongs to the service: the tenant feature status, the tag a
created object gets and how an existing one may evolve, the system-object rule, and the change context - a
possibly restricted one - the action runs under.

**The security context of each lookup is part of the law, not a caller choice.** The two write lookups have
opposite requirements, so the service provides both contexts itself (the lookups are `QueryContext ?=> ...`
context functions, so a repository writes `roRepo.getOpt(id)` with no context at all):

* the object being written is read with system rights (`QueryContext.systemQC`). Reading it with the actor's
  context would conflate "does not exist" with "exists but you can not see it", and a save would then take
  its creation path on an existing object and re-tag it with the writer's tenants. Revealing existence this
  way is harmless: the write is still authorized against the existing object, so it can only end in a denial;
* the container is read with the **actor's own** context, so a container the actor can not see reads as absent.

The lattice primitives (`canSee`, `canModify`, `restrictToWrite`, `plus`) remain on `TenantAccessGrant` and
are used only *inside* the service.

## Consequences

* The authorization surface is small and testable in isolation; the persistence layer is reviewed unchanged.
  It is now covered by a direct unit suite (`TenantCheckLogicTest`, one test per law) on top of the
  repository and API suites.
* A new write method can not forget *part* of the check: there is a single operation per kind of write and it
  wraps the persistence action, so an authorized write and an unauthorized one are not two code paths that
  can drift. What is not yet compiler-enforced is that the write goes through the proxy at all.
* The YAML-based REST test framework was extended to drive API logic under several user profiles
  (admin / single-tenant / multi-tenant / read-only tenant / no-tenant), which pins these laws end to end.
* Slight indirection cost (a proxy per repository) in exchange for a clean, auditable separation.
