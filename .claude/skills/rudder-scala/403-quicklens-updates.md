# 403 — Updating immutable data with quicklens

To produce a modified copy of a case class — especially a **nested** one — use
softwaremill **quicklens**, not `.copy`.

```scala
import com.softwaremill.quicklens.*

val updated = obj.modify(_.field).setTo(newValue)

// nested, no copy-of-copy-of-copy:
val updated = rule.modify(_.info.status.value).setTo(Enabled)

// multiple fields, chain:
val updated = plugin
  .modify(_.name).setTo(newName)
  .modify(_.status).setTo(Disabled)
```

## Why

- Reads far better than nested `.copy`, especially for deep updates
  (`a.copy(b = a.b.copy(c = a.b.c.copy(...)))` → one `.modify` chain).
- It's already a dependency (declared in `webapp/sources/pom.xml`, compile dep of
  rudder-core). Existing example:
  `rudder-core/.../facts/nodes/NodeFactStorage.scala`.

## Handy combinators

```scala
xs.modify(_.each.field).setTo(v)                 // every element of a collection
opt.modify(_.each.field).using(f)                // Option / collection, transform
m.modify(_.at("key").field).setTo(v)             // Map value at key
obj.modify(_.field).using(old => f(old))         // compute from old value
```

## Guidance

- **Default to quicklens** for updates to new/changed code.
- `.copy` is acceptable only for trivial single-field, single-level updates in code
  already written that way — but prefer `.modify(_.x).setTo(v)` even there for
  consistency.
- For *building* a different type from this one, that's a mapping job → use
  [chimney](402-chimney-transformers.md), not quicklens.

(This is a standing team preference — see the `prefer-quicklens-over-copy` memory.)
