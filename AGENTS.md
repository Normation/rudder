# Agent guide

Guidance for any coding agent (Claude, or otherwise) working in this repository.

## Responsible use (read first)

Human contributors must follow the **[Responsible use of AI agents](AI_POLICY.md)**
policy. Key points an agent should help uphold: no "vibe coding" (code must be understood
by the human who submits it), **any** AI/LLM use must be **disclosed traceably** — record
the exact tool, model, and version in **commit trailers** (e.g.
`Assisted-by: Claude Code 2.0.1 (model: claude-opus-4-8)`) so contributions stay auditable
later, all code is human-reviewed, and a **human is always responsible** for what is
contributed, reviewed, and maintained (and signs the CLA).

If you are an agent, you operate **confined**: do **not** access secrets/credentials or
production/customer data, do **not** push, open/merge PRs, comment, or otherwise act on
GitHub or CI directly, and never act "in the name of" the human. Produce changes locally;
the human reviews the diff and performs all commits, pushes, PRs, and merges. Remind the
contributor to disclose AI assistance in the PR.

## Rust - read the skill first

Before reading, writing, rewiewing or refactoring Rust code in this repository,
read [`.claude/skills/rudder-rust/SKILL.md`](.claude/skills/rudder-rust/SKILL.md),
nothing there is Claude-specific.

## Scala conventions — read the skill first

Before reading, writing, reviewing, or refactoring **Scala** in this repo, read the
project's Scala conventions, which live as plain Markdown under:

> [`.claude/skills/rudder-scala/`](.claude/skills/rudder-scala/)

Start with **[`.claude/skills/rudder-scala/SKILL.md`](.claude/skills/rudder-scala/SKILL.md)**
— it's the router: it states the always-apply "golden rules" and indexes the per-topic
files. Each topic is its own file named `NNN-topic.md`, where the first digit is the
subject area:

| 1st digit | Subject area |
|-----------|--------------|
| 0 | Generalities — coding philosophy & Scala 3 idioms |
| 1 | Architecture — package layout, DDD/hexagonal, traits & DI, REST |
| 2 | Persistence — repositories, parse-don't-validate |
| 3 | Effects & errors — ZIO, `IOResult`, `RudderError`, logging |
| 4 | Data & serialization — case classes, zio-json, chimney, quicklens, contracts |
| 5 | Datetime — `java.time` |
| 6 | Security — security in depth |
| 7 | Dependencies & ecosystem |
| 8 | Build, tooling & formatting |
| 9 | Testing |

These files are vendor-neutral Markdown — read them directly regardless of your tooling.
(The `.claude/skills/` path is also where Claude Code auto-discovers the same content as
a "skill", but nothing in the files is Claude-specific.)

### Scope

The conventions apply to the Scala code in **`rudder`** and in the sibling plugin repos
**`rudder-plugins`** and **`rudder-plugins-private`** (same stack: `IOResult`/
`RudderError`, zio-json, chimney, quicklens, enumeratum, the plugin API framework). The
skill physically lives in this repo; when working in a plugin repo, follow it too and
look up referenced code/ADRs in this `rudder` checkout.

## Other sources of truth

- **ADRs** (architecture decisions) in [`adr/webapp/`](adr/webapp/) and
  [`adr/system/`](adr/system/) are authoritative; the skill cites the relevant ADR per
  topic. Add an ADR (see [`adr/template.md`](adr/template.md)) for new decisions.
- The Scala build/test specifics live in the skill's
  [`800-build-and-formatting.md`](.claude/skills/rudder-scala/800-build-and-formatting.md).
