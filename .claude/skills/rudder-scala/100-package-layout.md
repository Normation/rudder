# 100 — Package & directory layout

Organize packages by **bounded context first**, then by the aspects of that context —
*not* by technical layer.

## The rule

A bounded context (`score`, `campaigns`, `tenants`, `properties`, `facts/nodes`, …) is
a top-level package, and everything about that context lives under it: its domain
types, its repositories, its services, its serialization, etc.

Real example — `rudder-core/.../com/normation/rudder/score/`:

```
com/normation/rudder/score/
├── Score.scala                 // domain types
├── ComplianceScore.scala
├── SystemUpdateScore.scala
├── ScoreRepository.scala       // persistence port (200)
├── GlobalScoreRepository.scala
└── ScoreService.scala          // domain service (100 §Services)
```

Domain, repository and service for *score* sit together because they form one cohesive
topic — you read and change them as a unit.

## The legacy layout (don't extend it)

Older code is organized by **technical layer** at the top level, with per-concept
sub-packages underneath:

```
com/normation/rudder/
├── domain/...        // ~76 files: types for every concept
├── repository/...    // ~37 files: repositories for every concept
└── services/...      // ~83 files: services for every concept
```

This scatters one concept across three (or more) distant trees. It was acceptable when
Rudder was small and a whole **sub-project** *was* the bounded context (e.g.
`ldap-inventory`, the `rudder` projects) — but at today's size it hurts cohesion and
discoverability.

## What to do

- **New context → new top-level package** under `com.normation.rudder.<context>`, with
  its domain/repository/service aspects inside it (the `score` shape).
- **New code in an existing context** → put it in that context's package, even if a
  technical-layer home also exists. Prefer adding to `…/properties/` over `…/services/`.
- **Don't** add new files under the legacy `domain/` `repository/` `services/` trees,
  and **don't** mass-migrate existing ones on sight — moving files is costly to
  [up-merge](000-coding-philosophy.md#concurrent-branches--up-merge). Migrate a concept
  when you're already substantially reworking it.
- Module boundaries (`rudder-core`, `rudder-rest`, `rudder-web`, `ldap-inventory`, …)
  still matter and are orthogonal to this: within a module, lay out by context.

See [`101`](101-architecture-ddd-hexagonal.md) for bounded contexts and services,
[`200`](200-persistence-repositories.md) for repositories.
