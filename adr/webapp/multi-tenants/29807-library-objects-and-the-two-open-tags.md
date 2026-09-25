# Library objects: `open` becomes `open-ro` and `open-rw`

* Status: accepted
* Deciders: FAR
* Date: 2026-09-25

## Context

The `SecurityTag` of [the tenant model](28945-tenant-model-and-security-tags.md) had a single `Open` value, described as "visible to everyone whatever their grant - used for library/shared roots that all tenants must see". The read law used it as intended. The write law reused the same predicate with a write-restricted grant (`restrictToWrite.canSee`), so "everyone sees it" meant "anyone who may write at all may change it".

That is wrong for what `Open` was introduced for. The root categories, the groups and special targets Rudder provides and the techniques it ships are "library" objects: every tenant must see them and build on them, and none of them may change them. 

The broader "modifiable by anyone" meaning can be kept for example to build a template of technique collaboratively that each participant clones before using it. 

## Decision

`SecurityTag.Open` is split in two values, and `canWrite` is separated from `canSee`.

* `OpenRo`, serialized `open-ro`: everybody sees and uses the object, only an administrator (all-tenants grant) changes it. This is the library tag, exposed as `SecurityTag.LIBRARY_SECURITY_TAG`.
* `OpenRw`, serialized `open-rw`: everybody sees the object and anybody with a write grant may change it. 
* `canSee` is unchanged: both open tags are visible to every actor, including one with no grant at all.
* `canWrite(tag)` is no longer `restrictToWrite.canSee(tag)`: for a `ByTenants` actor, `OpenRo` is not writable. `canModify(obj)` is `canWrite(obj.security)`, and every write path goes through it.

The old name `open` is unclear for `open read-only`, and since the read/write case seems rarer, there is no reason to keep it for that borader case. It will be only kept for decoding and map to `open-ro` (which was likely the intent). 

Putting an object into a container only requires seeing that container; the object put there carries its own tag and is checked on its own.

The two open tags sit at the same place in the visibility lattice (`isWiderOrEqual`). The monotonic-growth law constrains visibility only, so moving an object between them is allowed - and only an administrator may change a tag anyway. 
`join`, used when an object inherits from several sources at once (an active technique covers every version of its technique, each declaring its own tag), returns `OpenRo` as soon as one side is read-only: equally visible, narrower write wins.

## Consequences

* A tenant user loses the ability to change or delete a shared library object it can see. That is the point: such an object is shared with every tenant, so no tenant owns it.
* we add a migration bootcheck writing `open-ro` on the roots, the provided groups and targets.
* The special targets were admin-only before the split. They are now `open-ro` so that a tenant can use them (the list of nodes is still filtered by tenant). 
* The system groups that are not special targets (`hasPolicyServer-*`, `all-nodes-with-cfengine-agent`) stay admin-only: they are internal plumbing, no user targets them directly, and the system rules that do are admin-only too.
* Node facts stored the tag with zio-json's derived on some branch. This is normalized toward the new serialization format.
* `"open"` and `<open/>` stay readable, so 9.2 development instance upgrades without losing a tag. They are never written again.
