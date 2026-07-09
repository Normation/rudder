# Tenant model and security tags

* Status: accepted
* Deciders: FAR
* Date: 2026-07-03

## Context

Rudder needs to give an actor (user or API account) a *restricted view* of configuration objects
(active techniques, directives, rules, node groups, global parameters, and their categories) and nodes.
This segmentation - "tenants" - must be:

* orthogonal to the existing rights/roles system: it is a pre-filter that produces a smaller world, on top
  of which the usual authorization then applies;
* backward compatible and migratable: existing deployments have no tenants, and people want to keep their
  existing rules/groups/etc and merely sort them into tenants afterwards.

We need a single model shared by all taggable objects (nodes already had a simplified tenant notion).

## Decision

A *tenant* is an arbitrary segmentation identifier (ascii-alnum + `-`/`_`). Two orthogonal notions:

**Object side - `SecurityTag`** (optional on each taggable object):

* `None` (no tag): visible only to an actor with an all-tenants grant (admin). This is the default and the
  migration state of every pre-existing object.
* `ByTenants(tenants)`: visible to an actor sharing at least one of the listed tenants. An empty list means
  admin-only.
* `Open`: visible to everyone whatever their grant - used for library/shared roots that all tenants must see.

**Actor side - `TenantAccessGrant`**:

* `All`: sees everything (admin); this is what a user with no tenant restriction gets, so non-tenant
  deployments behave exactly as before.
* `None`: sees nothing.
* `ByTenants(accesses)`: a list of `tenantId:permission` where permission is `r` (read) or `rw` (read+write);
  a bare `tenantId` means `rw` for backward compatibility.

**Laws**:

* Read: an actor sees an object iff its grant is `All`, or the object is `Open`, or they share a tenant
  (whose access allows read) with a `ByTenants` object.
* Write: same, but only `rw` tenants are considered (`restrictToWrite` drops the `r`-only accesses, so the
  actor behaves as if it did not have the grant for a read-only tenant).
* Visibility is monotone: `None` (admin only) ⊂ `ByTenants(S)` ⊆ `ByTenants(S')` for `S ⊆ S'` ⊂ `Open`.

## Consequences

* Non-tenant deployments are unchanged: pre-existing objects are `None` (admin-only) and users default to
  the `All` grant, so everyone sees everything.
* Nodes are unified under the same `SecurityTag`/grant model as configuration objects.
* The JSON serialization of `SecurityTag` (`{"tenants":[...]}` for `ByTenants`, `"open"` for `Open`, absent
  for `None`) is an external API contract and must be evolved carefully.
* The lifecycle of the tag (who assigns/changes it, how it may evolve) is defined in a dedicated ADR.
