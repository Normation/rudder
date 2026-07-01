# 200 — Persistence: repositories

**Every piece of persisted data goes through a repository**, which is the *single
point of change* for that data. Nothing else reads or writes the underlying store
(LDAP, git, DB, files) directly.

## Shape of a repository

- A **trait** (the port) declares the API in domain terms, returning `IOResult[...]`
  for every operation (all persistence is effectful — see [`300`](300-effects-zio-ioresult.md)).
- One or more **`*Impl`** adapters implement it against a concrete store.
- It is wired by constructor in `RudderConfig` (see [`102`](102-traits-and-dependency-injection.md)).

```scala
trait PropertiesRepository {
  def getNodeProps(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Option[ResolvedNodePropertyHierarchy]]
  def saveNodeProps(props: Map[NodeId, ResolvedNodePropertyHierarchy]): IOResult[Unit]
  def deleteNode(nodeId: NodeId): IOResult[Unit]
}
```

## API speaks the domain, not the storage

- Inputs and outputs are **typed domain objects**, never raw rows/JSON/LDAP entries.
- Parsing storage → domain and serializing domain → storage happens *inside* the impl.
  Callers never see the wire/storage format.
- Security context (e.g. `QueryContext` carrying tenant scoping) is part of the
  signature where access must be restricted (see [`600`](600-security-in-depth.md)).

## Split read and write at the interface (`Ro*` / `Wo*`)

Separate **read-only** and **write-only** operations into distinct traits, prefixed
`Ro`/`Wo` — e.g. `RoNodeGroupRepository` (reads) and `WoNodeGroupRepository` (writes).

- **Why:** it's design hygiene. Splitting forces you to think about the *real scope of
  access* a caller needs, and lets you hand a component read-only capability without
  also granting writes (defense in depth, see [`600`](600-security-in-depth.md)).
- **The more complex / sensitive the data, the more this matters.** For a trivial
  store a single trait is fine; for a central aggregate, split it.
- This is *not* full CQRS. There's no separate read/write model or eventual
  consistency. The **same `*Impl`** typically implements both traits (and the write
  impl usually needs read access anyway).

```scala
trait RoNodeGroupRepository { def get(id: NodeGroupId): IOResult[Option[NodeGroup]]; /* ... */ }
trait WoNodeGroupRepository { def update(g: NodeGroup): IOResult[Unit];             /* ... */ }
class NodeGroupRepositoryImpl(...) extends RoNodeGroupRepository with WoNodeGroupRepository { ... }
```

## Optional dedicated persistence layer

A repository may directly contain its storage logic. When the complexity warrants it,
it can instead delegate to a **dedicated persistence/storage layer** — e.g.
`NodeFactRepository` is backed by a separate `NodeFactStorage`
(`rudder-core/.../facts/nodes/`). This split is **optional**: introduce it only when
the storage concerns are genuinely complex enough to earn the extra indirection.
Default to the simpler single-trait repository.

## Do / don't

- **Do** funnel all reads/writes of a given dataset through its one repository.
- **Do** return `IOResult[Option[A]]` for "maybe absent", `IOResult[A]` for
  "must exist or fail", `IOResult[Unit]` for writes.
- **Don't** scatter store access across services. If two places touch the same data
  directly, one of them should be calling the repository instead.
- **Don't** leak storage types (LDAP entries, SQL rows, JSON) out of the impl.

Schema and data **migrations** are not done here ad hoc — the app migrates itself at
startup through the bootstrap-checks pipeline (self-managed integrity, async where
possible). See [`104`](104-bootstrap-and-migrations.md).
