# 700 — Dependencies & ecosystem

We are **strict** about dependencies. Adding one is a deliberate decision, not a
reflex.

## License policy (hard rule)

- Dependencies **must** be under an **Apache-2.0 / BSD-compatible** license (MIT,
  Apache-2.0, BSD-2/3-Clause…).
- Anything else (GPL/LGPL/MPL/CDDL/EPL, "source-available", custom licenses…) must be
  **carefully validated before use** — do not add it on your own initiative; flag it.
- License compliance is enforced in CI (see `deny.toml`, `LICENSES/`,
  `dependency-check-suppression.xml`). A new dep that trips these will fail the build.

## Minimize dependencies

- The default posture is to **remove** dependencies, not add them. We actively prefer
  shrinking the dependency surface.
- Before adding a library, check whether an already-present one does the job:
  - effects/concurrency → **zio** (core)
  - JSON → **zio-json**
  - object mapping → **chimney**
  - immutable updates → **quicklens**
  - enumerations → **enumeratum** (see [`401`](401-json-zio-json.md#enums))
  - SQL / PostgreSQL access → **doobie** (in the persistence layer, see
    [`200`](200-persistence-repositories.md))
  - generic type-level / category-theory abstractions ZIO doesn't cover (functors,
    `traverse`, `Semigroup`, `NonEmptyList`, `Ior`…) → **cats** (used sparingly, for
    these data types and syntax — *not* as a second effect system)
  - dates → **java.time** (+ `DateFormaterService`)
- Adding a new dependency *is* allowed when there's a genuine need and no reasonable
  in-tree option — but justify it (need, license, maintenance health, size) and prefer
  small, well-maintained, permissively-licensed libs.

## ZIO ecosystem scope (important)

The ZIO *ecosystem* is fragile and shrinking, so among **`zio-*` modules** we restrict
ourselves to:

- **`zio`** — the core framework (effects), and
- **`zio-json`** — serialization.

**Do not** introduce other `zio-*` modules (zio-http, zio-config, zio-streams as a
public dependency, zio-prelude, zio-cli, …) without explicit team approval. If you need
something they offer, prefer composing it from zio core, from cats, or from a tiny
dedicated lib.

This restriction is about the ZIO *ecosystem* specifically. It does **not** forbid the
other sanctioned libraries above (cats, doobie, chimney, quicklens, enumeratum), which
are established, permissively-licensed dependencies.

## When proposing a dependency change

State: what it's for, its license, whether an existing dep already covers it, and what
(if anything) it lets us **remove**. Net-negative dependency changes are the most
welcome kind.
